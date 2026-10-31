#!/usr/bin/env python3
r"""Cheap pre-flight checks for Android resource XML.

Catches the mistakes aapt2 reports late in the build:
  - unescaped apostrophes / quotes inside <string>
  - malformed escape sequences (\z and friends)
  - format placeholders that do not match their arguments

Run: python3 tools/check-resources.py
"""
import glob
import re
import sys

VALID_ESCAPES = set("nt'\"@?\\")
problems = []


def check_string(path, name, body):
    if re.search(r"(?<!\\)'", body):
        problems.append(f"{path}: string/{name}: unescaped apostrophe (use \\')")
    for m in re.finditer(r"\\(.)", body):
        ch = m.group(1)
        if ch not in VALID_ESCAPES and not ch.startswith("u"):
            problems.append(f"{path}: string/{name}: invalid escape \\{ch}")
    indexes = sorted({int(x) for x in re.findall(r"%(\d+)\$[sdf]", body)})
    if indexes and indexes != list(range(1, max(indexes) + 1)):
        problems.append(f"{path}: string/{name}: format argument gap {indexes}")
    leftover = re.sub(r"%\d*\$?[sdf]|%%", "", body)
    if "%" in leftover:
        problems.append(f"{path}: string/{name}: dangling %")


FIELD_RE = re.compile(
    r'(?:(?:private|protected|public|static|final|volatile|transient)\s+)+'
    r'[\w<>\[\], .?]+?\s+(\w+)\s*(?:=[^;]*)?;')
LOCAL_RE = re.compile(r'(?:final\s+)?[\w<>\[\], .?]+?\s+(\w+)\s*=(?!=)')


def check_java_unary_plus(path):
    """A string initialiser may not *start* with `+ "..."`.

    Java has a unary plus, so `String X =\n  // comment\n  + "text"` is not a
    concatenation - it is `+ "text"`, which javac rejects with "bad operand type String for
    unary operator +". That is exactly how splitting a JS constant in two broke the 1.4.8
    build: the body began with a comment, so the first literal became a continuation of
    nothing. The first piece after `=` has to be a bare literal (or another operand).
    """
    src = open(path, encoding="utf-8", errors="replace").read()
    decl = re.compile(r"\b(?:static\s+|final\s+|private\s+|public\s+)*String\s+\w+\s*=\s*")
    for m in decl.finditer(src):
        rest = src[m.end():]
        # Walk past comments, then look at the first real token.
        i = 0
        saw_comment = False
        while True:
            while i < len(rest) and rest[i] in " \t\r\n":
                i += 1
            if rest.startswith("//", i):
                nl = rest.find("\n", i)
                if nl < 0:
                    break
                i = nl + 1
                saw_comment = True
                continue
            if rest.startswith("/*", i):
                end = rest.find("*/", i)
                if end < 0:
                    break
                i = end + 2
                saw_comment = True
                continue
            break
        if not saw_comment:
            continue
        if rest.startswith('+', i):
            line = src[:m.start()].count("\n") + 1
            problems.append(f"{path}:{line}: string initialiser starts with `+` after a "
                            f"comment - Java reads that as a unary plus; drop the first `+` "
                            f"so the first piece is a bare literal")


def check_java_shadowing(path):
    """A local that shadows a field silently changes what a call refers to.

    This is exactly how `String host = uri.getHost()` turned `host.onSignedIn()`
    into a String method call and broke the build.
    """
    lines = open(path, encoding="utf-8").read().split("\n")
    fields = {}
    for i, line in enumerate(lines):
        indent = len(line) - len(line.lstrip())
        if indent == 4 and "(" not in line:
            match = FIELD_RE.fullmatch(line.strip())
            if match:
                fields[match.group(1)] = i + 1
    for i, line in enumerate(lines):
        indent = len(line) - len(line.lstrip())
        stripped = line.strip()
        if indent <= 4 or not stripped:
            continue
        if stripped.startswith(("/", "*", "return", "if", "for", "while", "else")):
            continue
        match = LOCAL_RE.match(stripped)
        if match and match.group(1) in fields:
            problems.append(
                f"{path}:{i + 1}: local '{match.group(1)}' shadows the field declared "
                f"on line {fields[match.group(1)]}")


# Known signatures of the AppUi helpers; a wrong argument count is a compile error.
APPUI_ARITY = {
    "dp": {2}, "color": {2}, "text": {5}, "icon": {4}, "rounded": {3},
    "pressable": {3}, "sheetScrim": {2}, "slideUp": {1}, "fadeIn": {1},
    "pop": {1}, "press": {1}, "isDark": {1}, "wrap": {1},
}


def _arg_count(text):
    text = text.strip()
    if not text:
        return 0
    depth, count = 0, 1
    for ch in text:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        elif ch == "," and depth == 0:
            count += 1
    return count


def check_java_helper_calls(path):
    src = open(path, encoding="utf-8").read()
    for m in re.finditer(r"AppUi\.(\w+)\s*\(", src):
        name = m.group(1)
        expected = APPUI_ARITY.get(name)
        if not expected:
            continue
        i, depth = m.end(), 1
        start = i
        while i < len(src) and depth:
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
            i += 1
        argc = _arg_count(src[start:i - 1])
        if argc not in expected:
            line = src[:m.start()].count("\n") + 1
            problems.append(
                f"{path}:{line}: AppUi.{name}() called with {argc} args, "
                f"expected {sorted(expected)}")


