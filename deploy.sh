#!/usr/bin/env bash
# 快速部署 qimu-guide-app 到已连接的 Android 设备
# 用法：
#   ./deploy.sh            编译 + 安装 + 拉起
#   ./deploy.sh -r         安装前先卸载旧包（换签名/换环境时用）
#   ./deploy.sh -n         装完不自动拉起 app
#   ./deploy.sh -c         安装前 clean 一次
set -euo pipefail

cd "$(dirname "$0")"

PKG="com.qimu.guide"
FORCE_UNINSTALL=0
NO_LAUNCH=0
DO_CLEAN=0

while getopts "rnc" opt; do
  case "$opt" in
    r) FORCE_UNINSTALL=1 ;;
    n) NO_LAUNCH=1 ;;
    c) DO_CLEAN=1 ;;
    *) echo "用法: $0 [-r 先卸载] [-n 不拉起] [-c clean]"; exit 1 ;;
  esac
done

# 1. 确认设备
DEVICE_COUNT=$(adb devices | grep -cw "device" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "❌ 没检测到设备。检查：数据线是否支持传输 / USB 模式选『传输文件』/ 已开 USB 调试并授权。"
  adb devices -l
  exit 1
fi
echo "📱 设备："
adb devices -l | grep -w "device" | grep -v "List of"

if [ "$FORCE_UNINSTALL" -eq 1 ]; then
  echo "🗑  卸载旧包 $PKG ..."
  adb uninstall "$PKG" 2>/dev/null || echo "   (设备上无旧包，跳过)"
fi

[ "$DO_CLEAN" -eq 1 ] && { echo "🧹 clean ..."; ./gradlew clean; }

# 2. 安装（签名不匹配时自动卸载重装）
echo "🔨 编译 + 安装 ..."
if ! ./gradlew installDebug; then
  echo "⚠️  安装失败，尝试卸载旧包后重装（多为签名不匹配）..."
  adb uninstall "$PKG" 2>/dev/null || true
  ./gradlew installDebug
fi

# 3. 拉起
if [ "$NO_LAUNCH" -eq 0 ]; then
  echo "🚀 拉起 app ..."
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
    && echo "✅ 已部署并拉起 $PKG" \
    || echo "✅ 已部署 $PKG（拉起失败，手动点开即可）"
else
  echo "✅ 已部署 $PKG（未拉起）"
fi
