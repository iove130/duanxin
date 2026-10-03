"""配置和转发日志的存储。

UI 进程（Kivy）和服务进程会同时访问同一份数据，因此：
- 写入一律走「临时文件 + os.replace」原子替换，避免读到半截 JSON；
- 读取带 mtime 缓存，服务进程据此判断是否需要热重载；
- 所有路径统一由 app_dir() 解析，跨进程保持一致。
"""
import json
import os
import threading
import time

PACKAGE = "org.example.smsforwarder"

DEFAULT_CONFIG = {
    "enabled": True,          # 总开关
    "receivers": [],           # 接收人手机号列表（目标手机）
    "senders": [],             # 允许的发信人；为空表示不限
    "sender_mode": "allow",    # allow=白名单 / block=黑名单
    "keywords": [],            # 关键词列表
    "keyword_mode": "any",     # any=任一命中 / all=全部命中 / regex=正则
    "case_sensitive": False,
    "exclude_keywords": [],    # 屏蔽词，命中则不转发
    "template": "[转发] 来自 {from}\n时间 {time}\n\n{body}",
    "max_len": 500,            # 单条正文最大长度，超出拆分多条
    "split_sms": True,         # 超长是否自动拆分
    "dedup_minutes": 5,        # 相同（号码+内容）在该窗口内去重，0 表示不去重
    "delay_seconds": 0,        # 转发前延迟，给主窗体争取一点点时间
    "subs_id": -1,             # 双卡：使用的 SIM 订阅 ID，-1 表示系统默认
    "include_slot": False,     # 模板是否输出 SIM 槽位（模板变量 {sim}）
    "log_limit": 300,          # 本地日志最多保留条数
}

APP_DIR_HINTS = [
    os.environ.get("ANDROID_APP_PATH"),
    os.environ.get("ANDROID_PRIVATE"),
    os.environ.get("PRIVATE_PATH"),
    "/data/data/%s/files/app" % PACKAGE,
    "/data/user/0/%s/files/app" % PACKAGE,
    "/sdcard/Android/data/%s/files" % PACKAGE,
]

_lock = threading.Lock()


def app_dir():
    """返回可写的数据目录，跨进程保持一致。"""
    for p in APP_DIR_HINTS:
        try:
            if p and os.path.isdir(p):
                return p
        except Exception:
            continue
    return os.getcwd()


def config_path():
    return os.path.join(app_dir(), "sms_forwarder_config.json")


def log_path():
    return os.path.join(app_dir(), "sms_forwarder_log.jsonl")


def stop_flag_path():
    return os.path.join(app_dir(), "sms_forwarder.stop")


def heartbeat_path():
    return os.path.join(app_dir(), "sms_forwarder.heartbeat")


def _read_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    loaded = _read_json(config_path(), {})
    if isinstance(loaded, dict):
        cfg.update(loaded)
    # 历史版本兼容：曾经的单接收人字段
    old = cfg.pop("receiver", None) if "receiver" in cfg else None
    if old and not cfg.get("receivers"):
        cfg["receivers"] = [old]
    return cfg


def config_mtime():
    try:
        return os.path.getmtime(config_path())
    except Exception:
        return 0.0


def save_config(cfg):
    """原子写入配置，返回最终落盘的配置。"""
    merged = dict(DEFAULT_CONFIG)
    merged.update(cfg or {})
    path = config_path()
    tmp = path + ".tmp"
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d, exist_ok=True)
    with _lock:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(merged, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    return merged


def clear_stop_flag():
    p = stop_flag_path()
    if os.path.exists(p):
        try:
            os.remove(p)
        except Exception:
            pass


def request_stop():
    with open(stop_flag_path(), "w", encoding="utf-8") as f:
        f.write(str(time.time()))


def should_stop():
    return os.path.exists(stop_flag_path())


def write_heartbeat():
    try:
        with open(heartbeat_path(), "w", encoding="utf-8") as f:
            f.write("%.3f" % time.time())
    except Exception:
        pass


def read_heartbeat():
    p = heartbeat_path()
    if not os.path.exists(p):
        return 0.0
    try:
        with open(p, "r", encoding="utf-8") as f:
            return float(f.read().strip() or 0)
    except Exception:
        return 0.0


def is_service_alive(max_age=15.0):
    hb = read_heartbeat()
    if hb <= 0:
        return False
    return (time.time() - hb) <= max_age


def append_log(entry):
    """追加一条转发记录，并裁剪到 log_limit 条。"""
    cfg = load_config()
    limit = int(cfg.get("log_limit") or 300)
    path = log_path()
    lines = []
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                lines = f.readlines()
        except Exception:
            lines = []
    lines.append(json.dumps(entry, ensure_ascii=False) + "\n")
    lines = lines[-limit:]
    tmp = path + ".tmp"
    with _lock:
        with open(tmp, "w", encoding="utf-8") as f:
            f.writelines(lines)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)


def read_logs():
    path = log_path()
    if not os.path.exists(path):
        return []
    out = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    out.append(json.loads(line))
                except Exception:
                    continue
    except Exception:
        return []
    return out


def clear_logs():
    path = log_path()
    if os.path.exists(path):
        try:
            os.remove(path)
        except Exception:
            pass
