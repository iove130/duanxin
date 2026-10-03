"""Android 侧能力封装（pyjnius）。

只在真机/P4A 打包环境里可用；开发机上导入会静默失败，
由调用方通过 is_android() 判断，便于跑单元测试。
"""
import os
import sys
import time

ANDROID = "ANDROID_ARGUMENT" in os.environ or "ANDROID_APP_PATH" in os.environ


def is_android():
    return ANDROID


def _jni():
    """延迟导入 jnius，避免在桌面环境报错。"""
    from jnius import autoclass  # noqa: F401
    return autoclass


def ensure_import_path():
    """服务进程的 sys.path 有时缺了应用根目录，先把候选路径都塞进去。"""
    import os

    candidates = [
        os.environ.get("ANDROID_APP_PATH"),
        os.environ.get("ANDROID_PRIVATE"),
        os.getcwd(),
        os.path.dirname(os.path.abspath(__file__)),
    ]
    for p in candidates:
        if p and p not in sys.path:
            sys.path.insert(0, p)


# ---------------------------------------------------------------- 权限

PERMISSIONS = [
    "android.permission.RECEIVE_SMS",
    "android.permission.SEND_SMS",
    "android.permission.READ_SMS",
    "android.permission.READ_PHONE_STATE",
    "android.permission.POST_NOTIFICATIONS",  # Android 13+，低版本会被自动忽略
]


def missing_permissions():
    """返回尚未授予的危险权限列表。"""
    try:
        from android.permissions import Permission, check_permission
    except Exception:
        try:
            from jnius import autoclass
            from android import activity as _a  # noqa: F401

            activity = autoclass("org.kivy.android.PythonActivity").mActivity
        except Exception:
            return []
        missing = []
        for p in PERMISSIONS:
            if activity.checkSelfPermission(p) != 0:
                missing.append(p)
        return missing
    mapping = {
        "android.permission.RECEIVE_SMS": getattr(Permission, "RECEIVE_SMS", "android.permission.RECEIVE_SMS"),
        "android.permission.SEND_SMS": getattr(Permission, "SEND_SMS", "android.permission.SEND_SMS"),
        "android.permission.READ_SMS": getattr(Permission, "READ_SMS", "android.permission.READ_SMS"),
        "android.permission.READ_PHONE_STATE": getattr(Permission, "READ_PHONE_STATE", "android.permission.READ_PHONE_STATE"),
        "android.permission.POST_NOTIFICATIONS": "android.permission.POST_NOTIFICATIONS",
    }
    missing = []
    for name in PERMISSIONS:
        try:
            if not check_permission(mapping.get(name, name)):
                missing.append(name)
        except Exception:
            pass
    return missing


def request_permissions(callback=None):
    try:
        from android.permissions import request_permissions as _req

        _req(PERMISSIONS, callback)
        return True
    except Exception:
        try:
            from jnius import autoclass

            activity = autoclass("org.kivy.android.PythonActivity").mActivity
            activity.requestPermissions(PERMISSIONS, 10086)
            return True
        except Exception:
            return False


def open_app_settings():
    """跳转到本应用的系统详情页，方便用户手动开权限。"""
    try:
        from jnius import autoclass, cast

        Intent = autoclass("android.content.Intent")
        Uri = autoclass("android.net.Uri")
        PythonActivity = autoclass("org.kivy.android.PythonActivity")
        activity = PythonActivity.mActivity
        intent = Intent()
        intent.setAction("android.settings.APPLICATION_DETAILS_SETTINGS")
        pkg = activity.getPackageName()
        intent.setData(Uri.parse("package:%s" % pkg))
        cast("android.app.Activity", activity).startActivity(intent)
        return True
    except Exception:
        return False


# ---------------------------------------------------------------- 提示

def toast(text, long=False):
    try:
        from jnius import autoclass, cast

        PythonActivity = autoclass("org.kivy.android.PythonActivity")
        activity = PythonActivity.mActivity
        Toast = autoclass("android.widget.Toast")
        String = autoclass("java.lang.String")
        duration = Toast.LENGTH_LONG if long else Toast.LENGTH_SHORT

        def _show():
            t = Toast.makeText(activity, cast("java.lang.CharSequence", String(str(text))), duration)
            t.show()

        try:
            activity.runOnUiThread(_show)
        except Exception:
            _show()
        return True
    except Exception:
        return False


def get_context():
    """优先用 Activity；服务进程里 UI 可能没起来，回退到 PythonService。"""
    from jnius import autoclass

    for cls, field in (
        ("org.kivy.android.PythonActivity", "mActivity"),
        ("org.kivy.android.PythonService", "mService"),
    ):
        try:
            ctx = getattr(autoclass(cls), field, None)
            if ctx is not None:
                return ctx
        except Exception:
            continue
    return None


def _to_java_string(text):
    from jnius import autoclass

    String = autoclass("java.lang.String")
    return String(str(text))


# ---------------------------------------------------------------- 发送短信

