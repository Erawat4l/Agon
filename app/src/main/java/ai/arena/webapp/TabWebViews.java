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

    /** Which tab a WebView belongs to, or -1 when it is not a warm tab. */
    public int tabOf(WebView view) {
        for (java.util.Map.Entry<Integer, WebView> e : views.entrySet()) {
            if (e.getValue() == view) return e.getKey();
        }
        return -1;
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
        currentTab = tab;
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
        String js = "(function(){try{localStorage.setItem('theme','" + mode + "');}"
                + "catch(e){}"
                + "var u=new URL(location.href);"
                + "if(u.searchParams.get('__theme')!=='" + mode + "'){"
                + "u.searchParams.set('__theme','" + mode + "');"
                + "location.replace(u.toString());}"
                + "document.documentElement.setAttribute('data-theme','" + mode + "');"
                + "document.documentElement.style.colorScheme='" + mode + "';})();";
        web.evaluateJavascript(js, null);
    }

    /** Applies the current appearance to every live tab. */
    public void applyThemeToAll(boolean dark) {
        for (WebView w : views.values()) applyTheme(w, dark);
    }

    /**
     * The app paints its own top bar and bottom nav around the page. Arena positions a
     * few things with position:fixed (composer hint, floating menus), so without an
     * inset they slide under our chrome ("the page spreads out of the UI").
     * This exposes the reserved heights as CSS variables and pads the document, so the
     * page lays itself out inside the app frame.
     */
    public static void applyChromeInsets(WebView web, int topPx, int bottomPx) {
        if (web == null) return;
        // WebView CSS px are density scaled, so convert the pixel insets with the real
        // density rather than assuming 2x.
        float density = web.getResources().getDisplayMetrics().density;
        int cssTop = Math.round(Math.max(0, topPx) / density);
        int cssBottom = Math.round(Math.max(0, bottomPx) / density);
        String js = "(function(){"
                + "var id='arena-app-insets';var old=document.getElementById(id);"
                + "if(old) old.remove();"
                + "var s=document.createElement('style');s.id=id;"
                + "s.textContent="
                + "':root{--arena-top-inset:" + cssTop + "px;"
                + "--arena-bottom-inset:" + cssBottom + "px;}'"
                + "+'body{padding-top:" + cssTop + "px !important;"
                + "padding-bottom:" + cssBottom + "px !important;"
                + "box-sizing:border-box !important;}'"
                // Keep the page from growing past the viewport: arena sizes some
                // wrappers with 100vh, which would overflow by the insets.
                + "+'html,body{min-height:100% !important;"
                + "overflow-y:auto !important;}'"
                + "+(document.documentElement.style.setProperty('--arena-top-inset',"
                + "'" + cssTop + "px'),"
                + "document.documentElement.style.setProperty('--arena-bottom-inset',"
                + "'" + cssBottom + "px'));"
                + "window.dispatchEvent(new Event('arena-insets'));"
                + "})();";
        web.evaluateJavascript(js, null);
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

    /** Records that a tab's document exists, and re-applies the pending insets. */
    void markLoaded(int tab, WebView web) {
        loaded.add(tab);
        if (lastTopInset > 0 || lastBottomInset > 0) {
            applyChromeInsets(web, lastTopInset, lastBottomInset);
        }
    }

    private WebView ensure(int tab) {
        WebView existing = views.get(tab);
        if (existing != null) return existing;

        WebView w = createWebView(tab);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        container.addView(w, lp);
        w.setVisibility(android.view.View.GONE);
        views.put(tab, w);

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
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Cache aggressively: repeat visits paint from disk, then refresh in place.
        s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setUserAgentString(AppChrome.userAgent(activity, s.getUserAgentString()));

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.addJavascriptInterface(new WebBridge(activity, w), "ArenaNative");
        w.setWebViewClient(host.clientFactory().create());
        w.setWebChromeClient(host.chromeFactory().create());
        w.setDownloadListener(host.downloadListener());
        return w;
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
