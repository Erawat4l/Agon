# Agon — a native Android app for arena.ai

Agon puts arena.ai in a real Android app instead of a bookmark in Chrome.

One command in **cmd** builds a ready-to-install APK:

```bat
build.bat
```

That produces **`Agon-debug.apk`** in this folder. Copy it to your phone, tap it,
allow *"install unknown apps"*, done. App name on the home screen: **Agon**.

---

## The commands

Run these from this folder in `cmd` (or double-click the `.bat` files).

| Command | What it does |
| --- | --- |
| `build.bat` | Builds `Agon-debug.apk` — signed with the debug key, installs immediately |
| `build.bat install` | Builds, then installs + launches on a USB-connected phone (needs `adb`) |
| `build.bat release` | Builds `Agon-release.apk` (signed if you ran `make-keystore.bat` first) |
| `build.bat clean` | Wipes build output |
| `make-keystore.bat` | Creates `arena-release.jks` + `keystore.properties` for a signed release build |
| `publish.bat` | Builds **and publishes a downloadable APK release** to GitHub (auto-bumps the patch version) |
| `publish.bat 1.2.0` | Publishes that exact version |
| `publish.bat 1.2.0 beta` | Publishes as a pre-release (visible on the Beta channel in-app) |
| `publish.bat --token ghp_…` | Remembers a GitHub token in `gh-token.txt` (git-ignored) |
| `commit.bat` | **All-in-one:** pull → bump version → build → commit → push → publish release → install on USB phone → open release page |
| `commit.bat "my changes"` | Same, with your commit message |
| `commit.bat "msg" 1.3.0` | Same, at a version you choose |
| `commit.bat "msg" skipbuild` | Commit + push only |
| `commit.bat "msg" beta` | Publish as a pre-release |

First build downloads Gradle and the Android build tools (~3–5 minutes). Later builds
take well under a minute, since everything is cached in `%USERPROFILE%\.gradle`.

`build.bat` also runs `tools/check-resources.py` before Gradle, so a bad string
(unescaped apostrophe, invalid escape, mismatched `%1$s`), a wrong `AppUi` helper
arity, a shadowed field, an unbalanced brace or a tab-id lookup that can go out of
bounds fails with a readable line instead of an aapt2/javac stack trace.

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
| Pull to refresh | Drag down at the very top of the page (never hijacks ordinary scrolling) |
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
| Tapping Chat | counted in a 380 ms window: 1 = your current chat, 2 = arena's history sidebar, 3 = new chat |
| Tapping Rankings / Search | flips `View.VISIBILITY` on an already-painted WebView — no reload, no white flash, ~1 frame |
| First visit to a tab | the tab's WebView is pre-built in the background at launch (`TabWebViews.preload()`), so even the first tap skips the cold start |
| Returning to a tab | keeps DOM, scroll position and session; a silent `focus` event asks the page for fresh data |
| Caching | `LOAD_DEFAULT` — the network answers, the cache answers only what is still fresh. `LOAD_CACHE_ELSE_NETWORK` painted stale Arena documents and made pages look permanently stuck |
| Stalled page | a load that has not finished after 9 s raises one small bar: *This page stopped loading — Load again* |
| Loading ring | the nav item whose page is still loading shows a small spinner in place of its icon |
| Scroll | scrolling only scrolls; nothing about scrolling triggers a reload |
| Pull-to-refresh | **removed** — scrolling never reloads the page; use *More → Reload* when you want a fresh load |
| Renderer crash | the dead WebView is dropped and rebuilt on the next visit instead of showing a blank page |

### Sign-in: Google, in the app, with a browser handoff

Sign-in starts and normally finishes inside the app, with no browser frame around a login
page. Success is decided by the page, not by a redirect: a bare redirect is never reported
as a login. When Google refuses the embedded page, the browser is a labelled second route -
and the link it comes back with is what finishes the sign-in **in the app**.