# Tab ids are not dense: TAB_UPDATE (3) has no nav entry, so an int[] indexed by tab
# id is out of bounds for TAB_MORE (4). That exact bug crashed the app on launch.
TAB_VALUES = {"TAB_CHAT": 0, "TAB_LEADERBOARD": 1, "TAB_SEARCH": 2,
              "TAB_UPDATE": 3, "TAB_MORE": 4}

DECL_RE = re.compile(r"(?:int\s*\[\s*\]|int\[\])?\s*(\w+)\s*=\s*\{([^{};]*)\}")


def check_java_tab_arrays(path):
    """Flags arrays indexed by tab ids (literal or via a for-each over tab ids).

    An array built from a subset of tab ids and then indexed by a tab id throws
    ArrayIndexOutOfBoundsException at runtime. That already happened once (nav icons:
    4 entries, TAB_MORE = 4), so it is checked before every build now.
    """
    src = open(path, encoding="utf-8", errors="replace").read()

    sizes = {}       # name -> number of entries
    tab_arrays = {}  # name -> highest tab id the array can contain
    for m in DECL_RE.finditer(src):
        name, body = m.group(1), m.group(2)
        entries = [t.strip() for t in body.split(",") if t.strip()]
        sizes[name] = len(entries)
        values = [TAB_VALUES[e.split(".")[-1]] for e in entries
                  if e.split(".")[-1] in TAB_VALUES]
        if values:
            tab_arrays[name] = max(values)
    arrays = {name: (size, tab_arrays.get(name))
              for name, size in sizes.items() if name in tab_arrays}

    # for (int tab : TAB_LIST) -> tab ranges over that list's tab ids
    loop_vars = {}
    for m in re.finditer(r"for\s*\(\s*int\s+(\w+)\s*:\s*(\w+)\s*\)", src):
        if m.group(2) in tab_arrays:
            loop_vars[m.group(1)] = tab_arrays[m.group(2)]

    for name, size in sizes.items():
        for use in re.finditer(r"\b" + re.escape(name) + r"\s*\[\s*([\w.]+)\s*\]", src):
            index = use.group(1)
            key = index.split(".")[-1]
            highest = TAB_VALUES.get(key, loop_vars.get(key))
            if highest is not None and highest >= size:
                line = src[:use.start()].count("\n") + 1
                problems.append(
                    f"{path}:{line}: {name}[{index}] can be out of bounds "
                    f"(array has {size} entries, index reaches {highest}); "
                    f"tab ids are not dense - use a switch")


def _anonymous_scopes(src):
    """Brace offset -> ("class"|"anon", nearest class name) for the enclosing scope.

    Inside an anonymous class Java's `this` is the anonymous instance, so a bare
    `this::someMethod` there resolves against the anonymous type - a compile error when the
    method belongs to the outer class. That is not hypothetical:
    `ui.postDelayed(this::verifySession, 1200)` inside a WebViewClient compiled nowhere,
    and only the user's Gradle run said so.

    The distinction that matters: a bare `this::` inside an ordinary method of a class is
    fine; only one inside `new Foo() { ... }` is not. Braces are paired with a stack, so
    the answer is the innermost scope a position really sits in.
    """
    stack = []      # ("class", name) | ("anon", None) | ("block", None)
    scopes = {}
    i, n = 0, len(src)
    while i < n:
        ch = src[i]
        if ch == "{":
            tail = src[max(0, i - 160):i]
            if re.search(r"\bclass\s+(\w+)[^{]*$", tail):
                stack.append(("class", re.search(r"\bclass\s+(\w+)[^{]*$", tail).group(1)))
            elif re.search(r"\bnew\s+[\w.]+\s*(?:<[^>]*>)?\s*\([^;{}]*\)\s*$", tail):
                stack.append(("anon", None))
            else:
                stack.append(("block", None))
            nearest = next((entry for entry in reversed(stack)
                            if entry[0] in ("class", "anon")), None)
            owner = next((name for kind, name in reversed(stack) if kind == "class"), "")
            scopes[i] = (nearest[0] if nearest else "", owner)
        elif ch == "}":
            if stack:
                stack.pop()
        i += 1
    return scopes


def check_method_refs(java_paths):
    """A method reference has to point at a method that exists.

    `ui.postDelayed(this::verifySession, 1200)` where verifySession was accidentally removed
    compiles nowhere, and javac reports it as "invalid method reference / cannot find
    symbol" - the same class of failure the user's build kept hitting. The reference's
    receiver is either `this` or a class name: a bare `this::` is checked against the same
    file (the anonymous-class part is check_anonymous_this), and `X::` against the file that
    declares class X when there is one.
    """
    declared = {}
    for path in java_paths:
        src = open(path, encoding="utf-8", errors="replace").read()
        declared[path] = _method_names(src)
    by_class = {}
    for path in java_paths:
        src = open(path, encoding="utf-8", errors="replace").read()
        match = re.search(r"\bclass\s+(\w+)", _strip_java_noise(src))
        if match:
            by_class[match.group(1)] = path
    ref = re.compile(r"\b(\w+)?\s*(?:\.\s*)?this\s*::\s*(\w+)|\b([A-Z]\w*)\s*::\s*(\w+)")
    for path in java_paths:
        src = _strip_java_noise(open(path, encoding="utf-8", errors="replace").read())
        for m in ref.finditer(src):
            if m.group(2):
                name = m.group(2)
                pool = declared[path]
            else:
                name = m.group(4)
                owner = by_class.get(m.group(3))
                if owner is None:
                    continue      # an Android/library class: not ours to check
                pool = declared[owner]
            if name in pool:
                continue
            line = src[:m.start()].count("\n") + 1
            problems.append(f"{path}:{line}: method reference to {name}(), "
                            f"which this class does not declare")


