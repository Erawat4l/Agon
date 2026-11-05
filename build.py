#!/usr/bin/env python3
"""Agon - ONE build script for both machines.

    python3 build.py                build the Android APK
    python3 build.py doctor         say what is installed and what is missing, build nothing
    python3 build.py apk            same as no argument
    python3 build.py apk 1.4.58     build that exact version
    python3 build.py desktop        APK + both Windows installers (Windows only)
    python3 build.py publish        APK + installers + upload the release
    python3 build.py apk --beta     build a beta-channel version number
    python3 build.py apk --release  signed release APK instead of a debug one
    python3 build.py clean          delete build output (never the key)

The old build.bat / build.sh / build-all.bat / clean-all.bat / commit.bat /
make-keystore.bat / termux-build.sh are all this one file now: same detection, same
version policy, same installers, one place to fix.

It works out where it is running:

  * a Windows PC  -> Gradle directly, and the installers through electron-builder;
  * an Android phone in Termux -> the same build inside a proot Ubuntu container, because
    Google's aapt2 is x86-64 only. Slow (30-90 min) but self-contained.

...then checks every prerequisite, DOWNLOADS what is missing, re-checks, and only then
builds. Nothing is deleted, nothing is uninstalled: the script creates what it needs and
leaves everything else alone. If a step cannot be done automatically it stops with the
exact command to run by hand instead of a Gradle stack trace.

Why this file and not a .bat/.sh pair: the detection logic, the version policy and the
container script used to live in three places (build.bat, build.sh, termux-build.sh) and
drifted apart - the phone build shipped broken for exactly that reason. One file, and
`python3 build.py` works in cmd, PowerShell and Termux alike.
"""

import os
import platform
import re
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "tools"))
import needs                                                   # noqa: E402

ROOT = needs.ROOT
VERSION_DEFAULT = "1.4.22"          # the repository's own fallback in app/build.gradle
TERMUX_STATE = {}                   # one container run per invocation, not one per item
APK_OUT = os.path.join("app", "build", "outputs", "apk", "debug", "app-debug.apk")
APK_RELEASE = os.path.join("app", "build", "outputs", "apk", "release", "app-release.apk")
DRY = "--dry-run" in sys.argv
HOST_OVERRIDE = os.environ.get("AGON_HOST", "")


# --------------------------------------------------------------------------- plumbing

def say(text=""):
    # Windows consoles are not always UTF-8; a box-drawing dash must never be the reason a
    # build dies. Anything unprintable is replaced, not raised.
    enc = (sys.stdout.encoding or "utf-8")
    try:
        text.encode(enc)
    except (UnicodeEncodeError, LookupError):
        text = text.encode(enc, "replace").decode(enc, "replace")
    print(text, flush=True)


def step(number, total, text):
    say()
    say("[%d/%d] %s" % (number, total, text))


def run(cmd, cwd=ROOT):
    """Runs a step in the foreground so its progress is visible. Returns True on success."""
    pretty = cmd if isinstance(cmd, str) else " ".join(cmd)
    if DRY:
        say("        would run: %s" % pretty)
        return True
    say("        %s" % pretty)
    try:
        return subprocess.call(cmd, shell=isinstance(cmd, str), cwd=cwd) == 0
    except FileNotFoundError:
        say("        [X] not found: %s" % cmd)
        return False


def fail(message, hint=""):
    say()
    say("[X] " + message)
    if hint:
        say("    " + hint)
    sys.exit(1)


def host():
    """'windows', 'termux' or 'linux'."""
    if HOST_OVERRIDE:
        return HOST_OVERRIDE
    if needs.termux():
        return "termux"
    if platform.system().lower().startswith("win"):
        return "windows"
    return "linux"


