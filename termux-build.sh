#!/usr/bin/env bash
# Build Agon's APK on the phone, in Termux, with no PC involved.
#
#   bash termux-build.sh 1.4.49
#
# ---------------------------------------------------------------------------
# This script only ever CREATES things (a container, an SDK, a build). It never
# deletes a container, never uninstalls a package, and never touches anything
# outside the two folders it names. Cleanup is deliberately a separate command
# the user runs by hand: a script that lives on GitHub must not remove anything
# from somebody else's phone because they happened to run it.
#
# To undo everything this does (paste in Termux, NOT inside the container):
#
#   proot-distro remove ubuntu
#   pkg uninstall proot-distro
#   rm -rf ~/Agon ~/storage/downloads/Agon-*.apk
#
# ---------------------------------------------------------------------------
# Why this can work at all
#
# Google ships aapt2 (and zipalign, aidl) as x86-64 binaries only, so a normal
# Android build dies on an ARM phone with "cannot execute binary file". The
# Android-on-Android community solved this, and the fix is two parts:
#
#   1. android.aapt2FromMavenOverride - a Gradle property that makes the build
#      use a different aapt2 than the one it downloads from Maven. (This is how
#      AndroidIDE builds APKs on a phone.)
#   2. arm64 builds of the build-tools, published on GitHub by third parties:
#        - HomuHomu833/android-sdk-custom  (aarch64-linux-gnu: for this container)
#        - ReVanced/aapt2                  (single arm64 aapt2, as a fallback)
#
# So this script installs an Ubuntu container, drops arm64 build-tools over the
# SDK's x86_64 ones, points Gradle at them with the override, and builds.
#
# It is still unofficial and slow: expect 30-90 minutes and ~4 GB. A real PC is
# faster. But it is not blocked any more.
# ---------------------------------------------------------------------------
set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "usage: bash termux-build.sh 1.4.49" >&2
  exit 1
fi

if [ ! -d /data/data/com.termux ]; then
  echo "[!] This looks like a normal Linux shell, not Termux."
  echo "    Use ./build.sh instead - it is shorter and needs no container."
  exit 1
fi

echo "[1/4] Termux packages..."
pkg update -y >/dev/null 2>&1 || true
pkg install -y proot-distro >/dev/null 2>&1 || true

echo "[2/4] Ubuntu container (skipped if already installed)..."
# The only reliable test is the one that matters: can we log in?
#
# Three attempts at predicting this from the outside all failed on a real phone -
# `list --installed`, then the rootfs path, then the os-release file. So the
# script stops guessing: it asks proot-distro to run `true` inside the container.
# Success means the container exists AND is usable, which is exactly the question.
if proot-distro login ubuntu -- true >/dev/null 2>&1; then
  echo "      already installed - reusing it"
else
  echo "      not usable yet - installing"
  if ! proot-distro install ubuntu; then
    echo ""
    echo "  [X] proot-distro could not install the container."
    echo "      If it mentions another session, go back to Termux and type: exit"
    echo "      ...or open a NEW Termux session and run this script there."
    exit 3
  fi
fi

# A login session left behind by an interrupted run does block install/remove.
# It does not block a new login, so nothing is killed here: a build that shares
# this container with another ssh session is fine.

echo "[3/4] Toolchain, arm64 build-tools, and the build itself..."
proot-distro login ubuntu -- bash -lc "
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq openjdk-17-jdk unzip curl git python3 xz-utils file >/dev/null

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH=\"\$JAVA_HOME/bin:\$PATH\"
export ANDROID_HOME=/root/android-sdk
SDKMANAGER=\"\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\"

