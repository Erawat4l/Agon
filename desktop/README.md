# Agon for Windows

The same client as the Android app, as a Windows program: one window showing arena.ai in a
persistent session, with the app's own menu, downloads and updates. Unofficial — see
`../NOTICE.md`.

## Build and run

Needs **Node.js 18+** (nothing else; the Android SDK is not involved).

```bat
cd desktop
npm install
npm run icon      :: optional - regenerates build\icon.ico from ..\artwork\agon-mark.jpg
npm start         :: run it from source
npm run dist      :: build the installers into desktop\release\
```

`npm run dist` produces, for **both 64-bit and 32-bit Windows**:

| File | What it is |
| --- | --- |
| `Agon-Desktop-<version>-x64.exe` | 64-bit installer (per-user, choose the folder) |
| `Agon-Desktop-<version>-ia32.exe` | **32-bit** installer, for old Windows on old hardware |
| `Agon-Desktop-<version>-x64.exe` / `-ia32.exe` (portable) | no install, runs from anywhere |
| `latest.yml` + `.blockmap` | what the auto-updater reads |

`ia32` is not decoration: it is the build that runs on a 32-bit Windows install, and it is
why the target list names both architectures explicitly. (Electron 26 is the last line that
still ships 32-bit Windows binaries, which is why the dependency is pinned to it.)

## Updates

The Windows build updates itself from **this repository's own GitHub releases**, the same
release the APK lives in — `tools/publish.ps1` builds both for one tag, so the version
number on the phone and on the PC always match.

- Checks 20 seconds after launch, then every 6 hours, and on demand from
  *Agon → Check for updates…*.
- `electron-updater` verifies the download against the SHA-512 in the release's
  `latest.yml` before it is offered. Nothing installs silently: it asks, then restarts.
- A private repository needs a token. Set `GH_TOKEN` before launching `npm run dist`
  (read-only *Contents* is enough to download; the upload needs the write token that
  `publish.bat` already uses).

## What it does and does not do

- One window, no Node in the page (context isolation on, sandbox on, no `<webview>`).
- Only `https://arena.ai` / `lmarena.ai` may load in the window; a provider popup
  (Google) gets a child window in the same session so the cookie still lands in Arena,
  and every other link is refused.
- Downloads go to the user's Downloads folder with the name flattened to one path segment.
- No camera, microphone, location, clipboard-read or notification permission is granted:
  the permission handler allows only fullscreen and a sanitized clipboard write.
- Devtools only when `AGON_DEVTOOLS=1` is set, or from *View → Toggle developer tools*.

## Status of the other platforms

- **Android** — this repository's main app, `app/`.
- **Windows** — this folder.
- **iOS** — not buildable from this repository, and not for lack of an icon: an iOS build
  needs a Mac, Xcode, and a `WKWebView` app plus its own signing profile. The equivalent
  client would be a small Swift shell around `WKWebView` with the same host allow-list;
  it is a separate project, and it is deliberately not a stub here so nobody downloads a
  file that cannot run.
- **macOS / Linux** — dropped: the scope is Android, iOS and Windows.
