#!/usr/bin/env python3
r"""Cheap pre-flight checks for Android resource XML.

Catches the mistakes aapt2 reports late in the build:
  - unescaped apostrophes / quotes inside <string>
  - malformed escape sequences (\z and friends)
  - format placeholders that do not match their arguments

Run: python3 tools/check-resources.py
"""
import glob
import re
import sys

VALID_ESCAPES = set("nt'\"@?\\")
problems = []


def check_string(path, name, body):
    if re.search(r"(?<!\\)'", body):
        problems.append(f"{path}: string/{name}: unescaped apostrophe (use \\')")
    for m in re.finditer(r"\\(.)", body):
        ch = m.group(1)
        if ch not in VALID_ESCAPES and not ch.startswith("u"):
            problems.append(f"{path}: string/{name}: invalid escape \\{ch}")
    indexes = sorted({int(x) for x in re.findall(r"%(\d+)\$[sdf]", body)})
    if indexes and indexes != list(range(1, max(indexes) + 1)):
        problems.append(f"{path}: string/{name}: format argument gap {indexes}")
    leftover = re.sub(r"%\d*\$?[sdf]|%%", "", body)
    if "%" in leftover:
        problems.append(f"{path}: string/{name}: dangling %")


def main():
    for path in glob.glob("app/src/main/res/values*/*.xml"):
        raw = open(path, encoding="utf-8").read()
        if not raw.lstrip().startswith("<?xml"):
            problems.append(f"{path}: missing XML declaration")
        for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', raw, re.S):
            check_string(path, m.group(1), m.group(2))

    if problems:
        print("\n".join(problems))
        print(f"\n{len(problems)} resource problem(s) found.")
        return 1
    print("resources OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
