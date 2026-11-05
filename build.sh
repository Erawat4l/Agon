#!/usr/bin/env bash
# Agon - the launcher for Termux (and any Linux shell). Everything real happens in
# build.py, so the phone and the PC run the same logic; this file only finds Python.
#
#   bash build.sh                 build the APK
#   bash build.sh doctor          what is installed / what is missing (builds nothing)
#   bash build.sh apk 1.4.58      build that exact version
#   bash build.sh publish         build and upload the APK to the release
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3 is needed to run the build - installing it once."
  if command -v pkg >/dev/null 2>&1; then
    pkg install -y python
  else
    echo "[X] Install python3 (e.g. sudo apt install python3), then run this again." >&2
    exit 1
  fi
fi

exec python3 build.py "$@"