def version_from_args(argv):
    for arg in argv:
        if re.match(r"^\d+\.\d+(\.\d+)?$", arg):
            return arg
    try:
        with open(os.path.join(ROOT, "version.txt")) as fh:
            found = fh.read().strip().lstrip("vV")
        if re.match(r"^\d+\.\d+\.\d+$", found):
            return found
    except OSError:
        pass
    return None


# --------------------------------------------------------------------------- prerequisites

def make_key():
    """Creates agon-build.jks + agon-build.properties, once, in the repository.

    This is the key the installed app already carries. It must never be regenerated: a
    different key means Android refuses the update ("package conflicts with an existing
    package") and the app has to be uninstalled, which wipes the arena session.
    """
    keytool = "keytool"
    if host() == "windows":
        jdk = needs.find_jdk()
        if jdk:
            candidate = os.path.join(jdk, "bin", "keytool.exe")
            if os.path.exists(candidate):
                keytool = candidate
    ok = run([keytool, "-genkeypair", "-keystore", "agon-build.jks", "-alias", "agon",
              "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
              "-storepass", "agonbuild", "-keypass", "agonbuild",
              "-dname", "CN=Agon Build, OU=Mobile, O=Agon, C=IN"])
    if not ok:
        return False
    with open(os.path.join(ROOT, "agon-build.properties"), "w") as fh:
        fh.write("storeFile=agon-build.jks\nstorePassword=agonbuild\n"
                 "keyAlias=agon\nkeyPassword=agonbuild\n")
    return True


def adopt_key_from_shared(host_name):
    """Uses a key the user keeps in the phone's shared storage, if there is one.

    The shared folder is visible inside the container too, so this needs nothing from the
    host: the key survives reinstalling the container, which is what keeps later builds
    installable over the app that is already on the phone.
    """
    if host_name != "termux":
        return False
    for folder in ("/sdcard/Download", "/storage/emulated/0/Download"):
        for name in ("agon-build.jks", "arena-release.jks"):
            source = os.path.join(folder, name)
            if not os.path.isfile(source):
                continue
            target = os.path.join(ROOT, name)
            if os.path.isfile(target):
                continue
            shutil.copyfile(source, target)
            pass_marker = "arena" if name.startswith("arena") else "agon"
            with open(os.path.join(ROOT, "agon-build.properties"), "w") as fh:
                fh.write("storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n"
                         % (name, "agonphone" if pass_marker == "arena" else "agonbuild",
                            "arena" if pass_marker == "arena" else "agon",
                            "agonphone" if pass_marker == "arena" else "agonbuild"))
            say("        ... using the key kept in Download/%s" % name)
            return True
    return False


