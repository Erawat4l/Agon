package ai.arena.webapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps one warm WebView per tab, stacked in a FrameLayout.
 *
 * Why: switching tabs must feel instant. Each tab keeps its DOM, scroll position and
 * session, so a tap just flips View.VISIBILITY (no reload, no white flash). Only the
 * first visit to a tab touches the network; afterwards it is memory-resident and a
 * silent background refresh keeps it current.
 */
public final class TabWebViews {

    /** Hook the activity needs: chrome client, error reporting, downloads. */
    public interface Host {
        AppWebViewClientFactory clientFactory();

        WebChromeClientFactory chromeFactory();

        android.webkit.DownloadListener downloadListener();

        void onPageFinished(WebView view, String url);

        void onMainFrameError(WebView view);

        void onLoadProgress(WebView view, int progress);
    }

    public interface AppWebViewClientFactory {
        android.webkit.WebViewClient create();
    }

    public interface WebChromeClientFactory {
        android.webkit.WebChromeClient create();
    }

    private static final int MAX_WARM = 5;

    private final Activity activity;
    private final Host host;
    private final FrameLayout container;
    private final Map<Integer, WebView> views = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.Set<Integer> loaded = new java.util.HashSet<>();
    private int currentTab = -1;
    private int lastTopInset = 0;
    private int lastBottomInset = 0;

    public TabWebViews(Activity activity, FrameLayout container, Host host) {
        this.activity = activity;
        this.container = container;
        this.host = host;
        ensure(AppUi.TAB_CHAT);
    }

    public WebView current() {
        return views.get(currentTab);
    }

    public WebView get(int tab) {
        return views.get(tab);
    }

    /** Every warm WebView, current tab included. */
    public java.util.Collection<WebView> all() {
        return new java.util.ArrayList<>(views.values());
    }

    public static String urlFor(int tab) {
        switch (tab) {
            case AppUi.TAB_LEADERBOARD:
                return "https://arena.ai/leaderboard";
            case AppUi.TAB_SEARCH:
                return "https://arena.ai/history/search";
            default:
                return "https://arena.ai/";
        }
    }

    // ---------------------------------------------------------------- tabs

    /** Creates the tab's WebView if needed and shows it. Cheap after the first visit. */
    public WebView show(int tab) {
        WebView target = ensure(tab);
        boolean switched = currentTab != -1 && currentTab != tab;
        currentTab = tab;
        // Settings -> "Remember scroll position": when off, every visit starts at the
        // top of the page instead of where the tab was left.
        if (switched && !Settings.keepScroll(activity)) {
            final WebView scrolled = target;
            main.post(() -> scrolled.evaluateJavascript(
                    "window.scrollTo(0,0);var m=document.scrollingElement;if(m)m.scrollTop=0;",
                    null));
        }
        if (target.getVisibility() != android.view.View.VISIBLE) {
            target.setVisibility(android.view.View.VISIBLE);
            target.setAlpha(0f);
            target.animate().alpha(1f).setDuration(AppUi.ANIM_FAST).start();
        }
        target.bringToFront();
        return target;
    }

    /**
     * Builds the other tabs' WebViews in the background so the first tap is not a
     * cold start. ensure() keeps them GONE until the user asks for them.
     */
    public void preload() {
        int[] tabs = {AppUi.TAB_LEADERBOARD, AppUi.TAB_SEARCH};
        for (int i = 0; i < tabs.length; i++) {
            final int tab = tabs[i];
            main.postDelayed(() -> {
                if (!views.containsKey(tab)) ensure(tab);
            }, 500L + 400L * i);
        }
    }

    /**
     * Arena reads its palette from a "?__theme=light|dark" query parameter (and stores
     * it in localStorage), so the page follows the app's Appearance setting instead of
     * the system default. Re-applied whenever the tab is shown.
     */
    public static void applyTheme(WebView web, boolean dark) {
        if (web == null) return;
        String mode = dark ? "dark" : "light";
        // Never navigate here. This used to call location.replace() to add ?__theme=,
        // which replaced the document every single time a tab was shown: that is the
        // "app takes forever to load" symptom (and it dropped the injected styles, which
        // made the page look blank for a moment).
        String js = "(function(){try{localStorage.setItem('theme','" + mode + "');}"
                + "catch(e){}"
                + "var d=document.documentElement;"
                + "if(d.getAttribute('data-theme')!=='" + mode + "'){"
                + "d.setAttribute('data-theme','" + mode + "');}"
                + "d.style.colorScheme='" + mode + "';"
                + "try{document.body&&document.body.classList.toggle('dark','" + mode + "'==='dark');}"
                + "catch(e){}"
                + "window.dispatchEvent(new Event('arena-theme'));})();";
        web.evaluateJavascript(js, null);
    }

