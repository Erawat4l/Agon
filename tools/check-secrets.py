#!/usr/bin/env python3
"""Fails when something that must never be public is about to be committed.

Run before publishing (or from a pre-commit hook):

    python3 tools/check-secrets.py

It scans *tracked* files only, so a git-ignored keystore/token file is fine - the point is
to catch one that has been force-added or committed before .gitignore covered it.
"""
import re
import subprocess
import sys

# High-signal patterns. Anything that matches here is a real leak, not a style problem.
PATTERNS = [
    ("GitHub token", re.compile(r"\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})")),
    ("GitHub OAuth token", re.compile(r"\bgho_[A-Za-z0-9]{30,}")),
    ("private key block", re.compile(r"-----BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----")),
    ("AWS key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z\-_]{30,}")),
    ("Slack token", re.compile(r"\bxox[abpsr]-[A-Za-z0-9-]{10,}")),
    ("OpenAI-style key", re.compile(r"\bsk-[A-Za-z0-9]{32,}")),
    # A real store password only appears next to a key alias; the key itself is ignored.
    ("keystore password", re.compile(r"(storePassword|keyPassword)\s*[=:]\s*['\"][^'\"$%{]{6,}")),
    ("Windows user path", re.compile(r"[A-Za-z]:[\\/]+Users[\\/]+[A-Za-z0-9._-]+")),
    ("personal e-mail", re.compile(r"[\w.+-]+@(gmail|outlook|hotmail|yahoo|proton|icloud)\.[a-z]{2,}")),
]

# Files whose *content* is allowed to look like a secret (documentation of the pattern).
SKIP_FILES = {"tools/check-secrets.py", "NOTICE.md"}


def tracked_files():
    out = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True)
    return [line for line in out.stdout.splitlines() if line.strip()]


def main():
    problems = []
    for name in tracked_files():
        if name in SKIP_FILES:
            continue
        try:
            with open(name, "rb") as handle:
                raw = handle.read()
        except OSError:
            continue
        if b"\x00" in raw[:4096]:
            continue          # binary (png/jar): not text, nothing to match
        text = raw.decode("utf-8", "replace")
        for label, pattern in PATTERNS:
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                problems.append("%s:%d  %s" % (name, line, label))

    if problems:
        print("Refusing to publish - these look like secrets or personal data:")
        for problem in problems:
            print("  " + problem)
        print("\nRemove the value, rotate it if it was ever pushed, then run this again.")
        return 1
    print("no secrets or personal paths in tracked files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
