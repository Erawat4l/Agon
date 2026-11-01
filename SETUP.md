# Agon — first-time setup on a new PC

You deleted the folder, so this starts from nothing. **Downloading the release does not
rebuild your setup** — the source code, the signing key and the GitHub token were never
in it, and the signing key cannot be recovered at all.

Work through the steps in order. Every command is typed into **cmd** (press
<kbd>Win</kbd>, type `cmd`, press Enter), not PowerShell.

---

## Step 0 — What you need to install

| Program | Why | Install |
| --- | --- | --- |
| **Git** | the source lives on GitHub | `winget install Git.Git` |
| **JDK 17** (17–21) | compiles the Android app | `winget install EclipseAdoptium.Temurin.17.JDK` |
| **Node.js 18+** | builds the Windows installers | `winget install OpenJS.NodeJS.LTS` |
| **Android SDK** | builds the APK | see Step 3 |
| **GitHub CLI** | `gh auth login` — its credential is what `publish.bat` uploads with | `winget install GitHub.cli` |

After installing, **close cmd and open it again** so it picks up the new `PATH`.

Check all of it in one line:

```bat
git --version & java -version & node --version & gh --version
```

All four must print a version. `java -version` must report **17 or newer** — JDK 17 is the
safe choice, and it is what this project's Gradle and Android plugin are pinned against.

---

## Step 1 — Get the code

Your folder was `%USERPROFILE%\Desktop\Erawat\Agon`. Recreate it by cloning:

```bat
mkdir %USERPROFILE%\Desktop\Erawat
cd /d %USERPROFILE%\Desktop\Erawat
git clone https://github.com/Erawat4l/Agon.git
cd Agon
```

> **The folder name decides where you build.** `%USERPROFILE%\Desktop\Erawat\Agon` and a
> clone named `Agon` are the same thing. Do **not** clone into `...\Agon-App` or
> `Agon-main`; the scripts use the folder they sit in, so the name only has to be the one
> you want to keep using.

Check you are on the right branch and the code is current:

```bat
git branch --show-current
git pull
```

`git branch --show-current` must print `main`. If it prints something starting with
`arena/`, run `git checkout main` first — those branches are working copies and are deleted
after merging.

---

## Step 2 — The two files that are *not* in the repository

Both are `git`-ignored on purpose. You have to recreate them, and one of them you cannot
recreate *exactly*.

### 2a. `gh-token.txt` — the upload credential

`publish.bat` needs write access to the repo only to upload the release.

The easy route: sign the CLI in once, and `publish.bat` reuses that credential (do this
instead of making a token, unless `gh auth login` fails):

```bat
gh auth login
```

Choose **GitHub.com** → **HTTPS** → **Login with a web browser**, and paste the code it
shows into the page it opens. Then check:

```bat
gh auth status
```

It must say *Logged in to github.com*.

If you would rather use a token than the CLI:

1. <https://github.com/settings/personal-access-tokens/new>
2. Repository access → *Only select repositories* → **Agon**
3. Permissions → **Contents: Read and write**
4. Generate, copy it, then:

```bat
publish.bat --token github_pat_YOUR_TOKEN_HERE
```

That writes `gh-token.txt` (git-ignored) and exits. Do not paste the token into any file
you commit, and do not put it in a chat window.

### 2b. `arena-release.jks` — the signing key — **read this before your first release**

Android will only install an update over an existing app if the new APK is signed with
**the same key** as the installed one. Your old key lived in the folder you deleted, so
unless you have a backup somewhere (another PC, a cloud drive, a `.jks` you copied out),
**it is gone and no tool can regenerate it**.

What that means for you:

- The people who already have Agon installed will get
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / *App not installed* on the next update.
- The only fix **for them** is to uninstall Agon once and install the new APK by hand.
  After that, updates work normally again.

Search for a surviving copy before you accept that:

```bat
where /r C:\ arena-release.jks
```

(That scans the whole drive and takes a couple of minutes. It also finds copies inside
`%USERPROFILE%\Downloads`, OneDrive and any backup folder.) If it finds one, move it to the
`Agon` folder and skip to Step 2c.

### 2c. Create a new key (only if Step 2b found nothing)

```bat
cd /d %USERPROFILE%\Desktop\Erawat\Agon
make-keystore.bat
```

It asks for a password you choose, writes `arena-release.jks` and `keystore.properties`,
and returns. Both stay out of git.

> **Back `arena-release.jks` and `keystore.properties` up now** — copy both to a USB stick
> or a password manager's file storage. Losing them again means every installed copy has to
> be uninstalled by hand a second time. This is the single most important file in the
> project.