    /** Applies the current appearance to every live tab. */
    public void applyThemeToAll(boolean dark) {
        for (WebView w : views.values()) applyTheme(w, dark);
    }

    /**
     * Fits arena's desktop layout into the phone viewport.
     *
     * The page is a responsive web app, but a WebView is not a browser: it reports a
     * phone-sized viewport, so arena renders its phone layout with its own sidebar and
     * sticky composer - which is why it looked like a squeezed desktop page sliding
     * under the app's chrome ("the page spreads out of the UI").
     *
     * Two things happen here, both re-applied whenever the chrome sizes change:
     *  1. a CSS variable carries the height of the app's own bars, and the document is
     *     padded by it so nothing is hidden behind the top bar or the nav;
     *  2. the layout is pinned to the viewport (100dvh, no horizontal overflow) so the
     *     page always fits the app frame instead of growing past it.
     */
    public static void applyChromeInsets(WebView web, int topPx, int bottomPx) {
        if (web == null) return;
        final boolean hideSidebar = Settings.hideWebSidebar(web.getContext());
        final boolean hideHeader = Settings.hideWebHeader(web.getContext());
        final boolean selectable = Settings.selectText(web.getContext());

        float density = web.getResources().getDisplayMetrics().density;
        int cssTop = Math.round(Math.max(0, topPx) / density);
        int cssBottom = Math.round(Math.max(0, bottomPx) / density);

        String css = ":root{--arena-top-inset:" + cssTop + "px;"
                + "--arena-bottom-inset:" + cssBottom + "px;"
                + "--arena-app-height:100dvh;}"
                // Reserve the app's bars inside the document.
                + "body{padding-top:" + cssTop + "px !important;"
                + "padding-bottom:" + cssBottom + "px !important;"
                + "box-sizing:border-box !important;"
                + "min-height:100dvh !important;"
                + "max-width:100vw !important;"
                + "overflow-x:hidden !important;}"
                + "html{max-width:100vw !important;overflow-x:hidden !important;}"
                // An app is not a web page: no accidental text selection and no long-press
                // copy callout. Inputs and editable areas keep normal behaviour.
                + (selectable ? "" : "*{-webkit-user-select:none !important;"
                + "user-select:none !important;-webkit-touch-callout:none !important;}"
                + "input,textarea,[contenteditable=\"" + "true" + "\"]{"
                + "-webkit-user-select:text !important;user-select:text !important;}")
                // Arena's own sidebar duplicates the app's bottom bar, so it is hidden.
                + (hideSidebar ? PAGE_SIDEBAR_CSS : "")
                // The page's own top header (optional).
                + (hideHeader ? PAGE_HEADER_CSS : "");

        String js = "(function(){"
                + "var old=document.getElementById('arena-app-insets');"
                + "if(old)old.remove();"
                + "var st=document.createElement('style');st.id='arena-app-insets';"
                + "st.textContent=" + quote(css) + ";"
                + "(document.head||document.documentElement).appendChild(st);"
                + "document.documentElement.style.height='100%';"
                + "document.body.style.height='auto';"
                + "var undo=[];"
                + "function hide(el){undo.push(el);"
                + "el.style.setProperty('display','none','important');}"
                + "function shown(el){var r=el.getBoundingClientRect();"
                + "return r.width>0&&r.height>0;}"
                + "function empty(el){return !el.querySelector("
                + "'main,textarea,form,[contenteditable=\"true\"]');}"
                + "function text(el){return ((el.innerText||'')+' '+"
                + "(el.getAttribute&&el.getAttribute('aria-label')||'')).toLowerCase();}"
                + (hideSidebar ? JS_SIDEBAR : "")
                + (hideHeader ? JS_HEADER : "")
                + "window.dispatchEvent(new Event('arena-insets'));"
                // Safety net: hiding something must never leave an empty page. If nothing
                // sizeable is visible afterwards, put everything back and drop the CSS.
                + "setTimeout(function(){"
                + "var kids=document.body?document.body.children:[];var ok=false;"
                + "for(var i=0;i<kids.length;i++){var r=kids[i].getBoundingClientRect();"
                + "if(r.height>40&&r.width>0){ok=true;break;}}"
                + "if(!ok){for(var j=0;j<undo.length;j++){"
                + "undo[j].style.removeProperty('display');}"
                + "var s2=document.getElementById('arena-app-insets');"
                + "if(s2)s2.style.setProperty('--arena-sidebar-hidden','0');"
                + "if(s2)s2.remove();}},700);"
                + "})();";
        web.evaluateJavascript(js, null);
    }

