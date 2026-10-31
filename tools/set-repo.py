#!/usr/bin/env python3
"""Point the whole project at a different GitHub repository in one command.

The repository name appears in several places that must agree, or the in-app updater,
the Windows updater and the release script start looking at different projects:

    app/src/main/java/ai/arena/webapp/UpdateManager.java      (Android: releases/latest)
    desktop/src/main.js                                        (Windows: electron-updater)
    desktop/package.json                                       (build.publish)
    tools/publish.ps1                                          (default -Owner / -Repo)
    publish.bat, commit.bat                                    (help text / clone hints)
    README.md, desktop/README.md                               (docs)

Usage:
    python tools/set-repo.py Agon              # keep the owner, change the repo name
    python tools/set-repo.py someone/Agon      # change both

After renaming the repository on GitHub, GitHub redirects the old path for a while, but
the scripts call the API directly, so run this to keep them exact. Nothing else changes:
no version bump, no rebuild of the sources.

Exit codes: 0 changed (or already correct), 1 bad arguments, 2 nothing matched.
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The current identity, read from UpdateManager.java so there is one starting point.
OWNER_FILE = os.path.join(ROOT, "app", "src", "main", "java", "ai", "arena", "webapp",
                          "UpdateManager.java")

FILES = [
    ("app/src/main/java/ai/arena/webapp/UpdateManager.java", "owner+repo"),
    ("desktop/src/main.js", "owner+repo"),
    ("desktop/package.json", "publish.owner+repo"),
    ("tools/publish.ps1", "ps-defaults"),
    ("publish.bat", "mentions"),
    ("commit.bat", "mentions"),
    ("README.md", "mentions"),
    ("desktop/README.md", "mentions"),
    ("app/src/main/res/values/strings.xml", "mentions"),
]


def current_identity():
    with open(OWNER_FILE, encoding="utf-8") as handle:
        source = handle.read()
    owner = re.search(r'REPO_OWNER\s*=\s*"([^"]+)"', source)
    repo = re.search(r'REPO_NAME\s*=\s*"([^"]+)"', source)
    if not owner or not repo:
        sys.exit("could not read REPO_OWNER / REPO_NAME from UpdateManager.java")
    return owner.group(1), repo.group(1)


def rewrite(path, old_owner, old_repo, new_owner, new_repo):
    full = os.path.join(ROOT, path)
    if not os.path.exists(full):
        return 0
    with open(full, encoding="utf-8", newline="") as handle:
        text = handle.read()
    before = text
    if new_owner != old_owner:
        text = text.replace(old_owner, new_owner)
    if new_repo != old_repo:
        text = text.replace(old_repo, new_repo)
    if text != before:
        with open(full, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
        return 1
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("target", nargs="?", help="Agon, or owner/Agon")
    parser.add_argument("--current", action="store_true",
                        help="print the repository this project points at (owner/name)")
    args = parser.parse_args()

    if args.current or not args.target:
        owner, repo = current_identity()
        if args.current:
            print(owner + "/" + repo)
            return 0
        parser.error("give the new name, e.g. Agon or someone/Agon")

    if "/" in args.target:
        new_owner, new_repo = args.target.split("/", 1)
    else:
        new_owner, new_repo = None, args.target

    old_owner, old_repo = current_identity()
    new_owner = new_owner or old_owner

    if not re.fullmatch(r"[A-Za-z0-9._-]+", new_repo):
        sys.exit("the repository name may only contain letters, digits, dot, dash, underscore")
    if (new_owner, new_repo) == (old_owner, old_repo):
        print("Already pointed at " + old_owner + "/" + old_repo + " - nothing to do.")
        return 0

    changed = 0
    for path, _ in FILES:
        changed += rewrite(path, old_owner, old_repo, new_owner, new_repo)

    print("Repository: " + old_owner + "/" + old_repo + "  ->  " + new_owner + "/" + new_repo)
    print("Files updated: " + str(changed))
    if changed == 0:
        print("Nothing matched - check that the name really appears in those files.")
        return 2
    print("")
    print("Next:")
    print("  1. Rename it on GitHub too: Settings -> Repository name -> " + new_repo)
    print("     (the old URL keeps redirecting for a while, which keeps old installs working)")
    print("  2. Rebuild so the app and the installers carry the new name:")
    print("       build-all.bat")
    return 0


if __name__ == "__main__":
    sys.exit(main())
