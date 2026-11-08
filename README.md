# Agon — a native Android app for arena.ai

Agon puts arena.ai in a real Android app instead of a bookmark in Chrome.

> **Starting from a fresh clone on a new PC?** Read **[SETUP.md](SETUP.md)** first: it covers
> the two files the repository deliberately does not contain (the APK signing key and the
> GitHub credential), the toolchain, and the publish-then-delete-the-old-release routine.

One command builds a ready-to-install APK — on Windows **or** on the phone itself. On
Windows it is **two lines**: cmd has to be *inside* the project folder before it can find
`build.bat` (a prompt reading `C:\Users\<you> ~ ➜` means you are in the fish shell, where no
`.bat` file runs — type `cmd` first).

```bat
cd /d "%USERPROFILE%\Desktop\Erawat\Agon"
build.bat
```

```bash
cd ~/Agon && bash build.sh     # Termux / Linux
```

That produces **`Agon-<version>.apk`**. Copy it to your phone, tap it, allow *"install
unknown apps"*, done. App name on the home screen: **Agon**.

`build.bat desktop` additionally builds both Windows installers (`Agon-Setup-64.exe`,
`Agon-Setup-32.exe`). On the phone the same command builds only the APK and says so, because
Electron cannot compile for Windows there.

---

## The commands

**One build script.** It works out where it is running — a Windows PC, or an Android phone
in Termux (where the toolchain goes into a proot Ubuntu container, because Google's `aapt2`
is x86-64 only) — then checks every prerequisite, **downloads what is missing**, and only
then builds.

```bat
:: Windows - TWO lines, not one. The prompt in the line below the first must show
:: C:\Users\<you>\Desktop\Erawat\Agon before the second line is typed.
cd /d "%USERPROFILE%\Desktop\Erawat\Agon"
build.bat
```