1. **Google, or email** — two rows in the sheet. *Continue with email* asks for the address
   in an Agon form (the app's own field, remembered for next time, never a password), then
   opens Arena's page for the password or the emailed link, so the account can be created or
   confirmed there. It is the route that works when a Google account's second-factor devices
   are not to hand.
   *Continue with Google* opens Arena's own sign-in
   page **inside the app**, full-screen, in `SignInSheet`: same cookie jar as the tabs, so
   the session lands in Arena directly. Google is the only provider listed, because it is
   the only one Arena accepts for an account — the GitHub account is what connects Agent
   Mode connectors and authorises app updates, not a way into Arena.
2. `SignInDriver` watches the page with a `MutationObserver` and presses Arena's own
   "Log in" control the moment it is inserted, then "Continue with Google" (the home page
   has no such control; the search covers shadow roots, the menu button, and finally
   `/leaderboard`, which does render one). The old build polled every 900 ms; the observer
   makes the tap part of the page's own paint instead of a round trip after it.
3. `window.open` / `target=_blank` from the page is captured by `onCreateWindow` and hosted
   as a layer of the same in-app sheet instead of being dropped or pushed to Chrome.
4. When arena hands the account step to Google (`accounts.google.*`), the sheet stops
   looking for Arena's dialog — there is none on that host — and says whose page is on
   screen. If Google refuses the embedded page outright ("This browser or app may not be
   secure"), the header says so and the browser row is always one tap away.
4b. **On a Google page, the first sign-in has to be typed there, and that is not avoidable
   by the app.** (It *is* avoidable by using the email route above, which is why that row
   exists.) A
   WebView has its own cookie jar, separate from Android's accounts and from Chrome, so a
   fresh app has no Google session and Google shows its email form. Once that jar holds a
   Google session, Google shows *"Choose an account"* instead, and the sign-in is that one
   tap plus the return to Arena. The jar is never wiped (that was a 1.4.6 bug), it is
   flushed on completion and on pause (Android 9 writes cookies lazily), and the address
   the user typed is sent back as `login_hint` on later attempts so Google can skip the
   form entirely.
5. Success is decided by the page: cookies cannot be read when they are HttpOnly, so a
   rendered arena page that offers no sign-in control **is** the signed-in state. A page
   that still offers one keeps the sheet open and says so. Arena's own session cookie
   (`arena-auth-prod-v1`, chunked into `.0`/`.1`) counts as proof too — it was in the jar
   on a device that still reported "session cookie: absent", which was a name-matching bug.
6. **The browser row is a fallback with a caveat.** Chrome usually does show Google's real
   picker, because Chrome has a Google session — but a session created there belongs to
   Chrome. Bringing it into the app needs a link Arena itself publishes
   (`arena.ai/auth/handoff?ticket=…`); `takeBrowserHandoff` handles one and only accepts an
   `arena.ai` / `lmarena.ai` host, but that link has never been observed on a real device,
   so the row says "it stays signed in there, not here" rather than promising a transfer.
   If that link does turn up in real traffic, the handoff already works.
7. **Finding out for certain.** `SignInTrace` keeps a short local notebook: every arena
   link the OS hands the app, every Google page that came up, every outcome, with query
   values stripped to `name=set` — never a ticket, code, token, or anything typed. The
   diagnostics sheet prints it, says `handoff link seen: yes/no`, and can clear it so an
   experiment starts blank. Do a browser sign-in, open diagnostics: if nothing was
   recorded, the handoff does not exist for this app and the browser row stays a caveat.
7. Back closes the sheet.

`AppChrome.openExternally()` no longer hands http(s) links to another app at all: only
non-web schemes (`mailto:`, `market:`, `intent:`) leave Arena, everything else opens in
an in-app WebView sheet. That is what used to drop you into Chrome.

### Appearance follows the app *and* the page

Light/Dark/System does not stop at the native chrome. Arena accepts a
`?__theme=dark|light` query parameter and remembers it in `localStorage`, so the app sets
both — and re-applies it after every page load and on each tab switch, otherwise the page
flips itself back to light.

### Developer Settings

An ordinary sheet shows a user's things only: appearance, sign-in, sign out, clear data.
Everything a developer needs is behind one row — **Enable Developer Settings** (or seven
taps on the build line in the footer, the Android gesture) — which adds a *Developer
options* section: sign-in diagnostics, the page switches (Arena's sidebar and header), every
test toggle, the version line, reset settings, and a lock row to hide it all again.

### Navigation, animation, theme

- Bottom nav shows **icons + labels**; a slim chevron handle above it collapses the bar
  to icons only and back (the old always-visible bar with a refresh button is gone).
- **Four tabs only: Chat · Rankings · Search · More.** Updates live inside the More
  sheet, and More carries the update dot/label when a release is waiting.
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
| More sheet | **Update**, reload, **sign in**, **appearance**, clear data, plus the exact build number — no share / open-in-browser |
| WebBridge / Downloads | page-generated files (`blob:`, `data:`) land in `Downloads/` |
| No browser chrome | No URL bar, no tabs, no "powered by Chrome"; app-styled sheets, dialogs and toasts |
| Offline screen, dark mode | Native, not web fallbacks |

The web content fills the screen edge to edge; the top bar and the bottom nav **float
over it** as real Android views, and the page is told their heights as CSS insets so
arena's own composer and fixed menus stay inside the app frame instead of being
clipped or pushed out of it. Nothing about the shell reloads the page.

---

## If the app ever fails to start

Startup is wrapped in a crash guard. A failure no longer closes the app silently:
the trace is written to `files/last_crash.txt` and shown on screen with a
**Copy error details** button, so the exact exception can be pasted into a chat
(and `adb logcat -b crash -d` works too).

---

## In-app updates from your private GitHub releases

The app checks this repo's releases, marks the **Update** button and fires a
notification with its own chime — and it can never install anything silently.

**How it flows**

1. On launch, on resume and every 6 hours the app queries
   `api.github.com/repos/Erawat4l/Arena-Ai-App/releases/latest` (or all releases when
   the Beta channel is selected).
2. If the release's version is newer than the installed `versionCode`, the app:
   - tints the **Update** tab in the bottom bar (or the More icon when no Update tab is
     shown) and reveals the pill,
   - reveals a `v1.2.3` pill in the top bar,
   - posts a high-priority notification (*"Arena update available"*) that plays
     `res/raw/update_chime.wav` — a short two-note chime, not the system blip.
     The chime is a notification-channel sound, so you control it in
     *Settings → Apps → Arena → Notifications*.
3. Tapping the pill, *More → App Update*, or the notification opens the **App Update** sheet:
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

### After every change: `commit.bat`

```bat
commit.bat "fixed the composer overlap"
```

Commits everything, pushes this branch, builds, and publishes a new release with the APK
attached. Installed apps then see it on their next check. Run it after every update you
make; that is the whole workflow.

### After every change: `commit.bat` (the all-in-one command)

```bat
commit.bat "fixed the composer overlap"
```

Seven steps in one go:

1. `git pull --rebase --autostash` — picks up anything pushed from elsewhere, and
   clears a stale local `version.txt` first so it can never block the pull
2. bumps the version from `version.txt` (or the latest `v*` tag), e.g. `1.2.0` → `1.2.1`
3. builds `app-debug.apk` with that version and copies it to `Agon-<version>.apk`
4. commits + pushes this branch
5. publishes the GitHub release with the APK attached
6. runs `adb install -r` if a phone is on USB (skipped silently otherwise)
7. opens the release page in your browser

`version.txt` in the repo root holds the last published version. It is **git-ignored
and generated locally** — committing it made every later `git pull` fail with
*"Your local changes to version.txt would be overwritten by merge"* — so the bump
counts locally even before the first tag exists, without ever conflicting. Installed apps pick the new release up on
their next check (`Update → Check for updates`).

### A. From your PC: `publish.bat` (works today)

```bat
publish.bat
```

That builds the APK with an auto-bumped version (`1.1.0` → `1.1.1`), creates the GitHub
release **v1.1.1**, uploads `Agon-1.1.1.apk`, and leaves a copy in this folder.
Every installed app then sees it: tap **Update → Check for updates**, download, tap
Install, approve Android's prompt. The release page link opens **inside the app** —
the app never sends you to Chrome.

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
  `gh release create v1.0.1 Agon-1.0.1.apk --notes "What changed…"` if you publish by hand.

**Update settings inside the sheet:** release channel (Stable = newest published
release, Beta = includes pre-releases), auto-check on/off, and the GitHub token.

---

## Building the APK on GitHub instead (no local setup)

If **Actions** is enabled on the repository, every push runs
`.github/workflows/build-apk.yml`: **Actions → "Build APK" → latest run →
Artifacts → `Agon-debug-apk`**. Pushing a tag like `v1.0` also attaches the APK
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
    CrashLog.java             last-crash recorder shown on screen
app/src/main/res/             icons (adaptive + legacy), theme, colors, strings
.github/workflows/build-apk.yml
```

Almost dependency-free: only `androidx.core` (for `FileProvider`, which the package
installer requires) — everything else is platform APIs, so the APK stays small.

---

## Security, privacy and licensing — read this before publishing

This repository is an unofficial client for arena.ai. Nothing in it is affiliated with,
endorsed by, or supported by Arena / LMArena; that sentence is also in `NOTICE.md`, which
must ship whenever the project is made public.

**What the code holds about the user**

| Thing | Where | Leaves the device? |
| --- | --- | --- |
| Arena session cookie | the WebView cookie jar (HttpOnly, owned by arena.ai) | only to arena.ai, as any browser does |
| Account email + provider | `agon_profile` prefs | no |
| GitHub token (private-repo update checks) | `agon_update` prefs, or `ARENA_GH_TOKEN` at build time | only to api.github.com |
| Sign-in trace (URLs, query values stripped to `name=set`) | `agon_signin_trace` prefs | no |
| Crash trace | `files/last_crash.txt` | no |

Both backup rule files exclude `agon_update`, `agon_profile` and `agon_signin_trace`, so a
cloud backup of the app cannot be used to read someone else's token or address. The app has
no analytics, no telemetry and no third-party network calls of its own: it talks to arena.ai
(the page) and api.github.com (update checks) and nothing else.

**What was hardened in 1.4.20** (all of it is in the code with a comment saying why)

- The exported activity accepts only `https` links to `arena.ai` / `lmarena.ai` compared
  per host segment — `arena.ai.evil.example` and `notarena.ai` no longer pass.
- The browser route refuses anything that is not an arena URL, so a link can never be used
  to make the app open an arbitrary page in the user's browser.
- The in-app updater only downloads an APK from `github.com` / `githubusercontent.com` over
  https, and only if it is an `.apk` release asset. Android's installer still verifies the
  signing certificate, so a swapped APK fails to install rather than replacing the app.
- The `ArenaNative` JS bridge is write-only and flattens file names to a single path
  segment, so a script in the page cannot aim a download outside `Downloads/`.
- Every WebView has `setAllowFileAccess(false)` and denies mixed content, so a remote page
  cannot read the app's private files.
- `allowBackup` data that could carry a secret is excluded (above).

**Known limits, stated instead of hidden**

- Arena can detect that the page is running in a WebView, and Google's policy for embedded
  browsers may refuse its sign-in. That is a policy decision by those services, not
  something an app can fix; the app does not spoof its way around a refusal.
- The "desktop Chrome user agent" switch is a developer tool. Turning it on makes the app
  claim to be a browser, which can make a Google sign-in fail. It is off by default.
- There is no password storage, no credential capture and no local proxy, by design.

**Licence and third-party marks**

`LICENSE` is Apache-2.0: it covers this repository's own source, and it disclaims warranty.
It does not give anyone a licence to Arena's name, logo, service or page content — the
Arena/LMArena names and marks belong to their owner, and `artwork/agon-mark.jpg` is the
app's own icon, not Arena's logo. If the project is published, publish it as an unofficial
client, keep `NOTICE.md`, and do not put Arena's logo in it.

---

## Customising

- **Change the start page** — `START_URL` in `MainActivity.java`.
- **App name** — `app_name` in `app/src/main/res/values/strings.xml`.
- **Icon** — the mark is the rendered illustration in `artwork/agon-mark.jpg`. Run
  `python3 artwork/make-icons.py` (needs Pillow) to re-cut every launcher size from it:
  legacy `mipmap-*dpi/ic_launcher[_round].png`, the adaptive foreground/monochrome layers
  in `mipmap-xxxhdpi/`, and the in-app `drawable-xxxhdpi/agon_logo.png`. The adaptive
  background is the vector `drawable/ic_launcher_background.xml`. Preview:
  `artwork/icon-preview.png`.
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
| Blank page after sign-in | Clear the app's data (More → Clear app data), or check that your network allows Google sign-in |
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
