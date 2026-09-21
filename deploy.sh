#!/usr/bin/env bash
# 快速部署 qimu-guide-app 到已连接的 Android 设备
# 用法：
#   ./deploy.sh            编译 + 安装 + 拉起
#   ./deploy.sh -r         安装前先卸载旧包（换签名/换环境时用）
#   ./deploy.sh -n         装完不自动拉起 app
#   ./deploy.sh -c         安装前 clean 一次
#   ./deploy.sh -e dev     本地联调：API 走 127.0.0.1:8787 + 开发密钥（配合 adb reverse）
#   ./deploy.sh -e prod    线上环境：API 走 api.equavision.cn + 生产密钥（默认）
set -euo pipefail

cd "$(dirname "$0")"

PKG="com.qimu.guide"
FORCE_UNINSTALL=0
NO_LAUNCH=0
DO_CLEAN=0
QIMU_ENV="prod"

while getopts "rnce:" opt; do
  case "$opt" in
    r) FORCE_UNINSTALL=1 ;;
    n) NO_LAUNCH=1 ;;
    c) DO_CLEAN=1 ;;
    e) QIMU_ENV="$OPTARG" ;;
    *) echo "用法: $0 [-r 先卸载] [-n 不拉起] [-c clean] [-e dev|prod]"; exit 1 ;;
  esac
done

case "$QIMU_ENV" in
  dev|prod) ;;
  *) echo "❌ 未知环境: $QIMU_ENV（仅支持 dev / prod）"; exit 1 ;;
esac
echo "🌍 环境：$QIMU_ENV"

# 1. 确认设备
DEVICE_COUNT=$(adb devices | grep -cw "device" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "❌ 没检测到设备。检查：数据线是否支持传输 / USB 模式选『传输文件』/ 已开 USB 调试并授权。"
  adb devices -l
  exit 1
fi
echo "📱 设备："
adb devices -l | grep -w "device" | grep -v "List of"

# 本地联调：确保 adb reverse 隧道存在（App 指向 127.0.0.1:8787 时依赖它，重插/重连后易丢）
adb reverse tcp:8787 tcp:8787 2>/dev/null || true

if [ "$FORCE_UNINSTALL" -eq 1 ]; then
  echo "🗑  卸载旧包 $PKG ..."
  adb uninstall "$PKG" 2>/dev/null || echo "   (设备上无旧包，跳过)"
fi

[ "$DO_CLEAN" -eq 1 ] && { echo "🧹 clean ..."; ./gradlew clean; }

# 2. 安装（签名不匹配时自动卸载重装）
echo "🔨 编译 + 安装 ..."
GRADLE_ARGS=(-PqimuEnv="$QIMU_ENV")
# 密钥注入：
#   · dev 联调：强制 dev 密钥（覆盖本机 ~/.gradle/gradle.properties 里的 prod 密钥，
#     避免本地 8787 鉴权 401）。
#   · prod 构建：外部传了 QIMU_APP_SHARED_SECRET 则显式注入（CI 场景）；
#     未传时由 Gradle 读取 ~/.gradle/gradle.properties / local.properties。
if [ "$QIMU_ENV" = "dev" ]; then
  GRADLE_ARGS+=(-PQIMU_APP_SHARED_SECRET=dev-app-shared-secret-change-in-prod)
elif [ -n "${QIMU_APP_SHARED_SECRET:-}" ]; then
  GRADLE_ARGS+=(-PQIMU_APP_SHARED_SECRET="$QIMU_APP_SHARED_SECRET")
fi
if ! ./gradlew "${GRADLE_ARGS[@]}" installDebug; then
  echo "⚠️  安装失败，尝试卸载旧包后重装（多为签名不匹配）..."
  adb uninstall "$PKG" 2>/dev/null || true
  ./gradlew "${GRADLE_ARGS[@]}" installDebug
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
