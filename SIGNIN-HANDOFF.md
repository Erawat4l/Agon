# Sign-in hand-off: what is broken, what to ask Claude

Paste this whole file into Claude. It is the complete state of the problem — no other
context is needed. The last section is the question to answer.

---

## 1. The product

**Agon** — an Android WebView wrapper for `arena.ai` (an AI model leaderboard / chat site).

- Package: `app.erawat.agon`
- Version in question: **1.4.7** (`1002018`)
- Device: **Samsung SM-S908E, Android 9 (SDK 28)**
- WebView: `Mozilla/5.0 (Linux; Android 9; SM-S908E Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.70 Safari/537.36`
- `setSupportMultipleWindows(true)`, `setJavaScriptCanOpenWindowsAutomatically(true)` — on.
- Cookies: `CookieManager` with `setAcceptThirdPartyCookies(webView, true)`, `setAcceptCookie(true)`.
- Third-party cookies accepted, DOM storage on, database on.
- The app is a personal project, distributed outside the Play Store via GitHub releases.

### The requirement

Sign in to Arena **inside the app**, with no external browser, as close to one tap as
possible. The user's words: *"i want fast as that direct ui click, login through
selecting account"* — i.e. tap "Continue with Google" → pick the already-signed-in Google
account from a list → done.

Refused outright (and correctly, and permanently):

- Any local proxy/server that captures email or password, or replays Google credentials.
- Any browser-automation shim that steals credentials.
- GitHub as a sign-in provider (Arena does not offer it; it is only used for app updates).
- Signing in by having the user type email + password every time.

A browser *is* acceptable as an explicitly labelled fallback, but it is a fallback.

---

## 2. The current sign-in implementation (as of 1.4.7)

Files, all under `app/src/main/java/ai/arena/webapp/`:

| File | Role |
| --- | --- |
| `SignInSheet.java` | The whole in-app flow: bottom sheet → full-screen `WebView` layer on `https://arena.ai/`, a `WebViewClient` policy, and the "is it signed in" verdict. |
| `SignInDriver.java` | Injected JavaScript that taps Arena's own "Log in" control, then "Continue with Google". Also `SIGNED_OUT_JS`, which decides signed-in vs signed-out from the rendered page. |
| `AppChrome.java` | UA cleaning (`strip "; wv"`, `"Version/4.0 "`) and a `WebChromeClient.Base` that hosts `window.open` popups inside the app. |
| `MainActivity.java` | Routes `arena.ai` VIEW intents (with `ticket`/`code`/`token`) into `SignInSheet.takeBrowserHandoff`. |

### Flow as built

1. User taps **Continue with Google**.
2. `openProvider()` builds a full-screen layer with one `WebView` and loads
   `https://arena.ai/`.
3. `SignInDriver.OPEN_DIALOG_JS` runs repeatedly (up to 10 rounds, ~900 ms apart) looking
   for Arena's log-in control — inside shadow roots too — then presses it.
4. `CHOOSE_PROVIDER_JS` presses "Continue with Google" inside Arena's dialog.
5. Arena navigates to `accounts.google.com`, which is where the screenshot and the
   diagnostics below come from.
6. Success is decided by `SIGNED_OUT_JS` on the rendered page (HttpOnly cookies cannot be
   read through `document.cookie`; `CookieManager.getCookie()` also misses them in
   practice — see the evidence below).

### What the diagnostics already prove

Last run on the device (1.4.7):

```
cookie jar: 3226 chars
session cookie: absent
cookie names: *ga, arena-auth-prod-v1, user*country_code, arena_build_bucket,
              *vcrr*ec68a2456e920849, __cf_bm, sidebar_state, arena_visit_id,
              arena-auth-prod-v1-code-verifier, *dd*s, ph_phc_..., *ga*L5C4D55WJJ,
              *ga*DB32ZN1WHB
page url: https://accounts.google.com/v3/signin/identifier?...
controls: 17, visible: 13, login: 0, google: 0, github: 0, textLogin: 1,
shadowRoots: 0, title: "Sign in - Google Accounts"
```

Read that carefully:

- **`arena-auth-prod-v1-code-verifier` is present.** That is Arena's OAuth code
  verifier, written when Arena started the PKCE/authorization-code flow. So Arena's side of
  the round trip is working and in place.
- **`arena-auth-prod-v1` is present but the session is reported absent.** This is the
  cookie that looks like the session; it is presumably HttpOnly, or only the anon shape of
  it exists before the callback completes.
- **The redirect chain in the URL is the important artifact:**
  - `redirect_uri=https://auth.arena.ai/auth/v1/callback` — Google returns to
    **`auth.arena.ai`**, NOT `arena.ai`.
  - `client_id=899073161779-...apps.googleusercontent.com`
  - `scope=email profile`, `response_type=code`, `access_type=offline`, `prompt=consent`
  - `app_domain=https://auth.arena.ai`
  - The nested `opparams` shows Arena's own next hop:
    `https://arena.ai/nextjs-api/callback/google?action=sign-in&custom_state=...&...`
    with `custom_state` JSON containing `arenaUserId`, `anonSupabaseUserId`,
    `shouldLinkHistory`, `registeredCountryCode: "IN"`, and
    `returnTo: "/?drafthandoff=..."`.
