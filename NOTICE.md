AGON — unofficial Android client for arena.ai
=============================================

Agon is an independent, unofficial app that displays arena.ai in an Android window. It is
not affiliated with, endorsed by, sponsored by, or supported by Arena, LMArena, or any
company or team behind arena.ai. "Arena" and "LMArena" and their logos are the property of
their respective owners and are used here only to describe what the app displays.

The app does not host, copy, mirror or redistribute Arena's content, code or data. It shows
the live site through Android's WebView, with the user's own account and the user's own
session cookie, exactly as a browser would.

WHAT THIS REPOSITORY CONTAINS
-----------------------------
- Source code for the app (Apache-2.0, see LICENSE).
- The app's own icon (artwork/agon-mark.jpg and the launcher sizes cut from it). It is an
  original mark, not Arena's logo.
- No Arena source code, no Arena assets, no Arena credentials, no scraped data.

WHAT IS NOT IN THIS REPOSITORY
------------------------------
- No API keys, passwords or GitHub tokens. The token the in-app updater can use is entered
  on the device (or supplied at build time) and is git-ignored: keystore.properties,
  gh-token.txt, *.jks and *.keystore are all excluded by .gitignore.
- No personal data of the author: no real name, address, e-mail or build-machine path.

THIRD-PARTY SERVICES
--------------------
- arena.ai — displayed, not modified. Its own terms and privacy policy apply to the user's
  use of the site.
- api.github.com — used only to check this repository's releases and to download the APK
  that the user then chooses to install.
- accounts.google.com — reached only if the user picks that sign-in route; the app stores no
  Google credentials.

NO WARRANTY
-----------
The software is provided "as is", without warranty of any kind. It is not a security
product, and it is not a way to get around any service's policies: where a provider refuses
an in-app browser, the app says so instead of working around it.

If you are the owner of a mark or service mentioned here and want something changed or
removed, open an issue on this repository.
