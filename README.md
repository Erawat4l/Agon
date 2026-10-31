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
| `publish.bat` | Builds **and publishes a downloadable APK release** to GitHub (auto-bumps the patch version) |
| `publish.bat 1.2.0` | Publishes that exact version |
| `publish.bat 1.2.0 beta` | Publishes as a pre-release (visible on the Beta channel in-app) |
| `publish.bat --token ghp_…` | Remembers a GitHub token in `gh-token.txt` (git-ignored) |

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

### Speed: warm tabs, not a website reload

Every tab is its own WebView that is created once and **kept alive**:

| Behaviour | Detail |
| --- | --- |
| Tapping a nav item | flips `View.VISIBILITY` on an already-painted WebView — no reload, no white flash, ~1 frame |
| First visit to a tab | the tab's WebView is pre-built in the background at launch (`TabWebViews.preload()`), so even the first tap skips the cold start |
| Returning to a tab | keeps DOM, scroll position and session; a silent `focus` event asks the page for fresh data |
| Caching | `LOAD_CACHE_ELSE_NETWORK` — repeat visits paint from disk cache, then refresh in place |
| Scroll | scrolling only scrolls; nothing about scrolling triggers a reload |
| Pull-to-refresh | still available, but only on an explicit pull, and it refreshes the *current* tab |
| Renderer crash | the dead WebView is dropped and rebuilt on the next visit instead of showing a blank page |

### Sign-in stays inside the app

Google/Apple/GitHub sign-in uses `window.open` / `target=_blank`. Instead of bouncing to
Chrome, `onCreateWindow` opens an **in-app popup WebView** ("Sign in to Arena") that
shares the same cookie jar, so the login completes in the app and lands you back on the
signed-in Arena screen. The popup closes itself once the redirect returns to arena.ai,
and Back dismisses it.

### Navigation, animation, theme

- Bottom nav shows **icons + labels**; a slim chevron handle above it collapses the bar
  to icons only and back (the old always-visible bar with a refresh button is gone).
- Top bar is now just the brand plus a contextual **update chip** (only when one exists)
  and a single overflow button — no permanent reload/three-dot clutter.
- The **More** sheet slides up with a scrim fade; the Update sheet does the same;
  nav items scale on press; tab switches cross-fade; the shell fades in on launch.
- **Appearance: System / Light / Dark** in the More sheet. It is applied through
  `attachBaseContext`, so every screen, sheet and dialog follows it immediately
  (the activity recreates with a fade instead of a hard flash).

### Shell details

| Native shell | What you see |
| --- | --- |
| Top bar | Brand only, plus an **update chip** that appears when a release is waiting and one overflow button |
| Bottom nav | **Chat · Rankings · Search · Update · More** with icons + labels; chevron handle collapses it to icons |
| Update screen | Bottom sheet: installed vs latest version, release notes, download progress, explicit **Install** |
| More sheet | Update, reload, share, open in browser, **appearance**, clear data, plus the exact build number |
| WebBridge / Downloads | page-generated files (`blob:`, `data:`) land in `Downloads/` |
| No browser chrome | No URL bar, no tabs, no "powered by Chrome"; app-styled sheets, dialogs and toasts |
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

Two ways — use whichever is available to you.

### A. From your PC: `publish.bat` (works today)

```bat
publish.bat
```

That builds the APK with an auto-bumped version (`1.1.0` → `1.1.1`), creates the GitHub
release **v1.1.1**, uploads `ArenaAI-1.1.1.apk`, and leaves a copy in this folder.
Every installed app then sees it: pull down to refresh or open **Update → Check for
updates**, download, tap Install, approve Android's prompt.

First run asks for a GitHub token once (stored in `gh-token.txt`, git-ignored):

1. <https://github.com/settings/personal-access-tokens/new>
2. Repository access → *Only select repositories* → `Arena-Ai-App`
3. Permissions → **Contents: Read and write**
4. Generate, then `publish.bat --token github_pat_…`

Use **Read-only** Contents for the token you paste into the app; the write token is only
for publishing from your PC.

### B. GitHub Actions: automatic on every push

`.github/workflows/build-apk.yml` builds *and publishes* a release on every push to this
branch (version = current minor + commit count, e.g. `1.1.12`), with the APK attached.
No tagging, no manual step — the app picks it up on its next check.

> If a run fails in ~2 seconds with **no steps and `runner_id: 0`**, Actions is not
> permitted to run for this repository (billing/runner restriction). Enable it in
> *Settings → Actions → General → Allow all actions*, or just use `publish.bat`.

**Rules of thumb**

- Version code = `major*1,000,000 + minor*1,000 + patch`, derived from the tag, so a
  higher tag is always seen as newer.
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
| Update check says *private repo* | Add the GitHub token — the app asks on first launch, or use Update → GitHub token |
| `publish.bat` says 401/403 | Token needs **Contents: read and write** on this exact repo |
| Actions run fails instantly, 0 steps | Actions is blocked for the repo — use `publish.bat` |
| *App not installed* when updating | Old APK was signed with a different key — uninstall once, then install |
| No update notification | Allow notifications for Arena (Android 13+ asks on first launch) |
| `build.bat install` can't see the phone | Enable Developer options → USB debugging, accept the RSA prompt |

`keystore.properties`, `*.jks` and `local.properties` are git-ignored. **Back up
`arena-release.jks`** — without it you cannot ship updates with the same signature.

---

This is an unofficial WebView wrapper around [arena.ai](https://arena.ai). It is not
affiliated with Arena; all content and accounts belong to Arena.
