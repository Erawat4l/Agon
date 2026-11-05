#!/usr/bin/env python3
"""Is this machine able to build Agon, and what is missing if not?

One checker, two very different hosts: a Windows PC (cmd/PowerShell) and an Android phone
running Termux (proot Ubuntu, because Google's aapt2 is x86-64 only). `build.py` asks this
module, installs whatever it reports as missing, and then asks again before building - so a
half-installed toolchain produces a short, exact list instead of a Gradle stack trace.

    python tools/needs.py beta            what is missing (and have a beta channel)
    python tools/needs.py verify <pkg>    one `tool=version` line, after installing it
    python tools/needs.py version         the version the next build would carry

The split matters: detection and the version policy live in ONE file that both hosts use,
so the phone and the PC cannot drift into building different version numbers.

Exit codes: 0 nothing missing, 1 something is missing, 2 usage error.
"""

import glob
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = ("Erawat4l", "Agon")

# What the project itself declares. These are the numbers a missing tool has to match: an
# SDK without platform 35 and build-tools 35 fails deep inside AGP with a message about a
# missing android.jar, which says nothing about the cause.
COMPILE_SDK = "35"
BUILD_TOOLS = "35.0.0"
MIN_JAVA = 17


# --------------------------------------------------------------------------- helpers

def run(cmd, timeout=30):
    """Runs a command and returns (exit code, stdout+stderr). Never raises."""
    try:
        p = subprocess.run(cmd, shell=isinstance(cmd, str), stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, timeout=timeout,
                           cwd=ROOT, text=True, errors="replace")
        return p.returncode, p.stdout or ""
    except Exception as exc:                                  # noqa: BLE001 - reported
        return 127, str(exc)


def have(name):
    return shutil.which(name) is not None


def termux():
    return os.path.isdir("/data/data/com.termux")


def java_major(java_home=None):
    """The major version of the JDK that would be used, or 0."""
    exe = "java"
    if java_home:
        exe = os.path.join(java_home, "bin", "java")
    code, out = run([exe, "-version"] if exe.startswith("/") else "java -version")
    if code != 0:
        return 0, ""
    first = (out.splitlines() or [""])[0]
    m = re.search(r'"(\d+)(?:\.(\d+))?', first)
    if not m:
        return 0, first
    major = int(m.group(1))
    if major == 1 and m.group(2):          # 1.8 style
        major = int(m.group(2))
    return major, first


JDK_CANDIDATES = [
    r"C:\Program Files\Eclipse Adoptium\jdk-17*",
    r"C:\Program Files\Eclipse Adoptium\jdk-21*",
    r"C:\Program Files\Java\jdk-17*",
    r"C:\Program Files\Microsoft\jdk-17*",
    r"C:\Program Files\Android\Android Studio\jbr",
    os.path.expanduser(r"~\.jdks\*17*"),
    os.path.expanduser(r"~\.jdks\*21*"),
]


def find_jdk():
    """A JDK 17-23 found on a Windows box, as a path, or None.

    Android's build tools reject Java 24+ (Gradle 8.10 refuses to start on it), so the
    newest JDK on the machine is not necessarily the right one.
    """
    if os.environ.get("JAVA_HOME"):
        major, _ = java_major(os.environ["JAVA_HOME"])
        if MIN_JAVA <= major <= 23:
            return os.environ["JAVA_HOME"]
    for pattern in JDK_CANDIDATES:
        for path in sorted(glob.glob(pattern), reverse=True):
            major, _ = java_major(path)
            if MIN_JAVA <= major <= 23:
                return path
    if have("java"):
        major, _ = java_major()
        if MIN_JAVA <= major <= 23:
            return ""                     # the one on PATH is fine
    return None


SDK_CANDIDATES = [
    os.environ.get("ANDROID_HOME", ""),
    os.environ.get("ANDROID_SDK_ROOT", ""),
    os.path.expanduser(r"~\AppData\Local\Android\Sdk"),
    r"C:\Program Files\Android\Android Studio\Sdk",
    os.path.expanduser("~/Android/Sdk"),
    os.path.expanduser("~/Library/Android/sdk"),
    "/root/android-sdk",
    "/opt/android-sdk",
]


def find_sdk():
    for path in SDK_CANDIDATES:
        if path and os.path.isdir(path) and os.path.isdir(
                os.path.join(path, "cmdline-tools")):
            return path
    for path in SDK_CANDIDATES:
        if path and os.path.isdir(path) and (
                os.path.isdir(os.path.join(path, "platforms"))
                or os.path.isdir(os.path.join(path, "build-tools"))):
            return path
    return None


def sdk_manager(sdk):
    for name in ("sdkmanager", "sdkmanager.bat"):
        for pattern in (os.path.join(sdk, "cmdline-tools", "*", "bin", name),
                        os.path.join(sdk, "tools", "bin", name)):
            hits = glob.glob(pattern)
            if hits:
                return hits[0]
    return None