    /**
     * CSS that hides the page's own sidebar.
     *
     * Every selector carries a :has() guard: an element that contains a <main>, a
     * textarea, an editable area or a form is the app shell, never the sidebar, so it can
     * never match. Extra belt: the whole document body is exempt. If a WebView is too old
     * for :has(), the browser drops the rule rather than applying it, which fails safe
     * (nothing hidden) instead of blanking the page - that is what happened before.
     */
    private static final String GUARD = ":not(body):not(:has(main))"
            + ":not(:has(textarea)):not(:has(form)):not(:has([contenteditable=\"true\"]))";

    private static final String PAGE_SIDEBAR_CSS =
            "[class*=\"sidebar\" i]" + GUARD + ","
            + "[id*=\"sidebar\" i]" + GUARD + ","
            + "[data-testid*=\"sidebar\" i]" + GUARD + ","
            + "[aria-label*=\"sidebar\" i]" + GUARD + ","
            + "[class*=\"side-panel\" i]" + GUARD + ","
            + "aside" + GUARD + "{display:none !important;}";

    private static final String PAGE_HEADER_CSS =
            "header" + GUARD + ","
            + "[role=\"banner\"]" + GUARD + ","
            + "[class*=\"topbar\" i]" + GUARD + ","
            + "[class*=\"top-bar\" i]" + GUARD + ","
            + "[class*=\"app-header\" i]" + GUARD + ","
            + "[class*=\"main-header\" i]" + GUARD + ","
            + "[class*=\"page-header\" i]" + GUARD + ","
            + "[class*=\"navbar\" i]" + GUARD + ","
            + "[class*=\"nav-bar\" i]" + GUARD + "{display:none !important;}";

    /** Text-based fallback for a sidebar whose class names say nothing. */
    private static final String JS_SIDEBAR =
            "var cands=document.body?document.body.querySelectorAll('nav,aside,div'):[];"
            + "for(var i=0;i<cands.length;i++){var el=cands[i];"
            + "if(!shown(el)||!empty(el))continue;"
            + "var r=el.getBoundingClientRect();"
            + "if(r.width>420||r.height<0.5*r.width)continue;"
            + "var t=text(el);if(t.length>600)continue;"
            + "var words=['new chat','leaderboard','search','sign in'];"
            + "var hits=0;"
            + "for(var j=0;j<words.length;j++){if(t.indexOf(words[j])>=0)hits++;}"
            + "if(hits<3)continue;"
            + "if(el.querySelectorAll('a,button').length<3)continue;"
            + "hide(el);break;}";

    /** Text/size based fallback for the page's own top header. */
    private static final String JS_HEADER =
            "var tops=document.body?document.body.querySelectorAll('header,nav,div'):[];"
            + "for(var i=0;i<tops.length;i++){var el=tops[i];"
            + "if(!shown(el)||!empty(el))continue;"
            + "var r=el.getBoundingClientRect();"
            + "if(r.top>10)continue;"
            + "if(r.height<24||r.height>110)continue;"
            + "if(r.width<window.innerWidth*0.6)continue;"
            + "if(el.querySelectorAll('a,img,button').length<1)continue;"
            + "hide(el);break;}";

