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

**Requires:** JDK 17+ and the Android SDK (Android Studio installs both). If `build.bat`
says something is missing, it prints the exact `winget` command to fix it.

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

## Building the APK on GitHub instead (no local setup)

Push this repo and the included workflow builds the APK for you:
**Actions → "Build APK" → run → download the `ArenaAI-debug-apk` artifact.**
Pushing a tag like `v1.0` also attaches the APK to a GitHub Release.

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

Deliberately **zero third-party dependencies** — plain platform APIs only, so nothing
has to be resolved at runtime and the APK stays small (~1 MB).

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
| Blank page after sign-in | Clear the app's data, or check that your network allows Google/Apple auth |
| `build.bat install` can't see the phone | Enable Developer options → USB debugging, accept the RSA prompt |

`keystore.properties`, `*.jks` and `local.properties` are git-ignored. **Back up
`arena-release.jks`** — without it you cannot ship updates with the same signature.

---

This is an unofficial WebView wrapper around [arena.ai](https://arena.ai). It is not
affiliated with Arena; all content and accounts belong to Arena.