- The page currently on screen is Google's **identifier** step (`/signin/identifier`),
  i.e. the "type your email" form — **not** the "Choose an account" picker.

### What the user observes

- With 1.4.6, after the email form the login *does* complete ("the login happens after
  email and pass"), but slowly, and the user has to type credentials each time.
- The user wants the account-picker path instead, and considers typing credentials a
  failure of the product.
- A previous build showed a "Choose an account" screen instead of signing in; the exact
  cause of *that* screen was never established — it is not the current state.

---

## 3. What has been tried (do not re-try blindly)

| Attempt | Result |
| --- | --- |
| UA "clean Chrome" string (strip `; wv`) | Google still serves the identifier form; does not trigger the account picker. |
| `WebView.preconnect()` | Does not exist — compile error. Removed. |
| `setSupportMultipleWindows(false)` | Killed popups; "Continue with Google" appeared to do nothing. Reverted. |
| Invented endpoints (`/login`, `/signin`, `/api/auth/*`) | Do not exist. |
| Injecting `location.reload()/replace()` | Breaks Arena's SPA state. Abandoned. |
| Native `AccountManager` / account picker (`AccountPicker.java`, `GET_ACCOUNTS`) | Never produced a usable token for a WebView OAuth flow. Removed along with the permission. |
| Searching Arena's page while on `accounts.google.com` for Arena's dialog | Wasted 10 rounds, then "fell back" to `/leaderboard`, destroying a live sign-in. Fixed in 1.4.6. |
| Wiping session cookies on each attempt (`removeSessionCookies(null)`) | This actively caused the repeated email prompt. **Removed in 1.4.7** — a Google session for this app now survives, in principle enabling the picker. Untested on device yet. |
| `androidx.browser` Custom Tabs as the primary route | Rejected: leaves the session in Chrome, requires the `arena.ai/auth/handoff?ticket=` return, and the app must adopt it. Kept only as a labelled fallback. |

Known constraint from research: Google blocks OAuth in embedded WebViews
(`403 disallowed_useragent`) for *some* client configurations, and Google's OAuth policy
for Android WebViews is "use Custom Tabs or the native SDK". `help.arena.ai` and Arena's
own flow do not offer a native Android SDK for third-party apps.

---

## 4. The questions for Claude

Answer these concretely, with code where code is needed. Assume no other context.

1. **Why is Google serving `/signin/identifier` instead of "Choose an account" inside this
   WebView, when the device has a Google account signed in to Android?** Is that a
   cookie-partition issue (the WebView's cookie jar has no Google session), a UA/`wv`
   signal, a `client_id`/`prompt=consent` effect, or something else? What is the smallest,
   legitimate change that makes the picker appear reliably?

2. **Given `redirect_uri=https://auth.arena.ai/auth/v1/callback`** (Arena's own backend),
   is there any supported way to complete Arena sign-in without Google's HTML form — e.g.
   Arena's own `/nextjs-api/callback/google` hop, a magic-link/email flow, or an Arena
   token exchange — that an app like this may legitimately use? If Arena has no public
   API for it, say so plainly.

3. **Is the "one tap, pick your account" experience actually achievable in an embedded
   `WebView` on Android 9 with a stock Samsung WebView?** If the honest answer is "no, not
   inside an embedded WebView", say that and describe the fastest *legitimate* path that
   still ends with the session inside the app:

   - Custom Tabs + `arena.ai` deep-link handoff (`AppAuth`-style), or
   - Google Identity Services / Credential Manager to get an ID token, then exchange it
     with Arena — **only if Arena exposes such an exchange**, or
   - something else.

   For each: what exactly must change in this app, and what does the user still have to
   tap?

4. **Does `setAcceptThirdPartyCookies` + a persistent, non-wiped cookie jar actually
   produce Google's account picker in a WebView**, in your experience? If it needs
   specific flags (e.g. `prompt=none`, `login_hint`, `authuser`, `CookieManager.flush()`,
   waiting for `onPageCommitVisible`), name them.

5. **If the truthful answer is "embedded WebView sign-in cannot be one tap", what should
   the app's UI say and do** so the flow is one clear action rather than a slow form —
   e.g. explicit "Continue in browser, comes back signed in" as the primary button, with
   the ticket handoff already implemented in `SignInSheet.takeBrowserHandoff`?

---

## 5. Hard constraints on any answer

- **Must compile** with JDK 17 + the Android SDK the user has (no hypothetical APIs).
  `WebView.preconnect()` and bare `this::method` inside an anonymous class have both
  already broken builds; the repo now has `tools/check-resources.py` for exactly that.
- **No credential capture, no local proxy, no password replay.** Ever.
- **No GitHub sign-in.** Arena does not offer it.
- **No new third-party SDKs** unless they are the only path — the app has no Play Services
  dependency today and the user builds offline-ish with Gradle.
- Prefer changes that are a handful of methods in `SignInSheet` / `SignInDriver`, not a
  rewrite.
