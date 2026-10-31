#!/usr/bin/env python3
"""Reports which CPU architectures a built APK can actually run on.

    python3 tools/check-apk-abis.py app/build/outputs/apk/debug/app-debug.apk

Why this exists: "does it work on 32-bit?" cannot be answered from the Gradle file. The
only honest answers are in the APK itself:

  * the AndroidManifest's minSdkVersion (a 32-bit-only phone is usually old *and* armv7),
  * every lib/<abi>/*.so in the zip, and the ELF header of each one (a native library
    built only for arm64 makes the app install on a 32-bit phone and then fail to load),
  * whether any .so is 64-bit only.

An APK with no native libraries at all is architecture-independent: it runs on 32-bit and
64-bit alike, because the only code in it is Dalvik bytecode. That is this app's case
today, and the script says so explicitly rather than staying silent.
"""
import struct
import sys
import zipfile

# e_machine values that matter for Android.
MACHINES = {
    0x28: "arm (32-bit)",
    0xB7: "aarch64 (64-bit)",
    0x03: "x86 (32-bit)",
    0x3E: "x86_64 (64-bit)",
}

ABI_NAMES = {
    "armeabi-v7a": "32-bit ARM",
    "arm64-v8a": "64-bit ARM",
    "x86": "32-bit x86",
    "x86_64": "64-bit x86",
}


def elf_machine(data):
    """e_machine from an ELF header, or None when it is not an ELF file."""
    if len(data) < 20 or data[:4] != b"\x7fELF":
        return None
    little = data[5] == 1
    try:
        return struct.unpack("<H" if little else ">H", data[18:20])[0]
    except struct.error:
        return None


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip().splitlines()[2].strip())
        return 2
    path = argv[1]
    try:
        apk = zipfile.ZipFile(path)
    except Exception as error:
        print("cannot open %s: %s" % (path, error))
        return 1

    native = {}
    for name in apk.namelist():
        if not name.startswith("lib/") or not name.endswith(".so"):
            continue
        parts = name.split("/")
        if len(parts) < 3:
            continue
        abi = parts[1]
        machine = elf_machine(apk.read(name)[:64])
        native.setdefault(abi, []).append((name, MACHINES.get(machine, "unknown")))

    print("APK: %s" % path)
    if not native:
        print("  native libraries: none")
        print("  -> architecture independent (Dalvik bytecode only): runs on 32-bit and")
        print("     64-bit devices, arm and x86 alike.")
        return 0

    problems = []
    for abi in sorted(native):
        print("  %s (%s):" % (abi, ABI_NAMES.get(abi, "?")))
        for name, machine in native[abi]:
            print("     %-52s %s" % (name.split("/")[-1], machine))
        if abi == "armeabi-v7a" and any("64-bit" in m for _, m in native[abi]):
            problems.append("a 64-bit .so is packaged under lib/armeabi-v7a")
        if abi == "arm64-v8a" and any("32-bit" in m for _, m in native[abi]):
            problems.append("a 32-bit .so is packaged under lib/arm64-v8a")

    has32 = any(abi in native for abi in ("armeabi-v7a", "x86"))
    has64 = any(abi in native for abi in ("arm64-v8a", "x86_64"))
    print("  32-bit devices: %s" % ("yes" if has32 else "NO - it will not install/run"))
    print("  64-bit devices: %s" % ("yes" if has64 else "NO"))
    if not has32:
        problems.append("no 32-bit native library: a 32-bit-only phone would fail to load it")

    if problems:
        print("\nproblems:")
        for problem in problems:
            print("  - " + problem)
        return 1
    print("\nOK: covers the architectures above.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
