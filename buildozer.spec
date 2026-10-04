[app]

# ---- 基本信息 ----------------------------------------------------------
title = 短信转发器
package.name = smsforwarder
package.domain = org.example
version = 1.0.0

# 打包进去的目录/文件类型（同目录树会被整体打进 APK）
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,json,txt
source.include_patterns = common/*,service/*,assets/*
source.exclude_exts = spec,gif,md

# 依赖：jnius 与 android recipe 由 python3 recipe 自动带上
requirements = python3,kivy==2.3.0,android

# 有自定义图标时取消下面两行注释即可
# icon.filename = %(source.dir)s/icon.png
# presplash.filename = %(source.dir)s/presplash.png

orientation = portrait
fullscreen = 0

android.permissions =
    RECEIVE_SMS,
    SEND_SMS,
    READ_SMS,
    READ_PHONE_STATE,
    POST_NOTIFICATIONS,
    FOREGROUND_SERVICE,
    FOREGROUND_SERVICE_DATA_SYNC,
    WAKE_LOCK

# ---- Android 构建配置 --------------------------------------------------
android.archs = arm64-v8a, armeabi-v7a
android.api = 34
android.minapi = 23
android.accept_sdk_license = True
android.allow_backup = True

# 核心：把 service/main.py 注册为常驻的前台服务
# 服务名 sms_forwarder 必须与 UI 中 AndroidService("sms_forwarder", ...) 一致
services = sms_forwarder:service/main.py:foreground

# 主题色（深青绿）
presplash.color = #0F6E56

# 钉到稳定发布版：其 python3 recipe 用 CPython 3.11.5（master 已升到 3.14，
# 与 NDK 28c 的 bionic 头文件不兼容，编译 remote_debugging.c 报 preadv/pwritev 未声明）
# 该版本对应 NDK r25b，已在 ubuntu-22.04 上久经验证。
p4a.branch = v2024.01.21

[buildozer]
log_level = 2
# CI 构建机常以 root 运行且无 TTY，必须为 0 否则 buildozer 交互式确认导致 EOFError
warn_on_root = 0
build_dir = ./.buildozer
bin_dir = ./bin