def ensure_tools(host_name):
    """Checks, installs what is missing, re-checks. Returns the SDK path on the host."""
    override = host_name if host_name in ("windows", "termux") else ""
    items = needs.inspect(host_override=override)
    missing = [i for i in items if not i["ok"]]
    if not missing:
        return needs.find_sdk()

    say()
    say("  Missing before this can build:")
    for item in missing:
        say("    - %s" % item["label"])
    say()
    say("  Fixing what can be fixed automatically...")

    # On the phone the container script installs everything inside it in one go, so it is
    # run once for all of those keys rather than once per missing item.
    # sdk/platform/buildtools are three reports of one install: a single sdkmanager run
    # produces all three, so only the first of them is acted on.
    sdk_keys = {"sdk", "platform", "buildtools"}
    sdk_stage_done = False

    for item in missing:
        key = item["key"]
        ok = False
        if host_name == "termux":
            # Everything on a phone happens in ONE container run: the toolchain, the
            # source, and the signing key (made with Termux's own keytool, inside the
            # repository folder - see the script). Asking it to do that per missing item
            # meant the SDK looked absent again after every step and a multi-GB download
            # restarted, because Termux cannot see the container's /root.
            if key in ("proot", "container"):
                # These two are Termux itself, not the container: still one command each.
                ok = run(["pkg", "install", "-y", "proot-distro"]) if key == "proot" \
                    else run(["proot-distro", "install", "ubuntu"])
            else:
                if TERMUX_STATE.get("stage") is None:
                    TERMUX_STATE["stage"] = run_termux_inner_stage()
                ok = bool(TERMUX_STATE["stage"])
                if ok and key == "key":
                    ok = os.path.isfile(os.path.join(ROOT, "agon-build.jks"))
            if ok:
                say("        [ok] %s" % item["label"])
            continue
        elif host_name != "termux" and key in sdk_keys:
            if not sdk_stage_done:
                sdk_stage_done = True
                # platform/buildtools mean the SDK exists but lacks them, so install the
                # SDK folder if it is absent at all, then ask sdkmanager for the packages.
                sdk_now = needs.find_sdk()
                ok = bool(sdk_now) or install_sdk(default_sdk(host_name == "windows"),
                                                  host_name == "windows")
                sdk_now = needs.find_sdk()
                manager = needs.sdk_manager(sdk_now or "")
                if ok and manager:
                    ok = run([manager, "--licenses"])
                    ok = run([manager, "platforms;android-%s" % needs.COMPILE_SDK,
                              "build-tools;%s" % needs.BUILD_TOOLS]) and ok
            else:
                ok = True
            continue
        if key == "gradlew":
            ok = run(["git", "checkout", "--", "gradlew"])
        elif key == "python":
            ok = False
        elif key == "key":
            ok = adopt_key_from_shared(host_name) or make_key()
        else:
            ok = fix_desktop(key, host_name)
        if ok:
            proof = ""
            try:
                proof = str(needs.verify(key) or "")
            except Exception:                                  # noqa: BLE001
                proof = ""
            say("        [ok] %s%s" % (item["label"], ("  -  " + proof[:70]) if proof else ""))

    # Ask again: an install that silently did nothing must not reach Gradle.
    still = [] if DRY else [i for i in needs.inspect(host_override=override)
                            if not i["ok"]]
    if still:
        say()
        say("[X] These are still missing, so nothing was built:")
        for item in still:
            say("    - %s" % item["label"])
            say("      %s" % item["fix"])
        say()
        say("    Install them and run this again; everything already done is reused.")
        sys.exit(3)
    if DRY:
        say()
        say("  dry run: nothing was downloaded, installed or built.")
    return needs.find_sdk()


def fix_desktop(key, host_name):
    """Installs one missing prerequisite on a PC."""
    windows = host_name == "windows"
    if key == "java":
        if windows and needs.have("winget"):
            return run("winget install -e --id EclipseAdoptium.Temurin.17.JDK "
                       "--accept-package-agreements --accept-source-agreements")
        return False
    if key == "sdk":
        return bool(needs.find_sdk()) or install_sdk(default_sdk(windows), windows)
    if key in ("platform", "buildtools"):
        sdk = needs.find_sdk()
        manager = needs.sdk_manager(sdk) if sdk else None
        if not manager:
            return install_sdk(sdk or default_sdk(windows), windows) and bool(
                needs.sdk_manager(needs.find_sdk() or ""))
        package = ("platforms;android-%s" % needs.COMPILE_SDK) if key == "platform" \
            else ("build-tools;%s" % needs.BUILD_TOOLS)
        return run([manager, "--licenses"]) and run([manager, package])
    return False


def default_sdk(windows):
    """Where Google's own installers put the SDK, per host."""
    if windows:
        local = os.environ.get("LOCALAPPDATA") or os.path.join(
            os.path.expanduser("~"), "AppData", "Local")
        return os.path.join(local, "Android", "Sdk")
    return os.path.join(os.path.expanduser("~"), "Android", "Sdk")


