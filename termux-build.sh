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

echo "[1/5] Termux packages..."
pkg update -y >/dev/null 2>&1 || true
pkg install -y proot-distro >/dev/null 2>&1 || true

echo "[2/5] Ubuntu container (skipped if already installed)..."
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

echo "[3/5] Toolchain, arm64 build-tools, and the build itself..."
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

# ---- 3a. sign with the key the installed app already has
#
# Android installs an update only over an app signed with the SAME key. It is not a
# build setting that can be swapped later: with key A installed, an APK signed with
# key B is refused with App not installed / package conflicts with an existing
# package, and the only cures are uninstalling (which wipes the arena session) or
# keeping one key forever.
#
# The app already on the phone was published by publish.bat, which runs
# `gradlew assembleDebug` on Windows. AGP signs a debug build with the account's
# ~/.android/debug.keystore, so THAT is the key the installed app carries. No build
# on the phone can invent it - it has to be copied across, once:
#
#   ADOPT.md, placed in the shared Download folder, is a copy of that PC file. It
#   becomes this build's key, and every APK afterwards carries the same signature as
#   the installed app - so every later build is a normal update.
#
# Without ADOPT.md a key is generated and reused forever. That only helps builds from
# this phone: the first install still has to replace the old app (one uninstall, one
# sign-in). Either file is git-ignored, so `git pull` cannot remove it.
SHARED=/sdcard/Download
# Two names are accepted for the incoming key, because both workflows exist:
#   ADOPT.md  - the PC's old %USERPROFILE%\.android\debug.keystore (password "android")
#   AGON-KEY.md - the shared key the PC now makes (agon-build.jks, password "agonbuild")
# The second is the one that makes every later PC and phone build interchangeable, so it
# is preferred when both are present.
if [ ! -f agon-build.jks ]; then
  for cand in AGON-KEY.md ADOPT.md; do
    [ -f \"\$SHARED/\$cand\" ] || continue
    for pass in agonbuild android; do
      cp \"\$SHARED/\$cand\" /tmp/incoming.jks
      if keytool -list -keystore /tmp/incoming.jks -storepass \"\$pass\" >/dev/null 2>&1; then
        # The alias, read from the key entry line rather than guessed: keytool prints
        # "alias, date, PrivateKeyEntry," and nothing else in that list has a comma.
        alias=\$(keytool -list -keystore /tmp/incoming.jks -storepass \"\$pass\" 2>/dev/null \
          | grep 'PrivateKeyEntry' | head -1 | cut -d, -f1 | tr -d ' ')
        [ -n \"\$alias\" ] || alias=agon
        mv /tmp/incoming.jks agon-build.jks
        printf 'storeFile=agon-build.jks\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
          \"\$pass\" \"\$alias\" \"\$pass\" > agon-build.properties
        rm -f \"\$SHARED/\$cand\"
        echo \"  ... using the key from \$cand\"
        break 2
      fi
      rm -f /tmp/incoming.jks
    done
    echo ''
    echo \"  [X] \$SHARED/\$cand did not open with either known password.\"
    echo '      Nothing was built. Remove it from Download/ and run again.'
    exit 5
  done
fi
# One key for both machines. Older builds recorded the key in keystore.properties;
# adopting that file is what lets the PC sign for this phone later, and it means an
# existing phone build never has to be re-installed just because a file was renamed.
if [ ! -f agon-build.properties ] && [ -f keystore.properties ] && [ -f arena-release.jks ]; then
  cp keystore.properties agon-build.properties
  echo '  ... reusing the signing key this phone already has'
fi
if [ ! -f arena-release.jks ] || [ ! -f agon-build.properties ]; then
  echo ''
  echo '  [!] No key from the PC yet, so a key is being created for THIS phone only.'
  echo '      That APK will be refused over the installed app. To update instead:'
  echo '        PC:    copy \"%USERPROFILE%\\.android\\debug.keystore\" to a USB/Download'
  echo '        phone: rename that copy to ADOPT.md, put it in Download/, run again.'
  echo ''
  rm -f arena-release.jks agon-build.properties
  keytool -genkeypair -v -keystore arena-release.jks \
    -alias arena -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass agonphone -keypass agonphone \
    -dname 'CN=Agon Phone Build, OU=Mobile, O=Arena, C=IN' >/dev/null 2>&1
  printf 'storeFile=arena-release.jks\nstorePassword=agonphone\nkeyAlias=arena\nkeyPassword=agonphone\n' > agon-build.properties
fi
# The key fingerprint is printed after the build instead of inside this quoted
# block, where a command substitution would be parsed by Termux, not the container.

echo '  ... building (this is the slow part; a phone can take half an hour)'
./gradlew --no-daemon --stacktrace assembleDebug -PVERSION_NAME=$VERSION -PAGON_DEBUG_KEY=1 || {
  echo ''
  echo '  [X] The build failed. The two usual causes on a phone:'
  echo '      - \"AAPT2 ... Daemon startup failed\": the arm64 aapt2 was not used.'
  echo '      - OutOfMemoryError: lower org.gradle.jvmargs in /root/.gradle/gradle.properties.'
  exit 4
}
"

# The signing key's fingerprint, so two builds can be compared. If a future APK is
# refused with "package conflicts with an existing package", this line is the answer:
# a different fingerprint than the installed app means a different key.
KEYHASH=$(proot-distro login ubuntu -- sh -c "keytool -list -v -keystore /root/agon/arena-release.jks -alias arena -storepass agonphone 2>/dev/null | sed -n 's/.*SHA256: //p' | head -1" 2>/dev/null | tr -d '\r' | head -1) || true
[ -n "${KEYHASH:-}" ] && echo "      signing key: $KEYHASH"

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
echo "[4/5] Built: $(basename "$IN_CONTAINER")"

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

echo
echo "[5/5] Done"
cat <<'NOTE'

Signing - read this once, then it stops mattering.

  Android only installs an update over an app signed with the SAME key as the one
  already on the phone. The app you have was built on the PC by publish.bat, so it
  carries the PC's debug key (%USERPROFILE%\.android\debug.keystore).

  Two ways forward, both one-time:

  1. UPDATE, no uninstall (needs the PC once):
       copy "%USERPROFILE%\.android\debug.keystore" to the phone as ADOPT.md
       in Download/, then run this script again. It picks the file up, signs with
       it, and every future build updates the installed app normally.

  2. Uninstall once: install this APK over the old app fails, so remove Agon,
     install this one. The arena session clears, so sign in again. Builds from
     this phone then update each other - but an APK from the PC will not fit this
     one, so keep building in one place.

NOTE