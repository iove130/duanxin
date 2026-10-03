# 短信转发器（SMS Forwarder）

两台安卓手机之间通过**短信通道**做定向转发：**A 机**收到符合规则的短信 → 自动用 A 机号码转发给 **B 机**。
纯本地运行，**免登录、不联网、无后台服务器**，所有配置只存在本机。

> 本项目使用 **Python（Kivy + pyjnius）** 实现，通过 python-for-android 打成 APK。

---

## 一、功能

| 能力 | 说明 |
| --- | --- |
| 接收人设置 | 支持**多个**目标手机号，逗号/换行分隔；转发走本机短信通道逐个发出 |
| 发信人过滤 | 白名单 / 黑名单两模式；支持通配符 `1069*`（前缀）、`*10086`（后缀）、`*95588*`（包含）；留空表示接收所有人 |
| 关键词筛选 | 三种模式：**任一**（命中一个即转发）、**全部**（全部词同时出现）、**正则**（每行一个表达式） |
| 屏蔽词 | 命中则不转发，可写普通词或 `/regex/` 形式的正则 |
| 转发模板 | 可用变量 `{from}` 发信人、`{time}` 时间、`{sim}` 卡槽、`{body}` 原文 |
| 超长拆分 | 超过设定字数自动拆成多条，带 `(1/3)` 序号前缀 |
| 去重 | 同号码 + 同内容在设定分钟窗口内只转发一次，防止重复扣费 |
| 双卡支持 | 可指定用哪张 SIM 发送（需要 Android 5.1+ 读取订阅信息） |
| 转发日志 | App 内查看最近记录，✓ 已转发 / ○ 被规则拦截 / ✕ 失败 |
| 热更新 | 改完配置点「保存」，后台服务 1 秒内自动生效，无需重启 |

---

## 二、目录结构

```
sms-forwarder/
├── main.py                 Kivy 主界面（配置 + 日志 + 服务启停）
├── service/main.py         常驻前台服务：监听 SMS_RECEIVED 并执行转发
├── common/
│   ├── rules.py            规则引擎（纯 Python，可桌面单测）
│   ├── config_store.py     配置与日志的跨进程存储
│   └── android_ext.py      pyjnius 封装：权限、SmsManager、BroadcastReceiver
├── tests/                      28 项单元测试 + headless 界面冒烟
├── tools/render_preview.py     桌面渲染 UI 预览图（可选，不参与打包）
├── preview/                    预览图 ui-1 ~ ui-3
├── buildozer.spec              安卓打包配置（含 services 注册）
├── .github/workflows/build-apk.yml   GitHub Actions 自动出 APK
├── .workflow/MasterPipeline.yml      Gitee Go 流水线（国内网络适配）
└── ci/gitee_go_build.sh              Gitee Go 构建脚本（腾讯/阿里镜像加速）
```

核心流程：

```
短信到达 A 机
   └─ 系统广播 SMS_RECEIVED
        └─ Python 常驻服务（动态注册的 BroadcastReceiver）
              ├─ 解析发信人 / 正文 / 时间 / 卡槽
              ├─ 规则引擎：发信人过滤 → 关键词 → 屏蔽词 → 去重
              ├─ 按模板渲染 + 超长拆分
              └─ SmsManager 逐条发给接收人 → 写本地日志（App 可见）
```

---

## 三、打包 APK

Buildozer **不支持 Windows 原生环境**，请用下面任一方式：

### 方式 A：GitHub Actions（推荐，零配置）

1. 把整个 `sms-forwarder` 目录推到一个 GitHub 仓库；
2. 仓库页面 → **Actions** → `Build Android APK` → **Run workflow**；
3. 首次构建约 20–35 分钟（要下载 Android SDK/NDK），完成后在 **Artifacts** 里下载 APK。

### 方式 B：Gitee Go 流水线（国内网络适配版）

仓库已内置 `.workflow/MasterPipeline.yml` + `ci/gitee_go_build.sh`，针对国内构建机做了三项适配：
SDK/NDK 走腾讯镜像预下载、p4a 走 Gitee 官方镜像、Gradle 依赖走阿里云镜像。

1. 推送本仓库到 Gitee；
2. 仓库页 → **流水线** → 开通 Gitee Go（需账号绑定手机号；单仓库送 200 分钟，个人每月再送 500 分钟）；
3. 若推送后流水线未自动出现：**新建流水线 → 空模板**，把 `.workflow/MasterPipeline.yml` 内容原样粘贴保存；
4. push 到 `main` 或手动点「执行」触发构建，约 30–45 分钟；
5. 构建完成后在流水线的**发布记录 / 制品**中下载 APK。

