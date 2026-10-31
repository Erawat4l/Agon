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
                   "ApkInstaller", "SettingsSheet", "Settings")
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
                    "setGeolocationEnabled", "getCacheMode", "setMinimumFontSize"},
    "Build": {"MODEL", "MANUFACTURER", "BRAND", "DEVICE", "VERSION", "SDK_INT"},
    "Build.VERSION": {"RELEASE", "SDK_INT", "CODENAME", "PREVIEW_SDK_INT"},
}
# Calls that belong to java.lang.Object, not to the Android class.
JAVA_METHODS = {"getClass", "hashCode", "equals", "toString", "notify", "notifyAll",
                "wait", "clone", "finalize"}


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
            problems.append(f"{path}:{src[:m.start()].count(chr(10)) + 1}: "
                            f"{'/'.join(sorted(types))}.{member}() is not an Android API "
                            f"member")


def main():
    for path in glob.glob("*.bat"):
        check_batch(path)

    java_paths = glob.glob("app/src/main/java/**/*.java", recursive=True)
    check_tab_api(java_paths)
    check_java_cross_class(java_paths)
    check_android_api(java_paths)
    for path in java_paths:
        check_java_self_calls(path)
        check_java_shadowing(path)
        check_java_helper_calls(path)
        check_java_tab_arrays(path)
        check_java_braces(path)

    for path in glob.glob("app/src/main/res/values*/*.xml"):
        raw = open(path, encoding="utf-8").read()
        if not raw.lstrip().startswith("<?xml"):
            problems.append(f"{path}: missing XML declaration")
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
