#!/usr/bin/env python3
"""Rename the built installers to the two names people actually look for.

After a build this leaves, in the repository root:

    Agon.apk    the Android build (no architecture in the name, see README)
    Agon.exe    the 64-bit Windows installer
    Agon-32.exe the 32-bit Windows installer  (only if a matching file was built)

The versioned names electron-builder produces are kept as well, because the Windows
auto-updater and the release page refer to them. The short names are copies, never links:
a copy survives being moved to a phone or a USB stick on its own.

Usage:
    python tools/collect-artifacts.py [--root PATH]

Exit codes: 0 both artifacts present, 1 something was missing (the reason is printed).
"""

import argparse
import glob
import os
import shutil
import sys


def first_match(patterns):
    for pattern in patterns:
        hits = sorted(glob.glob(pattern), key=os.path.getmtime, reverse=True)
        if hits:
            return hits[0]
    return None


def copy(src, dst):
    if os.path.abspath(src) == os.path.abspath(dst):
        return dst
    shutil.copyfile(src, dst)
    return dst


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))), help="repository root (default: the one above tools/)")
    args = parser.parse_args()
    root = args.root

    apk = first_match([
        os.path.join(root, "Agon-release.apk"),
        os.path.join(root, "Agon-debug.apk"),
    ])
    # Prefer the two names that are actually published; the arch-suffixed originals are
    # electron-builder's own output and are only used if the hook did not run.
    exe64 = first_match([os.path.join(root, "Agon-Setup-64.exe"),
                         os.path.join(root, "desktop", "release", "Agon-Setup-64.exe"),
                         os.path.join(root, "Agon-Setup-*-x64.exe")])
    exe32 = first_match([os.path.join(root, "Agon-Setup-32.exe"),
                         os.path.join(root, "desktop", "release", "Agon-Setup-32.exe"),
                         os.path.join(root, "Agon-Setup-*-ia32.exe")])

    ok = True

    if apk:
        copy(apk, os.path.join(root, "Agon.apk"))
        print("  Agon.apk    <- " + os.path.basename(apk))
    else:
        print("  Agon.apk    not found (build the APK first: build-all.bat)")
        ok = False

    if exe64:
        copy(exe64, os.path.join(root, "Agon.exe"))
        print("  Agon.exe    <- " + os.path.basename(exe64) + "   (64-bit installer)")
    else:
        print("  Agon.exe    not found (build the EXE first: build-all.bat)")
        ok = False

    if exe32:
        copy(exe32, os.path.join(root, "Agon-32.exe"))
        print("  Agon-32.exe <- " + os.path.basename(exe32) + "   (32-bit installer)")
    else:
        # Not an error: a 32-bit target is optional, and an old PC can still install the
        # 64-bit setup only if Windows itself is 64-bit.
        print("  Agon-32.exe skipped (no 32-bit installer was built)")

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