---

## Step 3 — The Android SDK

`build.bat` looks for the SDK in `%LOCALAPPDATA%\Android\Sdk`, then `ANDROID_HOME`, then
`ANDROID_SDK_ROOT`, then an Android Studio install. Install it without Android Studio
(~5 minutes, from cmd):

```bat
mkdir "%LOCALAPPDATA%\Android\Sdk\cmdline-tools" 2>nul & curl -L -o "%TEMP%\cmdtools.zip" https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
tar -xf "%TEMP%\cmdtools.zip" -C "%LOCALAPPDATA%\Android\Sdk\cmdline-tools" && ren "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\cmdline-tools" latest
(for /l %i in (1,1,30) do @echo y) | "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
"%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

The `(for /l …)` line answers `y` to every licence prompt — that is expected, not a script
you should worry about. It prints a lot; only the last `100%` matters.

If you already had Android Studio, skip this step.

---

## Step 4 — Build once, locally, with nothing published

```bat
build-all.bat
```

Expected result: `Agon.apk` (Android) and `Agon-Setup-64.exe` / `Agon-Setup-32.exe`
(Windows) appear in the folder. The first run downloads Gradle and the Electron packages,
so it takes 5–10 minutes; later runs are much faster.

- If the Android half fails → Step 3 is wrong, or the JDK is not 17–21.
- If the Windows half fails → Node is missing (`node --version`), or a stray
  `%LOCALAPPDATA%\electron-builder\Cache` folder; delete it and retry.
- It is normal for one half to succeed and the other to fail — the summary says which.

Nothing is published by this step. `build-all.bat` never touches GitHub.

---

## Step 5 — Publish

**Do this only when Step 4 produced all three files.**

```bat
publish.bat 1.4.43
```

`publish.bat` does the whole thing in the right order: pre-flight checks → Gradle build with
version 1.4.43 → APK built and its architectures checked → release created as a **draft** →
Windows installers built and attached → draft published. **A failure anywhere leaves it a
draft**, so installed apps never see a half-empty release.

Wait for `[OK] Release live: v1.4.43`. The draft is invisible to every phone until that
line prints.

### Which version number to use

The old releases are about to be deleted, but **the phones still have 1.4.42 installed**, so
the number has to stay above it: **1.4.43**, not 1.0.0. Publish a number lower than what is
on the devices and every installed copy decides it is already newer and says *"you are up to
date"* for ever. `publish.ps1` refuses to publish a lower number, which is why the version is
passed explicitly here instead of being auto-bumped.

(`publish.bat` with no argument auto-bumps from the newest `v*` tag. That is fine **only**
while the old tags still exist. Once you delete the old releases **and** their tags, always
pass the number as above — otherwise the bump restarts from `1.0.1`, which `publish.ps1`
then refuses.)

## Step 6 — Delete the older releases, keeping the new one

> **Do not paste anything from this step until Step 5 printed `[OK] Release live`.**
> `gh release delete` is immediate and irreversible. Type one line at a time and read the
> tag in it before pressing Enter. Pasting this whole section as a block has already
> happened once — it ran a list *and* a delete in one go.

Deleting the old ones is what makes the new tag the *latest* release: every installed app
reads GitHub's `releases/latest`, and with one release left there is no ambiguity.

**6a. Prove the new release is complete before touching anything:**

```bat
gh release list
```

The new version must be listed and **not** marked *Draft*. Then:

```bat
gh release view --json assets --jq ".assets[].name"
```

That must print all four names:

```
Agon-1.4.43.apk
Agon-Setup-32.exe
Agon-Setup-64.exe
latest.yml
```

**If any of the four is missing, stop here.** Do not delete the older release — it is
currently the working one, and the new release is incomplete. Fix the build and publish
again with the same version.

**6b. Delete the older releases, one line per release:**

```bat
gh release delete v1.4.42 --yes --cleanup-tag
```

Read that tag before pressing Enter. If the only release listed is the one you just
published, there is nothing to delete and you are already done.

`--cleanup-tag` removes the git tag too, which is what stops the auto-bump from reading a
version that no longer exists.

**6c. Confirm:**

```bat
gh release list
```

Exactly one row, marked **Latest**. If the old one is still listed, delete it and re-run;
nothing is broken by having two releases for a while.

## Step 7 — Check it actually works

1. **On the PC**: double-click `Agon-Setup-64.exe`, install, open Agon. Then
   **Agon menu → Use the sign-in link from my browser** and follow the window. This is the
   part that was just fixed; it is the first thing worth testing.

   The steps that window shows, matching arena's panel exactly:

   1. On arena's **Log In or Create Account** panel, type your address into **Your email**
      — the field must not be empty.
   2. Click **Continue with email**. With the field empty this only shows
      *Email is required*; that is not a failure.
   3. Open the email and **do not click the link** — it works once, and clicking uses it up
      in your mail app.
   4. Right-click the link and choose **Copy Link Address**, paste it into Agon's window.

   Google's button cannot work on the PC: Google refuses its sign-in inside any embedded
   browser, which is Google's policy, not a setting in Agon. The email route avoids that
   entirely because the account is confirmed by the mail.
2. **On the phone**: install `Agon-1.4.43.apk` (uninstall the old Agon first if the
   signing key changed — see Step 2b). Open it, sign in, then
   **More → Update → Check for updates** to confirm the updater sees 1.4.43.

---

## If you make the repository private

Making it private is fine, and it hides the releases and the source from everyone else. Three
consequences, so nothing surprises you later:

| Effect | Detail |
| --- | --- |
| **The in-app updater stops finding releases** | `UpdateManager` and the desktop updater ask GitHub anonymously. A private repo answers `404`, so both report *"no release found"*. Fix: give the app a **read-only** token (Contents: Read) and have it send that, or keep the repo public and just stop publishing releases. |
| **Only accounts with access can download** | Anyone you send an installer to needs to be signed in to GitHub and have access. The APK/EXE you built locally still installs fine — the restriction is the download, not the file. |
| **`publish.bat` keeps working** | The token from `gh auth login` belongs to your account, which owns the repo, so publishing is unaffected. |

Nothing needs changing in the code to make the repo private. The update check is the only
part that quietly stops working, and it fails **silently as "nothing new"**, which is easy to
mistake for being up to date.

## Your routine from now on

```bat
cd /d %USERPROFILE%\Desktop\Erawat\Agon
git pull
publish.bat 1.4.44
```

Then delete the previous release, as in Step 6. Bump the last number by one each time
(`1.4.44`, `1.4.45`, …).

**Never make the version go backwards, and never reuse a number that already has a tag.**

`main` on GitHub is the complete, current source — this file included. If the folder is ever
lost again, Step 1 is the only recovery step for the code. The two files that need backing up
yourself are the signing key and the credential (Step 2); nothing else is irreplaceable.

---

## Things that will bite you, in the order they usually do

| Symptom | Cause and fix |
| --- | --- |
| `'git' is not recognized` | Git installed but cmd was open before it. Close cmd, open it again. |
| `Java not found` | JDK missing, or `JAVA_HOME` not set. `winget install EclipseAdoptium.Temurin.17.JDK`, reopen cmd. |
| Gradle: `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable...` | The SDK is missing or in an unexpected place — Step 3. `publish.bat` now finds it and writes `local.properties` itself; if it still says this, the SDK genuinely is not installed. |
| `App not installed` when updating | Different signing key (Step 2b). Uninstall Agon once, install the APK by hand, and updates work again. |
| *This would publish 1.0.1, but v1.4.42 is already published* | A stale `version.txt`. Delete it (`del version.txt`) and pass the version explicitly: `publish.bat 1.4.43`. |
| `publish.bat` says 401 / 403 | The credential expired. `gh auth login` again, delete `gh-token.txt` if it exists, retry. |
| `git pull` refuses: *local changes would be overwritten* | Someone changed a tracked file. `git checkout -- .` throws away local edits, then `git pull`. |
| Publish failed and left a draft | Nothing is live, nothing broke. Fix the error, re-run `publish.bat` with the **same** version — it reuses the draft instead of making another one. |
| A GitHub Actions run appeared | It does not build anything. `build.yml` is manual-only; ignore or delete the run. All building is local. |

---

## Where the pieces are, if you need to debug

| Thing | Location |
| --- | --- |
| Signing key + password | `arena-release.jks`, `keystore.properties` (both git-ignored) |
| Upload credential | `gh-token.txt` (git-ignored), or `gh auth login`'s stored credential |
| Android app source | `app/src/main/java/ai/arena/webapp/` |
| Windows app source | `desktop/src/` (`main.js`, `preload.js`) |
| Build output | `app/build/outputs/apk/debug/app-debug.apk`, `desktop/release/` |
| Version baseline | `version.txt` (git-ignored, generated, safe to delete) |
| Publish logic | `tools/publish.ps1` (called by `publish.bat`) |
