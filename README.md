# Arena AI — native Android app

Arena AI as a real Android app, not a bookmark in Chrome.

One command in **cmd** builds a ready-to-install APK:

```bat
build.bat
```

That produces **`ArenaAI-debug.apk`** in this folder. Copy it to your phone, tap it,
allow *"install unknown apps"*, done. App name on the home screen: **Arena**.

---

## The commands

Run these from this folder in `cmd` (or double-click the `.bat` files).

| Command | What it does |
| --- | --- |
| `build.bat` | Builds `ArenaAI-debug.apk` — signed with the debug key, installs immediately |
| `build.bat install` | Builds, then installs + launches on a USB-connected phone (needs `adb`) |
| `build.bat release` | Builds `ArenaAI-release.apk` (signed if you ran `make-keystore.bat` first) |
| `build.bat clean` | Wipes build output |
| `make-keystore.bat` | Creates `arena-release.jks` + `keystore.properties` for a signed release build |

First build downloads Gradle and the Android build tools (~3–5 minutes). Later builds
take well under a minute, since everything is cached in `%USERPROFILE%\.gradle`.

`build.bat` also runs `tools/check-resources.py` before Gradle, so a bad string
(unescaped apostrophe, invalid escape, mismatched `%1$s`) fails with a readable line
instead of an aapt2 stack trace.

**Requires:** JDK 17–21 and the Android SDK.

No Android Studio needed — you can install just the SDK from cmd (~5 min):

```bat
mkdir "%LOCALAPPDATA%\Android\Sdk\cmdline-tools" 2>nul & curl -L -o "%TEMP%\cmdtools.zip" https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
tar -xf "%TEMP%\cmdtools.zip" -C "%LOCALAPPDATA%\Android\Sdk\cmdline-tools" && ren "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\cmdline-tools" latest
(for /l %i in (1,1,30) do @echo y) | "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
"%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

`build.bat` auto-detects that path, `ANDROID_HOME`, `ANDROID_SDK_ROOT` and an
Android Studio install; if it finds nothing it prints the same steps.

---

## What the app does

| Feature | Notes |
| --- | --- |
| Full Arena experience | `arena.ai` in a WebView with JavaScript, DOM storage, cookies, third-party cookies (needed for Google/Apple sign-in) |
| Persistent login | Cookies are kept between launches, so you stay signed in |
| Real Chrome user agent | WebView's default UA (`wv` + `Version/4.0`) makes some identity providers refuse to render; it is rewritten to a Chrome-style UA |
| Pull to refresh | Drag down at the top of the page |
| File upload | Image/document pickers work, including multi-select |
| Downloads | Handles `http(s)`, `data:` and `blob:` downloads into `Downloads/` (generated images, exports) |
| Mic / camera | Agents that ask for microphone or camera permission get a real Android permission prompt |
| Deep links | Tapping an `arena.ai` / `lmarena.ai` link opens the app |
| External links | `mailto:`, `tel:`, `upi:`, Play Store links open in their own apps |
| Back button | Navigates web history first, "press back again to exit" at the root |
| Offline screen | Friendly retry screen instead of WebView's error page |
| Dark mode | Follows the system theme, including status/navigation bars |
| Rotation, split screen | Handled without reloading the page |

Live streaming replies keep running when you switch apps (the WebView is intentionally
not paused).


---

## It looks like an app, not a browser tab

| Native shell | What you see |
| --- | --- |
| Top bar | Arena logo + name, a live **Update** pill, reload, overflow menu |
| Bottom navigation (phones) / left rail (tablets, landscape) | **Chat · Rankings · Search · Update · More**, with the active tab tinted |
| Update screen | Bottom sheet: installed vs latest version, release notes, download progress, explicit **Install** |
| More sheet | Update, reload, share, open in browser, clear data — plus the exact build number |
| No browser chrome | No URL bar, no tabs, no "powered by Chrome"; app-styled dialogs, sheets and toasts |
| Pull-to-refresh, offline screen, dark mode | All native, not web fallbacks |

The web content is only the middle of the screen — navigation, update flow and menus
are real Android views, so it reads as an app.

---

## In-app updates from your private GitHub releases

The app checks this repo's releases, marks the **Update** button and fires a
notification with its own chime — and it can never install anything silently.

**How it flows**

1. On launch, on resume, on pull-to-refresh and every 6 hours the app queries
   `api.github.com/repos/Erawat4l/Arena-Ai-App/releases/latest` (or all releases when
   the Beta channel is selected).
2. If the release's version is newer than the installed `versionCode`, the app:
   - tints the **Update** entry in the nav bar and shows a dot on its icon,
   - reveals a `v1.2.3` pill in the top bar,
   - posts a high-priority notification (*"Arena update available"*) that plays
     `res/raw/update_chime.wav` — a short two-note chime, not the system blip.
     The chime is a notification-channel sound, so you control it in
     *Settings → Apps → Arena → Notifications*.
3. Tapping the pill, the nav entry or the notification opens the **App Update** sheet:
   release notes, download size, **Download update** (progress bar + progress
   notification), then **Install update**.
4. Install always goes through Android's package installer: it shows
   *"Do you want to update this app?"* and the user must approve. On first use Android
   asks to allow *install unknown apps* for Arena — the app sends you straight to that
   toggle and then retries. **There is no silent install path anywhere in the code.**

**Private repo: GitHub token**

`Erawat4l/Arena-Ai-App` is private, so GitHub's API answers `404` without a token.
Any one of these works:

| Where | How |
| --- | --- |
| In the app (easiest) | Update sheet → *GitHub token (private repo)* → **Add** → paste a fine-grained token with **Contents: read-only** on just this repo. Stored in app-private prefs. |
| Build time | `gradle.properties` → `ARENA_GH_TOKEN=github_pat_…` (file is git-ignored) |
| Environment | `set ARENA_GH_TOKEN=github_pat_…` before `build.bat` |

Without a token the sheet shows *"private repo: add a GitHub token"* and everything
else keeps working.

**Publishing a release (this is what makes the update appear)**

```bat
git tag v1.0.1 && git push origin v1.0.1
```

Pushing a `v*` tag triggers `.github/workflows/build-apk.yml`, which builds with that
version (`v1.0.1` → `versionCode 1000001`), attaches `ArenaAI-1.0.1.apk` to a GitHub
Release, and every installed copy of the app sees it on its next check. Pushes to a
branch also build a `0.0.<run>` APK artifact (not a release) with a tiny version
code, so a tagged release always outranks it and the updater stays quiet until you tag.

**Rules of thumb**

- Bump the tag every release: `v1.0.1`, `v1.0.2`, … (version code = `major*1,000,000 + minor*1,000 + patch`).
- The release must contain an `.apk` asset; the app picks the first one.
- Debug and release builds are separate installs (`ai.arena.webapp.debug` vs
  `ai.arena.webapp`) — each updates itself from the same release.
- Android installs updates only from the same signing key, so keep `arena-release.jks`
  once you ship release builds.
- Release notes come from the release body, so use
  `gh release create v1.0.1 ArenaAI-1.0.1.apk --notes "What changed…"` if you publish by hand.

**Update settings inside the sheet:** release channel (Stable = newest published
release, Beta = includes pre-releases), auto-check on/off, and the GitHub token.

---

## Building the APK on GitHub instead (no local setup)

If **Actions** is enabled on the repository, every push runs
`.github/workflows/build-apk.yml`: **Actions → "Build APK" → latest run →
Artifacts → `ArenaAI-debug-apk`**. Pushing a tag like `v1.0` also attaches the APK
to a GitHub Release. (If the run fails instantly with no steps, Actions is disabled
for the repo: *Settings → Actions → General → Allow all actions*.)

---

## Project layout

```
build.bat / build.sh          one-command builders
make-keystore.bat             release signing key helper
app/build.gradle              module config (app id, version, signing)
app/src/main/AndroidManifest.xml
app/src/main/java/ai/arena/webapp/
    MainActivity.java         WebView shell: navigation, uploads, downloads, permissions
    PullToRefreshLayout.java  dependency-free pull-to-refresh container