# ---- 1. the SDK itself (the tools inside are the x86_64 ones we replace next)
if [ ! -x \"\$SDKMANAGER\" ]; then
  echo '  ... downloading Android command-line tools'
  curl -fL -o /tmp/cmdtools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  mkdir -p \"\$ANDROID_HOME/cmdline-tools\"
  unzip -q -o /tmp/cmdtools.zip -d \"\$ANDROID_HOME/cmdline-tools\"
  rm -rf \"\$ANDROID_HOME/cmdline-tools/latest\"
  mv \"\$ANDROID_HOME/cmdline-tools/cmdline-tools\" \"\$ANDROID_HOME/cmdline-tools/latest\"
fi
if [ ! -d \"\$ANDROID_HOME/platforms/android-35\" ]; then
  echo '  ... accepting licences and installing platform 35 + build-tools'
  yes | \"\$SDKMANAGER\" --licenses >/dev/null 2>&1 || true
  \"\$SDKMANAGER\" 'platforms;android-35' 'build-tools;35.0.0' platform-tools >/dev/null
fi

# ---- 2. arm64 build-tools, dropped over the x86_64 ones
#
# android-sdk-custom publishes the same tools compiled for aarch64-linux-gnu,
# which is exactly what this container is. The x86_64 originals are kept as
# *.x86_64 so nothing is destroyed and the swap can be undone.
TOOLS=/opt/arm64-tools
mkdir -p \"\$TOOLS\"
if [ ! -x \"\$TOOLS/aapt2\" ]; then
  echo '  ... downloading arm64 build-tools'
  curl -fL -o /tmp/armtools.tar.xz \
    https://github.com/HomuHomu833/android-sdk-custom/releases/download/37.0.0/android-sdk-aarch64-linux-gnu.tar.xz
  tar -xJf /tmp/armtools.tar.xz -C \"\$TOOLS\" --strip-components=1 2>/dev/null \
    || tar -xJf /tmp/armtools.tar.xz -C \"\$TOOLS\"
  # The archive layout is not guaranteed: find the binaries wherever they landed.
  for tool in aapt2 zipalign aidl; do
    found=\$(find \"\$TOOLS\" -name \"\$tool\" -type f | head -1)
    if [ -n \"\$found\" ] && [ \"\$found\" != \"\$TOOLS/\$tool\" ]; then
      cp \"\$found\" \"\$TOOLS/\$tool\"
    fi
    [ -f \"\$TOOLS/\$tool\" ] && chmod +x \"\$TOOLS/\$tool\"
  done
fi

# Fallback for aapt2 alone: ReVanced publishes one current arm64 binary. It is
# used only if the archive above did not yield a working aapt2.
if ! \"\$TOOLS/aapt2\" version >/dev/null 2>&1; then
  echo '  ... archive aapt2 unusable, using the ReVanced arm64 build'
  curl -fL -o \"\$TOOLS/aapt2\" \
    https://github.com/ReVanced/aapt2/releases/download/v1.1.0/aapt2-arm64-v8a
  chmod +x \"\$TOOLS/aapt2\"
fi
if ! \"\$TOOLS/aapt2\" version >/dev/null 2>&1; then
  echo ''
  echo '  [X] No arm64 aapt2 would run on this CPU.'
  echo '      That is unusual - the two known sources are both in this script.'
  echo '      Nothing was built.'
  exit 3
fi
echo \"  arm64 aapt2: \$(\"\$TOOLS/aapt2\" version 2>&1 | head -1)\"

BT=\"\$ANDROID_HOME/build-tools/35.0.0\"
for tool in aapt2 zipalign aidl; do
  if [ -f \"\$TOOLS/\$tool\" ] && [ -f \"\$BT/\$tool\" ]; then
    # Keep the original once, then replace it with the arm64 build.
    [ -f \"\$BT/\$tool.x86_64\" ] || mv \"\$BT/\$tool\" \"\$BT/\$tool.x86_64\"
    cp -f \"\$TOOLS/\$tool\" \"\$BT/\$tool\"
    chmod +x \"\$BT/\$tool\"
  fi
done

# ---- 3. tell Gradle to use it
#
# Written to the USER's gradle.properties, not the project's: the path is about
# this phone, and machine-specific paths must not end up in the repository.
mkdir -p /root/.gradle
cat > /root/.gradle/gradle.properties <<EOF
android.aapt2FromMavenOverride=\$TOOLS/aapt2
org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m
org.gradle.parallel=false
org.gradle.daemon=false
android.useAndroidX=true
EOF

# ---- 4. the source, and the build
cd /root
if [ ! -d agon ]; then
  git clone https://github.com/Erawat4l/Agon.git agon
else
  cd agon && git fetch --all --quiet && git checkout --quiet main && git pull --ff-only --quiet && cd ..
fi
cd agon
printf 'sdk.dir=%s\n' \"\$ANDROID_HOME\" > local.properties
chmod +x gradlew

echo '  ... building (this is the slow part; a phone can take half an hour)'
./gradlew --no-daemon --stacktrace assembleDebug -PVERSION_NAME=$VERSION || {
  echo ''
  echo '  [X] The build failed. The two usual causes on a phone:'
  echo '      - \"AAPT2 ... Daemon startup failed\": the arm64 aapt2 was not used.'
  echo '      - OutOfMemoryError: lower org.gradle.jvmargs in /root/.gradle/gradle.properties.'
  exit 4
}
"

# Getting the APK out of the container.
#
# Three attempts at naming the file from the outside all failed on a real phone:
# the path relative to Termux, then $PREFIX/var/lib/proot-distro/installed-rootfs/...,
# then a search of that rootfs. proot-distro's on-disk layout is not something this
# script should be predicting.
#
# So it never names the file. It asks the CONTAINER where the APK is, then streams
# the bytes out through stdout - `cat` works no matter where the rootfs lives.
IN_CONTAINER=$(proot-distro login ubuntu -- sh -c "
  if [ -f /root/agon/app/build/outputs/apk/debug/app-debug.apk ]; then
    echo /root/agon/app/build/outputs/apk/debug/app-debug.apk
  else
    find /root /home -name '*.apk' 2>/dev/null | head -1
  fi" 2>/dev/null | tr -d '\r' | head -1) || true

if [ -z "${IN_CONTAINER:-}" ]; then
  echo "[X] The build reported success but no APK is inside the container." >&2
  echo "    Find it with:  proot-distro login ubuntu -- find / -name '*.apk'" >&2
  exit 1
fi
echo
echo "[4/4] Built: $(basename "$IN_CONTAINER")"

# Where the package installer can actually see it. Termux's own HOME is private to
# Termux, so a copy there is not installable from a file manager.
mkdir -p "$HOME/storage/downloads" 2>/dev/null || true
DEST=""
for d in "$HOME/storage/downloads" /sdcard/Download /storage/emulated/0/Download; do
  if [ -d "$d" ] && [ -w "$d" ]; then DEST="$d"; break; fi
done

if [ -z "$DEST" ]; then
  echo "      Termux cannot write to a shared folder yet."
  echo "      Run  termux-setup-storage  (allow the prompt), then run this script again."
  echo "      It will not rebuild - Gradle is up to date."
  DEST="$HOME"
fi

OUT="$DEST/Agon-$VERSION.apk"
if proot-distro login ubuntu -- cat "$IN_CONTAINER" > "$OUT" 2>/dev/null && [ -s "$OUT" ]; then
  echo "      saved to: $OUT  ($(du -h "$OUT" | cut -f1))"
else
  echo "[X] Could not copy the APK out of the container." >&2
  echo "    It is at $IN_CONTAINER inside the container. Copy it with:" >&2
  echo "      proot-distro login ubuntu -- cat $IN_CONTAINER > $OUT" >&2
  exit 1
fi

cat <<'NOTE'

Signing, which matters on the FIRST install from this machine:

  Android only installs an update over an app signed with the same key. The key
  publish.bat uses is the Windows account's debug key
  (%USERPROFILE%\.android\debug.keystore); this build uses the container's own.

  If Android says "App not installed": uninstall Agon once (that clears the
  arena session, so sign in again), then install this APK. After that, updates
  from this same setup install normally.
NOTE