def sdk_has(sdk, package):
    """True when `package` is installed in `sdk`. SDK packages use ; in their path."""
    folder = os.path.join(sdk, *package.split(";"))
    if package.startswith("platform-tools"):
        return os.path.isdir(folder)
    if package.startswith("build-tools"):
        return os.path.isfile(os.path.join(folder, "aapt2")) or os.path.isfile(
            os.path.join(folder, "aapt2.exe"))
    if package.startswith("platforms"):
        return os.path.isfile(os.path.join(folder, "android.jar"))
    return os.path.isdir(folder)


def arm64_tools_ok(tools="/opt/arm64-tools"):
    """True when the aarch64 aapt2 in the container actually runs."""
    exe = os.path.join(tools, "aapt2")
    if not os.access(exe, os.X_OK):
        return False
    code, _ = run([exe, "version"])
    return code == 0


# --------------------------------------------------------------------------- versions

def github_tags(owner, repo, limit=100):
    url = "https://api.github.com/repos/%s/%s/tags?per_page=%d" % (owner, repo, limit)
    try:
        with urllib.request.urlopen(url, timeout=20) as resp:
            data = json.load(resp)
        return [t.get("name", "") for t in data]
    except Exception:                                          # noqa: BLE001
        return []


def releases(owner, repo):
    url = "https://api.github.com/repos/%s/%s/releases?per_page=100" % (owner, repo)
    try:
        with urllib.request.urlopen(url, timeout=20) as resp:
            return json.load(resp)
    except Exception:                                          # noqa: BLE001
        return []


def code_of(version):
    parts = (version or "").split(".")
    nums = []
    for part in parts[:3]:
        m = re.match(r"^(\d+)", part)
        nums.append(int(m.group(1)) if m else 0)
    while len(nums) < 3:
        nums.append(0)
    return nums[0] * 1000000 + nums[1] * 1000 + nums[2]


def known_versions():
    """Every version this project has ever published, from the places it is recorded."""
    found = set()
    try:
        with open(os.path.join(ROOT, "version.txt")) as fh:
            found.add(fh.read().strip().lstrip("vV"))
    except OSError:
        pass
    code, out = run("git tag --list v*")
    if code == 0:
        found.update(t.strip().lstrip("vV") for t in out.split())
    for tag in github_tags(*REPO):
        found.add(tag.lstrip("vV"))
    for rel in releases(*REPO):
        found.add(str(rel.get("tag_name", "")).lstrip("vV"))
    return {v for v in found if re.match(r"^\d+\.\d+\.\d+$", v or "")}


def next_version(channel="stable"):
    """The version the next build must carry: above everything already out there.

    A build numbered below what is installed is invisible for ever - `releases/latest`
    only offers something newer, so the in-app updater quietly says "you are up to date"
    from then on. Hence: highest known + 1, or the highest *published* + 1 for a beta,
    which keeps beta numbers ahead of stable ones instead of colliding with them.
    """
    every = known_versions()
    if not every:
        return "1.0.0"
    if channel == "beta":
        published = {str(r.get("tag_name", "")).lstrip("vV") for r in releases(*REPO)
                     if not r.get("draft")}
        published = {v for v in published if re.match(r"^\d+\.\d+\.\d+$", v)}
        base = max(published or every, key=code_of)
    else:
        base = max(every, key=code_of)
    major, minor, patch = (int(x) for x in base.split("."))
    return "%d.%d.%d" % (major, minor, patch + 1)


# --------------------------------------------------------------------------- the report