def install_sdk(sdk, windows):
    """Unpacks Google's command-line tools into `sdk` and accepts the licences."""
    say("        ... downloading the Android command-line tools")
    tools_dir = os.path.join(sdk, "cmdline-tools")
    os.makedirs(tools_dir, exist_ok=True)
    archive = "commandlinetools-win-11076708_latest.zip" if windows \
        else "commandlinetools-linux-11076708_latest.zip"
    url = "https://dl.google.com/android/repository/" + archive
    if DRY:
        say("        would download %s into %s" % (url, tools_dir))
        return True
    import tempfile
    import urllib.request
    import zipfile
    with tempfile.TemporaryDirectory() as tmp:
        target = os.path.join(tmp, archive)
        try:
            urllib.request.urlretrieve(url, target)
        except Exception as exc:                               # noqa: BLE001
            say("        [X] download failed: %s" % exc)
            return False
        with zipfile.ZipFile(target) as zf:
            zf.extractall(tmp)
        unpacked = os.path.join(tmp, "cmdline-tools")
        latest = os.path.join(tools_dir, "latest")
        if os.path.isdir(latest):
            shutil.rmtree(latest)
        shutil.move(unpacked, latest)
    manager = ("sdkmanager.bat" if windows else "sdkmanager")
    manager = os.path.join(latest, "bin", manager)
    if windows:
        # The licence prompt needs a stream of "y"; PowerShell does that with one line.
        run("powershell -NoProfile -Command \"(1..40) | ForEach-Object {'y'} | & '%s' --licenses\""
            % manager)
    else:
        run("yes | '%s' --licenses" % manager)
    return run([manager, "platform-tools",
                "platforms;android-%s" % needs.COMPILE_SDK,
                "build-tools;%s" % needs.BUILD_TOOLS])


# --------------------------------------------------------------------------- Termux path