> That is the command to **hand to somebody else**. `build.bat` builds the APK and the
> installers and stops; it contacts nothing. `build.bat publish <version>` additionally
> uploads a GitHub release, and that is only for this repository — see
> [Who is allowed to publish](#who-is-allowed-to-publish).

> Typing the build in the wrong place is the single most common failure:
> `'build.bat' is not recognized as an internal or external command` means cmd is not
> inside the project folder (a prompt of `C:\Users\<you> ~ ➜` means you are in the fish
> shell, where no `.bat` file can run - type `cmd` first). `build.bat` changes into the
> project folder *after* it starts, which cannot help cmd find the file it was asked to run.

```bash
bash build.sh             # Termux / Linux / macOS
```

Both are thin launchers for `build.py`, so the phone and the PC cannot drift apart.

| Command | What it does |
| --- | --- |
| `build.bat` | Builds `Agon-<version>.apk` — signed with the shared key, installs as an update |
| `build.bat doctor` | Prints what is installed and what is missing. Builds nothing, installs nothing |
| `build.bat apk 1.4.58` | Builds that exact version (refused if it is lower than what is published) |
| `build.bat apk --release` | Signed release APK instead of a debug one |
| `build.bat apk --beta` | Next version on the beta channel |
| `build.bat desktop` | APK **and** both Windows installers (Windows only) |
| `build.bat publish` | Everything above, then uploads the release. **Refused in a fork or someone else's clone** |
| `build.bat sign` | **Fixes signing in one command**: finds a key you already have, adopts it, or makes one — then prints its fingerprint |
| `build.bat clean` | Deletes build output. Never the signing key, never the source |
| `publish.bat` | The publishing path on its own (version policy, release notes, upload) |
| `rename-repo.bat Agon` | Renames the GitHub repository and repoints everything at it |
| `python tools\set-repo.py Agon` | Same, without the git or `gh` steps |

`python3 build.py ...` is the same thing on every platform; `bash build.sh` is the same
launcher as `build.bat` for Termux.

### What it installs for you

| Missing | Windows | Termux |
| --- | --- | --- |
| Python 3 | `winget install Python.Python.3.12` (the launcher does this) | `pkg install python` |
| JDK 17–23 | `winget install EclipseAdoptium.Temurin.17.JDK` | inside the container, `apt-get` |
| Android SDK + platform 35 + build-tools 35 | unpacked from Google's command-line tools, licences accepted | the same, in the container |
| arm64 `aapt2` | not needed (x86-64 is native) | downloaded and put over the SDK's copy |
| Signing key | created once as `agon-build.jks` | adopted from `Download/`, else created |

Nothing is ever uninstalled or deleted, and the signing key is never regenerated: a new
key means Android refuses the update ("package conflicts with an existing package") and the
app has to be removed, which wipes the arena session.

### Building in Android Studio, or by hand

It is safe now, and it was not before. The package name is fixed
(`app.erawat.agon`, no `.debug` suffix), so the only thing that can make one build refuse
another is the **signing key** — and the project's own `agon-build.jks` used to be wired to
the debug build type behind a `-PAGON_DEBUG_KEY=1` flag that only `build.py` and
`publish.ps1` passed. A build started from Android Studio, or with a plain
`./gradlew assembleDebug`, was therefore signed with *that machine's* `~/.android/debug.keystore`:
it installed, and then every published APK was refused over it.

Both build types now sign with `agon-build.jks` whenever it exists — no flag, no opt-in — and
the release build prefers it too. So an APK from the PC, from the phone, from Android Studio
or from CI all update each other. The one thing that still breaks it is a **missing key**:
without `agon-build.jks` + `agon-build.properties` in the project root, Gradle falls back to
the local debug key. Copy both over (or `build.bat sign` on the PC, `bash build.sh sign` on
the phone) before building anywhere new.

### Where the signing key lives

| Host | File | Why there |
| --- | --- | --- |
| Windows | `agon-build.jks` + `agon-build.properties` in the repository root | Gradle reads `agon-build.properties`; the pair is git-ignored |
| Windows | `%USERPROFILE%\Downloads\AGON-KEY.md` | a copy, for moving to the phone. A key in the repo is one `git add -A` from being published |
| Termux | `~/Agon/agon-build.jks` + `agon-build.properties` | same files, same names, so either machine can sign the other's updates |
| Termux | `Download/agon-build.jks` | the copy the next build (or a container reinstall) picks up |
| Android install | `~/.android/debug.keystore` | **do not delete**: Android keeps this key forever; a build signed with it can never be replaced by one signed with anything else |

Android will not install an APK over an app whose key differs, and the only cure is
uninstalling, which clears the arena session. So the key is the one thing to back up:
copy `agon-build.jks` somewhere that is not this repository. `build.bat` prints its
SHA-256 fingerprint on every run — two machines holding the same key print the same line.

**If anything about signing goes wrong, run one command:**

```bat
build.bat sign
```

It looks for `agon-build.jks`, then `arena-release.jks`, then a key in `Downloads` /
`Desktop` / the parent folder, adopts the first one it can open, rewrites
`agon-build.properties` to match it (probing every password this project has used), and
prints the SHA-256. If there is no key at all it creates one and says plainly that the app
has to be installed once more. It never touches an existing key it can read.

A debug-signed APK is only `debuggable`, never `debug`-insecure: Gradle's debug keystore
still uses the modern RSA key with a real digest for minSdk 24, so the file is signed with
a full v1/v2 signature. There is no "unsigned build" to hand anyone.

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
| Saved pages | Each tab's last good copy is kept on the device, so a page you have already opened still opens with no usable connection |
| Offline screen | Friendly retry screen instead of WebView's error page — and only when there is no saved copy to show |
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
| Caching | `LOAD_DEFAULT` while there is a connection — the network answers, the cache answers only what is still fresh. `LOAD_CACHE_ELSE_NETWORK` painted stale Arena documents and made pages look permanently stuck, so it is used *only* when the phone has no usable connection |
| Stalled page | a load that has not finished after 9 s raises one small bar: *This page stopped loading — Load again* |
| Loading ring | the nav item whose page is still loading shows a small spinner in place of its icon |
| Scroll | scrolling only scrolls; nothing about scrolling triggers a reload |
| Pull-to-refresh | **removed** — scrolling never reloads the page; use *More → Reload* when you want a fresh load |
| Renderer crash | the dead WebView is dropped and rebuilt on the next visit instead of showing a blank page |
| Tab strip arrows | the two arrows look identical (never dimmed) and **go round**: from the last tab `>` lands on the first, from the first `<` lands on the last. No "this is the last tab" message anywhere |
| Closing a tab | the cross closes **exactly the tab it is drawn on** and says nothing. The x on the default (chat) chip closes that tab — its neighbour takes the screen and the chat tab starts empty — instead of closing whichever tab was opened last; every other chip already closed only itself. With a single tab open the strip draws no cross at all |
| A new tab | is a **browser tab**, not a new chat: it opens on arena's front page and stays wherever the user drives it. Nothing re-routes it behind their back |
| Destinations | Ask / Search open **in the tab on screen**, not in the chat tab — with a conversation tab in front, tapping Search loads it in that tab and leaves the other tab alone |
| Chat history | two taps on the **Chat** tab open the conversation list. Arena only draws its own history button inside a chat, so the front page had no route to it, and the app's sheet reads the page actually in front rather than always the chat tab. There is no *More → Chats* row: it was a second door to a list two taps already open |
| A press you did not make | the app used to make three of its own: *new chat* ended its selector list with a link to the front page, so it clicked the site logo or the sidebar's Home row; the unstick repair pressed a fixed point on the left edge without looking, which on a phone is often a row or a toggle; and closing the chat tab started a new chat 900 ms later, into a half-loaded page. All three are gone — *new chat* only ever presses a button labelled as one, the repair picks the first genuinely empty point (and presses nothing if there is none), and closing only loads |

### Preview links and dev servers on a phone

A dev server's preview link usually says `http://localhost:3000`. On a computer that is the server; on a phone it is **the phone**, where nothing is listening — the page stays blank and the tap looks dead. The app now says so instead of loading an empty frame, listing the addresses that can work:

* this phone's wifi address (which is the number the computer's dev server has to be reached at — so the server must listen on `0.0.0.0`, not only on `localhost`);
* this phone's other network addresses;
* `127.0.0.1`, for a server actually running on the phone (Termux).