def inspect(channel="stable", host_override=""):
    """Everything the build needs, as a list of dicts.

    Every entry says WHAT it is, WHETHER it is there, and HOW to get it on this host. The
    installer in build.py is driven entirely by this list, so adding a requirement here is
    the only place a new prerequisite has to be described.

    `host_override` exists so the Windows and Termux lists can be inspected (and tested)
    from anywhere: `AGON_HOST=windows python3 tools/needs.py check`.
    """
    on_termux = host_override == "termux" or (not host_override and termux())
    windows = host_override == "windows" or (
        not host_override and platform.system().lower().startswith("win"))
    items = []

    def add(key, label, ok, fix, detail=""):
        items.append({"key": key, "label": label, "ok": bool(ok),
                      "fix": fix, "detail": detail})

    # ---- the toolchain, inside the container when we are on a phone
    if on_termux:
        add("proot", "proot-distro (Ubuntu container)", have("proot-distro"),
            "pkg install -y proot-distro")
        container = False
        if have("proot-distro"):
            container = run("proot-distro login ubuntu -- true", timeout=120)[0] == 0
        add("container", "Ubuntu container", container, "proot-distro install ubuntu")
        # Only meaningful once the container exists; reported as missing otherwise so the
        # installer gets a chance to fill it in.
        inner = run("proot-distro login ubuntu -- sh -c "
                    '"command -v java && command -v keytool && command -v unzip"',
                    timeout=180)[1] if container else ""
        add("apt", "Ubuntu toolchain (JDK 17, unzip, curl, git)",
            container and inner.count("\n") + (1 if inner.strip() else 0) >= 3,
            "apt-get install -y openjdk-17-jdk unzip curl git python3 xz-utils file")
        sdk = find_sdk()
        manager = sdk_manager(sdk) if sdk else None
        add("sdk", "Android SDK (command-line tools)", bool(sdk and manager),
            "download commandlinetools-linux and install into /root/android-sdk")
        add("arm64", "arm64 build-tools (aapt2)", arm64_tools_ok(),
            "fetch the aarch64 build-tools and drop them over the SDK's x86_64 ones")
    else:
        jdk = find_jdk()
        major, line = java_major(jdk) if jdk is not None else (0, "")
        add("java", "JDK %d+ (17-23)" % MIN_JAVA, jdk is not None,
            "winget install EclipseAdoptium.Temurin.17.JDK" if windows
            else "apt install openjdk-17-jdk", line)
        sdk = find_sdk()
        manager = sdk_manager(sdk) if sdk else None
        add("sdk", "Android SDK (command-line tools)", bool(sdk and manager),
            "download commandlinetools-%s and unpack into the SDK folder"
            % ("win" if windows else "linux"))
        if sdk and manager:
            add("platform", "platforms;android-%s" % COMPILE_SDK,
                sdk_has(sdk, "platforms;android-%s" % COMPILE_SDK),
                "sdkmanager platforms;android-%s" % COMPILE_SDK)
            add("buildtools", "build-tools;%s" % BUILD_TOOLS,
                sdk_has(sdk, "build-tools;%s" % BUILD_TOOLS),
                "sdkmanager build-tools;%s" % BUILD_TOOLS)
        else:
            add("platform", "platforms;android-%s" % COMPILE_SDK, False, "installed with the SDK")
            add("buildtools", "build-tools;%s" % BUILD_TOOLS, False, "installed with the SDK")

    # ---- the source tree itself (same on both hosts)
    add("gradlew", "Gradle wrapper", os.path.isfile(os.path.join(ROOT, "gradlew")),
        "it is part of the repository - git checkout -- gradlew restores it")
    add("wrapper", "Gradle distribution URL",
        os.path.isfile(os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.properties")),
        "part of the repository")
    # This file is Python, so Python is already proven by being here. Reporting it as a
    # requirement would ask build.py to install the thing that is running it.
    add("python", "Python 3", True, "already running", platform.python_version())

    # ---- what makes a built APK installable over the one already on the phone
    key = os.path.join(ROOT, "agon-build.jks")
    props = os.path.join(ROOT, "agon-build.properties")
    add("key", "Shared signing key (agon-build.jks)", os.path.isfile(key) and os.path.isfile(props),
        "generated once with keytool; the key must stay the same for ever")

    return items


def describe(items, host_override=""):
    missing = [i for i in items if not i["ok"]]
    name = host_override or ("termux" if termux() else platform.system().lower())
    print("host: %s" % name)
    for item in items:
        mark = "ok  " if item["ok"] else "MISS"
        line = "  [%s] %s" % (mark, item["label"])
        if item["detail"]:
            line += "  -  %s" % item["detail"].strip()
        print(line)
        if not item["ok"]:
            print("         how: %s" % item["fix"])
    return missing


def verify(package):
    """One line proving a package is now usable, printed after its install step."""
    checks = {
        "proot": lambda: run(["proot-distro", "--help"])[1].splitlines()[0],
        "container": lambda: run("proot-distro login ubuntu -- sh -c 'cat /etc/os-release'",
                                 timeout=120)[1].splitlines()[0],
        "apt": lambda: run("proot-distro login ubuntu -- sh -c 'java -version 2>&1 | head -1'",
                           timeout=120)[1].strip(),
        "sdk": lambda: sdk_manager(find_sdk() or ""),
        "arm64": lambda: run(["/opt/arm64-tools/aapt2", "version"])[1].strip(),
        "java": lambda: java_major(find_jdk())[1],
        "platform": lambda: "platforms;android-%s" % COMPILE_SDK,
        "buildtools": lambda: "build-tools;%s" % BUILD_TOOLS,
        "key": lambda: os.path.basename(find_key() or "agon-build.jks"),
        "gradlew": lambda: os.path.join(ROOT, "gradlew"),
        "wrapper": lambda: "gradle-wrapper.properties",
        "python": lambda: platform.python_version(),
    }
    return checks.get(package, lambda: package)()


def find_key():
    for name in ("agon-build.jks", "arena-release.jks"):
        path = os.path.join(ROOT, name)
        if os.path.isfile(path):
            return path
    return None


def main(argv):
    override = os.environ.get("AGON_HOST", "")
    if not argv or argv[0] in ("list", "check"):
        channel = argv[1] if len(argv) > 1 else "stable"
        items = inspect(channel, override)
        missing = describe(items, override)
        return 1 if missing else 0
    if argv[0] == "verify" and len(argv) > 1:
        print(verify(argv[1]))
        return 0
    if argv[0] == "version":
        print(next_version(argv[1] if len(argv) > 1 else "stable"))
        return 0
    if argv[0] == "host":
        print("termux" if termux() else platform.system().lower())
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