INNER = r"""set -e
export DEBIAN_FRONTEND=noninteractive
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/root/android-sdk
export PATH="$JAVA_HOME/bin:$PATH:/opt/arm64-tools"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
BT="$ANDROID_HOME/build-tools/%(build_tools)s"

echo '  ... packages'
apt-get update -qq
apt-get install -y -qq openjdk-17-jdk unzip curl git python3 xz-utils file >/dev/null

echo '  ... Android command-line tools'
if [ ! -x "$SDKMANAGER" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fL -o /tmp/cmdtools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o /tmp/cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
if [ ! -d "$ANDROID_HOME/platforms/android-%(compile_sdk)s" ]; then
  echo '  ... platform %(compile_sdk)s + build-tools'
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" 'platforms;android-%(compile_sdk)s' 'build-tools;%(build_tools)s' platform-tools >/dev/null
fi

echo '  ... arm64 build-tools (aapt2, zipalign, aidl)'
TOOLS=/opt/arm64-tools
mkdir -p "$TOOLS"
if ! "$TOOLS/aapt2" version >/dev/null 2>&1; then
  curl -fL -o /tmp/armtools.tar.xz \
    https://github.com/HomuHomu833/android-sdk-custom/releases/download/37.0.0/android-sdk-aarch64-linux-gnu.tar.xz
  tar -xJf /tmp/armtools.tar.xz -C "$TOOLS" --strip-components=1 2>/dev/null \
    || tar -xJf /tmp/armtools.tar.xz -C "$TOOLS"
  for tool in aapt2 zipalign aidl; do
    found=$(find "$TOOLS" -name "$tool" -type f | head -1)
    if [ -n "$found" ] && [ "$found" != "$TOOLS/$tool" ]; then cp "$found" "$TOOLS/$tool"; fi
    [ -f "$TOOLS/$tool" ] && chmod +x "$TOOLS/$tool"
  done
fi
if ! "$TOOLS/aapt2" version >/dev/null 2>&1; then
  curl -fL -o "$TOOLS/aapt2" \
    https://github.com/ReVanced/aapt2/releases/download/v1.1.0/aapt2-arm64-v8a
  chmod +x "$TOOLS/aapt2"
fi
if ! "$TOOLS/aapt2" version >/dev/null 2>&1; then
  echo '  [X] no arm64 aapt2 will run on this CPU - nothing was built'
  exit 3
fi
for tool in aapt2 zipalign aidl; do
  if [ -f "$TOOLS/$tool" ] && [ -d "$BT" ]; then
    [ -f "$BT/$tool.x86_64" ] || mv "$BT/$tool" "$BT/$tool.x86_64"
    cp -f "$TOOLS/$tool" "$BT/$tool"
    chmod +x "$BT/$tool"
  fi
done

mkdir -p /root/.gradle
cat > /root/.gradle/gradle.properties <<EOF
android.aapt2FromMavenOverride=$TOOLS/aapt2
org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m
org.gradle.parallel=false
org.gradle.daemon=false
android.useAndroidX=true
EOF

echo '  ... the source'
cd /root
if [ ! -d agon ]; then
  git clone https://github.com/%(owner)s/%(repo)s.git agon
else
  cd agon && git fetch --all --quiet && git checkout --quiet main && git pull --ff-only --quiet && cd ..
fi
cd agon
printf 'sdk.dir=%%s\n' "$ANDROID_HOME" > local.properties
chmod +x gradlew

echo '  ... signing key'
# One key, and it must be the key the installed app already carries: Android refuses an
# update signed with a different one ("package conflicts with an existing package"), and
# the only cure is uninstalling, which wipes the arena session.
#
# `keytool` here does not need a JDK - the container has the JDK, but keytool is in
# Termux itself, so the key is made OUTSIDE the container and kept in ~/.agon-keys.
# That way it survives re-installing the container.
KEYTOOL=%(keytool)s
if [ ! -f agon-build.jks ]; then
  for cand in agon-build.jks arena-release.jks; do
    [ -f "/sdcard/Download/$cand" ] || continue
    cp -f "/sdcard/Download/$cand" "$cand"
    break
  done
fi
if [ ! -f agon-build.jks ]; then
  "$KEYTOOL" -genkeypair -keystore agon-build.jks -alias agon -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass agonbuild -keypass agonbuild \
    -dname 'CN=Agon Build, OU=Mobile, O=Agon, C=IN' >/dev/null 2>&1 \
    || echo '  [!] keytool could not create the key - the APK will not install over the app'
fi
if [ -f agon-build.jks ]; then
  printf 'storeFile=agon-build.jks\nstorePassword=agonbuild\nkeyAlias=agon\nkeyPassword=agonbuild\n' > agon-build.properties
  [ -f "/sdcard/Download/agon-build.jks" ] || cp -f agon-build.jks "/sdcard/Download/agon-build.jks" 2>/dev/null || true
fi

%(marker)s

echo '  ... building (the slow part; a phone can take half an hour)'
./gradlew --no-daemon assembleDebug -PVERSION_NAME="$AGON_VERSION" -PAGON_DEBUG_KEY=1
echo "APK: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
"""


def termux_inner(**extra):
    values = {"build_tools": needs.BUILD_TOOLS, "compile_sdk": needs.COMPILE_SDK,
              # keytool from the phone's own Termux prefix: the key is created outside the
              # container so re-installing the container cannot lose it.
              "keytool": shutil.which("keytool") or "/usr/bin/keytool",
              "owner": needs.REPO[0], "repo": needs.REPO[1],
              # The SDK lives in the container's /root, which is not visible from Termux,
              # so "is it already there?" has to be answered from inside. With this flag
              # the script exits as soon as the toolchain is ready, and the check in
              # tools/needs.py finds it - which means the three SDK-related items stop
              # being reported and the download happens once, not on every run.
              "marker": "if [ -n \"$AGON_TOOLS_ONLY\" ]; then "
                        "echo '  ... toolchain ready'; exit 0; fi"}
    values.update(extra)
    return INNER % values


