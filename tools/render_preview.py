# -*- coding: utf-8 -*-
"""渲染界面预览图（仅用于开发预览，不参与 APK 打包）。

用法：pip install kivy && python tools/render_preview.py
产物：preview/ui-1.png、ui-2.png、ui-3.png
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

OUT = os.path.join(ROOT, "preview")
os.makedirs(OUT, exist_ok=True)

tmp_home = os.path.join(ROOT, ".preview_kivy_home")
os.makedirs(tmp_home, exist_ok=True)
os.environ.setdefault("KIVY_HOME", tmp_home)
os.environ.setdefault("KIVY_NO_ARGS", "1")
os.environ.setdefault("KIVY_NO_FILELOG", "1")

from common import config_store  # noqa: E402

sample_dir = os.path.join(ROOT, ".preview_data")
os.makedirs(sample_dir, exist_ok=True)
config_store.APP_DIR_HINTS.insert(0, sample_dir)

# 用系统中文字体，保证预览图里的中文不是方块
FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\Microsoft YaHei UI\msyh.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
]

from kivy.core.text import LabelBase  # noqa: E402

for font in FONT_CANDIDATES:
    if os.path.exists(font):
        LabelBase.register(name="Roboto", fn_regular=font)
        break

from kivy.app import App  # noqa: E402
from kivy.core.window import Window  # noqa: E402
from kivy.uix.scrollview import ScrollView  # noqa: E402

Window.size = (430, 940)

from main import SmsForwarderApp  # noqa: E402


def build_app():
    # 造一份示例配置
    config_store.save_config(
        {
            "receivers": ["13901234567", "13809876543"],
            "senders": ["1069*", "10086"],
            "sender_mode": "allow",
            "keywords": ["验证码", "余额", "交易"],
            "keyword_mode": "any",
            "exclude_keywords": ["退订"],
            "template": "[转发] 来自 {from}\n时间 {time}\n\n{body}",
            "max_len": 500,
            "split_sms": True,
            "dedup_minutes": 5,
            "delay_seconds": 0,
            "enabled": True,
            "subs_id": -1,
            "log_limit": 40,
        }
    )
    for lvl, msg in (
        ("info", "转发 -> 13901234567 成功"),
        ("info", "转发 -> 13809876543 成功"),
        ("skip", "未命中关键词 | 10690300123"),
        ("skip", "去重命中，跳过转发 | 10086"),
        ("error", "转发 -> 13809876543 失败：SEND_SMS denied"),
    ):
        config_store.append_log(
            {"ts": "2026-10-03 15:20:11", "level": lvl, "msg": msg}
        )

    app = SmsForwarderApp()
    App._running_app = app
    root = app.build()
    Window.add_widget(root)  # 让 root 撑满窗口（App.run 内部也会这么做）
    app._pick_sim("-1")
    app.refresh_status()
    return app, root


def main():
    from kivy.base import EventLoop
    from kivy.clock import Clock

    app, root = build_app()
    sv = [w for w in root.walk() if isinstance(w, ScrollView)][0]

    shots = [
        ("ui-1.png", None),
        ("ui-2.png", app._editors["keywords"]),
        ("ui-3.png", app.log_host.children[0] if app.log_host.children else None),
    ]
    state = {"i": 0, "phase": 0}

    def _tick(dt):
        # phase 0: 滚动到位；phase 1: 让界面再绘制一帧；phase 2: 导出
        i = state["i"]
        if i >= len(shots):
            app.stop()
            return False
        name, target = shots[i]
        if state["phase"] == 0:
            if target is not None:
                sv.scroll_to(target, padding=24, animate=False)
            else:
                sv.scroll_y = 1
            state["phase"] = 1
        elif state["phase"] == 1:
            EventLoop.idle()
            state["phase"] = 2
        else:
            root.export_to_png(os.path.join(OUT, name))
            print("saved %s" % name)
            state["i"] += 1
            state["phase"] = 0
        return True

    Clock.schedule_interval(_tick, 0.25)
    app.run()


if __name__ == "__main__":
    main()