app/src/main/res/             icons (adaptive + legacy), theme, colors, strings
.github/workflows/build-apk.yml
```

Almost dependency-free: only `androidx.core` (for `FileProvider`, which the package
installer requires) — everything else is platform APIs, so the APK stays small.

---

## Customising

- **Change the start page** — `START_URL` in `MainActivity.java`.
- **App name** — `app_name` in `app/src/main/res/values/strings.xml`.
- **Icon** — replace the PNGs in `app/src/main/res/mipmap-*dpi/` and the vectors in
  `app/src/main/res/drawable/ic_launcher_*.xml`; keep the same file names.
- **Version** — `versionCode` / `versionName` in `app/build.gradle`.
- **Package id** — `applicationId` in `app/build.gradle` (and the folder path under
  `app/src/main/java`).

---

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `Java not found` | `winget install EclipseAdoptium.Temurin.17.JDK`, then reopen cmd |
| `Android SDK not found` | Install Android Studio once, or set `ANDROID_HOME` to your SDK folder |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | You had a build with a different signature — uninstall the old app first |
| Play Protect blocks the install | Normal for sideloaded APKs — choose *"Install anyway"* |
| Blank page after sign-in | Clear the app's data (More → Clear app data), or check that your network allows Google/Apple auth |
| Update check says *private repo* | Add the GitHub token in the Update sheet |
| *App not installed* when updating | Old APK was signed with a different key — uninstall once, then install |
| No update notification | Allow notifications for Arena (Android 13+ asks on first launch) |
| `build.bat install` can't see the phone | Enable Developer options → USB debugging, accept the RSA prompt |

`keystore.properties`, `*.jks` and `local.properties` are git-ignored. **Back up
`arena-release.jks`** — without it you cannot ship updates with the same signature.

---

This is an unofficial WebView wrapper around [arena.ai](https://arena.ai). It is not
affiliated with Arena; all content and accounts belong to Arena.
