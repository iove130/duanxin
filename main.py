# -*- coding: utf-8 -*-
"""短信转发器 —— 免登录、纯本地、两台安卓机之间的短信转发客户端。

主进程只负责配置展示；真正的收发 SMS 逻辑在 service/main.py 的常驻前台服务里。
两者通过同一个 JSON 配置文件通信，读者均为 Kivy / jnius 混合实现。
"""
import os
import re
import sys
import time
import traceback

from kivy.app import App
from kivy.clock import Clock, mainthread
from kivy.core.window import Window
from kivy.properties import BooleanProperty, ListProperty, StringProperty
from kivy.graphics import Color, Rectangle, RoundedRectangle
from kivy.uix.behaviors import ToggleButtonBehavior
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.lang import Builder

from kivy.metrics import dp

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from common import android_ext, config_store, rules  # noqa: E402

try:
    Window.softinput_mode = "below_target"
except Exception:
    pass

# ----------------------------------------------------------------- 主题
BG = (0.043, 0.055, 0.055, 1)
CARD = (0.086, 0.108, 0.106, 1)
CARD2 = (0.113, 0.137, 0.133, 1)
ACCENT = (0.059, 0.431, 0.337, 1)   # #0F6E56
ACCENT_D = (0.043, 0.325, 0.255, 1)
DANGER = (0.545, 0.196, 0.196, 1)
OKC = (0.235, 0.62, 0.44, 1)
TEXT = (0.878, 0.906, 0.898, 1)
SUB = (0.545, 0.620, 0.604, 1)
FIELD = (0.141, 0.169, 0.165, 1)
LINE = (0.176, 0.208, 0.204, 1)

Builder.load_string(
    """
<Card@BoxLayout>:
    orientation: 'vertical'
    padding: dp(14), dp(12)
    spacing: dp(9)
    size_hint_y: None
    height: self.minimum_height
    canvas.before:
        Color:
            rgba: app.c_card
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(12)]

<CardTitle@Label>:
    size_hint_y: None
    height: dp(24)
    text_size: self.width, None
    shorten: False
    bold: True
    font_size: '15sp'
    halign: 'left'
    valign: 'middle'
    color: app.c_text

<Hint@Label>:
    size_hint_y: None
    height: self.texture_size[1] + dp(2)
    text_size: self.width, None
    font_size: '12sp'
    halign: 'left'
    valign: 'top'
    color: app.c_sub

<RowLabel@Label>:
    size_hint_y: None
    height: dp(22)
    text_size: self.width, None
    halign: 'left'
    valign: 'middle'
    font_size: '13sp'
    color: app.c_sub

<Input@TextInput>:
    size_hint_y: None
    height: max(dp(44), self.minimum_height)
    padding: dp(10), dp(10)
    font_size: '14sp'
    hint_text_color: (0.45, 0.52, 0.50, 1)
    foreground_color: app.c_text
    cursor_color: app.c_accent
    background_color: (0, 0, 0, 0)
    canvas.before:
        Color:
            rgba: app.c_field
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(8)]

<ChipBtn@ToggleButtonBehavior+Label>:
    size_hint: None, None
    size: dp(76), dp(32)
    font_size: '12.5sp'
    bold: True
    color: app.c_sub
    canvas.before:
        Color:
            rgba: app.c_card2 if self.state == 'normal' else app.c_accent
        RoundedRectangle:
            pos: self.pos
            size: self.size
            radius: [dp(8)]

<SwitchBtn@ToggleButtonBehavior+BoxLayout>:
    allow_no_selection: True
    size_hint_y: None
    height: dp(30)
    padding: dp(2), dp(3)
    canvas.before:
        Color:
            rgba: app.c_card2 if self.state == 'normal' else app.c_accent
        RoundedRectangle:
            pos: self.pos
            size: [dp(48), self.height]
            radius: [dp(15)]
    Label:
        id: sw_text
        size_hint: None, 1
        width: dp(48)
        text: '关'
        font_size: '11.5sp'
        bold: True
        color: (1, 1, 1, 1) if root.state == 'down' else app.c_sub

<LogRow@Label>:
    size_hint_y: None
    height: self.texture_size[1] + dp(8)
    text_size: self.width, None
    font_size: '12sp'
    halign: 'left'
    valign: 'top'
    color: app.c_text
"""
)