Picking one opens it, with the port and path of the link you tapped (`:3000/report` is kept). Two things were also blocking previews that *were* reachable: cleartext `http` is refused everywhere except `127.0.0.1`, `localhost` and `10.0.2.2` (the network security config), and an https arena page embedding an http preview was a mixed-content block — tabs and popup windows now use `MIXED_CONTENT_COMPATIBILITY_MODE`, while the sign-in sheet keeps the strict setting.

The link itself has to be reachable: a server on your laptop is only visible to the phone if both are on the same wifi and the server is bound to the LAN address, or if the preview is a public URL (a tunnel), which works as it is.

### Copying text out of the app

Two ways, both of them the app's own:

| Route | How |
| --- | --- |
| Hold to copy | hold a reply or a command for about 0.7 s and the app copies **that paragraph, not the page**: what you already had selected if anything, otherwise the smallest text block under your finger. It copies when your **finger lifts**, not on the timer, so a hold-and-drag extends a selection first and a press you thought better of copies nothing |
| One press copies nothing | a tap, a scroll, a shaky press that wanders and a finger left resting on the screen all copy nothing — and a press that lands on a **button, link, sidebar row, the nav or the composer** copies nothing either. After a copy the press is swallowed, so the control underneath is not also pressed |
| A quick drag | still never selects anything — that is what "App-style text" means now, and the setting's description says so |

There is deliberately no *More → Copy selected text* row. It asked you to select text first,
in an app whose whole point is that a drag does not select, and the hold already copies the
block you pressed. One way in that works beats two where one does not.

The old rule switched off `-webkit-touch-callout` on everything and cancelled every
selection, which is why a command in a code block could not be copied at all. The long-press
callout is no longer suppressed, and the guard now refuses only a selection that a **drag**
starts (under ~320 ms of press).

Letting a long press *through* was not enough — whether a hold starts a selection at all is
the engine's decision, and the menu that follows is the part that kept not appearing. So the
app does the whole job: after ~700 ms of a genuinely still finger it hit-tests the point,
walks out to the smallest block that holds that text, and copies it — when the finger lifts.
Two routes are tried in order — the page's clipboard API, and a native `ClipData` write
through the bridge — and the block is only left selected for the page's own menu if both are
refused, in which case a toast says *Selected – tap the selection to copy it* rather than
leaving the press silent.