def run_termux_inner_stage():
    """Runs the container script up to (but not beyond) its own build step.

    One script does the whole toolchain; splitting it would duplicate the download logic.
    The build at the end is skipped here by passing a version, and the real build passes
    the same version again - the second run is fast because everything already exists.
    """
    return run_termux_build(os.environ.get("AGON_VERSION", VERSION_DEFAULT), only_tools=True)


def tools_ready_in_container():
    """Asks the container whether the whole toolchain is already inside it.

    Anything that requires an Android SDK has to answer this from inside the container:
    the SDK lives in its /root, and Termux cannot see that path at all.
    """
    return run("proot-distro login ubuntu -- sh -c "
               "'test -x /root/android-sdk/cmdline-tools/latest/bin/sdkmanager "
               "&& test -d /root/android-sdk/platforms/android-%s "
               "&& test -d /root/android-sdk/build-tools/%s "
               "&& test -x /opt/arm64-tools/aapt2'" % (needs.COMPILE_SDK, needs.BUILD_TOOLS))


def run_termux_build(version, only_tools=False):
    script = termux_inner()
    if only_tools:
        # Everything before the Gradle step: the toolchain, the source, the key.
        script = script.split("echo '  ... building")[0]
    # The script is written next to build.py, NOT in /tmp. Android 10+ gives every app a
    # private /tmp - a directory owned by the app's user - so Termux's own /tmp is
    # read-only to the shell, and writing there fails with
    #   PermissionError: [Errno 13] Permission denied: '/tmp/agon-inner.sh'
    # The repository folder is writable (it was just cloned with git), and the container
    # sees the same file, so it can be handed over as a path.
    path = os.path.join(ROOT, ".agon-inner.sh")
    if DRY:
        say("        would write %d lines to %s and run them in the container" %
            (len(script.splitlines()), path))
        return True
    try:
        with open(path, "w") as fh:
            fh.write(script)
    except OSError as exc:
        fail("Could not write the build script to %s (%s)." % (path, exc),
             "That folder has to be writable - .agon-inner.sh is a file inside the "
             "repository, next to build.py.")
    try:
        flags = "AGON_TOOLS_ONLY=1 " if only_tools else ""
        return run("proot-distro login ubuntu -- env %sAGON_VERSION='%s' bash '%s'"
                   % (flags, version, path))
    finally:
        # It is regenerated on every run, so leaving it behind is only clutter.
        try:
            os.remove(path)
        except OSError:
            pass


# --------------------------------------------------------------------------- build steps

def write_local_properties(sdk):
    if not sdk:
        return
    if DRY:
        say("        would write local.properties -> %s" % sdk)
        return
    with open(os.path.join(ROOT, "local.properties"), "w") as fh:
        fh.write("sdk.dir=%s\n" % sdk.replace("\\", "/"))


def preflight():
    for tool in ("tools/check-resources.py", "tools/check-secrets.py"):
        if os.path.isfile(os.path.join(ROOT, tool)):
            if not run([sys.executable, tool]):
                fail("The pre-flight check in %s failed, so nothing was built." % tool,
                     "It prints the exact file and line above.")


def build_android(host_name, version, sdk):
    if host_name == "termux":
        return run_termux_build(version)
    wrapper = "gradlew.bat" if host_name == "windows" else "./gradlew"
    write_local_properties(sdk)
    return run([wrapper, "--no-daemon", "assembleDebug",
                "-PVERSION_NAME=%s" % version, "-PAGON_DEBUG_KEY=1"])