def subscription_ids():
    """返回可用 SIM 卡列表 [(slot, sub_id, carrier)]。失败返回空列表。"""
    try:
        from jnius import autoclass

        SubscriptionManager = autoclass("android.telephony.SubscriptionManager")
        infos = SubscriptionManager.getActiveSubscriptionInfoList()
        out = []
        if infos is not None:
            for i in range(infos.size()):
                info = infos.get(i)
                out.append((info.getSimSlotIndex(), info.getSubscriptionId(), str(info.getCarrierName())))
        return out
    except Exception:
        return []


def _sms_manager(subs_id=-1):
    from jnius import autoclass

    SmsManager = autoclass("android.telephony.SmsManager")
    try:
        if subs_id and int(subs_id) > 0:
            mgr = SmsManager.createForSubscriptionId(int(subs_id))
            if mgr is not None:
                return mgr
    except Exception:
        pass
    try:
        sm_default = SmsManager.getDefault()
        if sm_default is not None:
            return sm_default
    except Exception:
        pass
    return SmsManager.getDefault()


def send_sms(phone, text, subs_id=-1):
    """发送一条短信。长短信交给底层 divideMessage 处理。返回 (成功, 错误信息)。"""
    try:
        from jnius import autoclass, cast

        phone = str(phone or "").strip()
        text = str(text or "")
        if not phone:
            return False, "接收人为空"
        if not text:
            return False, "转发内容为空"

        mgr = _sms_manager(subs_id)
        ArrayList = autoclass("java.util.ArrayList")
        parts = mgr.divideMessage(_to_java_string(text))
        single = parts is None or parts.size() <= 1
        sentIntent = None
        deliveryIntent = None
        if single:
            mgr.sendTextMessage(
                cast("java.lang.String", _to_java_string(phone)),
                None,
                cast("java.lang.String", _to_java_string(text)),
                sentIntent,
                deliveryIntent,
            )
        else:
            # 多段彩信/长短信
            sent_list = ArrayList()
            del_list = ArrayList()
            for _ in range(parts.size()):
                sent_list.add(None)
                del_list.add(None)
            mgr.sendMultipartTextMessage(
                cast("java.lang.String", _to_java_string(phone)),
                None,
                parts,
                sent_list,
                del_list,
            )
        return True, "" if single else "已分 %d 条发送" % parts.size()
    except Exception as exc:
        return False, "%s: %s" % (type(exc).__name__, exc)


def sim_slot_of(sms_msg):
    """尝试读取短信来自哪一槽 SIM，失败返回 '未知'。"""
    try:
        # API 31+: getSubscriptionId()
        sub = sms_msg.getSubscriptionId()
        for slot, sub_id, carrier in subscription_ids():
            if sub_id == sub:
                return "卡%d(%s)" % (slot + 1, carrier)
    except Exception:
        pass
    return "未知"


# ---------------------------------------------------------------- 接收短信

def get_message_body(sms_msg):
    try:
        return str(sms_msg.getMessageBody())
    except Exception:
        return ""


def get_originating_address(sms_msg):
    try:
        addr = sms_msg.getDisplayOriginatingAddress()
        if addr:
            return str(addr)
        return str(sms_msg.getOriginatingAddress())
    except Exception:
        return ""


def parse_sms_intent(intent):
    """从 SMS_RECEIVED intent 解析出 [{from, body, time}]。"""
    out = []
    try:
        from jnius import autoclass

        Intents = autoclass("android.provider.Telephony$Sms$Intents")
        msgs = Intents.getMessagesFromIntent(intent)
        n = 0 if msgs is None else len(msgs)
        for i in range(n):
            m = msgs[i]
            out.append(
                {
                    "from": get_originating_address(m),
                    "body": get_message_body(m),
                    "time": _fmt_time(m.getTimestampMillis() / 1000.0),
                    "sim": sim_slot_of(m),
                }
            )
    except Exception:
        # 回退路径：手动解 pdus
        out = _parse_pdus(intent)
    return out


def _parse_pdus(intent):
    out = []
    try:
        from jnius import autoclass

        SmsMessage = autoclass("android.telephony.SmsMessage")
        bundle = intent.getExtras()
        if bundle is None:
            return out
        pdus = bundle.get("pdus")
        fmt = bundle.get("format")
        n = 0 if pdus is None else len(pdus)
        for i in range(n):
            try:
                if fmt is not None:
                    m = SmsMessage.createFromPdu(pdus[i], fmt)
                else:
                    m = SmsMessage.createFromPdu(pdus[i])
            except Exception:
                continue
            out.append(
                {
                    "from": get_originating_address(m),
                    "body": get_message_body(m),
                    "time": _fmt_time(m.getTimestampMillis() / 1000.0),
                    "sim": sim_slot_of(m),
                }
            )
    except Exception:
        pass
    return out


def _fmt_time(ts):
    try:
        return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts))
    except Exception:
        return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