### What a hold actually does

| One still press | Nothing is copied while the finger is down. The copy happens on **lift** |
| After ~700 ms, then lift | One copy: the text you had selected, else the smallest block under the finger (`AgonCopy.under`), written natively through the bridge |
| Travel more than ~10 px before or after the hold | No copy. That is a scroll, or you extended a selection by hand |
| A press that wanders (~4 px between frames) and comes back | No copy. It was not still, whatever its start and end points say |
| Finger still down after ~2.5 s | No copy. It is resting on the page, not asking for anything |
| Press on a button, link, sidebar row, nav or the composer | Never. The page reports no text there, and the press is not also fired at the control |
| Press in the composer | Never. That is where you type |
| Three taps, or *Settings → App-style text* | Turns selection on and off; the unlock survives page re-injection |

The page has no copy timer of its own. There were two holds for one press at one point —
the page's (fires on a timer, copies the whole block) and the WebView's (fires on the lift) —
which is why a single press could copy twice and always looked like it grabbed everything.

### Seeing where a tap landed

*Settings → For developers → Show what a tap lands on* (off by default) makes every press toast
the element under the finger: `<button> Send the message   @50,220  80×40   (felt: <span>)`.
The name is what the app would call the control, the box is where the page put it, and
*"felt"* is the element that actually received the press — when the two differ, something is
drawn over the control, and that overlay is the bug.

### Three taps to unlock selection

A phone has no Ctrl key, so the same switch is reachable from the page: **three quick taps in
the same place** toggle *App-style text*. Three more lock it again. Taps in the composer, a
textarea or an editable box never count, and the setting is written either way — so the
Settings switch and the next launch agree with what the gesture did.

### Keyboard shortcuts

| Keys | What it does |
| --- | --- |
| Ctrl/Cmd + Tab | next tab (wraps, like `>` in the strip) |
| Ctrl/Cmd + Shift + Tab | previous tab |
| Ctrl/Cmd + W | close the tab on screen — never the app, never a message |
| Ctrl/Cmd + N | new chat tab |

Arena's page listens for some of these itself, so the app stops the key first and does the
thing in the strip, where the tabs are.

The composer is the other half of the same rule: it is exempt from `user-select: none`
entirely — `input`, `textarea`, `form` and everything inside it, `[role="textbox"]`,
`[contenteditable]` — because arena's composer is usually a rich-text box rather than a
`<textarea>`, and a document-wide rule took the caret away from it. That was "I can't move
the line of entering in chat": the tap focused the box and no caret could be placed.

### Panels that do not fit the phone

Arena's own menus and side panels are laid out for a desktop-sized window. On a phone that
shows up as a list whose left edge is off screen and whose right side is cropped — the model
picker in a device screenshot looked like it started halfway through its own text.

`JS_POPUP_FIT` measures instead of guessing: anything `fixed`/`absolute` that reaches past
the window gets a box no bigger than the window (`max-width: 100vw - 12px`), is pinned inside
it if it was shifted sideways, and is allowed to scroll inside itself — and gets its own
inline styles back the moment it fits again. It never touches the page shell (anything that
contains `main`, a `form`, a `textarea` or an editable area is skipped), never touches
ordinary content, and remembers nothing across a page reload, so a page cannot be left
permanently altered.

### Saved pages, and why a bad connection is not a dead app

Two device reports came from the same place — a slow connection: *"add also every page of
arena.ai is saved inside the app, it should update itself as I use it"* and *"the sign-in
icon only appears after a refresh, sometimes the app doesn't load"*.

What it does, exactly:

| Behaviour | Detail |
| --- | --- |
| Cache mode | chosen per connection, and re-chosen on every page finish: online the network wins, offline the cache does. Never pinned once at startup |
| A saved copy per tab | after a page finishes loading, the rendered page is written with `saveWebArchive()` to one file per URL in the app's private cache (`cache/pages/p-<hash>.mht`) |
| Kept current | re-saved at most once every 3 minutes per URL, so using the app is what updates it — the copy is always roughly what you last saw |
| Bounded | at most 8 pages, 12 MB each; the oldest is dropped first, and a page that fails to save is simply not kept |
| Opened offline | with no connection a tab starts from its saved copy instead of a blank page, and the normal load runs behind it |
| A load that fails | a main-frame error restores the saved copy instead of showing the error screen |
| Never in the way | the browser chrome always shows the real `arena.ai` URL, so Back, the strip, the title and Retry all point at the real page, not at a local file |
| Connection returns | the visible tab is reloaded by itself when the network comes back (a `NetworkCallback`), so a page that never loaded recovers without a refresh |
| Private | the copies live only in the app's cache dir, and are deleted on sign-out, on "clear data", and when *Developer Settings → Saved pages* is switched off |