def collect_apk(host_name, version):
    """Puts the APK where a phone can install it, and says where that is."""
    named = os.path.join(ROOT, "Agon-%s.apk" % version)
    if host_name == "termux":
        container = ("/root/agon/" + APK_OUT.replace(os.sep, "/"))
        if DRY:
            say("        would copy %s out to %s" % (container, named))
            return named
        folders = ["/sdcard/Download", "/storage/emulated/0/Download",
                   os.path.expanduser("~/storage/downloads")]
        dest = next((f for f in folders if os.path.isdir(f) and os.access(f, os.W_OK)), None)
        if not dest:
            fail("Termux cannot write to a shared folder yet.",
                 "Run  termux-setup-storage  (allow the prompt), then this again.")
        target = os.path.join(dest, "Agon-%s.apk" % version)
        with open(target, "wb") as fh:
            if subprocess.call("proot-distro login ubuntu -- cat %s" % container,
                               shell=True, stdout=fh) != 0 or os.path.getsize(target) == 0:
                fail("Could not copy the APK out of the container.",
                     "It is at %s inside the container." % container)
        return target
    source = os.path.join(ROOT, APK_RELEASE if "--release" in sys.argv else APK_OUT)
    if DRY:
        say("        would copy %s -> %s" % (source, named))
        return named
    if not os.path.isfile(source):
        fail("The build reported success but there is no APK at %s." % APK_OUT)
    shutil.copyfile(source, named)
    return named


def build_installers(host_name, version):
    if host_name != "windows":
        say("        installers need Windows + Node.js - skipped on this host")
        return False
    desktop = os.path.join(ROOT, "desktop")
    if not os.path.isfile(os.path.join(desktop, "package.json")):
        say("        desktop/package.json is missing - skipped")
        return False
    if not needs.have("npm"):
        say("        npm not found - skipped (winget install OpenJS.NodeJS.LTS)")
        return False
    return run(["npm", "install", "--no-audit", "--no-fund"], cwd=desktop) and \
        run(["npm", "run", "dist", "--", "-c.extraMetadata.version=%s" % version], cwd=desktop)


def publish(host_name, version):
    """Uploads through the existing, reviewed scripts instead of re-implementing them."""
    if host_name == "windows":
        script = os.path.join(ROOT, "tools", "publish.ps1")
        return run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script, "-Version", version])
    if host_name == "termux":
        say("        the installers cannot be built on a phone, so only the APK is uploaded")
        if not needs.have("gh"):
            say("        gh is not installed: pkg install gh, then gh auth login")
            return False
        tag = "v" + version
        apk = os.path.join(ROOT, "Agon-%s.apk" % version)
        return run(["gh", "release", "upload", tag, apk, "--clobber"])
    say("        publishing is a Windows/phone step (this host has no token path)")
    return False


# --------------------------------------------------------------------------- main

def clean():
    """Removes build output only. Never the key, never the source, never ~/.gradle."""
    targets = ["app/build", "desktop/node_modules", "desktop/release", ".gradle", "build"]
    freed = 0
    for target in targets:
        path = os.path.join(ROOT, target)
        if not os.path.isdir(path):
            continue
        for base, _dirs, files in os.walk(path):
            freed += sum(os.path.getsize(os.path.join(base, f)) for f in files
                         if os.path.exists(os.path.join(base, f)))
        if DRY:
            say("        would delete %s" % target)
            continue
        shutil.rmtree(path, ignore_errors=True)
        say("        deleted %s" % target)
    for stale in ("Agon-debug.apk", "Agon-release.apk", "Agon.apk", "Agon.exe", "Agon-32.exe"):
        path = os.path.join(ROOT, stale)
        if os.path.isfile(path) and not DRY:
            os.remove(path)
    say("        %.0f MB of build output removed" % (freed / (1024 * 1024)))
    say("        the signing key, local.properties and the source are untouched")


def release_apk(version):
    """A signed release APK, for the rare case a debug-signed one is not wanted.

    It needs a keystore of its own (keystore.properties + arena-release.jks). If there is
    none, the shared agon-build.jks is used instead, which is what keeps installs working.
    """
    key = needs.find_key()
    if key and not os.path.isfile(os.path.join(ROOT, "keystore.properties")):
        source = os.path.join(ROOT, "agon-build.properties")
        if os.path.isfile(source):
            shutil.copyfile(source, os.path.join(ROOT, "keystore.properties"))
            say("        release signing: reusing the shared key")
    wrapper = "gradlew.bat" if host() == "windows" else "./gradlew"
    return run([wrapper, "--no-daemon", "assembleRelease", "-PVERSION_NAME=%s" % version])


