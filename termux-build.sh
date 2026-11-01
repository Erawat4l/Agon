#!/usr/bin/env bash
# Build Agon's APK on a phone, in Termux, with no PC involved.
#
#   bash termux-build.sh 1.4.49
#
# What it does: installs an Ubuntu container with proot-distro (Termux alone cannot run
# Gradle - the Android toolchain expects a normal Linux userland), installs JDK 17 and the
# Android SDK there, and runs the same Gradle task publish.bat runs on Windows
# (assembleDebug), stamping the version the same way.
#
# READ THIS FIRST, because it is not a sure thing:
#
#  1. aapt2, the resource compiler, ships as x86_64 Linux binaries only - there is no
#     official ARM build. On an arm64 phone it either runs under box64 (slow, needs
#     installing) or fails with "Syntax error" / "cannot execute binary file". This script
#     detects that and says so instead of failing obscurely.
#  2. It downloads roughly 3-4 GB and can take 1-2 hours on a phone.
#  3. It needs ~5 GB free and a good charger; Gradle is the most power-hungry thing most
#     phones ever run.
#
# The APK it produces is equivalent to what publish.bat produces: assembled debug, so it is
# signed with THAT machine's debug key. See the signing note at the end of this file.
set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "usage: bash termux-build.sh 1.4.49" >&2
  exit 1
fi
# versionCode is derived from versionName by the build itself, so only the name is passed.
TAG="v$VERSION"

if [ ! -d /data/data/com.termux ]; then
  echo "[!] This looks like a normal Linux shell, not Termux."
  echo "    Use ./build.sh instead - it is shorter and needs no container."
  exit 1
fi

echo "[1/5] Termux packages..."
pkg update -y >/dev/null
pkg install -y proot-distro >/dev/null

echo "[2/5] Ubuntu container (first run downloads ~100 MB)..."
if ! proot-distro list --installed 2>/dev/null | grep -q ubuntu; then
  proot-distro install ubuntu
fi

echo "[3/5] Toolchain + source + build inside the container..."
# Everything runs in one proot session: the container's filesystem and the working copy
# live there, so nothing depends on Termux's own prefixes.
proot-distro login ubuntu -- bash -lc "
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq openjdk-17-jdk unzip curl git python3 >/dev/null

export ANDROID_HOME=/root/android-sdk
if [ ! -x \"\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\" ]; then
  curl -fL -o /tmp/cmdtools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  mkdir -p \"\$ANDROID_HOME/cmdline-tools\"
  unzip -q -o /tmp/cmdtools.zip -d \"\$ANDROID_HOME/cmdline-tools\"
  mv \"\$ANDROID_HOME/cmdline-tools/cmdline-tools\" \"\$ANDROID_HOME/cmdline-tools/latest\"
fi

if [ ! -d \"\$ANDROID_HOME/platforms/android-35\" ]; then
  yes | \"\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\" --licenses >/dev/null || true
  \"\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\" \
    'platforms;android-35' 'build-tools;35.0.0' platform-tools
fi

# ---- the one thing that really decides whether this works
if ! \"\$ANDROID_HOME/build-tools/35.0.0/aapt2\" version >/dev/null 2>&1; then
  echo ''
  echo '  [X] aapt2 will not run on this CPU.'
  echo '      Google ships aapt2 as an x86_64 Linux binary and there is no ARM build,'
  echo '      so Gradle cannot compile resources on an arm64 phone without an emulator'
  echo '      (box64) or a third-party arm64 build of aapt2.'
  echo ''
  echo '      Nothing was built. A PC, or any x86_64 Linux machine, is the short path.'
  exit 3
fi

cd /root
if [ ! -d agon ]; then
  git clone https://github.com/Erawat4l/Agon.git agon
else
  cd agon && git pull --ff-only && cd ..
fi
cd agon

printf 'sdk.dir=%s\n' \"\$ANDROID_HOME\" > local.properties
chmod +x gradlew
./gradlew --no-daemon assembleDebug -PVERSION_NAME=$VERSION
"

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  echo "[X] The build finished but $APK is not there." >&2
  exit 1
fi
echo
echo "[4/5] Built: $APK  ($(du -h "$APK" | cut -f1))"
cp "$APK" "$HOME/Agon-$VERSION.apk"
echo "[5/5] Copied to $HOME/Agon-$VERSION.apk - open it with a file manager to install."
echo
cat <<'NOTE'
Signing, which matters on the FIRST install from a new machine:

  Android only installs an update over an app that was signed with the same key. The key
  used by publish.bat is the *debug* key of that Windows account
  (%USERPROFILE%\.android\debug.keystore). A build made here is signed with this phone's
  own debug key, so installing it over the copy that is already on your phone will fail
  with "App not installed".

  If that happens: uninstall Agon once (it clears the arena session, so sign in again),
  then install this APK. From then on, updates from this same source install normally.

  Do not delete anything on the laptop's disk: if that debug.keystore is recovered later,
  keeping one key for every build is the cleanest long-term fix.
NOTE