This is **not** full offline Arena: the site is a live single-page app, and a chat that has
never been opened is not on the phone. What is on the phone is the last thing each tab
showed, which is what makes the app open instantly and survive a dropped connection.

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

**Hide Arena's sidebar** goes both ways. The rule hides the panel by writing an inline
`display: none` and tagging the element it touched; switching the switch off runs one pass
over those tags and takes the style away again, on every warm tab, without a reload and
without losing the conversation on screen. (Before this, the switch could only ever hide:
nothing removed the inline style, so the panel stayed away with the switch off.)

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

## In-app updates from this repository's releases

The app checks this repo's releases, marks the **Update** button and fires a
notification with its own chime — and it can never install anything silently.

**How it flows**

1. On launch, on resume and every 6 hours the app queries
   `api.github.com/repos/Erawat4l/Agon/releases/latest` (or all releases when
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

**No token, no account, nothing to configure.** The releases are public, so the app asks
`https://api.github.com/repos/Erawat4l/Agon/releases/latest` anonymously, takes the
`browser_download_url` of the `.apk` in that release, downloads it and hands it to Android's
installer. The token support that existed while the repository was private is gone: no token
field, no first-run prompt, no stored credential.

Two things are still checked, because they protect the install rather than the account:

- the asset URL must point at GitHub (`github.com`, `api.github.com`,
  `objects.githubusercontent.com`) - a release cannot redirect the installer elsewhere;
- the download is verified against the size and, where the server sends one, the checksum
  before the installer is opened.

If you ever make the repository private again, updates will stop working; a token would have
to come back with them.

**One version number, one formula.** `versionCode` is always
`major * 1_000_000 + minor * 1_000 + patch`, derived from the version name by Gradle, by CI
and by the app when it reads a release tag. Earlier builds mixed that with a separate
`1002xxx` base, which made an installed app think its own release was newer than itself and
offer the same update forever. `1.4.26` is the last build with the broken number; `1.4.27`
onwards is consistent.

**Publishing a release (this is what makes the update appear)**

### After every change

```bat
cd /d "%USERPROFILE%\Desktop\Erawat\Agon"
git pull --rebase --autostash
build.bat publish
```

`build.bat publish` builds the APK, builds the Windows installers, and uploads both to a
release whose version it takes from the highest number already published — so a release is
never numbered below what the phones already have. `publish.bat` does the same without the
Windows installers, which is the faster loop.

`version.txt` in the repo root holds the last published version. It is **git-ignored
and generated locally** — committing it made every later `git pull` fail with
*"Your local changes to version.txt would be overwritten by merge"* — so the bump
counts locally even before the first tag exists, without ever conflicting.

A **build** never reads it any more. It used to, and that is what made a plain `build.bat`
stop dead on a machine that had published a while ago: `version.txt` still named the old
version, so the run said *"1.4.48 is lower than 1.4.59, which is already published"* with
nothing typed on the command line. With no version given, `build.py` now takes the next free
number (`needs.next_version`); a number typed *on purpose* that is below what is published is
a warning — the APK still installs over the app by hand — not a refusal.

Because the two can disagree (a fresh clone, or a stale `version.txt` left behind by an
older checkout), `publish.bat` takes the **higher** of the two, and `publish.ps1` refuses
to publish a version lower than the highest release already on GitHub. That refusal
matters: `releases/latest` only ever offers a build with a higher `versionCode`, so a
release numbered *below* an existing one is invisible for ever and every phone keeps
saying *"you are up to date"*. The app no longer trusts that endpoint blindly either — it
compares the tags of the recent releases and installs the highest one that is actually
newer. Installed apps pick the new release up on
their next check (`Update → Check for updates`).

## Who is allowed to publish

Building the app and publishing a release are two different jobs, and only one of them is
anybody's to do.

| You want to | Type | What happens |
| --- | --- | --- |
| Build the app, for your own phone or PC | `build.bat` | APK (+ installers with `build.bat desktop`). Nothing is uploaded, no account or token is needed |
| Publish an update for everyone | `build.bat publish <version>` | Only works in a checkout that points at **Erawat4l/Agon** |

A clone or fork belongs to whoever cloned it, so `build.bat publish`, `publish.bat` and
`tools/publish.ps1` all check `git config --get remote.origin.url` before they upload
anything. If that remote is not `Erawat4l/Agon`, they say so, build nothing, upload nothing
and exit with an error — the upload is refused *before* the first Gradle step, so it costs
seconds rather than ten minutes.

The reason is not territorial. A release is what the in-app updater reads: any release in a
repository, at a version number above the installed one, is offered to the phone as the
next official build. Someone running `build.bat publish 1.4.60` on their own fork would
create exactly that under their own name, and then get a prompt to install it.

Publishing to your own repository on purpose:

```bat
build.bat publish 1.4.60 --publish-anyway
publish.bat 1.4.60 stable anyway
```

A folder with **no** git remote (a zip, a copy without `.git`) is treated as the real
project — there is nothing to publish to, so there is nothing to guard.

### A. From your PC: `publish.bat` (works today)

```bat
publish.bat
```

That builds the APK with an auto-bumped version (`1.1.0` → `1.1.1`), creates the GitHub
release **v1.1.1**, uploads `Agon-1.1.1.apk`, and leaves a copy in this folder.
Every installed app then sees it: tap **Update → Check for updates**, download, tap
Install, approve Android's prompt. The release page link opens **inside the app** —
the app never sends you to Chrome.

`publish.bat` needs a token **only for publishing** (write access to your own repo), not for
updating:

1. <https://github.com/settings/personal-access-tokens/new>
2. Repository access → *Only select repositories* → `Agon`
3. Permissions → **Contents: Read and write**
4. Generate, then `publish.bat --token github_pat_…`

The token is optional if the GitHub CLI is already signed in on that machine: with no
`gh-token.txt`, no `GH_TOKEN` and no `GITHUB_TOKEN` set, `publish.bat` reuses the
credential `gh auth login` stored, so there is only ever one copy to keep alive.

Use **Read-only** Contents for the token you paste into the app; the write token is only
for publishing from your PC.

### B. GitHub Actions: automatic on every push to `main`

`.github/workflows/build.yml` builds *and publishes* **one release with both platforms** on
every push to `main`, under a single version number:

| Asset in the release | Platform |
| --- | --- |
| `Agon-<version>.apk` | Android - one file for 32- **and** 64-bit phones |
| `Agon-Setup-64.exe` | Windows installer, 64-bit |
| `Agon-Setup-32.exe` | Windows installer, 32-bit |
| `latest.yml` | what the Windows auto-updater verifies |

Two `.exe` files, not one: Electron bundles a whole Chromium, compiled separately for
each architecture, so a single installer cannot run on both. The APK is genuinely one
file because the app has no native code — Android's own runtime handles either ABI.

`latest.yml` describes `Agon-Setup-64.exe`, because that is the only name
electron-updater reads on Windows and one YAML file cannot describe two binaries. 64-bit
installs therefore self-update; a 32-bit install is pointed at `Agon-Setup-32.exe` when
it checks. `desktop/scripts/make-release-assets.js` produces all three files, and rewrites
`latest.yml` after both architectures are built — electron-builder writes `latest.yml`
per arch, so the second build used to overwrite the first and the release ended up
advertising the 32-bit installer to 64-bit machines.

The APK job decides the version and publishes the release; the Windows job takes that same
tag from it, so the two never drift apart. No tagging by hand — both updaters pick the
release up on their next check.

Work branches do **not** publish: they build and upload artifacts for inspection only, so a
side branch can never become "the latest release" for people who already installed the app.

The version follows `app/build.gradle`: patch = base patch + commits since the last `v*` tag
(so `1.4.22` / `1002033` becomes `1.4.23` / `1002034`), keeping the `1002xxx` convention the
in-app updater compares against.

> This only works while the repository is **public** — GitHub Actions has no free minutes
> for private repos on a free plan, and a run then fails in ~2 seconds with no steps and
> `runner_id: 0`. If that happens, publish from your PC with `publish.bat`.

**Rules of thumb**

- Version code = `major*1,000,000 + minor*1,000 + patch`, derived from the version name in
  `app/build.gradle` and from the release tag - the same formula in all three places, so the
  number an app computes for a release is the number that release was built with.
- The release must contain an `.apk` asset; the app picks the first one.
- Debug and release builds are separate installs (`ai.arena.webapp.debug` vs
  `ai.arena.webapp`) — each updates itself from the same release.
- Android installs updates only from the same signing key, so keep `arena-release.jks`
  once you ship release builds.
- Release notes come from the release body, so use
  `gh release create v1.0.1 Agon-1.0.1.apk --notes "What changed…"` if you publish by hand.

**Update settings inside the sheet:** release channel (Stable = newest published
release, Beta = includes pre-releases) and auto-check on/off. Nothing else - there is no
credential to enter.

---

## Building the APK on GitHub instead (no local setup)

Every push to `main` runs `.github/workflows/build.yml`:
**Actions → "Build Agon (APK + Windows)" → latest run → Artifacts** gives you
`Agon-apk` and `Agon-windows` separately, and the same run attaches everything to a
GitHub Release. (If the run fails instantly with no steps, Actions is not permitted for
the repository: *Settings → Actions → General → Allow all actions* — this happens on
private repos without paid minutes.)

---

## Project layout

```
build.py                      ONE build script: detects Windows/Termux, installs what is
                              missing, builds, and optionally publishes
build.bat / build.sh          thin launchers for build.py (cmd / Termux)
tools/needs.py                the prerequisite check and the version policy it uses
app/build.gradle              module config (app id, version, signing)
app/src/main/AndroidManifest.xml
app/src/main/java/ai/arena/webapp/
    MainActivity.java         WebView shell: navigation, uploads, downloads, permissions
    CrashLog.java             last-crash recorder shown on screen
app/src/main/res/             icons (adaptive + legacy), theme, colors, strings
.github/workflows/build.yml
```

Almost dependency-free: only `androidx.core` (for `FileProvider`, which the package
installer requires) — everything else is platform APIs, so the APK stays small.

---

## Platforms: Android and Windows (iOS needs a Mac)

| Platform | Where | State |
| --- | --- | --- |
| **Android** | `app/` (this repository's main project) | builds here; one APK for 32- and 64-bit |
| **Windows** | `desktop/` | Electron 26 (the last line with 32-bit builds), installers for x64 **and** ia32, self-updates from this repo's releases |
| **iOS** | — | not buildable from this repository |
| macOS / Linux | — | out of scope |

The Windows client is a one-window shell with the same rules as the phone: no Node in the
page, only arena.ai may load in the window, provider popups stay in the same session,
downloads flattened to one path segment, and updates verified against the release's
`latest.yml`. `publish.bat` builds it for the same tag as the APK, so both carry one
version number. See `desktop/README.md`.

**Sign-in on Windows and Google.** Google refuses its own sign-in inside an embedded
browser, which an Electron window is. The email-link route in the Agon menu is the one that
works. Because the "Continue with Google" button on arena's panel can therefore only fail,
the Windows client hides it inside that panel only (`Agon -> Hide "Continue with Google"`,
on by default, switchable off). It is hidden with CSS, never removed, and only an element
matching both the panel's heading and an email field is touched — if arena changes its
markup the worst case is that the button reappears, never that something unrelated
disappears. If arena links an account to Google, the app says so instead of starting an
email flow that cannot work.

**iOS honestly:** there is no iOS app in here, and adding a fake Xcode project would be
worse than nothing. An iOS client is a separate Swift project around `WKWebView` (same
host allow-list, same "sign in on Arena's own page" rule), and Apple requires a Mac with
Xcode and a paid developer account to sign and install it — none of which this repository
can provide. The Android and Windows sources are the reference for what that shell has to
do.

---

## Which devices it runs on (32-bit and 64-bit)

**One APK, every Android architecture.** The app ships no native library of its own (the
only native code in it comes from AndroidX, which is plain Java), so the APK is
architecture-independent bytecode:

| Device | Works? | Why |
| --- | --- | --- |
| Android 7.0 (API 24) and newer, **32-bit ARM** (armeabi-v7a) | yes | no native code to match |
| Android 7.0+, **64-bit ARM** (arm64-v8a) | yes | same |
| x86 / x86_64 tablets and emulators (MSI App Player, BlueStacks) | yes | same |
| Android 6 and older | no | `minSdk 24` |

`app/build.gradle` declares `abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'` and
disables per-ABI splits, so a future dependency that *does* bring native code cannot
quietly drop one side, and there is only ever one APK to publish (so the in-app updater
cannot pick the wrong one). `build.bat` and `publish.bat` then run
`tools/check-apk-abis.py` against the APK that was actually produced: it reads every
`lib/<abi>/*.so` and its ELF header and prints exactly which architectures the file can run
on, and fails the publish if 32-bit is missing.

Why the old build could not be checked on a 32-bit device and what was verified instead:
the code has no `long`-to-`int` assumption that only holds on 64-bit, every API newer than
minSdk is behind a `Build.VERSION.SDK_INT` check (notifications O, media-store Q, POST_
NOTIFICATIONS 33, immutable PendingIntents M), and `getContentLengthLong()` is API 24 —
which is exactly why `minSdk` is 24, not lower. On a 32-bit build the WebView is Chromium
too, so the page renders identically.

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
| Sign-in trace (URLs, query values stripped to `name=set`) | `agon_signin_trace` prefs | no |
| Crash trace | `files/last_crash.txt` | no |
| Saved page copies (last page shown per tab) | `cache/pages/*.mht` — cleared on sign-out, clear-data, or the *Saved pages* switch | no |

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
- Every link a page can hand the app (a chat-history row, an app-link callback, the browser
  route, the exported activity's intent) is re-checked with one host test: https and
  `arena.ai` / `lmarena.ai`, compared per host segment.

`python3 tools/check-secrets.py` runs in `build.bat` and `publish.bat` pre-flight. It fails
the build when a tracked file contains a token-shaped string, a keystore password, a
private key, a `C:\\Users\\<name>` path or a personal e-mail address, so publishing cannot
quietly ship one. `git ls-files` also confirms none of `local.properties`, `gh-token.txt`,
`*.jks` / `*.keystore` or `version.txt` is tracked.

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
| Update check says *no release found* | The repository has no published release, or it was renamed: check `repos/Erawat4l/Agon/releases/latest` in a browser |
| Update offered forever, same version | You are on `1.4.26` or older, whose `versionCode` disagreed with its tag. Install `1.4.27`+ once by hand; the loop cannot come back |
| Upload fails with *"underlying connection was closed … on a send"* | The APK uploaded over a dropped TLS connection, not a rejected token. `publish.bat` retries 4× and leaves the release a **draft**, so nothing half-published. Retry without rebuilding: `powershell -NoProfile -ExecutionPolicy Bypass -File tools\upload-apk.ps1 <version>` |
| `publish.bat` refuses with *"would publish 1.0.2, but v1.4.29 is already published"* | A stale `version.txt` (or an old local tag). Delete `version.txt` and run `publish.bat` again — it takes the higher of the two |
| `publish.bat` says 401/403 | The token is expired, revoked or scoped to another repo. Run `gh auth login`, delete the stale `gh-token.txt`, then `publish.bat` — it reuses the CLI credential |
| Actions run fails instantly, 0 steps | Actions is blocked for the repo — use `publish.bat` |
| *App not installed* when updating | Old APK was signed with a different key — uninstall once, then install |
| No update notification | Allow notifications for Arena (Android 13+ asks on first launch) |
| `build.bat install` can't see the phone | Enable Developer options → USB debugging, accept the RSA prompt |

`keystore.properties`, `*.jks` and `local.properties` are git-ignored. **Back up
`arena-release.jks`** — without it you cannot ship updates with the same signature.

---

This is an unofficial WebView wrapper around [arena.ai](https://arena.ai). It is not
affiliated with Arena; all content and accounts belong to Arena.
