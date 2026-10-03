# -*- coding: utf-8 -*-
"""UI 冒烟测试（桌面运行，不需要真机）。

有可用窗口时：完整构建界面（App.build）+ 配置读写 + 日志渲染。
无窗口环境（CI / 无显示 / 远程会话）：退化为控件级检查，仍然校验
kv 规则能否加载、自定义控件能否实例化。

用法：python tests/smoke_ui.py
"""
import os
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

tmp_home = tempfile.mkdtemp(prefix="smsfw-kivy-")
os.environ.setdefault("KIVY_HOME", tmp_home)
os.environ.setdefault("KIVY_NO_ARGS", "1")
os.environ.setdefault("KIVY_NO_FILELOG", "1")

from common import config_store  # noqa: E402

data_dir = tempfile.mkdtemp(prefix="smsfw-data-")
config_store.APP_DIR_HINTS.insert(0, data_dir)

import main as main_mod  # noqa: E402
from main import ChipRow, FButton, SmsForwarderApp, ToggleCard  # noqa: E402


def check_widgets(app=None):
    """控件级检查：kv 规则加载 + 自定义控件实例化。

    kv 里用了 app.xxx 取主题色，因此必须先把 App 登记为 running app
    （真实运行时由 App.run() 完成）。
    """
    from kivy.factory import Factory

    Factory.Card()
    Factory.Input(multiline=True)
    Factory.RowLabel(text="x")
    Factory.Hint(text="x")
    btn = Factory.ChipBtn(text="任一")
    btn.state = "down"
    btn.state = "normal"
    Factory.SwitchBtn()
    Factory.LogRow(text="log")
    ToggleCard("区分大小写", True)
    row = ChipRow(["白名单", "黑名单"], "白名单", None, "模式")
    row.select("黑名单")
    assert row.selected == "黑名单"
    fb = FButton("Go", (0.1, 0.4, 0.3, 1))
    fb.set_color((0.5, 0.5, 0.5, 1))
    fb.state = "down"
    fb.state = "normal"
    print("[partial] 控件级检查通过")


def check_full_ui():
    """完整走一遍界面构建与交互序列化。"""
    from kivy.app import App

    app = SmsForwarderApp()
    App._running_app = app  # 模拟 App.run() 做的事
    root = app.build()
    assert root is not None, "build() 应返回根控件"

    app._editors["receivers"].text = "13800000000\n13900000000"
    app._editors["keywords"].text = "验证码, 余额"
    app._editors["senders"].text = "1069*"
    app._editors["template"].text = "[转发]{from} {body}"
    app.sender_mode_row.select("黑名单")
    app.kw_mode_row.select("全部")

    cfg = app.collect_config()
    assert cfg["receivers"] == ["13800000000", "13900000000"], cfg["receivers"]
    assert cfg["keywords"] == ["验证码", "余额"], cfg["keywords"]
    assert cfg["sender_mode"] == "block", cfg["sender_mode"]
    assert cfg["keyword_mode"] == "all", cfg["keyword_mode"]
    assert cfg["subs_id"] == -1, cfg

    config_store.save_config(cfg)
    reloaded = config_store.load_config()
    assert reloaded["senders"] == ["1069*"], reloaded["senders"]
    assert reloaded["template"] == "[转发]{from} {body}"

    config_store.append_log({"ts": "2026-01-01 00:00:00", "level": "info", "msg": "测试"})
    app.refresh_status()
    app._render_logs()
    assert len(config_store.read_logs()) == 1
    print("[full] 界面构建、配置读写、日志渲染通过")


def main():
    from kivy.app import App

    dummy = None
    try:
        from kivy.core.window import Window

        if Window.width > 0:
            dummy = SmsForwarderApp()
            App._running_app = dummy
    except Exception as exc:
        print("[skip] 无可用窗口：%s" % type(exc).__name__)
    if dummy is None:
        print("[skip] 无可用窗口，退化为纯控件级检查")
    check_widgets()
    if dummy is not None:
        check_full_ui()
    print("SMOKE OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