    /**
     * Drives arena's own sidebar (conversation history) instead of showing a native bar.
     *
     * The page is a web app whose sidebar is part of its own layout, so the honest way
     * to "open the sidebar" is to use its own control. Several candidate selectors are
     * tried because class names change; the last resort dispatches the events the app
     * listens to and asks arena's own toggle if one is exposed on window.
     */
    public static void openArenaSidebar(WebView web, boolean open) {
        if (web == null) return;
        String js = "(function(){"
                + "var open=" + (open ? "true" : "false") + ";"
                + "var sel=['[aria-label*=\"sidebar\" i]','[aria-label*=\"history\" i]',"
                + "'[data-testid*=\"sidebar\" i]','[data-testid*=\"history\" i]',"
                + "'button[title*=\"sidebar\" i]','button[title*=\"history\" i]',"
                + "'[aria-label*=\"menu\" i][aria-expanded]','header button:first-of-type'];"
                + "function visible(el){return !!el&&el.offsetParent!==null;}"
                + "for(var i=0;i<sel.length;i++){var el=document.querySelector(sel[i]);"
                + "if(visible(el)){"
                + "var expanded=el.getAttribute('aria-expanded');"
                + "if(expanded==='true'){ if(open){return true;} }"
                + "if(expanded==='false'){ if(!open){return true;} }"
                + "el.click();return true;}}"
                + "return false;})();";
        web.evaluateJavascript(js, null);
    }

    /** Starts a fresh conversation using arena's own "new chat" control. */
    public static void startNewChat(WebView web) {
        if (web == null) return;
        String js = "(function(){"
                + "var sel=['[aria-label*=\"new chat\" i]','[data-testid*=\"new-chat\" i]',"
                + "'a[href=\"/\"]','a[href$=\"/chat\"]','button[title*=\"new chat\" i]'];"
                + "for(var i=0;i<sel.length;i++){var el=document.querySelector(sel[i]);"
                + "if(el&&el.offsetParent!==null){el.click();return true;}}"
                + "if(location.pathname!=='/'){location.assign('/');return true;}"
                + "window.dispatchEvent(new KeyboardEvent('keydown',"
                + "{key:'k',ctrlKey:true,bubbles:true}));"
                + "return false;})();";
        web.evaluateJavascript(js, null);
    }