排障：构建日志按 `=== N. xxx ===` 分段，哪段挂了就查哪段（下载类失败多为镜像 URL 变动，改 `ci/gitee_go_build.sh` 顶部 `MIRROR` 即可）。

### 方式 C：本地 Linux / WSL2

```bash
sudo apt update
sudo apt install -y git zip unzip openjdk-17-jdk python3-pip \
    autoconf libtool pkg-config zlib1g-dev libncurses5-dev libffi-dev libssl-dev
pip3 install --user buildozer cython
cd sms-forwarder
buildozer -v android debug      # 首次会下载 SDK/NDK，耐心等待
# 产物：bin/smsforwarder-1.0.0-arm64-v8a_armeabi-v7a-debug.apk
```

桌面预览（可编辑配置，不能真机收发短信）：

```bash
pip install -r requirements.txt
python main.py
```

---

## 四、安装与配置

1. 手机安装 APK（若提示"未知来源"，在系统设置里允许本次安装）；
2. 打开 App → **申请权限**，允许「读取短信 / 发送短信 / 通知 / 电话状态」；
   - Android 13+ 还需允许**通知权限**（前台服务要显示常驻通知）；
3. 填写 **接收人手机号**（B 机号码）；
4. 按需填写 **发信人过滤** 与 **关键词**；
5. 点 **保存配置** → **启动服务**，顶部状态变成绿色「服务运行中」；
6. 点 **测试转发** 验证通道是否正常，B 机应收到一条 `[短信转发器] 测试消息`。

> 配置修改保存后立即生效，不必停止服务。

---

## 五、必须做的保活设置（非常重要）

Android 8 以后第三方应用**无法**靠静态注册接收短信广播，本项目采用
「前台服务 + 动态注册 Receiver」的方案，因此进程必须活着：

1. 系统设置 → 应用管理 → 本应用 → **电池/省电策略设为「无限制」**（关闭电池优化）；
2. 在手机管家里把它加入 **自启动 / 后台运行** 白名单；
3. 多任务界面把 App 卡片 **下拉锁定**（避免被一键清理）；
4. 部分 ROM（MIUI / ColorOS / EMUI / MagicOS）还需打开 **通知栏常驻**；
5. 若发现状态掉成灰色「未启动」，打开 App 一次即可重新拉起服务。

App 内的 **「了解保活」** 按钮也有同样说明。

---

## 六、常见问题

**Q：一直收不到短信、日志空白？**
A：检查①权限是否都给了；②服务是否为绿色「运行中」；③是否被省电策略杀掉；④确认关键词规则命中（日志里 `○` 表示被规则拦截，会写明原因）。

**Q：提示"命中但未配置接收人"？**
A：接收人列表为空，去 App 里填好并保存。

**Q：转发失败 ✕？**
A：多为「短信余额不足」「SIM 未就绪」「被运营商风控」。可先在 App 里手动给同一号码发一条短信验证。

**Q：是否可以两台手机互转？**
A：可以，两台都装同一个 APK，互填对方号码并设置各自的过滤规则即可。注意别让规则形成回环（A 转给 B，B 又转回 A），建议至少一端的发信人过滤加上另一方的号码黑名单。

**Q：数据会上传吗？**
A：不会。工程里没有任何网络请求代码，配置与日志都写在 App 私有目录，卸载即清除。

---

## 七、合规提醒

- `SEND_SMS` / `RECEIVE_SMS` 属于敏感权限，**仅供自用**：若要上架 Google Play，需提交短信权限声明并通过审核；
- 转发会占用本机短信通道，运营商可能按条计费，请自行确认套餐；
- 请勿用于诈骗、验证码窃取等违法用途。

---

## 八、本地自检

```bash
python tests/test_rules.py     # 28 项规则引擎测试
python tests/smoke_ui.py       # headless 冒烟：kv 加载 + 界面构建 + 配置存取
```

`smoke_ui.py` 需要本机装 Kivy（`pip install kivy`），会以 mock 窗口构建完整界面，
不会真的弹出窗口，适合改完 UI 后快速自检。

覆盖号码匹配（国际码/三种通配符）、关键词三模式、屏蔽词、去重窗口、模板渲染、长短信拆分、配置读写等。
