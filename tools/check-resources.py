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


FIELD_RE = re.compile(
    r'(?:(?:private|protected|public|static|final|volatile|transient)\s+)+'
    r'[\w<>\[\], .?]+?\s+(\w+)\s*(?:=[^;]*)?;')
LOCAL_RE = re.compile(r'(?:final\s+)?[\w<>\[\], .?]+?\s+(\w+)\s*=(?!=)')


def check_java_shadowing(path):
    """A local that shadows a field silently changes what a call refers to.

    This is exactly how `String host = uri.getHost()` turned `host.onSignedIn()`
    into a String method call and broke the build.
    """
    lines = open(path, encoding="utf-8").read().split("\n")
    fields = {}
    for i, line in enumerate(lines):
        indent = len(line) - len(line.lstrip())
        if indent == 4 and "(" not in line:
            match = FIELD_RE.fullmatch(line.strip())
            if match:
                fields[match.group(1)] = i + 1
    for i, line in enumerate(lines):
        indent = len(line) - len(line.lstrip())
        stripped = line.strip()
        if indent <= 4 or not stripped:
            continue
        if stripped.startswith(("/", "*", "return", "if", "for", "while", "else")):
            continue
        match = LOCAL_RE.match(stripped)
        if match and match.group(1) in fields:
            problems.append(
                f"{path}:{i + 1}: local '{match.group(1)}' shadows the field declared "
                f"on line {fields[match.group(1)]}")


# Known signatures of the AppUi helpers; a wrong argument count is a compile error.
APPUI_ARITY = {
    "dp": {2}, "color": {2}, "text": {5}, "icon": {4}, "rounded": {3},
    "pressable": {3}, "sheetScrim": {2}, "slideUp": {1}, "fadeIn": {1},
    "pop": {1}, "press": {1}, "isDark": {1}, "wrap": {1},
}


def _arg_count(text):
    text = text.strip()
    if not text:
        return 0
    depth, count = 0, 1
    for ch in text:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        elif ch == "," and depth == 0:
            count += 1
    return count


def check_java_helper_calls(path):
    src = open(path, encoding="utf-8").read()
    for m in re.finditer(r"AppUi\.(\w+)\s*\(", src):
        name = m.group(1)
        expected = APPUI_ARITY.get(name)
        if not expected:
            continue
        i, depth = m.end(), 1
        start = i
        while i < len(src) and depth:
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
            i += 1
        argc = _arg_count(src[start:i - 1])
        if argc not in expected:
            line = src[:m.start()].count("\n") + 1
            problems.append(
                f"{path}:{line}: AppUi.{name}() called with {argc} args, "
                f"expected {sorted(expected)}")


def check_batch(path):
    """cmd.exe gotchas: duplicate labels, unresolved jumps, unbalanced parens."""
    import collections
    text = open(path, encoding="utf-8", errors="replace").read()
    labels = [m.group(1).lower() for m in re.finditer(r"^:(\w+)", text, re.M)]
    for name, count in collections.Counter(labels).items():
        if count > 1:
            problems.append(f"{path}: label :{name} defined {count} times")
    jumps = {m.group(1).lower() for m in re.finditer(r"(?:goto|call) :(\w+)", text)}
    for missing in sorted(jumps - set(labels) - {"eof"}):
        problems.append(f"{path}: jumps to undefined label :{missing}")
    depth = 0
    for i, line in enumerate(text.split("\n"), 1):
        stripped = line.strip()
        if stripped.upper().startswith(("REM", "ECHO", ":")):
            continue
        bare = re.sub(r"\^\(|\^\)", "", line)
        bare = re.sub(r'"[^"]*"', '""', bare)
        depth += bare.count("(") - bare.count(")")
        if depth < 0:
            problems.append(f"{path}:{i}: unbalanced ')' ")


def main():
    for path in glob.glob("*.bat"):
        check_batch(path)

    for path in glob.glob("app/src/main/java/**/*.java", recursive=True):
        check_java_shadowing(path)
        check_java_helper_calls(path)

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