def _shade(color, factor=0.82):
    return (color[0] * factor, color[1] * factor, color[2] * factor, 1)


class FButton(Button):
    """圆角按钮。背景色画在 canvas 上，方便运行时改主色。"""

    def __init__(self, text="", color=None, callback=None, **kw):
        base = color or CARD2
        super(FButton, self).__init__(
            text=text,
            size_hint_y=None,
            height=42,
            font_size="14sp",
            bold=True,
            background_normal="",
            background_down="",
            background_color=(0, 0, 0, 0),
            color=(1, 1, 1, 1),
            **kw
        )
        self.base_color = base
        with self.canvas.before:
            self._col = Color(*base)
            self._rect = RoundedRectangle(pos=self.pos, size=self.size, radius=[10])
        self.bind(pos=lambda i, v: setattr(self._rect, "pos", v))
        self.bind(size=lambda i, v: setattr(self._rect, "size", v))
        self.bind(state=self._sync_color)
        if callback:
            self.bind(on_release=callback)

    def _sync_color(self, *_):
        self._col.rgba = _shade(self.base_color) if self.state == "down" else self.base_color

    def set_color(self, color):
        self.base_color = color
        self._col.rgba = _shade(color) if self.state == "down" else color


class ChipRow(BoxLayout):
    """一组互斥的选择按钮。"""

    options = ListProperty([])
    selected = StringProperty("")
    title = StringProperty("")

    def __init__(self, options, selected="", callback=None, title="", **kw):
        super(ChipRow, self).__init__(
            orientation="horizontal", spacing=8, size_hint_y=None, height=38, **kw
        )
        self.options = options
        self.selected = selected if selected in options else (options[0] if options else "")
        self._cb = callback
        if title:
            from kivy.uix.label import Label

            self.add_widget(
                Label(
                    text=title,
                    size_hint=(None, 1),
                    width=64,
                    font_size="13sp",
                    color=SUB,
                    halign="left",
                    valign="middle",
                )
            )
            self._cb_offset = True
        self._btns = {}
        for opt in options:
            btn = _mk("chip", opt)
            self._btns[opt] = btn
            btn.bind(on_release=lambda b, o=opt: self.select(o))
            self.add_widget(btn)
        self._sync()

    def select(self, value):
        if self.selected == value:
            return
        self.selected = value
        self._sync()
        if self._cb:
            self._cb(value)

    def _sync(self):
        for opt, btn in self._btns.items():
            btn.state = "down" if opt == self.selected else "normal"
            btn.color = (1, 1, 1, 1) if opt == self.selected else TEXT


def _mk(kind, name, **kw):
    from kivy.factory import Factory

    if kind == "chip":
        return Factory.ChipBtn(text=name, state="normal")
    return Factory.Label(text=name, **kw)


class ToggleCard(BoxLayout):
    """带标题的开关行。"""

    label_text = StringProperty("")
    active = BooleanProperty(False)

    def __init__(self, label, active=False, callback=None, **kw):
        super(ToggleCard, self).__init__(
            orientation="horizontal", size_hint_y=None, height=32, **kw
        )
        self._cb = callback
        from kivy.factory import Factory

        lbl = Factory.RowLabel(text=label, size_hint=(1, 1))
        self.add_widget(lbl)
        self.sw = Factory.SwitchBtn(size_hint=(None, 1), width=52)
        self.sw.bind(on_release=self._toggle)
        self.add_widget(self.sw)
        self.active = active
        self._set_switch(active)

    def _set_switch(self, active):
        self.sw.state = "down" if active else "normal"
        try:
            self.sw.ids.sw_text.text = "开" if active else "关"
        except Exception:
            pass

    def _toggle(self, inst):
        self.active = not self.active
        self._set_switch(self.active)
        if self._cb:
            self._cb(self.active)