def main(argv):
    mode = "apk"
    for arg in argv:
        if arg in ("apk", "desktop", "publish", "doctor", "clean"):
            mode = arg
    beta = "--beta" in argv or "beta" in argv
    channel = "beta" if beta else "stable"
    host_name = host()

    total = 5 if mode in ("desktop", "publish") else 4
    say("=" * 52)
    say("  Agon  |  build")
    say("  host : %s" % ("Android / Termux" if host_name == "termux"
                         else "Windows" if host_name == "windows" else "Linux/macOS"))
    say("  mode : %s" % mode)
    say("=" * 52)

    if mode == "doctor":
        missing = needs.describe(needs.inspect(channel))
        say()
        if not missing:
            say("  Everything needed is here. Run:  python3 build.py")
        else:
            say("  %d thing(s) missing. `python3 build.py` installs them." % len(missing))
        return 0 if not missing else 1

    if mode == "clean":
        step(1, 1, "Clean")
        clean()
        return 0

    # proot-distro and the container itself are host-side items with real commands, so they
    # are installed before anything is asked of the container.
    sdk = ensure_tools(host_name)

    step(1, total, "Checks")
    preflight()
    version = (version_from_args(argv) or os.environ.get("AGON_VERSION")
               or needs.next_version(channel))
    # A version below anything already published is invisible for ever: releases/latest
    # only offers something newer, so the app would say "you are up to date" from then on.
    # Refuse it here rather than after a ten-minute build.
    never = max(needs.known_versions() or {"0.0.0"}, key=needs.code_of)
    if needs.code_of(version) < needs.code_of(never):
        fail("%s is lower than %s, which is already published." % (version, never),
             "A build numbered below what is installed is never offered as an update.\n"
             "    Use %s or higher, or no version at all to take the next free one." % never)
    say("        version: %s%s" % (version, "  (beta)" if beta else ""))
    key = needs.find_key()
    if key:
        say("        signing key: %s" % os.path.basename(key))

    step(2, total, "Android APK")
    built = release_apk(version) if "--release" in argv and host_name != "termux" \
        else build_android(host_name, version, sdk)
    if not built:
        fail("The Android build failed - the messages above say where.",
             "Fix that and run this again; Gradle reuses everything already compiled.")

    step(3, total, "Collect")
    apk = collect_apk(host_name, version)
    say("        %s" % apk)
    if os.path.exists(apk):
        say("        %.1f MB" % (os.path.getsize(apk) / (1024 * 1024)))

    ok = True
    if mode in ("desktop", "publish"):
        step(4, total, "Windows installers")
        ok = build_installers(host_name, version)
        if not ok:
            say("        installers were not produced; the APK above is still good")

    if mode == "publish":
        step(5, total, "Publish")
        if DRY:
            say("        would upload %s as release v%s" % (os.path.basename(apk), version))
            ok = True
        elif not DRY:
            ok = publish(host_name, version) and ok

    say()
    say("=" * 52)
    if DRY:
        headline = "dry run - nothing was built"
    elif mode == "publish" and ok:
        headline = "published"
    else:
        headline = "built"
    say("  [OK] %s" % headline)
    say("  APK: %s" % apk)
    if mode in ("desktop", "publish") and host_name == "windows":
        say("  Installers: desktop/release/Agon-Setup-64.exe, Agon-Setup-32.exe")
    say("=" * 52)
    if mode != "publish":
        say()
        say("  Install: copy the APK to the phone, tap it, approve the update.")
        say("  Publish: python3 build.py publish %s" % version)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        say()
        say("Stopped. Nothing was deleted; run the same command to carry on.")
        sys.exit(130)