def check_anonymous_this(java_paths):
    """`this::m()` inside an anonymous class must name the enclosing class.

    Reports the ambiguous ones instead of guessing: a method reference whose method is not
    declared by the anonymous type itself is a compile error at worst, and silently the
    wrong receiver at best.
    """
    # `X.this::m` is explicit and legal; only a bare `this::m` is the problem.
    ref = re.compile(r"(?<![\w.])this\s*::\s*(\w+)")
    for path in java_paths:
        src = _strip_java_noise(open(path, encoding="utf-8", errors="replace").read())
        scopes = _anonymous_scopes(src)
        if not scopes:
            continue
        for m in ref.finditer(src):
            kind, owner = "", ""
            for brace, entry in scopes.items():
                if brace < m.start():
                    kind, owner = entry
            if kind != "anon":
                continue
            line = src[:m.start()].count("\n") + 1
            problems.append(
                f"{path}:{line}: this::{m.group(1)}() is inside an anonymous class - "
                f"write {owner or 'Enclosing'}.this::{m.group(1)}() instead")


def _strip_java_noise(src):
    """Removes comments, string and char literals so brace counting is honest."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
        elif c == '"':
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == "\\" else 1
            i += 1
        elif c == "'":
            i += 1
            while i < n and src[i] != "'":
                i += 2 if src[i] == "\\" else 1
            i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_java_braces(path):
    """Balanced braces/parens and no stray text after the final closing brace.

    The sandbox has no JDK, so this is the cheapest way to catch an edit that broke
    the structure of a Java file before the user's build burns a cycle on it.
    """
    src = _strip_java_noise(open(path, encoding="utf-8", errors="replace").read())
    depth = 0
    parens = 0
    line = 1
    closed_at = None
    for ch in src:
        if ch == "\n":
            line += 1
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0 and closed_at is None:
                closed_at = line
            if depth < 0:
                problems.append(f"{path}:{line}: unbalanced '}}'")
                return
        elif ch == "(":
            parens += 1
        elif ch == ")":
            parens -= 1
    if depth != 0:
        problems.append(f"{path}: {depth} unclosed '{{' at end of file")
    if parens != 0:
        problems.append(f"{path}: {parens} unbalanced '(' at end of file")
    if closed_at is not None:
        tail = src.split("}", 1)[0]
        # everything after the last brace of the outermost block must be whitespace
        last = src.rfind("}")
        if src[last + 1:].strip():
            problems.append(f"{path}: text after the final closing brace: "
                            f"{src[last + 1:].strip()[:40]!r}")


# Methods this project's own classes call on "tabs" (the TabWebViews field).
TABWEBVIEWS_API = {
    "current", "get", "all", "show", "preload", "applyThemeToAll", "applyInsetsToAll",
    "drop", "destroyAll", "clearAndReload", "reloadCurrent", "refreshInPlace",
    "canGoBack", "goBack", "loadInCurrent", "urlFor", "applyTheme", "applyChromeInsets",
}


def _method_names(src):
    """Every method declared in this file (concrete, abstract or interface)."""
    clean = _strip_java_noise(src)
    out = {}
    pattern = (r"(?:(?:public|private|protected|static|final|synchronized|abstract|"
               r"native|default)\s+)*[\w\[\]<>,.?]+\s+(\w+)\s*\([^;{]*\)"
               r"\s*(?:throws [\w., ]+)?(?:\{|;)")
    for m in re.finditer(pattern, clean):
        out.setdefault(m.group(1), clean[:m.start()].count("\n") + 1)
    return out


KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "new", "throw",
            "assert", "synchronized", "super", "this", "do", "else", "try", "finally",
            "case", "instanceof"}


def check_java_self_calls(path):
    """A call to a method that this class neither declares nor inherits.

    `x.foo()` is javac's problem, but an unqualified `foo()` inside the same class is
    exactly the shape that broke the build once: a cleanup deleted ensure() and left
    three calls behind. Only camelCase names are considered, so platform callbacks are
    not flagged, and string/comment content is stripped first.
    """
    raw = open(path, encoding="utf-8", errors="replace").read()
    declared = _method_names(raw)
    src = _strip_java_noise(raw)
    inherited = {
        "onCreate", "onDestroy", "onPause", "onResume", "onStart", "onStop",
        "onSaveInstanceState", "onRestoreInstanceState", "onNewIntent", "onBackPressed",
        "onActivityResult", "onRequestPermissionsResult", "onConfigurationChanged",
        "attachBaseContext", "setContentView", "findViewById", "getSystemService",
        "getString", "getApplicationContext", "getPackageName", "getWindow",
        "getIntent", "setIntent", "runOnUiThread", "requestPermissions",
        "checkSelfPermission", "startActivity", "startActivityForResult",
        "isFinishing", "isDestroyed", "recreate", "finish", "hashCode", "equals",
        "toString", "getClass", "notify", "notifyAll", "post", "postDelayed",
        "removeCallbacks", "invalidate", "getResources", "getTheme", "onTouchEvent",
        "onInterceptTouchEvent", "onMeasure", "onLayout", "onDraw", "performClick",
        "onAttachedToWindow", "onDetachedFromWindow", "wait", "clone",
    }
    for m in re.finditer(r"(?<![.\w])([a-z]\w*)\s*\(", src):
        name = m.group(1)
        if name in declared or name in inherited or name in KEYWORDS:
            continue
        line = src[src.rfind("\n", 0, m.start()) + 1:].split("\n", 1)[0].strip()
        if re.match(r"^(?:\(|else\s+)?(?:if|for|while|switch|catch|return|new|throw)\b",
                    line):
            continue
        problems.append(f"{path}:{src[:m.start()].count(chr(10)) + 1}: calls {name}() "
                        f"but this class declares no such method")


TABWEBVIEWS_API = {"urlFor", "applyTheme", "applyChromeInsets"}


def check_tab_api(paths):
    """Every `tabs.foo()` call must exist on TabWebViews (static or instance)."""
    api = set(TABWEBVIEWS_API)
    for path in paths:
        if path.endswith("TabWebViews.java"):
            api |= set(_method_names(open(path, encoding="utf-8",
                                         errors="replace").read()))
    for path in paths:
        src = _strip_java_noise(open(path, encoding="utf-8", errors="replace").read())
        for m in re.finditer(r"\btabs\.(\w+)\s*\(", src):
            if m.group(1) not in api:
                problems.append(f"{path}:{src[:m.start()].count(chr(10)) + 1}: "
                                f"tabs.{m.group(1)}() does not exist on TabWebViews")


# Members of this project's own classes, so a call like AppUi.chromeBg(...) is checked
# without a JDK. Field declarations plus methods, per class name.
PROJECT_CLASSES = ("AppUi", "AppChrome", "TabWebViews", "UpdateManager", "UpdateCenter",
                   "UpdateNotifications", "UpdateSheet", "MenuSheet", "SignInSheet",
                   "AccountPicker", "CrashLog", "NavIcons", "WebBridge", "Downloads",
                   "ApkInstaller", "SettingsSheet", "Settings", "ChatHistorySheet", "LocalProfile",
                   "SignInDriver", "SignInDiagnostics")
# Nested handler interfaces and enum constants are not "calls".
NOT_A_CALL = {"class", "valueOf", "values", "Host", "Callback", "DownloadCallback",
              "CheckCallback", "Progress", "Channel", "Nav", "NavItem", "Result",
              "Host_", "Factory", "AppWebViewClientFactory", "WebChromeClientFactory"}


def _members(src):
    """Method names and field names declared in a Java file.

    Comment bodies are removed first: words like "and" inside a doc comment would
    otherwise look like a call, and 'boolean ... {' inside a comment could swallow a real
    signature - that is why styleNav()/isDark() were once reported as missing.
    """
    clean = re.sub(r"\s+", " ", _strip_java_noise(src))
    names = set()
    names |= set(re.findall(
        r"(?:public|private|protected|static|final|synchronized|abstract|default)\s+"
        r"[\w<>\[\],.]+\s+(\w+)\s*\([^;{]*\)\s*(?:throws [\w., ]+)?\{", clean))
    names |= set(re.findall(r"^\s*(\w+)\s*\([^;{]*\)\s*(?:throws [\w., ]+)?\{", clean))
    names |= set(re.findall(
        r"(?:public|private|protected|static|final)\s+[\w<>\[\],. ]+?\s+(\w+)\s*(?:=|;)", clean))
    names |= set(re.findall(r"\b(?:class|interface|enum)\s+(\w+)", clean))
    names |= set(re.findall(r"\b([A-Z][A-Z0-9_]{2,})\b", clean))
    return names


def check_java_cross_class(paths):
    """Every OwnClass.member(...) call must exist on that class.

    AppUi.chromeBg() was deleted together with the top-bar code while the nav still
    called it, and the only feedback was a slow Gradle round trip. This catches the whole
    family of mistakes before the build starts.
    """
    classes = {}
    for path in paths:
        name = path.replace("\\", "/").split("/")[-1][:-5]
        classes[name] = _members(open(path, encoding="utf-8", errors="replace").read())
    for path in paths:
        src = _strip_java_noise(open(path, encoding="utf-8", errors="replace").read())
        for m in re.finditer(r"\b(\w+)\.(\w+)\s*\(", src):
            cls, member = m.group(1), m.group(2)
            if cls not in classes or cls not in PROJECT_CLASSES:
                continue
            if member in NOT_A_CALL or member in classes[cls]:
                continue
            problems.append(f"{path}:{src[:m.start()].count(chr(10)) + 1}: "
                            f"{cls}.{member}() does not exist")


def check_batch(path):
    """cmd.exe gotchas: duplicate labels, unresolved jumps, unbalanced parens."""
    import collections
    text = open(path, encoding="utf-8", errors="replace").read()
    labels = [m.group(1).lower() for m in re.finditer(r"^:(\w+)", text, re.M)]
    for name, count in collections.Counter(labels).items():
        if count > 1:
            problems.append(f"{path}: label :{name} defined {count} times")
    jumps = {m.group(1).lower() for m in re.finditer(r"(?:goto|call) :(\w+)", text)}
    for missing in sorted(jumps - set(labels) - {"eof"}):
        problems.append(f"{path}: jumps to undefined label :{missing}")
    depth = 0
    for i, line in enumerate(text.split("\n"), 1):
        stripped = line.strip()
        if stripped.upper().startswith(("REM", "ECHO", ":")):
            continue
        bare = re.sub(r"\^\(|\^\)", "", line)
        bare = re.sub(r'"[^"]*"', '""', bare)
        depth += bare.count("(") - bare.count(")")
        if depth < 0:
            problems.append(f"{path}:{i}: unbalanced ')' ")


# ---------------------------------------------------------------- Android API stubs
# Without a JDK or the SDK in this sandbox the only way to catch "method does not
# exist" on an Android class is to know the API surface myself. This is a stub of the
# framework types the app touches, listing what really exists on them (checked against
# the SDK 34 docs). AccountManager.getAuthenticators() once slipped through and cost a
# full Gradle round trip, so unknown members are now reported instead of compiled.
ANDROID_MEMBERS = {
    "AccountManager": {
        "get", "getAccounts", "getAccountsByType", "getAccountsByTypeAndFeatures",
        "addAccount", "addAccountExplicitly", "removeAccount", "blockingGetAuthToken",
        "getAuthToken", "getAuthTokenByFeatures", "invalidateAuthToken", "getPassword",
        "peekAuthToken", "setPassword", "clearPassword", "getUserData", "setUserData",
        "setAuthToken", "newChooseAccountIntent", "hasFeatures", "getAuthenticatorTypes",
        "KEY_ACCOUNT_NAME", "KEY_ACCOUNT_TYPE", "KEY_AUTHTOKEN", "KEY_INTENT",
        "KEY_PASSWORD", "KEY_ACCOUNT_AUTHENTICATOR_RESPONSE",
    },
    "AuthenticatorDescription": {"describeContents", "writeToParcel", "CREATOR", "type",
                                 "packageName", "labelId", "iconId", "accountLayoutId",
                                 "accountPreferencesId", "customTokens", "smallIconId"},
    "CookieManager": {"getInstance", "setAcceptCookie", "acceptCookie", "setCookie",
                      "getCookie", "removeAllCookies", "removeSessionCookies", "flush",
                      "setAcceptThirdPartyCookies", "acceptThirdPartyCookies",
                      "getCookieManager"},
    "WebSettings": {"getDefaultUserAgent", "getUserAgentString", "setUserAgentString",
                    "setJavaScriptEnabled", "setDomStorageEnabled", "setUseWideViewPort",
                    "setLoadWithOverviewMode", "setTextZoom", "setSupportZoom",
                    "setBuiltInZoomControls", "setDisplayZoomControls",
                    "setJavaScriptCanOpenWindowsAutomatically", "setMediaPlaybackRequiresUserGesture",
                    "setMixedContentMode", "setCacheMode", "setSupportMultipleWindows",
                    "setDatabaseEnabled", "setAllowFileAccess", "setAllowContentAccess",
                    "setLoadsImagesAutomatically", "setDefaultTextEncodingName",
                    "setGeolocationEnabled", "getCacheMode", "setMinimumFontSize",
                    "supportMultipleWindows", "getJavaScriptCanOpenWindowsAutomatically",
                    "supportZoom", "getTextZoom", "getLoadsImagesAutomatically"},
    # WebView is in here for one reason: 1.4.2 shipped `web.preconnect(url)`, which is a
    # URLConnection method, not a WebView one. javac caught it on the user's machine; this
    # table is what should have. Only real instance methods are listed.
    "WebView": {"addJavascriptInterface", "removeJavascriptInterface", "loadUrl", "reload",
                "evaluateJavascript", "post", "postDelayed", "stopLoading", "destroy",
                "canGoBack", "canGoForward", "goBack", "goForward", "getUrl", "getTitle",
                "getSettings", "setWebViewClient", "setWebChromeClient", "setDownloadListener",
                "clearCache", "clearHistory", "clearFormData", "setOverScrollMode",
                "setLongClickable", "setRendererPriorityPolicy", "getScale", "zoomIn",
                "zoomOut", "requestFocus", "setInitialScale", "pageUp", "pageDown",
                "setBackgroundColor", "getFocusedNode", "saveState", "restoreState"},
    "Build": {"MODEL", "MANUFACTURER", "BRAND", "DEVICE", "VERSION", "SDK_INT"},
    "Build.VERSION": {"RELEASE", "SDK_INT", "CODENAME", "PREVIEW_SDK_INT"},
}
# Calls that belong to java.lang.Object, not to the Android class.
JAVA_METHODS = {"getClass", "hashCode", "equals", "toString", "notify", "notifyAll",
                "wait", "clone", "finalize"}
# Calls that belong to android.view.View. Every Android widget inherits them, so they are
# never evidence that a type-specific member is wrong.
VIEW_METHODS = {"getVisibility", "setVisibility", "setAlpha", "animate", "bringToFront",
                "getContext", "getResources", "getParent", "setPadding", "setBackground",
                "setBackgroundColor", "setClickable", "setFocusable", "setElevation",
                "setLayoutParams", "setOnClickListener", "setLongClickable", "setTag",
                "getTag", "requestLayout", "invalidate", "setTranslationY", "setTranslationX",
                "setScaleX", "setScaleY", "setSelected", "setEnabled", "isEnabled",
                "setContentDescription", "findViewById", "post", "postDelayed",
                "removeCallbacks", "getHeight", "getWidth", "setMinimumHeight",
                "setMinimumWidth", "getLayoutParams", "setClipToOutline", "setOutlineProvider"}


def check_android_api(paths):
    """Reports member calls on Android-typed variables that are not real members.

    A file may reuse a name for different types (UpdateSheet has a local "settings" that
    is a LinearLayout while "s" is the WebSettings), so a variable is only checked when
    *every* declaration of that name in the file is one of the stubbed Android types.
    Static calls such as WebSettings.getDefaultUserAgent() are checked on the class name.
    """
    decl = re.compile(r"\b([A-Z]\w*)(?:<[^>]*>)?\s+(\w+)\s*[=;,):]")
    for path in paths:
        src = _strip_java_noise(open(path, encoding="utf-8", errors="replace").read())
        declared = {}
        for m in decl.finditer(src):
            declared.setdefault(m.group(2), set()).add(m.group(1))
        for m in re.finditer(r"\b(\w+)\.(\w+)\s*\(", src):
            recv, member = m.group(1), m.group(2)
            if member in JAVA_METHODS:
                continue
            if recv in ANDROID_MEMBERS:
                types = {recv}
            else:
                types = declared.get(recv)
                if not types or not all(t in ANDROID_MEMBERS for t in types):
                    continue
            if any(member in ANDROID_MEMBERS[t] for t in types):
                continue
            # Inherited View behaviour is not type-specific: allow it, but only for classes
            # that really are Views.
            if member in VIEW_METHODS and types & {"WebView"}:
                continue
            problems.append(f"{path}:{src[:m.start()].count(chr(10)) + 1}: "
                            f"{'/'.join(sorted(types))}.{member}() is not an Android API "
                            f"member")



def check_colour_names(java_paths):
    """Every R.color.foo used from Java must exist in values/ and values-night/."""
    def names(path):
        try:
            raw = open(path, encoding="utf-8").read()
        except OSError:
            return set()
        return set(re.findall(r'<color name="([^"]+)"', raw))
    have = names("app/src/main/res/values/colors.xml")
    night = names("app/src/main/res/values-night/colors.xml")
    used = set()
    for path in java_paths:
        used |= set(re.findall(r'R\.color\.(\w+)', open(path, encoding="utf-8",
                                                        errors="replace").read()))
    for name in sorted(used - have):
        problems.append(f"R.color.{name} is not defined in values/colors.xml")
    for name in sorted(have - night):
        problems.append(f"color {name} has no dark variant in values-night/colors.xml")



# ------------------------------------------------------------------ XML well-formedness
RES_DIR = "app/src/main/res"


def check_shell_attributes(res_paths):
    """Framework-theme attributes are not available to this app.

    `?attr/colorControlNormal` (and the rest of the android: namespace versions of it) come
    from AppCompat / Material themes. This app is a bare `android.app.Activity` with its own
    colours, so a drawable or layout referencing one fails at link time with
    "resource attr/colorControlNormal not found" - after the build has already spent the
    time compiling. Anything like that is reported here instead.
    """
    allowed = set()
    for path in glob.glob("app/src/main/res/values/*.xml"):
        raw = open(path, encoding="utf-8", errors="replace").read()
        allowed.update(re.findall(r'<attr\s+name="([\w.]+)"', raw))
    for path in res_paths:
        if not path.endswith(".xml"):
            continue
        raw = open(path, encoding="utf-8", errors="replace").read()
        # `?attr/name` references a theme attribute; `?xml` is just the XML declaration.
        for m in re.finditer(r"[?]attr/([A-Za-z_][\w.]*)", raw):
            name = m.group(1)
            if name in allowed:
                continue
            if name.startswith("android:"):
                continue
            line = raw[:m.start()].count("\n") + 1
            problems.append(f"{path}:{line}: ?attr/{name} is a framework/AppCompat theme "
                            f"attribute this app does not define - link will fail; use a "
                            f"colour from res/values/colors.xml instead")


def check_xml_files():
    """Parses every XML file the way aapt2 does.

    A resource file that is not well-formed XML fails the build at mergeDebugResources,
    which is exactly what happened when a patch closed strings.xml with </strings>
    instead of </resources> while the per-string checks below happily passed. Parsing is
    the only honest test for well-formedness, so it runs first and reports the parser's
    own line and column.
    """
    import xml.etree.ElementTree as ET
    for path in sorted(glob.glob(RES_DIR + "/**/*.xml", recursive=True)):
        raw = open(path, encoding="utf-8", errors="replace").read()
        try:
            ET.fromstring(raw)
        except ET.ParseError as exc:
            line, col = exc.position
            problems.append(f"{path}:{line}:{col}: not well-formed XML ({exc.msg})")
            continue
        if not raw.lstrip().startswith("<?xml"):
            problems.append(f"{path}: missing XML declaration")


def check_string_close(paths):
    """Every strings file must end with </resources>, not a stray closing tag."""
    for path in paths:
        raw = open(path, encoding="utf-8", errors="replace").read().rstrip()
        if not raw.endswith("</resources>"):
            last = raw.splitlines()[-1] if raw else ""
            problems.append(f"{path}: file does not end with </resources> "
                            f"(last line: {last.strip()[:40]!r})")



# ------------------------------------------------------------------ resource references
TYPED_REF = re.compile(r'@([a-z]+)/([\w.]+)')


def _res_index():
    """type -> set(names) for everything defined under app/src/main/res."""
    index = {"drawable": set(), "mipmap": set(), "string": set(), "color": set(),
             "style": set(), "xml": set(), "layout": set(), "anim": set(),
             "raw": set(), "menu": set(), "font": set(), "array": set(), "bool": set(),
             "integer": set(), "dimen": set(), "plurals": set(), "id": set()}
    # File-based resources: the whole tree, every extension (raw/*.wav included).
    for path in glob.glob(RES_DIR + "/**/*", recursive=True):
        if not __import__("os").path.isfile(path):
            continue
        parts = path.replace("\\", "/").split("/")
        folder = parts[-2]
        base = parts[-1]
        kind = ("mipmap" if folder.startswith("mipmap") else
                "drawable" if folder.startswith("drawable") else
                "layout" if folder.startswith("layout") else
                "xml" if folder.startswith("xml") else
                "raw" if folder.startswith("raw") else
                "menu" if folder.startswith("menu") else
                "anim" if folder.startswith("anim") else
                "font" if folder.startswith("font") else None)
        if kind:
            index[kind].add(base.split(".")[0])
            continue
        if not base.endswith(".xml"):
            continue
        raw = open(path, encoding="utf-8", errors="replace").read()
        for m in re.finditer(r'<(string|color|style|bool|integer|dimen|plurals|array|'
                             r'string-array|integer-array)\s+name="([^"]+)"', raw):
            kind = m.group(1).split("-")[0]
            index.setdefault(kind, set()).add(m.group(2))
        for m in re.finditer(r'<(?:item)\s+type="(\w+)"\s+name="([^"]+)"', raw):
            index.setdefault(m.group(1), set()).add(m.group(2))
    return index


def check_resource_refs(java_paths):
    """@type/name in XML and R.type.name in Java must exist.

    aapt2 reports these only after resource merging, so a typo costs a full Gradle run.
    """
    index = _res_index()
    known_types = set(index)
    sources = [p for p in glob.glob(RES_DIR + "/**/*.xml", recursive=True)]
    sources.append("app/src/main/AndroidManifest.xml")

    def note(where, kind, name):
        if kind in ("android", "id") or kind not in known_types:
            return
        names = index.get(kind, set())
        if name in names:
            return
        # "Theme.Agon" is a nested style name: "@style/Theme.Agon" is one reference,
        # and "@style/Theme" refers to the same file only if such a style exists.
        if kind == "style" and any(n.startswith(name + ".") for n in names):
            return
        problems.append(f"{where}: @{kind}/{name} does not exist")

    for path in sources:
        if not path or not __import__("os").path.exists(path):
            continue
        raw = open(path, encoding="utf-8", errors="replace").read()
        # XML comments are not references (the manifest mentions "@style/..." in prose).
        raw = re.sub(r"<!--.*?-->", "", raw, flags=re.S)
        for m in TYPED_REF.finditer(raw):
            kind, name = m.group(1), m.group(2)
            # "@raw/update_chime.wav" refers to the resource "update_chime"; a style name
            # such as "Theme.Agon" is a real name and must NOT be split on its dot.
            if kind == "raw":
                name = name.split(".")[0]
            note(path, kind, name)

    for path in java_paths:
        raw = open(path, encoding="utf-8", errors="replace").read()
        for m in re.finditer(r'R\.(drawable|mipmap|string|color|style|xml|layout|raw|'
                             r'menu|font|bool|integer|dimen|plurals)\.(\w+)', raw):
            note(path, m.group(1), m.group(2))



# ------------------------------------------------------------------ injected JavaScript
# The app builds JS strings in Java and runs them in a WebView. A missing quote there is
# invisible to javac and only fails on the phone, which is how a broken "hide the sidebar"
# script shipped twice. The literals are joined the way Java joins them, then parsed with
# node when it is available (and with a small bracket checker when it is not).
JS_CONSTANTS = ("JS_PRELUDE", "OPEN_DIALOG_JS", "CHOOSE_PROVIDER_JS",
                "SIGNED_OUT_JS", "WATCH_DIALOG_JS",
                "PAGE_SIDEBAR_CSS", "PAGE_HEADER_CSS", "JS_SIDEBAR", "JS_HEADER")

# Constants that are only ever spliced into another script: they are fragments, so on their
# own they are not valid JavaScript - but each one must still be free of the two mistakes
# that silently corrupt a whole script.
JS_FRAGMENTS = ("OPEN_DIALOG_BODY",)


def _java_literals(expr):
    """Every "..." literal in a Java expression, with escapes decoded."""
    out = []
    for m in re.finditer(r'"((?:\\.|[^"\\])*)"', expr):
        raw = m.group(1)
        text = []
        i = 0
        while i < len(raw):
            ch = raw[i]
            if ch == "\\" and i + 1 < len(raw):
                nxt = raw[i + 1]
                if nxt == "u" and i + 5 < len(raw) + 1:
                    try:
                        text.append(chr(int(raw[i + 2:i + 6], 16)))
                        i += 6
                        continue
                    except ValueError:
                        pass
                text.append({"n": "\n", "r": "\r", "t": "\t", '"': '"', "\\": "\\",
                             "'": "'"}.get(nxt, nxt))
                i += 2
                continue
            text.append(ch)
            i += 1
        out.append("".join(text))
    return out


def _fragment_text(body):
    """The concatenated string pieces of a fragment constant, with quotes decoded."""
    return "".join(_java_literals(body))


def _splice_fragments(path, src, body):
    """The script a constant builds, with its fragment constants pasted in place.

    A fragment is declared once and used inside a wrapper, so extracting the wrapper alone
    leaves `+ OPEN_DIALOG_BODY` in the text and the parse either fails for the wrong reason
    or - worse - a broken fragment goes unnoticed. The fragment's own `return`s are already
    inside the wrapper's function, so it is pasted verbatim.
    """
    for frag in JS_FRAGMENTS:
        marker = "+ " + frag
        if marker not in body:
            continue
        text = _fragment_text(_fragment_source(src, frag))
        if not text:
            problems.append(f"{path}: {frag} is used but not declared")
            continue
        # Escape only what a Java literal would escape, then keep it one string piece.
        pasted = text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ")
        body = body.replace(marker, '+ "' + pasted + '"')
    return "".join(_java_literals(body))


def _fragment_source(src, name):
    """The declaration text of a fragment constant, or "" when it is not declared."""
    at = src.find("String " + name)
    if at < 0:
        return ""
    return src[at:src.find(";\n", at)]


def check_injected_js(java_paths):
    """Extracts the JS constants and parses them with node (or a bracket check)."""
    import shutil
    import subprocess
    import tempfile
    scripts = []
    for path in java_paths:
        src = open(path, encoding="utf-8", errors="replace").read()
        for name in JS_CONSTANTS:
            at = src.find("String " + name)
            if at < 0:
                continue
            end = src.find(";\n", at)
            body = src[at:end]
            # A double quote inside a comment that sits between the pieces of a JS
            # constant is not a comment to the literal extractor: it becomes content, and
            # the extracted script silently gains a stray word (that is how "Log In" in a
            # comment turned OPEN_DIALOG_JS into invalid JavaScript). Quotes belong in
            # string literals only.
            for comment in re.finditer(r"//[^\n]*|/\*.*?\*/", body, re.S):
                if '"' in comment.group(0):
                    problems.append(
                        f"{path}: comment inside {name} contains a double quote - "
                        f"rewrite it without quotes")
            joined = _splice_fragments(path, src, body)
            if "{" in joined:
                # a {PROVIDER}-style token is data, not JS structure: replace it first
                joined = re.sub(r"\{[A-Z_]+\}", "google", joined)
            scripts.append((path, name, joined))
        # Inline scripts written as `String js = "(function(){..."`, as in the diagnostics
        # and chat-history sheets: same failure mode, so they get the same parse.
        for match in re.finditer(r'String\s+js\s*=\s*"(?:\(function|\()', src):
            end = src.find("page.evaluateJavascript", match.start())
            if end < 0:
                end = src.find("evaluateJavascript", match.start())
            if end < 0:
                continue
            joined = "".join(_java_literals(src[match.start():end]))
            line = src[:match.start()].count("\n") + 1
            scripts.append((path, f"inline js at line {line}", joined))

    node = shutil.which("node")
    for path, name, js in scripts:
        if not js.strip():
            problems.append(f"{path}: {name} is empty")
            continue
        if not name.endswith("_JS"):
            continue     # CSS: parsed by the browser, not by node
        if node:
            with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as handle:
                handle.write(js)
                temp = handle.name
            try:
                result = subprocess.run([node, "--check", temp],
                                        capture_output=True, text=True)
                if result.returncode != 0:
                    first = (result.stderr.strip().splitlines() or ["invalid JS"])[0]
                    problems.append(f"{path}: {name} is not valid JavaScript ({first})")
            finally:
                __import__("os").unlink(temp)
        else:
            # No node: at least catch an unbalanced literal by counting brackets outside
            # of strings.
            depth = 0
            in_str = None
            escaped = False
            for ch in js:
                if in_str:
                    if escaped:
                        escaped = False
                    elif ch == "\\":
                        escaped = True
                    elif ch == in_str:
                        in_str = None
                    continue
                if ch in "'\"":
                    in_str = ch
                elif ch in "([{":
                    depth += 1
                elif ch in ")]}":
                    depth -= 1
            if depth != 0 or in_str is not None:
                problems.append(f"{path}: {name} has unbalanced brackets or quotes")



def check_duplicate_resources(paths):
    """Two <string name="x"> in one file is an aapt2 error, not a warning."""
    for path in paths:
        raw = open(path, encoding="utf-8", errors="replace").read()
        seen = {}
        for m in re.finditer(r'<(string|color|dimen|bool|integer|style)\s+name="([^"]+)"',
                             raw):
            key = m.group(1) + "/" + m.group(2)
            line = raw[:m.start()].count("\n") + 1
            if key in seen:
                problems.append(f"{path}:{line}: duplicate resource {key} "
                                f"(first defined on line {seen[key]})")
            else:
                seen[key] = line


def main():
    for path in glob.glob("*.bat"):
        check_batch(path)

    java_paths = glob.glob("app/src/main/java/**/*.java", recursive=True)
    check_tab_api(java_paths)
    check_java_cross_class(java_paths)
    check_android_api(java_paths)
    check_colour_names(java_paths)
    check_resource_refs(java_paths)
    check_injected_js(java_paths)
    for path in java_paths:
        check_anonymous_this([path])
        check_method_refs([path])
        check_java_self_calls(path)
        check_java_shadowing(path)
        check_java_unary_plus(path)
        check_java_helper_calls(path)
        check_java_tab_arrays(path)
        check_java_braces(path)

    value_files = glob.glob("app/src/main/res/values*/*.xml")
    check_xml_files()
    check_shell_attributes(glob.glob("app/src/main/res/**/*.xml", recursive=True))
    check_string_close(value_files)
    check_duplicate_resources(value_files)
    for path in value_files:
        raw = open(path, encoding="utf-8").read()
        for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', raw, re.S):
            check_string(path, m.group(1), m.group(2))

    if problems:
        print("\n".join(problems))
        print(f"\n{len(problems)} resource problem(s) found.")
        return 1
    print("resources OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