    /** Escapes text into a single-quoted JavaScript string literal. */
    private static String quote(String text) {
        StringBuilder out = new StringBuilder("'");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\': out.append("\\\\"); break;
                case '\'': out.append("\\'"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                default: out.append(ch);
            }
        }
        return out.append('\'').toString();
    }

    /** Applies the current chrome insets to every warm tab. */
    public void applyInsetsToAll(final int topPx, final int bottomPx) {
        for (int tab : views.keySet()) {
            final WebView w = views.get(tab);
            if (w == null) continue;
            // A tab created before its first load finishes needs the style applied after
            // the document exists, so also queue it for the next load.
            if (loaded.contains(tab)) {
                applyChromeInsets(w, topPx, bottomPx);
            }
        }
        lastTopInset = topPx;
        lastBottomInset = bottomPx;
    }

    /** Creates the tab's WebView on first use and returns it. */
    private WebView ensure(int tab) {
        WebView existing = views.get(tab);
        if (existing != null) return existing;

        WebView w = createWebView(tab);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        container.addView(w, lp);
        w.setVisibility(android.view.View.GONE);
        views.put(tab, w);

        // The client must be installed BEFORE the first load, otherwise the first
        // onPageStarted (and therefore the chrome insets) is missed.
        w.setWebViewClient(withLoadHook(w, host.clientFactory().create(), tab));
        // Chat tab loads immediately; others start as soon as they are created so the
        // network work overlaps with the user reading the current screen.
        w.loadUrl(urlFor(tab));
        trim();
        return w;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView createWebView(int tab) {
        WebView w = new WebView(activity);
        w.setBackgroundColor(AppUi.color(activity, R.color.bg));
        w.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        w.setLongClickable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        // Use the phone's real width (CSS px) instead of a zoomed-out desktop viewport,
        // so arena's own responsive layout is the one that runs and nothing is cropped
        // on the right. applyChromeInsets() then pins the document to that width.
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Speed: paint the last known copy from disk immediately instead of going to the
        // network for every asset on every launch, then let the page revalidate itself.
        s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        // Settings -> "Chrome user agent": the clean string is what stops arena's
        // bot check from showing "Verify you are human".
        s.setUserAgentString(Settings.chromeUserAgent(activity)
                ? AppChrome.userAgent(activity, s.getUserAgentString())
                : s.getUserAgentString());
        // A WebView is identified to bot-detection scripts by these two too, not just the
        // UA string: without a profile the page can report "in-app browser" and serve a
        // Turnstile challenge. Keep the DOM/storage behaviour, hide the wrapper.
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.addJavascriptInterface(new WebBridge(activity, w), "ArenaNative");
        w.setWebChromeClient(host.chromeFactory().create());
        w.setDownloadListener(host.downloadListener());
        return w;
    }

    /**
     * Wraps the activity's WebViewClient so the chrome insets are injected as soon as a
     * document exists (onPageStarted), and again once it finished loading. Everything
     * else is delegated unchanged.
     */
    private android.webkit.WebViewClient withLoadHook(final WebView web,
                                                      final android.webkit.WebViewClient delegate,
                                                      final int tab) {
        return new android.webkit.WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                delegate.onPageStarted(view, url, favicon);
                loaded.add(tab);
                applyChromeInsets(view, lastTopInset, lastBottomInset);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                delegate.onPageFinished(view, url);
                loaded.add(tab);
                applyChromeInsets(view, lastTopInset, lastBottomInset);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                                                    android.webkit.WebResourceRequest request) {
                return delegate.shouldOverrideUrlLoading(view, request);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return delegate.shouldOverrideUrlLoading(view, url);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                delegate.onReceivedError(view, request, error);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                delegate.onReceivedError(view, errorCode, description, failingUrl);
            }

            @Override
            public boolean onRenderProcessGone(WebView view,
                                               android.webkit.RenderProcessGoneDetail detail) {
                return delegate.onRenderProcessGone(view, detail);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                delegate.onPageCommitVisible(view, url);
                applyChromeInsets(view, lastTopInset, lastBottomInset);
            }
        };
    }

    /** Keeps at most MAX_WARM WebViews; drops the least recently used background tab. */
    private void trim() {
        while (views.size() > MAX_WARM) {
            Integer victim = null;
            for (Integer key : views.keySet()) {
                if (key != currentTab) {
                    victim = key;
                    break;
                }
            }
            if (victim == null) return;
            WebView w = views.remove(victim);
            if (w != null) {
                container.removeView(w);
                w.destroy();
            }
        }
    }

    /** Removes a dead WebView (renderer crash) so the next visit recreates it. */
    public void drop(WebView view) {
        loaded.clear();
        Integer key = null;
        for (java.util.Map.Entry<Integer, WebView> e : views.entrySet()) {
            if (e.getValue() == view) {
                key = e.getKey();
                break;
            }
        }
        if (key != null) {
            views.remove(key);
            loaded.remove(key);
        }
        if (view.getParent() != null) container.removeView(view);
        view.destroy();
    }

    public void destroyAll() {
        for (WebView w : views.values()) {
            container.removeView(w);
            w.removeJavascriptInterface("ArenaNative");
            w.destroy();
        }
        views.clear();
    }

    /** Wipes cookies/cache for the signed-in session and reloads the current tab. */
    public void clearAndReload() {
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.flush();
        for (WebView w : views.values()) {
            w.clearCache(true);
            w.clearHistory();
            w.clearFormData();
        }
        reloadCurrent();
    }

    /**
     * Re-applies the user-agent choice to every warm tab.
     *
     * A WebView keeps the UA it was created with, so flipping "Chrome user agent" in
     * Settings has to push the new string into the settings object and reload the page
     * for arena to see it.
     */
    public void applyUserAgentToAll() {
        for (WebView w : views.values()) {
            WebSettings s = w.getSettings();
            s.setUserAgentString(Settings.chromeUserAgent(activity)
                    ? AppChrome.userAgent(activity, s.getUserAgentString())
                    : AppChrome.defaultUserAgent(activity));
            w.reload();
        }
    }

    /** Reloads every warm tab (page-hiding rules changed, so a repaint is needed). */
    public void reloadAll() {
        for (WebView w : views.values()) w.reload();
    }

    public void reloadCurrent() {
        WebView w = current();
        if (w != null) w.reload();
    }

    /** Silent refresh used when returning to the app (keeps scroll position). */
    public void refreshInPlace(int tab) {
        WebView w = views.get(tab);
        if (w != null) w.evaluateJavascript("window.dispatchEvent(new Event('focus'));", null);
    }

    public boolean canGoBack() {
        WebView w = current();
        return w != null && w.canGoBack();
    }

    public void goBack() {
        WebView w = current();
        if (w != null) w.goBack();
    }

    public void loadInCurrent(String url) {
        WebView w = current();
        if (w != null) w.loadUrl(url);
    }
}
