#!/usr/bin/env bash
# Gitee Go 构建脚本：在国内构建机上用腾讯镜像预装 SDK/NDK，再打 buildozer 包。
# 设计目标：所有网络下载都绕开 dl.google.com / github.com。
set -euo pipefail

MIRROR="https://mirrors.cloud.tencent.com/AndroidSDK"
SDK=/opt/android-sdk
NDK=/opt/android-ndk
P4A=/opt/python-for-android

step() { echo; echo "=================== $* ==================="; }

step "0. 环境探测"
cat /etc/os-release || true
id
uname -a
java -version 2>&1 || true

SUDO=""
if [ "$(id -u)" -ne 0 ]; then SUDO="sudo"; fi
export DEBIAN_FRONTEND=noninteractive

step "1. 安装系统依赖"
# 精简镜像常缺 man 目录，openjdk postinst 的 update-alternatives 建符号链接会失败
$SUDO mkdir -p /usr/share/man/man1 /usr/share/man/man7
$SUDO apt-get update -y
$SUDO apt-get install -y --no-install-recommends \
  git zip unzip curl ca-certificates \
  openjdk-17-jdk-headless \
  autoconf automake libtool pkg-config cmake ccache patch \
  zlib1g-dev libncurses-dev libffi-dev libssl-dev \
  python3-venv python3-pip python3-dev || {
  echo "apt 安装有残留，尝试修复..."
  $SUDO dpkg --configure -a
  $SUDO apt-get install -fy
}
$SUDO dpkg --configure -a
java -version

step "2. 预下载 Android SDK（腾讯镜像）"
dl() { curl -fSL --retry 3 --retry-delay 5 --connect-timeout 20 -o "$2" "$1"; }
# 镜像文件名会变（如 platform-34 已改 ext 命名），依次尝试候选 URL
dl_any() {
  local out="$1"; shift
  local u
  for u in "$@"; do
    echo "尝试下载: $u"
    if curl -fSL --retry 2 --retry-delay 3 --connect-timeout 20 -o "$out" "$u"; then
      return 0
    fi
    echo "  -> 不可用，换下一个候选"
  done
  echo "错误：所有候选 URL 均下载失败" >&2
  return 1
}
$SUDO mkdir -p "$SDK/cmdline-tools" "$SDK/platforms" "$SDK/build-tools"

dl "$MIRROR/commandlinetools-linux-11076708_latest.zip" /tmp/cmdtools.zip
$SUDO unzip -q /tmp/cmdtools.zip -d "$SDK/cmdline-tools"
[ -d "$SDK/cmdline-tools/latest" ] || $SUDO mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"

dl "$MIRROR/platform-tools_r34.0.5-linux.zip" /tmp/pt.zip
$SUDO unzip -q -o /tmp/pt.zip -d "$SDK"

dl_any /tmp/p34.zip \
  "$MIRROR/platform-34-ext12_r01.zip" \
  "$MIRROR/platform-34-ext11_r01.zip" \
  "$MIRROR/platform-34-ext10_r01.zip" \
  "$MIRROR/platform-34-ext8_r01.zip" \
  "$MIRROR/platform-34-ext7_r03.zip" \
  "$MIRROR/platform-34_r03.zip"
rm -rf /tmp/p34 && mkdir -p /tmp/p34 && unzip -q /tmp/p34.zip -d /tmp/p34
$SUDO rm -rf "$SDK/platforms/android-34"
$SUDO mv /tmp/p34/* "$SDK/platforms/android-34"

dl_any /tmp/bt.zip \
  "$MIRROR/build-tools_r34-linux.zip" \
  "$MIRROR/build-tools_r34.0.0-linux.zip"
rm -rf /tmp/bt && mkdir -p /tmp/bt && unzip -q /tmp/bt.zip -d /tmp/bt
$SUDO rm -rf "$SDK/build-tools/34.0.0"
$SUDO mv /tmp/bt/* "$SDK/build-tools/34.0.0"

step "3. 预下载 Android NDK r25b（腾讯镜像）"
dl_any /tmp/ndk.zip \
  "$MIRROR/android-ndk-r25b-linux.zip" \
  "$MIRROR/android-ndk-r25b-linux-x86_64.zip"
rm -rf /tmp/ndk && mkdir -p /tmp/ndk && unzip -q /tmp/ndk.zip -d /tmp/ndk
$SUDO rm -rf "$NDK"
$SUDO mv /tmp/ndk/* "$NDK"

export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK" ANDROID_NDK_HOME="$NDK"
export JAVA_HOME
JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
yes | $SUDO "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
# CI 容器临时环境，放开权限避免 buildozer 写入失败
$SUDO chmod -R a+rwX "$SDK" "$NDK"

step "4. 克隆 python-for-android（Gitee 官方镜像，绕开 github.com）"
$SUDO rm -rf "$P4A"
$SUDO git clone --depth 1 https://gitee.com/mirrors_kivy/python-for-android.git "$P4A"
$SUDO chmod -R a+rwX "$P4A"

step "5. 配置 Gradle 走阿里云镜像（AGP/androidx 依赖绕开 dl.google.com）"
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/china-mirror.gradle" <<'EOF'
settingsEvaluated { settings ->
    settings.pluginManagement {
        repositories {
            maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
            maven { url 'https://maven.aliyun.com/repository/google' }
            gradlePluginPortal()
            google()
            mavenCentral()
        }
    }
}
allprojects {
    buildscript {
        repositories {
            maven { url 'https://maven.aliyun.com/repository/google' }
            maven { url 'https://maven.aliyun.com/repository/central' }
        }
    }
    repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/central' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        google()
        mavenCentral()
    }
}
EOF

step "6. 安装 buildozer（清华 PyPI 镜像）"
python3 -m pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple || true
python3 -m pip install --user --upgrade pip
python3 -m pip install --user --upgrade "buildozer>=1.5" "cython<3" virtualenv
python3 -m pip install --user -e "$P4A"
export PATH="$PATH:$HOME/.local/bin"

step "7. 改写 buildozer.spec 指向本地 SDK/NDK/p4a"
cp buildozer.spec buildozer.spec.bak
sed -i '/^\[buildozer\]/i android.sdk_path = /opt/android-sdk\nandroid.ndk_path = /opt/android-ndk\np4a.source_dir = /opt/python-for-android\n' buildozer.spec
cat buildozer.spec

step "8. 开始打包（约 25-45 分钟）"
buildozer -v android debug 2>&1 | tee build.log

step "9. 产物"
ls -la bin/