class SmsForwarderApp(App):
    c_bg = BG
    c_card = CARD
    c_card2 = CARD2
    c_accent = ACCENT
    c_accent_d = ACCENT_D
    c_text = TEXT
    c_sub = SUB
    c_field = FIELD
    c_line = LINE
    c_danger = DANGER
    c_ok = OKC

    status_text = StringProperty("未启动")
    status_color = ListProperty(SUB)
    service_running = BooleanProperty(False)

    def __init__(self, **kw):
        super(SmsForwarderApp, self).__init__(**kw)
        self.cfg = {}
        self._svc = None
        self._editors = {}
        self.cards_host = None
        self.sim_selected = "-1"

    # ------------------------------------------------------------ App 生命周期
    def build(self):
        # 每帧状态刷新交给 Clock，避免 jnius 线程问题
        self.root = self._build_ui()
        Clock.schedule_interval(self.refresh_status, 1.5)
        Clock.schedule_once(lambda *_: self.refresh_status(), 0.4)
        return self.root

    def on_pause(self):
        return True

    def _get_service(self):
        if self._svc is not None:
            return self._svc
        try:
            from android import AndroidService

            try:
                self._svc = AndroidService("sms_forwarder", "短信转发服务运行中")
            except TypeError:
                self._svc = AndroidService("短信转发服务", "运行中")
        except Exception:
            self._svc = None
        return self._svc

    # ------------------------------------------------------------ UI 构建
    def _card(self, title, hint=None):
        from kivy.factory import Factory

        c = Factory.Card()
        c.add_widget(Factory.CardTitle(text=title))
        if hint:
            c.add_widget(Factory.Hint(text=hint))
        return c

    def _input(self, key, hint, multiline=False, height=None):
        from kivy.factory import Factory

        ti = Factory.Input(hint_text=hint, multiline=multiline)
        if height:
            ti.height = height
        elif multiline:
            ti.height = 96
        self._editors[key] = ti
        return ti

    def _build_ui(self):
        from kivy.factory import Factory
        from kivy.core.window import Window

        cfg = self.cfg = config_store.load_config()

        root = BoxLayout(orientation="vertical", padding=0, spacing=0)
        root.canvas.before.clear()
        with root.canvas.before:
            from kivy.graphics import Color, Rectangle

            Color(*BG)
            self._bg_rect = Rectangle(pos=root.pos, size=root.size)
        root.bind(pos=lambda i, v: setattr(self._bg_rect, "pos", v))
        root.bind(size=lambda i, v: setattr(self._bg_rect, "size", v))

        # 顶部标题栏
        header = BoxLayout(
            orientation="horizontal", size_hint_y=None, height=54, padding=16, spacing=6
        )
        with header.canvas.before:
            from kivy.graphics import Color, Rectangle

            Color(*CARD2)
            rect = Rectangle(pos=header.pos, size=header.size)
        header.bind(pos=lambda i, v: setattr(rect, "pos", v))
        header.bind(size=lambda i, v: setattr(rect, "size", v))
        title = Label(
            text="短信转发器",
            font_size="18sp",
            bold=True,
            color=TEXT,
            halign="left",
            valign="middle",
            size_hint=(1, 1),
        )
        title.bind(width=lambda i, v: setattr(title, "text_size", (v, None)))
        header.add_widget(title)
        self.dot = Label(text="●", font_size="12sp", color=SUB, size_hint=(None, 1), width=20)
        header.add_widget(self.dot)
        self.st_label = Label(
            text=self.status_text, font_size="12.5sp", color=SUB, size_hint=(None, 1), width=96
        )
        header.add_widget(self.st_label)
        root.add_widget(header)

        # 滚动主体
        from kivy.uix.scrollview import ScrollView

        sv = ScrollView(size_hint=(1, 1), bar_width=6, scroll_type=["bars", "content"])
        self.cards_host = BoxLayout(
            orientation="vertical", padding=14, spacing=12, size_hint_y=None
        )
        self.cards_host.bind(minimum_height=self.cards_host.setter("height"))
        sv.add_widget(self.cards_host)

        # ---- 1. 运行状态
        c = self._card("运行状态", "转发服务必须保持前台运行，才能在中后台监听到短信。")
        row = BoxLayout(orientation="horizontal", spacing=10, size_hint_y=None, height=42)
        self.btn_start = self._btn("启动服务", self.do_start, ACCENT)
        self.btn_stop = self._btn("停止服务", self.do_stop, DANGER)
        row.add_widget(self.btn_start)
        row.add_widget(self.btn_stop)
        c.add_widget(row)
        self.hb_label = Factory.Hint(text="心跳：--")
        c.add_widget(self.hb_label)
        self.cards_host.add_widget(c)

        # ---- 2. 权限
        c = self._card("权限", "首次使用请点击授权；部分国产 ROM 还需手动允许自启动与后台运行。")
        self.perm_label = Factory.RowLabel(text="检查中…")
        c.add_widget(self.perm_label)
        prow = BoxLayout(orientation="horizontal", spacing=10, size_hint_y=None, height=42)
        prow.add_widget(self._btn("申请权限", self.do_grant, ACCENT))
        prow.add_widget(self._btn("系统设置", self.do_open_settings, CARD2))
        c.add_widget(prow)
        self.cards_host.add_widget(c)

        # ---- 3. 接收人
        c = self._card("接收人手机号（目标手机）", "支持多个号码，每行一个或逗号分隔；会用本机短信发出。")
        self.sim_row_wrapper = c
        c.add_widget(self._input("receivers", "例如 13800138000", multiline=True))
        self.cards_host.add_widget(c)

        # ---- 4. 发信人过滤
        c = self._card("发信人过滤", "留空表示接收所有号码。支持通配符：1069* 前缀、*10086 后缀、*95588* 包含。")
        self.sender_mode_row = ChipRow(["白名单", "黑名单"], "白名单", None, "模式")
        c.add_widget(self.sender_mode_row)
        c.add_widget(self._input("senders", "例如 10086 / 1069* / 13800138000", multiline=True))
        self.cards_host.add_widget(c)

        # ---- 5. 关键词
        c = self._card("关键词筛选", "任一=命中任意一个词即转发；全部=所有词同时出现；正则=每行一个正则表达式。")
        self.kw_mode_row = ChipRow(["任一", "全部", "正则"], "任一", None, "模式")
        c.add_widget(self.kw_mode_row)
        c.add_widget(self._input("keywords", "例如 验证码 / 交易 / 余额", multiline=True))
        c.add_widget(Factory.RowLabel(text="屏蔽词（命中则不转发）"))
        c.add_widget(self._input("exclude_keywords", "例如 退订 / 广告", multiline=True))
        self.cs_toggle = ToggleCard("区分大小写", bool(cfg.get("case_sensitive")))
        c.add_widget(self.cs_toggle)
        self.cards_host.add_widget(c)

        # ---- 6. 转发内容
        c = self._card("转发内容", "可用变量：{from} 发信人、{time} 时间、{sim} 卡槽、{body} 原文。")
        c.add_widget(self._input("template", "{from} / {time}", multiline=True))
        c.add_widget(Factory.RowLabel(text="单条最大字数"))
        c.add_widget(self._input("max_len", "500", multiline=False))
        t1 = ToggleCard("超长自动拆分多条", bool(cfg.get("split_sms")))
        c.add_widget(t1)
        self.split_toggle = t1
        c.add_widget(Factory.RowLabel(text="去重窗口（分钟，0=不去重）"))
        c.add_widget(self._input("dedup_minutes", "5", multiline=False))
        c.add_widget(Factory.RowLabel(text="转发延迟（秒）"))
        c.add_widget(self._input("delay_seconds", "0", multiline=False))
        c.add_widget(Factory.RowLabel(text="发送用 SIM 卡"))
        self.sim_row = BoxLayout(orientation="horizontal", spacing=8, size_hint_y=None, height=38)
        c.add_widget(self.sim_row)
        self.cards_host.add_widget(c)

        # ---- 7. 总开关 + 操作
        c = self._card("总开关")
        self.enabled_toggle = ToggleCard("启用短信转发", bool(cfg.get("enabled")))
        c.add_widget(self.enabled_toggle)
        row = BoxLayout(orientation="horizontal", spacing=10, size_hint_y=None, height=42)
        row.add_widget(self._btn("保存配置", self.do_save, ACCENT))
        row.add_widget(self._btn("测试转发", self.do_test, ACCENT_D))
        c.add_widget(row)
        row2 = BoxLayout(orientation="horizontal", spacing=10, size_hint_y=None, height=42)
        row2.add_widget(self._btn("清空日志", self.do_clear_log, CARD2))
        row2.add_widget(self._btn("了解保活", self.do_tips, CARD2))
        c.add_widget(row2)
        self.cards_host.add_widget(c)

        # ---- 8. 日志
        c = self._card("转发日志", "最近 40 条。success=已转发 / skip=被规则拦截 / error=失败。")
        self.log_host = BoxLayout(orientation="vertical", size_hint_y=None)
        self.log_host.bind(minimum_height=self.log_host.setter("height"))
        c.add_widget(self.log_host)
        self.cards_host.add_widget(c)

        root.add_widget(sv)

        # 回填配置
        self._fill_values()
        self._build_sim_options()
        return root

    def _btn(self, text, callback, color):
        return FButton(text, color, callback)

    def _fill_values(self):
        cfg = self.cfg

        def setv(key, value):
            ti = self._editors.get(key)
            if ti is not None:
                ti.text = str(value)

        setv("receivers", "\n".join(cfg.get("receivers") or []))
        setv("senders", "\n".join(cfg.get("senders") or []))
        setv("keywords", "\n".join(cfg.get("keywords") or []))
        setv("exclude_keywords", "\n".join(cfg.get("exclude_keywords") or []))
        setv("template", cfg.get("template") or "")
        setv("max_len", cfg.get("max_len", 500))
        setv("dedup_minutes", cfg.get("dedup_minutes", 5))
        setv("delay_seconds", cfg.get("delay_seconds", 0))
        self.sender_mode_row.selected = "黑名单" if cfg.get("sender_mode") == "block" else "白名单"
        self.sender_mode_row._sync()
        mapping = {"any": "任一", "all": "全部", "regex": "正则"}
        self.kw_mode_row.selected = mapping.get(cfg.get("keyword_mode", "any"), "任一")
        self.kw_mode_row._sync()

    def _build_sim_options(self):
        self.sim_options = [("-1", "系统默认")]
        if android_ext.is_android():
            for slot, sub_id, carrier in android_ext.subscription_ids():
                self.sim_options.append((str(sub_id), "卡%d %s" % (slot + 1, carrier or "")))
        from kivy.factory import Factory

        self.sim_row.clear_widgets()
        self._sim_btns = {}
        current = str(self.cfg.get("subs_id", -1))
        if current not in [x[0] for x in self.sim_options]:
            current = "-1"
        self.sim_selected = current
        for val, label in self.sim_options:
            b = Factory.ChipBtn(text=label, state="down" if val == current else "normal")
            b.width = max(88, len(label) * 12)
            b.bind(on_release=lambda btn, v=val: self._pick_sim(v))
            b.color = (1, 1, 1, 1) if val == current else TEXT
            self._sim_btns[val] = b
            self.sim_row.add_widget(b)

    def _pick_sim(self, value):
        self.sim_selected = value
        for v, b in self._sim_btns.items():
            b.state = "down" if v == value else "normal"
            b.color = (1, 1, 1, 1) if v == value else TEXT

    # ------------------------------------------------------------ 读写配置
    def collect_config(self):
        def getv(key):
            ti = self._editors.get(key)
            return ti.text if ti is not None else ""

        def getn(key, default):
            raw = getv(key).strip()
            try:
                return int(float(raw))
            except Exception:
                return default

        return {
            "receivers": rules.parse_list(getv("receivers")),
            "senders": rules.parse_list(getv("senders")),
            "keywords": rules.parse_list(getv("keywords")),
            "exclude_keywords": rules.parse_list(getv("exclude_keywords")),
            "template": getv("template"),
            "max_len": getn("max_len", 500),
            "dedup_minutes": getn("dedup_minutes", 0),
            "delay_seconds": getn("delay_seconds", 0),
            "sender_mode": "block" if self.sender_mode_row.selected == "黑名单" else "allow",
            "keyword_mode": {"任一": "any", "全部": "all", "正则": "regex"}.get(
                self.kw_mode_row.selected, "any"
            ),
            "case_sensitive": bool(self.cs_toggle.active),
            "split_sms": bool(self.split_toggle.active),
            "enabled": bool(self.enabled_toggle.active),
            "subs_id": int(self.sim_selected) if str(self.sim_selected).lstrip("-").isdigit() else -1,
            "log_limit": int(self.cfg.get("log_limit", 300)),
        }

    def do_save(self, *_):
        cfg = self.collect_config()
        self.cfg = config_store.save_config(cfg)
        self._show_toast("配置已保存，服务会自动生效")
        self.refresh_status()

    # ------------------------------------------------------------ 服务启停
    def do_start(self, *_):
        if not android_ext.is_android():
            self._show_toast("请在安卓真机上运行")
            return
        missing = android_ext.missing_permissions()
        if missing:
            self._show_toast("缺少短信权限，请先授权")
            self.do_grant()
            return
        cfg = self.collect_config()
        self.cfg = config_store.save_config(cfg)
        config_store.clear_stop_flag()
        svc = self._get_service()
        try:
            svc.start("sms_forwarder")
            self._show_toast("转发服务已启动")
        except Exception as exc:
            self._show_toast("启动失败：%s" % exc)
        self.refresh_status()

    def do_stop(self, *_):
        config_store.request_stop()
        svc = self._get_service()
        try:
            svc.stop()
        except Exception:
            pass
        self._show_toast("已发送停止指令")
        Clock.schedule_once(lambda *_: self.refresh_status(), 1.2)

    # ------------------------------------------------------------ 权限 / 测试
    def do_grant(self, *_):
        if not android_ext.is_android():
            self._show_toast("请在安卓真机上运行")
            return

        ok = android_ext.request_permissions()
        if not ok:
            android_ext.open_app_settings()
        self._show_toast("请在弹窗中允许短信权限")
        Clock.schedule_once(lambda *_: self.refresh_status(), 1.0)

    def do_open_settings(self, *_):
        if not android_ext.open_app_settings():
            self._show_toast("无法打开设置页")

    def do_test(self, *_):
        cfg = self.collect_config()
        receivers = cfg.get("receivers") or []
        if not receivers:
            self._show_toast("请先填写接收人手机号")
            return
        if not android_ext.is_android():
            self._show_toast("桌面环境无法真机发送")
            return
        missing = android_ext.missing_permissions()
        if missing:
            self._show_toast("缺少权限：%s" % ",".join(m.replace("android.permission.", "") for m in missing))
            return
        text = "【短信转发器】测试消息 %s" % time.strftime("%H:%M:%S")
        ok, err = android_ext.send_sms(receivers[0], text, cfg.get("subs_id", -1))
        self._show_toast("已发送测试短信" if ok else "发送失败 %s" % err)
        self.refresh_status()

    def do_clear_log(self, *_):
        config_store.clear_logs()
        self._show_toast("日志已清空")
        self.refresh_status()

    def do_tips(self, *_):
        from kivy.factory import Factory

        pop = Popup(
            title="保活说明",
            title_size="15sp",
            title_color=TEXT,
            size_hint=(0.88, 0.72),
            background="",
            background_color=(0, 0, 0, 0),
            separator_color=ACCENT,
        )
        card = Factory.Card(padding=[18, 18], size_hint=(1, 1))
        from kivy.uix.scrollview import ScrollView
        from kivy.uix.label import Label as _L

        body = _L(
            text=(
                "Android 8 以后第三方应用无法靠静态注册接收短信，本 App 采用"
                "「前台服务 + 动态注册」的方式，因此必须保证进程存活：\n\n"
                "1. 在系统设置里把本应用加入「自启动 / 后台运行」白名单；\n"
                "2. 关闭本应用的电池优化（省电策略设为「无限制」）；\n"
                "3. 多任务界面把本应用卡片下拉锁定；\n"
                "4. 部分 ROM（MIUI / ColorOS / EMUI）需额外打开「通知栏常驻」；\n"
                "5. 转发会占用本机短信通道，运营商可能计费，请自行确认套餐；\n"
                "6. 全部数据仅存本机，免登录、不联网，卸载即丢失。"
            ),
            font_size="13sp",
            color=TEXT,
            size_hint_y=None,
            halign="left",
            valign="top",
            text_size=(Window.width * 0.78, None),
        )
        body.bind(texture_size=lambda i, v: setattr(body, "height", v[1] + 20))
        svs = ScrollView(size_hint=(1, 0.85))
        svs.add_widget(body)
        card.add_widget(svs)
        card.add_widget(self._btn("知道了", lambda *_: pop.dismiss(), ACCENT))
        pop.content = card
        pop.open()

    # ------------------------------------------------------------ 状态刷新
    @mainthread
    def refresh_status(self, *_):
        try:
            alive = config_store.is_service_alive()
            self.service_running = alive
            if alive:
                self.status_text = "服务运行中"
                self.status_color = OKC
            else:
                self.status_text = "未启动"
                self.status_color = SUB
            self.dot.color = OKC if alive else SUB
            self.st_label.text = self.status_text
            self.st_label.color = self.status_color
            hb = config_store.read_heartbeat()
            if hb and alive:
                self.hb_label.text = "心跳：%.0f 秒前（进程存活）" % (time.time() - hb)
            elif hb:
                self.hb_label.text = "上次心跳：%.0f 秒前（已掉线）" % (time.time() - hb)
            else:
                self.hb_label.text = "心跳：--"
            self.btn_start.set_color(CARD2 if alive else ACCENT)
            self.btn_stop.set_color(DANGER if alive else CARD2)

            if android_ext.is_android():
                missing = android_ext.missing_permissions()
                if missing:
                    self.perm_label.text = "未授权：%s" % ", ".join(
                        m.replace("android.permission.", "") for m in missing[:3]
                    )
                else:
                    self.perm_label.text = "短信权限已授予"
            else:
                self.perm_label.text = "非安卓环境，仅可编辑配置"
        except Exception:
            pass
        try:
            self._render_logs()
        except Exception:
            pass

    def _render_logs(self):
        from kivy.factory import Factory

        logs = config_store.read_logs()[-40:]
        self.log_host.clear_widgets()
        if not logs:
            lbl = Factory.Hint(text="暂无记录")
            self.log_host.add_widget(lbl)
            return
        for entry in reversed(logs):
            lvl = entry.get("level", "info")
            color = {"error": (0.85, 0.42, 0.42, 1), "skip": SUB}.get(lvl, TEXT)
            mark = {"error": "✕", "skip": "○"}.get(lvl, "✓")
            line = "[%s] %s %s" % (entry.get("ts", "")[5:], mark, entry.get("msg", ""))
            lbl = Factory.LogRow(text=line, color=color)
            self.log_host.add_widget(lbl)

    # ------------------------------------------------------------ 工具
    def _show_toast(self, text):
        if android_ext.is_android():
            android_ext.toast(text)
        print("[ui] %s" % text)


def main():
    SmsForwarderApp().run()


if __name__ == "__main__":
    main()
