"""纯 Python 的匹配/转发规则引擎。

不依赖任何 Android API，因此可以在开发机上直接跑单元测试。
全部函数都是纯函数（除 forwarding 相关的时间由调用方传入），便于测试。
"""
import re
import time

DIGIT_RE = re.compile(r"\D+")


def digits_only(value):
    return DIGIT_RE.sub("", str(value or ""))


def _strip_country_code(d):
    """把常见国家前缀剥掉，只留 11 位国内号码的主体部分用于比较。"""
    if d.startswith("0086") and len(d) > 11:
        d = d[4:]
    elif d.startswith("86") and len(d) > 11:
        d = d[2:]
    return d


def match_number(sender, pattern):
    """发信人号码匹配，支持三种写法：
    - 精确：'13800138000' → 忽略国家码后相等即命中
    - 前缀：'1069*'       → 1069 开头的通道号
    - 后缀：'*10086'      → 以 10086 结尾
    - 包含：'*95588*'     → 任意位置包含
    """
    s = _strip_country_code(digits_only(sender))
    p = str(pattern or "").strip()
    if not p or not s:
        return False
    has_head = p.startswith("*")
    has_tail = p.endswith("*")
    core = p.strip("*")
    core = _strip_country_code(digits_only(core))
    if not core:
        return False
    if has_head and has_tail:
        return core in s
    if has_head:
        return s.endswith(core)
    if has_tail:
        return s.startswith(core)
    # 精确匹配时，两边都去掉国家码；同时允许通道号的「归属号+扩展位」这种前后包含关系
    return s == core or core.endswith(s) or s.endswith(core)


def sender_allowed(sender, senders, mode="allow"):
    """判断发信人是否放行。返回 (是否放行, 原因)。"""
    rules = [x for x in (senders or []) if str(x).strip()]
    if not rules:
        return True, "未设置发信人过滤"
    hit = None
    for r in rules:
        try:
            if match_number(sender, r):
                hit = r
                break
        except Exception:
            continue
    if mode == "block":
        if hit:
            return False, "发信人在黑名单(%s)" % hit
        return True, "非黑名单号码"
    if hit:
        return True, "发信人在白名单(%s)" % hit
    return False, "发信人不在白名单"


def _prepare(text, case_sensitive):
    return str(text or "") if case_sensitive else str(text or "").lower()


def keyword_hit(body, keywords, mode="any", case_sensitive=False):
    """关键词是否命中。返回 (是否命中, 命中的词)。关键词为空表示全部转发。"""
    words_all = [str(w).strip() for w in (keywords or [])]
    words = [w for w in words_all if w]
    if not words:
        return True, ""
    text = _prepare(body, case_sensitive)
    if mode == "regex":
        flags = 0 if case_sensitive else re.I
        for w in words:
            try:
                if re.search(w, str(body or ""), flags):
                    return True, w
            except re.error:
                continue
        return False, ""
    elif mode == "all":
        for w in words:
            if _prepare(w, case_sensitive) not in text:
                return False, ""
        return True, ",".join(words)
    else:  # any
        for w in words:
            if _prepare(w, case_sensitive) in text:
                return True, w
        return False, ""


def exclude_hit(body, excludes, case_sensitive=False):
    text = _prepare(body, case_sensitive)
    for w in (excludes or []):
        w = str(w).strip()
        if not w:
            continue
        try:
            if mode_regex(w, text, case_sensitive):
                return True, w
        except Exception:
            pass
    return False, ""


def mode_regex(word, text, case_sensitive):
    w = str(word).strip()
    if w.startswith("/") and w.endswith("/") and len(w) > 2:
        flags = 0 if case_sensitive else re.I
        return re.search(w[1:-1], text, flags) is not None
    return _prepare(w, case_sensitive) in text


def should_forward(msg, cfg):
    """综合判断是否转发。msg 需要包含 from / body / time。返回 (bool, 原因, 命中词)。"""
    if not cfg.get("enabled", True):
        return False, "转发总开关关闭", ""
    allowed, reason = sender_allowed(
        msg.get("from"), cfg.get("senders"), cfg.get("sender_mode", "allow")
    )
    if not allowed:
        return False, reason, ""
    hit, word = keyword_hit(
        msg.get("body"),
        cfg.get("keywords"),
        cfg.get("keyword_mode", "any"),
        bool(cfg.get("case_sensitive")),
    )
    if not hit:
        return False, "未命中关键词", ""
    blocked, bad = exclude_hit(
        msg.get("body"),
        cfg.get("exclude_keywords"),
        bool(cfg.get("case_sensitive")),
    )
    if blocked:
        return False, "命中屏蔽词(%s)" % bad, word
    return True, reason, word


def render(template, msg, max_len=500):
    """按模板渲染待转发的正文。可用变量：from / time / body / sim / raw"""
    try:
        text = str(template or "").format(
            **{
                "from": msg.get("from", ""),
                "time": msg.get("time", ""),
                "body": msg.get("body", ""),
                "sim": msg.get("sim", ""),
                "slot": msg.get("sim", ""),
                "raw": msg.get("body", ""),
            }
        )
    except Exception:
        text = "[转发] 来自 %s\n%s" % (msg.get("from", ""), msg.get("body", ""))
    return text


def split_text(text, max_len=500, do_split=True):
    """把超长正文切成多条。max_len <= 0 或 do_split=False 时不切。"""
    text = str(text or "")
    max_len = int(max_len or 0)
    if not do_split or max_len <= 0 or len(text) <= max_len:
        return [text] if text else []
    parts = []
    total = len(text)
    idx = 0
    n = 0
    while idx < total:
        total_pages = (total + max_len - 1) // max_len
        n += 1
        chunk = text[idx: idx + max_len]
        parts.append("(%d/%d)%s" % (n, total_pages, chunk))
        idx += max_len
    return parts


class Deduplicator(object):
    """简单的时间窗口去重：同号码 + 同正文，在窗口内只转发一次。"""

    def __init__(self, window_minutes=0):
        self.window = float(window_minutes or 0) * 60.0
        self._seen = {}

    def update_window(self, window_minutes):
        self.window = float(window_minutes or 0) * 60.0

    def is_duplicate(self, sender, body, now=None):
        if self.window <= 0:
            return False
        now = time.time() if now is None else now
        key = "%s|%s" % (digits_only(sender), str(body or ""))
        last = self._seen.get(key)
        if last is not None and (now - last) < self.window:
            return True
        self._seen[key] = now
        # 顺手清理过期项，避免内存无限增长
        if len(self._seen) > 500:
            expired = [k for k, v in self._seen.items() if now - v >= self.window]
            for k in expired:
                self._seen.pop(k, None)
        return False


def parse_list(text):
    """把文本框按逗号/分号/换行拆成列表。"""
    raw = str(text or "").replace("，", ",").replace("；", ";").replace("、", ",")
    out = []
    for chunk in re.split(r"[,;\n\r]+", raw):
        chunk = chunk.strip()
        if chunk:
            out.append(chunk)
    return out
