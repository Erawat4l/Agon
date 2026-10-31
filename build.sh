#!/usr/bin/env bash
# Arena AI - one-command APK builder for Linux / macOS.
#
#   ./build.sh            -> app/build/outputs/apk/debug/app-debug.apk
#   ./build.sh release    -> app/build/outputs/apk/release/app-release.apk
#   ./build.sh clean
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-debug}"

if ! command -v java >/dev/null 2>&1; then
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  else
    echo "[X] Java not found. Install JDK 17+ (e.g. 'sudo apt install openjdk-17-jdk')." >&2
    exit 1
  fi
fi
echo "[1/3] Java: $(java -version 2>&1 | head -1)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -d "$HOME/Android/Sdk" ]; then SDK="$HOME/Android/Sdk"; fi
if [ -z "$SDK" ] && [ -d "$HOME/Library/Android/sdk" ]; then SDK="$HOME/Library/Android/sdk"; fi
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "[X] Android SDK not found. Set ANDROID_HOME (e.g. export ANDROID_HOME=\$HOME/Android/Sdk)." >&2
  exit 1
fi
echo "[2/3] SDK: $SDK"
printf 'sdk.dir=%s\n' "$SDK" > local.properties

case "$MODE" in
  clean)   ./gradlew clean; exit 0 ;;
  release) TASK=assembleRelease ;;
  *)       TASK=assembleDebug ;;
esac

echo "[3/3] Building ($TASK)..."
./gradlew --no-daemon "$TASK"

find app/build/outputs/apk -name '*.apk' -print
