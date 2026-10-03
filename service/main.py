"""短信转发常驻服务（Python-for-Android 前台服务）。

设计要点：
1. Android 8+ 静态注册 SMS_RECEIVED 对第三方应用基本失效，因此这里在
   **动态注册** BroadcastReceiver，并靠前台服务把进程钉住。
2. 主线程必须调用 Looper.loop() 回到消息循环，否则一个 while True 会把
   onReceive 的回调饿死 —— 所以轮询热重载、心跳都放在子线程。
3. 配置改动即时生效：子线程检测配置文件 mtime 变化后热重载。
"""
import os
import sys
import threading
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
for p in (ROOT, HERE, os.environ.get("ANDROID_APP_PATH", ""), os.getcwd()):
    if p and p not in sys.path:
        sys.path.insert(0, p)

from common import android_ext, config_store, rules  # noqa: E402

STOP_TIMEOUT = 20.0


class Forwarder(object):
    def __init__(self):
        self.cfg = None
        self.cfg_mtime = 0.0
        self.dedup = rules.Deduplicator(0)
        self.running = True
        self.total_forwarded = 0

    # -------------------------------------------------- 配置
    def reload_config(self, force=False):
        mt = config_store.config_mtime()
        if force or mt != self.cfg_mtime:
            self.cfg = config_store.load_config()
            self.cfg_mtime = mt
            self.dedup.update_window(self.cfg.get("dedup_minutes", 0))

    def _log(self, level, msg, extra=None):
        entry = {
            "ts": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime()),
            "level": level,
            "msg": msg,
        }
        if extra:
            entry.update(extra)
        try:
            config_store.append_log(entry)
        except Exception:
            pass
        try:
            _print(entry)
        except Exception:
            pass

    # -------------------------------------------------- 核心流程
    def handle_sms(self, context, intent):
        try:
            self.reload_config()
        except Exception:
            pass
        cfg = self.cfg or config_store.load_config()
        try:
            msgs = android_ext.parse_sms_intent(intent)
        except Exception as exc:
            self._log("error", "解析短信失败: %s" % exc)
            return
        if not msgs:
            return
        # 多条 pdu 可能是同一条长短信的分片，拼起来再转发
        if len(msgs) > 1:
            merged = {
                "from": msgs[0].get("from", ""),
                "body": "".join(m.get("body", "") for m in msgs),
                "time": msgs[0].get("time", ""),
                "sim": msgs[0].get("sim", ""),
            }
            items = [merged]
        else:
            items = msgs

        for m in items:
            try:
                self._process_one(m, cfg)
            except Exception:
                self._log("error", "处理短信异常\n%s" % traceback.format_exc(),
                          {"from": m.get("from", "")})

    def _process_one(self, msg, cfg):
        sender = msg.get("from", "")
        body = msg.get("body", "")
        ok, reason, word = rules.should_forward(msg, cfg)
        if not ok:
            # 只记录被「疑似命中但被拦」的情况，避免日志被垃圾短信刷满
            if word or cfg.get("verbose_log", False):
                self._log("skip", "%s | %s" % (sender, reason),
                          {"from": sender, "body": body[:60]})
            return

        receivers = [str(x).strip() for x in (cfg.get("receivers") or []) if str(x).strip()]
        if not receivers:
            self._log("error", "命中但未配置接收人，请在 App 里设置接收人手机号",
                      {"from": sender, "body": body[:60]})
            return

        window = float(cfg.get("dedup_minutes", 0) or 0)
        if window > 0 and self.dedup.is_duplicate(sender, body):
            self._log("skip", "去重命中，跳过转发 | %s" % sender, {"from": sender})
            return

        delay = float(cfg.get("delay_seconds", 0) or 0)
        if delay > 0:
            time.sleep(delay)

        text = rules.render(cfg.get("template"), msg, cfg.get("max_len", 500))
        subs_id = int(cfg.get("subs_id", -1) or -1)
        do_split = bool(cfg.get("split_sms", True))
        max_len = int(cfg.get("max_len", 500) or 0)

        for phone in receivers:
            if do_split and max_len > 0 and len(text) > max_len:
                for part in rules.split_text(text, max_len, True):
                    ok_send, err = android_ext.send_sms(phone, part, subs_id)
                    self._log("info" if ok_send else "error",
                              "转发明细 -> %s %s" % (phone, err or "成功"),
                              {"from": sender, "to": phone, "body": part[:120]})
                    if not ok_send:
                        break
                    time.sleep(0.3)
            else:
                ok_send, err = android_ext.send_sms(phone, text, subs_id)
                self._log("info" if ok_send else "error",
                          "转发 -> %s %s" % (phone, ("(%s)" % err) if err else "成功"),
                          {"from": sender, "to": phone,
                           "body": text[:120], "keyword": word})
            self.total_forwarded += 1

    # -------------------------------------------------- 接收器
    def register_receiver(self):
        from jnius import PythonJavaClass, autoclass, cast, java_method

        forwarder = self

        class SmsReceiver(PythonJavaClass):
            __javainterfaces__ = ["android/content/BroadcastReceiver"]
            __javacontext__ = "app"

            def __init__(self):
                super(SmsReceiver, self).__init__()

            @java_method("(Landroid/content/Context;Landroid/content/Intent;)V")
            def onReceive(self, context, intent):
                try:
                    forwarder.handle_sms(context, intent)
                except Exception:
                    _print("onReceive error: %s" % traceback.format_exc())

        IntentFilter = autoclass("android.content.IntentFilter")
        context = android_ext.get_context()
        receiver = SmsReceiver()
        flt = IntentFilter()
        flt.addAction("android.provider.Telephony.SMS_RECEIVED")
        flt.setPriority(2147483647)
        try:
            context.registerReceiver(cast("android.content.BroadcastReceiver", receiver), flt)
        except Exception:
            context.registerReceiver(receiver, flt)
        self._log("info", "已注册短信监听器（前台服务运行中）")
        return receiver

    # -------------------------------------------------- 循环
    def start_watchers(self):
        """心跳 + 配置热重载，都放在子线程，不占用主线程 Looper。"""

        def loop():
            last_alive = 0.0
            while self.running:
                try:
                    if config_store.should_stop():
                        self._log("info", "收到停止指令，服务即将退出")
                        self.running = False
                        break
                    self.reload_config()
                    now = time.time()
                    if now - last_alive >= 5:
                        config_store.write_heartbeat()
                        last_alive = now
                except Exception:
                    pass
                time.sleep(1.0)
            self._shutdown()

        t = threading.Thread(target=loop, name="fw-watcher")
        t.daemon = True
        t.start()

    def _shutdown(self):
        try:
            config_store.clear_stop_flag()
        except Exception:
            pass
        try:
            self._log("info", "转发服务已停止")
        except Exception:
            pass
        # Python 侧退出后 Service.onDestroy 结束剩余工作
        try:
            from jnius import autoclass

            service = autoclass("org.kivy.android.PythonService").mService
            service.stopSelf()
        except Exception:
            pass
        try:
            sys.stdout.flush()
        except Exception:
            pass
        os._exit(0)


def _print(entry):
    try:
        sys.stdout.write("[fw] %s %s\n" % (entry.get("level"), entry.get("msg")))
        sys.stdout.flush()
    except Exception:
        pass


def main():
    try:
        android_ext.ensure_import_path()
    except Exception:
        pass
    fwd = Forwarder()
    try:
        config_store.clear_stop_flag()
    except Exception:
        pass
    try:
        fwd.reload_config(force=True)
    except Exception as exc:
        _print({"level": "error", "msg": "配置读取失败 %s" % exc})
    try:
        fwd.register_receiver()
    except Exception:
        _print({"level": "error", "msg": "注册监听失败\n%s" % traceback.format_exc()})
    fwd.start_watchers()

    # 主线程交还给 Android 消息循环 —— onReceive 才能被派发到这里
    try:
        from jnius import autoclass

        Looper = autoclass("android.os.Looper")
        Looper.loop()
    except Exception:
        _print({"level": "error", "msg": "Looper 启动失败\n%s" % traceback.format_exc()})
        while fwd.running:
            time.sleep(1.0)


if __name__ == "__main__":
    main()
