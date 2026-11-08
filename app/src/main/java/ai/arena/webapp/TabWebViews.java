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

import java.util.HashMap;
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


    /**
     * Extra arena windows start here.
     *
     * A "window" is a chat the user opened from More -> New window: a fully independent
     * WebView with its own page, its own scroll position and its own conversation, kept
     * alive beside the built-in tabs so switching between two chats is a bringToFront()
     * and not a reload. The ids are above every built-in tab so the nav, the URL table and
     * the tab bookkeeping cannot confuse one with the other.
     */
    public static final int CHAT_WINDOW_BASE = 1000;

    /** How many extra windows may be open at once (each one is a Chromium renderer). */
    public static final int MAX_WINDOWS = 4;

    /** True for an id that is one of the user's own extra windows. */
    public static boolean isWindow(int id) {
        return id >= CHAT_WINDOW_BASE;
    }

    private final Activity activity;
    private final Host host;
    private final FrameLayout container;
    private final Map<Integer, WebView> views = new LinkedHashMap<>();
    /**
     * The arena URL of a tab that is showing a saved copy instead.
     *
     * A page put back from OfflinePages is loaded from file://, so getUrl() on that tab
     * answers with a path in the cache directory. That path must never reach the arrows,
     * the reload button, the history sheet or the tab list - so it is remembered here and
     * everything that asks "where is this tab?" gets the real page back.
     */
    private final Map<Integer, String> restoredUrl = new HashMap<>();
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

    /** Every tab that currently has a live WebView. */
    public java.util.List<Integer> warmTabs() {
        return new java.util.ArrayList<>(views.keySet());
    }

    /**
     * The extra windows the user has opened, in the order they were opened.
     *
     * A window built by preload() is deliberately not in here: it exists so that the first
     * New window is instant, but listing it in More before anyone asked for it would be a
     * menu entry for something the user never created.
     */
    public java.util.List<Integer> windows() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (Integer id : views.keySet()) {
            if (isWindow(id) && usedWindows.contains(id)) out.add(id);
        }
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Opens a new conversation tab and returns its id, or -1 when there is no room.
     *
     * A new tab is a *new chat*, not a copy of the chat on screen. Two things do that:
     *
     *  1. it loads the arena root, never the current tab's URL - copying the current URL is
     *     what made the previous build open the same conversation twice;
     *  2. once that document exists, {@link #startNewChat} is run in it. Arena keeps the
     *     conversation in its route, so the root alone is not always a fresh chat (a client
     *     router can restore the last one); pressing arena's own "new chat" affordance -
     *     or moving the route, when that button is hidden with the sidebar - is what
     *     guarantees the tab opens empty.
     *
     * "New tab" and "new chat" are not the same thing, which is the distinction the strip
     * gets wrong when it is treated as a conversation list only. A new TAB is a new
     * browser tab: it starts at arena's own front page and then the user drives it - that
     * page's history button, its search, a conversation from the list. It is not asked for
     * a new chat, because asking for one moves the route to the bare root and takes the
     * history button away with it (that button belongs to a conversation). A new chat is a
     * deliberate request, made from the nav's three taps or Ctrl/Cmd+N, and it opens in the
     * tab on screen.
     *
     * The tab starts loading immediately, so by the time the user looks at it there is
     * nothing left to fetch.
     */
    public int openWindow() {
        // A window that is already warm (preload() makes one) is handed over instead of
        // building a second one: the point is a page that is already loaded. windows()
        // lists only the ones the user opened, so a preloaded window would never be found
        // there - the warm views are what to look at.
        java.util.List<Integer> warm = new java.util.ArrayList<>(views.keySet());
        java.util.Collections.sort(warm);
        for (Integer id : warm) {
            if (isWindow(id) && !usedWindows.contains(id)) {
                usedWindows.add(id);
                return id;
            }
        }
        if (windows().size() >= MAX_WINDOWS) return -1;
        int id = CHAT_WINDOW_BASE;
        while (views.containsKey(id)) id++;
        // Created and loading now, while the user is still in More: by the time they
        // switch to it there is nothing left to fetch. ensure() starts the load.
        ensure(id);
        usedWindows.add(id);
        return id;
    }

    /**
     * Makes a tab warm without putting it on screen, and asks it for a fresh conversation.
     *
     * Used when the app wants "another chat tab" to exist in the background - the strip
     * then lists it, and switching to it costs a visibility change.
     */
    public int openTabInBackground() {
        // Loads immediately (ensure() did that) and stays on arena's front page: a new tab
        // is a browser tab, not a new conversation. See openWindow().
        return openWindow();
    }

    /** Windows the user has actually opened (as opposed to one built ahead of time). */
    private final java.util.Set<Integer> usedWindows = new java.util.HashSet<>();

    /** Closes an extra window. The one on screen is never left as the current page. */
    public boolean closeWindow(int id) {
        if (!isWindow(id)) return false;
        WebView w = views.get(id);
        if (w == null) return false;
        // Which tab takes over is decided BEFORE the closed one is dropped: its place in the
        // row is what says where the user should land.
        final boolean wasCurrent = currentTab == id;
        final int next = wasCurrent ? neighbourAfterClosing(id) : currentTab;
        views.remove(id);
        loaded.remove(id);
        reportedTitles.remove(id);
        container.removeView(w);
        w.destroy();
        if (wasCurrent) show(next);
        return true;
    }

    /**
     * The tab that takes the screen when `closing` goes away.
     *
     * The one immediately after it in the row, or the one before it when it was last - a
     * browser's rule, and the only one that does not feel like a jump. The chat tab is the
     * leftmost tab, so closing an extra tab lands on the tab to its left, and closing a
     * window that is not last lands on the one to its right. It is never "whatever happened
     * to be first", which is what made closing the current tab look like it had closed a
     * different one.
     */
    public int neighbourAfterClosing(int closing) {
        java.util.List<Integer> row = rowWithChat();
        int index = row.indexOf(closing);
        if (index < 0) return AppUi.TAB_CHAT;
        if (index + 1 < row.size()) return row.get(index + 1);
        if (index - 1 >= 0) return row.get(index - 1);
        return AppUi.TAB_CHAT;
    }

    /** Every tab in strip order, chat first - the same order MainActivity.openTabs() uses. */
    private java.util.List<Integer> rowWithChat() {
        java.util.List<Integer> row = new java.util.ArrayList<>();
        row.add(AppUi.TAB_CHAT);
        row.addAll(windows());
        return row;
    }

    /**
     * The tab the strip's arrows step to, or -1 when there is only one tab.
     *
     * The arrows and Ctrl/Cmd+Tab are the same gesture, so they share this: both move to the
     * next tab in the row and wrap round at the end. They used to work off `pageId()`, which
     * answers "the chat tab" whenever no window is in front - so with one extra tab open and
     * the chat on screen, `<` did not move at all, which is what "same for arrow" was.
     */
    public int steppedTab(int onScreen, int direction) {
        java.util.List<Integer> row = rowWithChat();
        if (row.size() < 2) return -1;
        int index = row.indexOf(onScreen);
        if (index < 0) index = direction > 0 ? -1 : 0;
        return row.get(((index + direction) % row.size() + row.size()) % row.size());
    }

    /** Conversation names reported by the pages themselves, keyed by tab id. */
    private final Map<Integer, String> reportedTitles = new java.util.HashMap<>();

    /** Called by the title bridge; keeps the latest name for a tab. */
    void rememberTitle(int tab, String name) {
        if (tab < 0 || name == null) return;
        String clean = name.trim();
        if (clean.isEmpty()) return;
        reportedTitles.put(tab, clean);
        if (activity instanceof MainActivity) ((MainActivity) activity).onTabTitleChanged();
    }

    /** The name the page reported for a tab, or null. */
    public String conversationTitle(int tab) {
        return reportedTitles.get(tab);
    }

    /** The page title arena gave a window, for the list in More. */
    public String windowTitle(int id) {
        WebView w = views.get(id);
        if (w == null) return null;
        String title = w.getTitle();
        if (title == null || title.trim().isEmpty()) return null;
        // "arena.ai" is what an empty document reports before the app renders: not a name.
        if (title.trim().equalsIgnoreCase("arena.ai")
                || title.trim().equalsIgnoreCase("arena")) return null;
        return title.trim();
    }

    /** The tab a WebView belongs to, or -1 when it is not one of ours (a popup). */
    public int tabOf(WebView view) {
        if (view == null) return -1;
        for (java.util.Map.Entry<Integer, WebView> entry : views.entrySet()) {
            if (entry.getValue() == view) return entry.getKey();
        }
        return -1;
    }

    /** Every warm WebView, current tab included. */
    public java.util.Collection<WebView> all() {
        return new java.util.ArrayList<>(views.values());
    }

    /** The arena URL a tab is really on, even while it shows a saved copy. */
    public String realUrl(int tab) {
        WebView w = views.get(tab);
        String live = w == null ? null : w.getUrl();
        if (live != null && !live.startsWith("file://")) return live;
        String remembered = restoredUrl.get(tab);
        return remembered != null ? remembered : urlFor(tab);
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
        applyRendererPriority();
        return target;
    }

    /**
     * Only the tab on screen keeps the high renderer priority; everything else is left to
     * the system. Called on every tab switch, because the priorities of hidden tabs are
     * exactly what was wrong before.
     */
    private void applyRendererPriority() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        for (Map.Entry<Integer, WebView> entry : views.entrySet()) {
            WebView web = entry.getValue();
            if (web == null) continue;
            boolean showing = entry.getKey() == currentTab;
            try {
                web.setRendererPriorityPolicy(
                        showing ? WebView.RENDERER_PRIORITY_IMPORTANT
                                : WebView.RENDERER_PRIORITY_BOUND,
                        true);
            } catch (RuntimeException ignored) {
                // a destroyed renderer must not take the tab switch with it
            }
        }
    }

    /**
     * Builds the other tabs' WebViews in the background so the first tap is not a
     * cold start. ensure() keeps them GONE until the user asks for them.
     *
     * Only when the device has room for it. Each WebView is a separate Chromium renderer
     * process, and two of them loaded before the user has tapped anything is most of why
     * the app felt heavy on a phone that is already short of memory. A low-RAM device
     * (ActivityManager.isLowRamDevice()) gets its tabs built on demand instead: a slightly
     * slower first tap is a much better trade than a laggy chat.
     */
    public void preload() {
        // Every page the app can show is built and loaded at launch, not on first tap -
        // "leaderboard and search load when I click" is what building them lazily looked
        // like. Each is a WebView, not a new process: Android decides how many renderers
        // to give out, and this app's tabs are cheap (no video, no canvas).
        //
        // They are built one after another with a short gap rather than all at once, so
        // the tab on screen keeps the CPU for its first paint. The gap is small because
        // the whole point is that they are ready by the time the user taps.
        final int[] pages = {AppUi.TAB_LEADERBOARD, AppUi.TAB_SEARCH};
        for (int i = 0; i < pages.length; i++) {
            final int tab = pages[i];
            main.postDelayed(() -> {
                if (!views.containsKey(tab)) ensure(tab);
            }, 250L + 250L * i);
        }
        // ...and one conversation tab, so the strip has a second chip the moment it is
        // asked for one, already loaded. It stays out of the strip until it is used.
        main.postDelayed(() -> {
            if (windows().isEmpty()) ensure(CHAT_WINDOW_BASE);
        }, 250L + 250L * pages.length);
    }

    /** True when Android itself has decided this phone has little memory to give. */
    private boolean isLowRamDevice() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    activity.getSystemService(Activity.ACTIVITY_SERVICE);
            return am != null && am.isLowRamDevice();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * What the page has selected right now, as a JSON string for the callback.
     *
     * Asked once when a hold fires: if the user had already selected a word or a run of
     * text, that selection is what they meant to copy - replacing it with the whole
     * paragraph under the finger is what made a hold feel like it grabbed everything.
     */
    private static final String SELECTION_PROBE =
            "(function(){try{return String(window.getSelection()||'');}catch(e){return '';}})();";

    /**
     * Copies the text under a point in the page, from the app side.
     *
     * A hold is timed in SelectionSafeWebView (Java), not in the page, so the copy does not
     * depend on the page's script having been installed or on its timers running. The page
     * reports what it found through AgonCopyNative.text; `x` and `y` are device pixels,
     * because that is what a MotionEvent carries.
     */
    public void copyUnder(String pointScript) {
        WebView w = current();
        if (w == null) return;
        // The page answers by itself when, and only when, it finds TEXT under the point
        // (AgonCopyNative.text). A press on a button, a link, a sidebar row, the app's own
        // nav or any control drawn over the page is reported as no text, so nothing is
        // copied. It used to top up with whatever was selected 260 ms later, which turned a
        // single tap on a control into a copy of the reply behind it.
        try {
            w.evaluateJavascript(pointScript, null);
        } catch (RuntimeException ignored) {
            // a destroyed view has nothing to say
        }
    }

    /**
     * The script form of a hold at a point.
     *
     * The MotionEvent carries device pixels and the page hit-tests in CSS pixels, so the
     * scale is passed through rather than assumed: at zoom 1 they are equal and at any other
     * zoom they are not, and guessing wrong copies the text under where the finger was not.
     */
    private static String pointJs(int xPx, int yPx) {
        return "(function(){try{"
                + "if(window.AgonCopy&&AgonCopy.under){"
                + "AgonCopy.under(" + xPx + "," + yPx + ",window.devicePixelRatio||1);"
                + "}}catch(e){}})();";
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

        if (cssTop <= 0 && cssBottom <= 0) {
            // The WebView is already exactly the free area between the app's bars, so there
            // is nothing to reserve and nothing to lift. Removing any earlier rule and
            // leaving arena's page alone is the only safe thing to do here: an injected
            // margin/height on html or body at this point can only fight the page's own
            // layout, and that is what made it look mashed together.
            //
            // The two *element-level* rules still have to run here, and this is why they
            // are separate from the frame CSS: they hide or fix things inside the page, not
            // the size of the page. Skipping them in this branch is exactly the bug that
            // kept the sidebar on screen for three builds - the app stopped reserving space
            // for its own bar, so applyChromeInsets() took this branch and never injected
            // the sidebar rule at all, no matter what Settings said.
            String clear = "(function(){var a=document.getElementById('arena-app-insets');"
                    + "if(a)a.remove();"
                    + "var h=document.documentElement,b=document.body;"
                    + "if(h){h.style.removeProperty('margin-top');"
                    + "h.style.removeProperty('margin-bottom');"
                    + "h.style.removeProperty('height');}"
                    + "if(b){b.style.removeProperty('padding-bottom');"
                    + "b.style.removeProperty('min-height');"
                    + "b.style.removeProperty('height');}"
                    + "})();"
                    // The selection rules are not frame CSS and must not be dropped with
                    // it: this branch used to leave the page selectable no matter what
                    // Settings said (see selectCss).
                    + "(function(){"
                    + "var st=document.getElementById('arena-select-policy');"
                    + "if(st)st.remove();"
                    + "st=document.createElement('style');st.id='arena-select-policy';"
                    // Three taps unlock selection from the page, and that unlock has to
                    // survive the next injection - applyChromeInsets() runs on every page
                    // finish and every tab switch, so without this flag the unlock lasted
                    // about a second ("i cant select" straight after unlocking).
                    // One decision, made in the page: the CSS is written only while the
                    // page is locked. An unlocked page gets an empty rule instead of a
                    // re-lock one injection later.
                    + "st.textContent=window.__agonSelectUnlocked?'':"
                    + "(" + quote(selectCss(selectable)) + ");"
                    + "if(st.textContent)(document.head||document.documentElement)"
                    + ".appendChild(st);"
                    // The lock checks the unlock flag itself; see selectGuard().
                    + selectGuard(selectable)
                    + "})();"
                    // Arena's own menus still have to fit the window...
                    + "(function(){" + JS_POPUP_FIT + "})();"
                    // ...and the page's own panel still has to go, if that is what was asked.
                    + (hideSidebar || hideHeader ? "(function(){try{"
                    + "var undo=[];"
                    // Nothing is hidden this pass, so anything an earlier pass hid is given
                    // back. This is the line that makes turning the switch OFF work:
                    // hide(el) writes an inline display:none, and an inline style outlives
                    // both the script and the setting - which is why "i disabled the button
                    // but it still hides that". It is only ever emitted while nothing is
                    // meant to be hidden, so a pass that is hiding cannot make the panel
                    // blink by showing it for a frame first.
                    + (hideSidebar || hideHeader ? "" : unwrapFromHide())
                    + "function hide(el){undo.push(el);"
                    + "el.style.setProperty('display','none','important');"
                    + "el.setAttribute('data-agon-hid','1');}"
                    + "function shown(el){var r=el.getBoundingClientRect();"
                    + "return r.width>0&&r.height>0;}"
                    + "function empty(el){return !el.querySelector("
                    + "'main,textarea,form,[contenteditable=\"true\"]');}"
                    + "function text(el){return ((el.innerText||'')+' '+"
                    + "(el.getAttribute&&el.getAttribute('aria-label')||'')).toLowerCase();}"
                    + watchHelpers(hideSidebar, hideHeader)
                    + (hideSidebar ? JS_SIDEBAR : "")
                    + (hideHeader ? JS_HEADER : "")
                    // ...and keep looking. The page is a single-page app: at the moment
                    // this runs the sidebar is often not in the DOM yet, so a one-shot pass
                    // hid nothing and the panel appeared a moment later. Toggling the
                    // setting re-ran this later in the page's life, which is exactly why
                    // that worked and opening the app did not.
                    + "watchForPanel();"
                    + "}catch(e){}})();" : "");
            web.evaluateJavascript(clear, null);
            return;
        }

        String css = buildFrameCss(cssTop, cssBottom, hideSidebar, hideHeader,
                selectable);

        String js = "(function(){"
                + "var old=document.getElementById('arena-app-insets');"
                + "if(old)old.remove();"
                + "var st=document.createElement('style');st.id='arena-app-insets';"
                + "st.textContent=" + quote(css) + ";"
                + "(document.head||document.documentElement).appendChild(st);"
                + "document.documentElement.style.height='100%';"
                + "document.body.style.height='auto';"
                + "var undo=[];"
                + (hideSidebar || hideHeader ? "" : unwrapFromHide())
                + "function hide(el){undo.push(el);"
                + "el.style.setProperty('display','none','important');"
                + "el.setAttribute('data-agon-hid','1');}"
                + "function shown(el){var r=el.getBoundingClientRect();"
                + "return r.width>0&&r.height>0;}"
                + "function empty(el){return !el.querySelector("
                + "'main,textarea,form,[contenteditable=\"true\"]');}"
                + "function text(el){return ((el.innerText||'')+' '+"
                + "(el.getAttribute&&el.getAttribute('aria-label')||'')).toLowerCase();}"
                + watchHelpers(hideSidebar, hideHeader)
                + (hideSidebar ? JS_SIDEBAR : "")
                + (hideHeader ? JS_HEADER : "")
                // Same watcher as the no-insets branch: the panel is drawn after the page
                // starts, so one pass here hides nothing on a cold start.
                + "watchForPanel();"
                // Arena's composer and floating controls are `position:fixed`, so a margin on
                // html/body cannot move them: they were pinned to the bottom of the *window*
                // and the app's bar was drawn over them - the squashed look in the
                // screenshots. Measure them and lift them by exactly the bar's height, and
                // shrink anything fixed that would otherwise be cut off at the top.
                + "function liftFixed(){"
                // Nothing to lift: the app's bar is a real row below the page now, so no
                // part of it overlaps the document. Returning here keeps arena's own fixed
                // elements completely untouched - a transform on them (even translateY(0))
                // creates a containing block and re-anchors anything fixed inside them.
                + "if(" + cssBottom + "<=0)return;"
                + "var els=document.querySelectorAll('body *');"
                + "for(var i=0;i<els.length;i++){var el=els[i];"
                + "var cs=getComputedStyle(el);"
                + "if(cs.position!=='fixed')continue;"
                + "var r=el.getBoundingClientRect();"
                + "if(r.width<1||r.height<1)continue;"
                // A panel parked down the SIDE of the page (a chat's files/workspace
                // rail) never reaches the bottom edge, so the branch below never ran
                // for it and it was left as tall as the unscrolled window: the footer
                // controls ended up under the app's bar and the list looked empty.
                // It needs the cap, not the lift - and the cap is idempotent, so
                // re-running this every 600ms cannot shrink it further.
                + "if(r.top<window.innerHeight*0.35||"
                + "r.height>window.innerHeight-" + cssTop + "-8){"
                + "el.style.setProperty('max-height','calc(100dvh - "
                + (cssTop + cssBottom) + "px)','important');}"
                + "if(r.bottom>=window.innerHeight-" + cssBottom + "-8){"
                + "el.style.setProperty('transform','translateY(-' + " + cssBottom
                + " + 'px)','important');"
                + "el.style.setProperty('max-height','calc(100dvh - " + (cssTop + cssBottom)
                + "px)','important');"
                + "}}}"
                + selectGuard(selectable)
                + "liftFixed();"
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
                // Arena re-renders as it navigates; a fixed element that appears later needs
                // the same lift, so re-run it a few times - cheap, and never more than a
                // handful of getComputedStyle calls per pass.
                + "var passes=0;var t=setInterval(function(){"
                + "if(++passes>12){clearInterval(t);return;}"
                + "if(document.hidden)return;"
                + "liftFixed();},600);"
                + "})();";
        web.evaluateJavascript(js, null);
    }


    /**
     * The CSS that makes a web app fit inside the app frame.
     *
     * What it fixes, from the on-device screenshot: the page's own floating nav sat on top
     * of the bottom four suggestion cards ("the web should start below, not mix there").
     * Padding the body alone does not constrain anything - a fixed or sticky bar inside the
     * document is positioned against the viewport, not the padded body - so three things
     * happen together:
     *
     *  1. the *document itself* is shrunk to the free area between the app's bars
     *     (html gets the insets as margins), which is what actually keeps a fixed bar inside
     *     the frame;
     *  2. the body reserves the same space and cannot scroll past it, so the last card is
     *     reachable instead of hidden under the nav;
     *  3. anything the page anchors to the bottom of the viewport is lifted by the inset.
     *
     * The variables stay in place, so a page (or a future script) can read the same numbers.
     */
    private static String buildFrameCss(int cssTop, int cssBottom, boolean hideSidebar,
                                        boolean hideHeader, boolean selectable) {
        return ":root{--arena-top-inset:" + cssTop + "px;"
                + "--arena-bottom-inset:" + cssBottom + "px;"
                + "--arena-app-height:100dvh;}"
                // 1. the document is the free area between the bars, not the whole screen
                + "html{margin-top:" + cssTop + "px !important;"
                + "margin-bottom:" + cssBottom + "px !important;"
                + "max-width:100vw !important;overflow-x:hidden !important;"
                + "box-sizing:border-box !important;}"
                // 2. the body fills that area and never grows past it
                + "body{padding-top:0 !important;padding-bottom:0 !important;"
                + "box-sizing:border-box !important;"
                + "min-height:calc(100dvh - " + (cssTop + cssBottom) + "px) !important;"
                + "max-width:100vw !important;overflow-x:hidden !important;}"
                // 3. NOTHING here may touch the page's own fixed elements.
                //
                // This used to be a rule matching any descendant whose inline style said
                // "position: fixed" and giving it a bottom margin. That was wrong twice
                // over. A bottom margin means nothing to a fixed element (it is not laid
                // out by margins), so it never fixed anything - and on arena's own side
                // panels, which are fixed columns whose flex list fills the remaining
                // height, that margin was *inside* the panel: the panel kept its size, its
                // content was squeezed into what was left, and the result is a panel that
                // opens empty and answers no taps - the files/workspace icon at the top
                // right of a chat. liftFixed() below is what actually moves fixed elements,
                // and it only ever moves chrome pinned to the very bottom edge.
                // Selection is handled by selectCss()/selectGuard(), which run on both
                // branches; keeping a second copy here is what made it look fixed when it
                // was only ever written on this branch.
                + ""
                // Arena's own panel is NOT hidden with CSS any more: an attribute match on
                // someone else's markup hid content containers and made the page look
                // mashed. If the user asks for it, JS_SIDEBAR removes one element that is
                // provably a nav panel (see there).
                // The page's own top header (optional).
                + (hideHeader ? PAGE_HEADER_CSS : "");
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

    /**
     * Keeps arena's popovers inside the visible area.
     *
     * Its model menu ("Auto", "Battle", "Web Search", ...) is positioned against the
     * composer and opens upward. On a short or landscape window - a phone in landscape, a
     * small resized desktop window - it is taller than the space above the composer, so the
     * top of the list is cut off by the window edge and those choices cannot be picked:
     * "can't see all the options".
     *
     * A popup that has already been positioned can be corrected without knowing anything
     * about arena's markup: if its own rect is clipped by the top or bottom of the viewport,
     * cap its height to the room that is actually left on ONE side and scroll inside it. The
     * first option stays reachable and nothing is hidden behind the edge.
     *
     * Deliberately narrow, so it can never touch the page itself:
     *  - only elements that are position:fixed or absolute (normal content is not a popup);
     *  - never html/body/main/form/input/textarea, and never anything holding the composer;
     *  - only when the element is genuinely clipped AND scrollable content overflows it;
     *  - only when it is list-like (several buttons/options/links inside);
     *  - the original inline height/max-height/overflow are remembered and restored as soon
     *    as the element is no longer clipped, so nothing is left permanently stretched.
     */
    private static final String JS_POPUP_FIT =
            "var order=[];"
            + "var fitCss='[data-agon-fitted]{box-sizing:border-box !important;max-width:calc(100vw - 12px) !important;overscroll-behavior:contain;}';"
            + "function ensureFitCss(){"
            + "if(document.getElementById('arena-popup-fit'))return;"
            + "var st=document.createElement('style');st.id='arena-popup-fit';"
            + "st.textContent=fitCss;"
            + "(document.head||document.documentElement).appendChild(st);}"
            + "function markBox(el){"
            + "var sv=el.__agonFit;if(!sv)return;"
            + "var r=el.getBoundingClientRect();sv.fw=r.width;sv.fh=r.height;}"
            + "function remember(el){"
            + "if(el.__agonFit)return;"
            + "el.__agonFit={maxHeight:el.style.maxHeight,height:el.style.height,"
            + "overflowY:el.style.overflowY,left:el.style.left,right:el.style.right,"
            + "width:el.style.width,transform:el.style.transform,fw:0,fh:0,"
            + "widthFit:false,heightFit:false};"
            + "order.push(el);"
            + "ensureFitCss();"
            + "el.setAttribute('data-agon-fitted','1');}"
            + "function forget(el){"
            + "var sv=el.__agonFit;"
            + "el.removeAttribute('data-agon-fitted');"
            + "el.style.removeProperty('max-width');el.style.removeProperty('overflow-x');"
            + "el.style.removeProperty('overscroll-behavior');"
            + "if(sv){el.style.maxHeight=sv.maxHeight;el.style.height=sv.height;"
            + "el.style.overflowY=sv.overflowY;el.style.left=sv.left;el.style.right=sv.right;"
            + "el.style.width=sv.width;el.style.transform=sv.transform;"
            + "try{delete el.__agonFit;}catch(e){el.__agonFit=null;}}}"
            + "function adopt(){"
            + "var seen=document.querySelectorAll('[data-agon-fitted]');"
            + "for(var i=0;i<seen.length;i++){"
            + "var el=seen[i];"
            + "if(el.__agonFit){if(order.indexOf(el)<0)order.push(el);}"
            + "else{"
            + "el.removeAttribute('data-agon-fitted');"
            + "el.style.removeProperty('max-width');"
            + "el.style.removeProperty('overflow-x');}}"
            + "var keep=[];"
            + "for(var j=0;j<order.length;j++)if(order[j].__agonFit)keep.push(order[j]);"
            + "order=keep;}"
            + "function forgetAll(){while(order.length)forget(order.shift());"
            + "var seen=document.querySelectorAll('[data-agon-fitted]');"
            + "for(var i=0;i<seen.length;i++)seen[i].removeAttribute('data-agon-fitted');}"
            + "function dropStale(){"
            + "adopt();"
            + "var keep=[];"
            + "for(var i=0;i<order.length;i++){"
            + "var el=order[i],sv=el.__agonFit;"
            + "if(!sv)continue;"
            + "var r=el.getBoundingClientRect();"
            + "if(sv.fw&&(Math.abs(r.width-sv.fw)>2||Math.abs(r.height-sv.fh)>2)){"
            + "forget(el);continue;}"
            + "keep.push(el);}"
            + "order=keep;"
            + "if(order.length>24)forget(order.shift());}"
            + "function safeToTouch(el){"
            + "if(el===document.body||el===document.documentElement)return false;"
            + "var cs=getComputedStyle(el);"
            + "if(cs.position!=='fixed'&&cs.position!=='absolute')return false;"
            + "if(el.querySelector('main,form,textarea,[contenteditable=\"true\"]'))return false;"
            + "return true;}"
            + "function pinInside(el,vw){"
            + "var r=el.getBoundingClientRect();"
            + "if(r.left>=-2&&r.right<=vw+2)return;"
            + "el.style.setProperty('left','6px','important');"
            + "el.style.setProperty('right','6px','important');"
            + "el.style.setProperty('width','auto','important');"
            + "el.style.setProperty('transform','none','important');}"
            + "function fitWidth(el){"
            + "var vw=window.innerWidth||document.documentElement.clientWidth;"
            + "var r=el.getBoundingClientRect();"
            + "if(r.width<120||r.height<80)return false;"
            + "var wide=r.left<-2||r.right>vw+2;"
            + "if(!wide){"
            + "if(el.__agonFit&&el.__agonFit.widthFit){"
            + "el.__agonFit.widthFit=false;"
            + "el.style.removeProperty('max-width');"
            + "el.style.removeProperty('overflow-x');"
            + "if(!el.__agonFit.heightFit)forget(el);}"
            + "return false;}"
            + "if(!el.__agonFit){"
            + "if(!safeToTouch(el))return false;"
            + "remember(el);}"
            + "el.__agonFit.widthFit=true;"
            + "el.style.setProperty('max-width',(vw-12)+'px','important');"
            + "el.style.setProperty('overflow-x','auto','important');"
            + "pinInside(el,vw);"
            + "markBox(el);"
            + "return true;}"
            + "function fitHeight(el){"
            + "var vh=window.innerHeight||document.documentElement.clientHeight;"
            + "var r=el.getBoundingClientRect();"
            + "if(r.height>=vh-24)return false;"
            + "if(r.height>vh*0.5)return false;"
            + "var overTop=r.top<4,overBottom=r.bottom>vh-4;"
            + "if(!overTop&&!overBottom)return false;"
            + "if(!safeToTouch(el))return false;"
            + "var room=overTop?Math.floor(r.bottom-8):Math.floor(vh-r.top-8);"
            + "if(room<120)return false;"
            + "if(!el.__agonFit){"
            + "var opts=el.querySelectorAll('button,[role=\"option\"],[role=\"menuitem\"],li,a');"
            + "if(opts.length<3)return false;"
            + "remember(el);}"
            + "el.__agonFit.heightFit=true;"
            + "el.style.maxHeight=room+'px';"
            + "el.style.overflowY='auto';"
            + "el.style.setProperty('overscroll-behavior','contain');"
            + "markBox(el);"
            + "return true;}"
            + "function fixPopups(){"
            + "dropStale();"
            + "var all=document.querySelectorAll('div,ul,section,article');"
            + "for(var i=0;i<all.length;i++)fitHeight(all[i]);"
            + "var nodes=document.querySelectorAll('div,ul,section,article,aside,nav,header,footer');"
            + "for(var j=0;j<nodes.length;j++){"
            + "var n=nodes[j];"
            + "if(n===document.body||n===document.documentElement)continue;"
            + "var pos=getComputedStyle(n).position;"
            + "if(pos!=='fixed'&&pos!=='absolute')continue;"
            + "var b=n.getBoundingClientRect();"
            + "if(b.width<120||b.height<80)continue;"
            + "fitWidth(n);}}"
            + "fixPopups();"
            + "document.addEventListener('click',function(){"
            + "setTimeout(fixPopups,0);setTimeout(fixPopups,120);},true);"
            + "window.addEventListener('resize',function(){forgetAll();fixPopups();},true);"
            + "if(window.visualViewport)window.visualViewport.addEventListener('resize',"
            + "function(){forgetAll();fixPopups();});"
            + "var fitPasses=0;var fitTimer=setInterval(function(){"
            + "if(++fitPasses>15){clearInterval(fitTimer);return;}"
            + "if(document.hidden)return;fixPopups();},400);";

    /**
     * The page-side half of the Enter key.
     *
     * Why this exists, and why it is in the page rather than in the activity: on a phone
     * with a hardware keyboard, Enter inside arena's composer does not send. Escape does
     * work, and Escape is handled by the page itself (pressEscape() proves that), so the
     * key arrives at the document - it is the page's own send handler that does not see it.
     * The likely reason is that the composer's handler listens for a keydown that never
     * reaches it, or only acts when the edit state happens to be settled.
     *
     * The app cannot fix that from Java: a WebView delivers a hardware Enter to the input
     * as an ordinary character, there is no "the page ignored it" callback, and swallowing
     * the key in the activity would break every text field on the page (including newline
     * in a multi-line box). So the page gets a listener of its own that does what the
     * composer should have done: find the send control next to the focused field and press
     * it.
     *
     * Deliberately narrow, because this runs on every keystroke of every page:
     *  - Enter with Shift or with IME composition in progress is never touched (a newline
     *    and an in-progress word are both legitimate);
     *  - only inside an editable field, and never inside a <form> with its own submit -
     *    that form already applies the browser's own rule;
     *  - the send control has to be visible and enabled and within the composer, and it is
     *    only pressed when the field actually holds text;
     *  - a press that the page does not act on is not repeated: one attempt per keystroke,
     *    plus a re-entrancy guard and a short cooldown, so a held key cannot fire twice.
     */
    private static final String JS_ENTER_SEND =
            "(function(){"
            + "if(window.__arenaEnterSend)return;window.__arenaEnterSend=1;"
            + "function editable(el){"
            + "if(!el)return false;"
            + "if(el.tagName==='TEXTAREA')return true;"
            + "return !!(el.isContentEditable||el.getAttribute&&"
            + "el.getAttribute('contenteditable')==='true');}"
            + "function value(el){"
            + "if(!el)return '';"
            + "if(el.tagName==='TEXTAREA')return (el.value||'');"
            + "return (el.innerText||el.textContent||'');}"
            + "function visible(el){var r=el.getBoundingClientRect();"
            + "return r.width>0&&r.height>0;}"
            + "function enabled(el){"
            + "if(!el)return false;"
            + "if(el.disabled)return false;"
            + "if(el.getAttribute&&el.getAttribute('aria-disabled')==='true')return false;"
            + "return visible(el);}"
            + "function isSend(el){"
            + "var label=((el.getAttribute&&(el.getAttribute('aria-label')||"
            + "el.getAttribute('title')||el.getAttribute('data-testid'))||'')+' '+"
            // The element's own type is not part of this string: a plain button reports
            // type submit, so including it made every button in the composer - the model
            // picker, the attach clip - look like the send control.
            + "String(el.className||'')"
            + ").toLowerCase();"
            + "if(/send|submit|arrow-up|arrowup|paper|prompt/.test(label))return true;"
            // A plain type=submit button is only a send button inside a real form: an
            // ordinary <button> outside one still reports type "submit", which would make
            // every button beside the composer - the model picker, the attach clip - look
            // like the send control and get clicked instead.
            + "return el.tagName==='BUTTON'&&el.type==='submit'&&!!el.form;}"
            + "function findSend(from){"
            + "var node=from;"
            + "for(var up=0;up<8&&node;up++,node=node.parentElement){"
            + "var bs=node.querySelectorAll?node.querySelectorAll('button,[role=\"button\"]'):[];"
            + "var best=null;"
            + "for(var i=0;i<bs.length;i++){var b=bs[i];"
            + "if(!enabled(b)||!isSend(b))continue;"
            + "if(b.querySelector('textarea,input,[contenteditable=\"true\"]'))continue;"
            // the last matching control in the composer is the one at the right-hand end
            + "best=b;}"
            + "if(best)return best;"
            + "if(node.tagName==='FORM')break;}"
            + "return null;}"
            + "var busy=false,lastMs=0;"
            + "function send(field){"
            + "var now=Date.now();"
            + "if(busy||now-lastMs<600)return;"
            + "if(!value(field).trim())return;"
            + "var target=findSend(field);"
            + "if(!target)return;"
            + "busy=true;lastMs=now;"
            + "try{target.click();}catch(e){try{target.dispatchEvent("
            + "new MouseEvent('click',{bubbles:true,cancelable:true}));}catch(x){}}"
            + "setTimeout(function(){busy=false;},260);}"
            + "document.addEventListener('keydown',function(e){"
            + "if(e.key!=='Enter'||e.shiftKey||e.altKey||e.ctrlKey||e.metaKey)return;"
            + "if(e.isComposing||e.keyCode===229)return;"
            + "var field=e.target;"
            + "if(!editable(field))return;"
            + "if(field.closest&&field.closest('form'))return;"
            + "send(field);},true);"
            + "})();";

    /** Injects the Enter-to-send listener once per document. */
    public static void installEnterSend(WebView web) {
        if (web == null) return;
        web.evaluateJavascript(JS_ENTER_SEND, null);
    }

    /**
     * The page-side half of "start a new chat".
     *
     * Arena exposes no URL for a fresh conversation, and the chat tab's URL at the moment
     * the user asked for one is still the old conversation's - so a new window opened with
     * the chat tab's URL opens that same conversation again. That is exactly what happened
     * on the device: two tabs, both showing the chat that was already open.
     *
     * The fix lives in the page because that is where arena's own "new chat" control is.
     * It first tries to find that control; when it cannot (the sidebar is hidden in this
     * app, and with it the button), it changes the route itself:
     *
     *  1. several URL shapes, in order: a bare /, then /chat, then coming back to / from
     *     whatever the app is on - whichever first produces a document without a
     *     conversation id in its address is the one that works;
     *  2. only if a route change leaves the address pointing at a conversation does it
     *     try the keyboard shortcut and, last, a full home load.
     *
     * It reports what it did through AgonNewChat so the app knows whether the tab needs a
     * load of its own (the callback is optional: without it the script still works).
     */
    private static final String JS_NEW_CHAT =
            "(function(){try{"
            + "function tell(state){"
            + "try{if(window.AgonNewChat&&AgonNewChat.state)AgonNewChat.state(state);}catch(e){}}"
            // A conversation route has an id after the first path segment; the landing page
            // does not. This is the only test used to decide "is this still a chat".
            + "function onConversation(){"
            + "var p=location.pathname.replace(/\\/+$/,'');"
            + "var seg=p.split('/').filter(function(x){return x.length>0;});"
            + "if(seg.length<2)return false;"
            + "return /^(c|chat|conversation|thread|chats)$/i.test(seg[0]);}"
            // 1. arena's own "new chat" control, and nothing else.
            //
            // This list used to end with a[href="/"] and a[href$="/chat"], which match
            // the site LOGO, the sidebar's home row, and any other link back to the front
            // page. Clicking one of those on the user's behalf is the "random touch I did
            // not make" report: something on screen visibly reacted - a menu opened, a row
            // highlighted, the page jumped - and it was not the finger. Only an element
            // that says "new chat" in its own label is clicked now, and never a link (a
            // click on an <a> is a navigation). With none found, the route is changed
            // instead, which touches no element at all.
            + "var sel=['[aria-label*=\"new chat\" i]','[data-testid*=\"new-chat\" i]',"
            + "'button[title*=\"new chat\" i]','a[title*=\"new chat\" i]',"
            + "'[data-testid=\"new-chat-button\" i]'];"
            + "for(var i=0;i<sel.length;i++){"
            + "var el=document.querySelector(sel[i]);"
            + "if(!el||el.offsetParent===null)continue;"
            + "if(el.tagName==='A')continue;"
            + "el.click();tell('clicked');return;}"
            // 2. route changes, cheapest first
            + "if(!onConversation()){tell('already-new');return;}"
            + "var tries=['/','/chat','/new'];"
            + "var next=tries[0];"
            + "for(var t=0;t<tries.length;t++){"
            + "if(tries[t]!==location.pathname){next=tries[t];break;}}"
            + "try{history.pushState({},'',next);"
            + "window.dispatchEvent(new PopStateEvent('popstate',{state:{}}));"
            + "tell('routed');}catch(e){tell('none');}"
            + "}catch(e){try{AgonNewChat.state('none');}catch(x){}}})();";

    /**
     * The one key the app claims for itself: Ctrl/Cmd+N asks for a new chat.
     *
     * It is installed on every document because a hardware keyboard shortcut is a browser
     * expectation, and the app is the thing that owns the window. Everything else is left
     * to the page - pressing Escape through is what the Back button already does.
     */
    /**
     * The keyboard shortcuts a browser tab has, because the page is a browser tab.
     *
     *  - Ctrl/Cmd+N: another conversation tab;
     *  - Ctrl/Cmd+Tab and Ctrl/Cmd+Shift+Tab: the next / previous tab;
     *  - Ctrl/Cmd+W: close this tab.
     *
     * Arena's own page listens for some of these itself (Ctrl+N is a new chat there), so
     * the app stops the event first and does the thing in the strip, where the tabs
     * actually live. Ctrl+Tab and Ctrl+W do not exist in a WebView otherwise: the browser
     * chrome that would handle them is the app's own tab strip.
     *
     * A phone has no such keys, which is why every one of these also exists as a tap.
     */
    /**
     * Three taps on the chat unlock text selection - and three more lock it again.
     *
     * A phone has no Ctrl key, and "App-style text" is a setting most people never
     * open. The gesture makes the same switch reachable from the page itself: three
     * quick taps in the same place toggle selection, the setting is written so the
     * next launch agrees, and the app says which way it went. Taps inside the
     * composer, a textarea or an editable box are ignored, so it can never fire while
     * the user is typing.
     *
     * The lock direction restores the stylesheet it removed, rather than re-deriving
     * it: the policy is one element and its text is what the app already wrote.
     */
    /**
     * Reports every press to the app, with the element under the finger.
     *
     * "Something got clicked that I did not click" cannot be answered from the outside:
     * the app sees a press and the page decides what it meant. So the page names it.
     * The honest question behind the report is "what did I actually hit", and the label
     * of the control the press reaches answers it - including the case where the press
     * landed on the wrong thing, which is the case worth seeing.
     *
     * Reading the element touches nothing: no click, no focus, no preventDefault. This
     * watcher cannot itself cause a press.
     */
    private static final String JS_TAP_WATCH =
            "(function(){"
            + "if(window.__agonTapWatch)return;window.__agonTapWatch=1;"
            + "/* \"Something got clicked that I did not click.\""
            + "   Every press is reported to the app with the element under the finger: its role, its"
            + "   label, and where it sits. The app decides whether to show it, so nothing is drawn over"
            + "   the page unless the switch is on. Reading the element never changes it - no click, no"
            + "   focus, no preventDefault - so watching cannot itself cause a touch. */"
            + "function label(el){"
            + "if(!el)return '';"
            + "var out=[];"
            + "try{"
            + "var aria=el.getAttribute&&(el.getAttribute('aria-label')||el.getAttribute('title'));"
            + "if(aria)out.push(aria);"
            + "var txt=(el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim();"
            + "if(txt)out.push(txt.length>40?txt.slice(0,40)+'\\u2026':txt);"
            + "var ph=el.getAttribute&&(el.getAttribute('placeholder')||el.getAttribute('alt'));"
            + "if(ph)out.push(ph);"
            + "}catch(e){}"
            + "return out.join(' \\u00b7 ');"
            + "}"
            + "function describe(el){"
            + "if(!el)return null;"
            + "var tag=(el.tagName||'').toLowerCase();"
            + "var role=(el.getAttribute&&el.getAttribute('role'))||'';"
            + "var name=label(el);"
            + "/* The control the press will actually reach: a tap on a <span> inside a button is a tap on"
            + "   the button, and that is what the user sees react. */"
            + "var owner=el.closest&&el.closest('a,button,[role=\"button\"],[role=\"link\"],[role=\"menuitem\"],[role=\"tab\"],input,select,textarea,summary,label');"
            + "var hit=owner||el;"
            + "var hitTag=(hit.tagName||'').toLowerCase();"
            + "var hitRole=(hit.getAttribute&&hit.getAttribute('role'))||'';"
            + "var hitName=owner?label(hit):name;"
            + "var r=null;"
            + "try{r=hit.getBoundingClientRect();}catch(e){}"
            + "return {"
            + "tag:tag,"
            + "role:role,"
            + "name:name,"
            + "hit:hitTag,"
            + "hitRole:hitRole,"
            + "hitName:hitName,"
            + "x:r?Math.round(r.left+r.width/2):0,"
            + "y:r?Math.round(r.top+r.height/2):0,"
            + "w:r?Math.round(r.width):0,"
            + "h:r?Math.round(r.height):0"
            + "};"
            + "}"
            + "document.addEventListener('touchstart',function(e){"
            + "try{"
            + "var t=e.touches&&e.touches[0];"
            + "var el=document.elementFromPoint?document.elementFromPoint(t?t.clientX:0,t?t.clientY:0):e.target;"
            + "var info=describe(el);"
            + "if(!info)return;"
            + "info.x0=t?Math.round(t.clientX):0;"
            + "info.y0=t?Math.round(t.clientY):0;"
            + "if(window.AgonTapWatch&&AgonTapWatch.tap)AgonTapWatch.tap(JSON.stringify(info));"
            + "}catch(x){}"
            + "},true);"
            + "})();";

    private static final String JS_TRIPLE_TAP_SELECT =
            "(function(){"
                + "if(window.__agonTripleTap)return;window.__agonTripleTap=1;"
                + "var lastTap=0,count=0,x0=0,y0=0;"
                + "function policy(){return document.getElementById('arena-select-policy');}"
                + "function unlock(){"
                + "var st=policy();"
                + "if(st)st.remove();"
                + "if(window.__agonNoSelect){"
                + "window.__agonSavedGuard=window.__agonNoSelect;"
                + "document.removeEventListener('selectstart',window.__agonNoSelect,true);"
                + "window.__agonNoSelect=null;"
                + "}"
                + "window.__agonSelectUnlocked=1;"
                + "try{if(window.ArenaNative&&ArenaNative.selectToggled)ArenaNative.selectToggled(true);}catch(e){}"
                + "}"
                + "function lock(){"
                + "var saved=window.__agonPolicyCss||'';"
                + "if(saved&&!policy()){"
                + "var st=document.createElement('style');"
                + "st.id='arena-select-policy';"
                + "st.textContent=saved;"
                + "(document.head||document.documentElement).appendChild(st);"
                + "}"
                + "if(!window.__agonNoSelect&&window.__agonSavedGuard){"
                + "window.__agonNoSelect=window.__agonSavedGuard;"
                + "document.addEventListener('selectstart',window.__agonNoSelect,true);"
                + "}"
                + "window.__agonSelectUnlocked=0;"
                + "try{if(window.ArenaNative&&ArenaNative.selectToggled)ArenaNative.selectToggled(false);}catch(e){}"
                + "}"
                + "function save(){"
                + "var st=policy();"
                + "if(st&&!window.__agonPolicyCss)window.__agonPolicyCss=st.textContent||'';"
                + "}"
                + "document.addEventListener('touchstart',function(e){"
                + "save();"
                + "var t=e.target;"
                + "try{"
                + "if(t&&t.closest&&t.closest('input,textarea,form,[role=\"textbox\"],[contenteditable]'))return;"
                + "}catch(x){}"
                + "var touch=e.touches&&e.touches[0];"
                + "var x=touch?touch.clientX:(e.clientX||0);"
                + "var y=touch?touch.clientY:(e.clientY||0);"
                + "var now=Date.now();"
                + "if(now-lastTap>600||Math.abs(x-x0)>40||Math.abs(y-y0)>40){count=0;x0=x;y0=y;}"
                + "lastTap=now;"
                + "count++;"
                + "if(count<3)return;"
                + "count=0;"
                + "if(window.__agonSelectUnlocked)lock();else unlock();"
                + "},true);"
            + "})();";

    /**
     * The page side of a hold: find the block under a point and hand its text over.
     *
     * The app times the hold in Java too, so the page is asked rather than trusted to
     * act: AgonCopy.under() is called from the native long press, and answers through
     * AgonCopyNative.text() with the text of the smallest block that holds it. A point
     * inside the composer is ignored - that is where the user types, not where they
     * read - and nothing here touches the selection, so the page keeps whatever
     * selection it already had.
     */
    private static final String JS_COPY_API =
            "(function(){"
                + "if(window.__agonCopyApi)return;window.__agonCopyApi=1;"
                + "function report(text){"
                + "try{"
                + "if(!text)return false;"
                + "if(window.AgonCopyNative&&AgonCopyNative.text)AgonCopyNative.text(text);"
                + "return true;"
                + "}catch(e){return false;}"
                + "}"
                + "/* The smallest element that holds readable text, walking UP from the hit point."
                + "   A hold used to take the first block-shaped ancestor, which on arena is often the"
                + "   message column or the whole conversation pane - so holding one line copied everything."
                + "   This stops at the nearest thing that is text and not a container: it must have its own"
                + "   text, must not be a layout/scroll/flex/grid wrapper, and must not already contain"
                + "   several paragraphs - if it does, the child that holds the point is the answer. */"
                + "function isWrapper(el){"
                + "var cs=getComputedStyle(el);"
                + "if(cs.display==='contents')return true;"
                + "if(/flex|grid/.test(cs.display)&&el.children.length>1)return true;"
                + "if(cs.overflowY==='auto'||cs.overflowY==='scroll'||cs.overflow==='auto'||cs.overflow==='scroll')return true;"
                + "var role=(el.getAttribute&&el.getAttribute('role'))||'';"
                + "if(/list|table|grid|navigation|main|region|tabpanel|log|feed/i.test(role))return true;"
                + "if(/^(MAIN|NAV|SECTION|ARTICLE|BODY|HTML|FORM|UL|OL|TABLE)$/.test(el.tagName))return true;"
                + "if(el.querySelector&&el.querySelector('main,form,textarea,[contenteditable]'))return true;"
                + "return false;"
                + "}"
                + "function textOf(el){"
                + "return ((el.innerText||el.textContent||'')).replace(/\\u00a0/g,' ').trim();"
                + "}"
                + "function blockAt(x,y,scale){"
                + "var px=x/(scale||1),py=y/(scale||1);"
                + "var range=null;"
                + "try{"
                + "if(document.caretRangeFromPoint)range=document.caretRangeFromPoint(px,py);"
                + "else if(document.caretPositionFromPoint){"
                + "var cp=document.caretPositionFromPoint(px,py);"
                + "if(cp){range=document.createRange();range.setStart(cp.offsetNode,cp.offset);range.setEnd(cp.offsetNode,cp.offset);}"
                + "}"
                + "}catch(e){return null;}"
                + "if(!range||!range.startContainer)return null;"
                + "var node=range.startContainer;"
                + "var el=node.nodeType===3?node.parentElement:node;"
                + "if(!el||el===document.body)return null;"
                + "if(el.closest&&el.closest('input,textarea,[contenteditable]'))return null;"
                + "/* Walk up, but stop at the first ancestor that is a real text block: one that is not a"
                + "   wrapper and whose own text is not the whole page. Never go past a few levels, so a hold"
                + "   on a message cannot escape into the conversation. */"
                + "var best=null,node2=el;"
                + "for(var i=0;i<6&&node2&&node2!==document.body;i++){"
                + "if(isWrapper(node2))break;"
                + "var t=textOf(node2);"
                + "if(t&&t.length<=600){best=node2;break;}"
                + "node2=node2.parentElement;"
                + "}"
                + "if(!best)best=el;"
                + "return best;"
                + "}"
                + "/* A press that lands on a control is a press on that control, not a request for"
                + "   the text near it. Without this a slow tap on Send, on a sidebar row or on a link"
                + "   copied the caption around it - the \"clicking anywhere copies everything\" report. */"
                + "function onControl(x,y,scale){"
                + "var px=x/(scale||1),py=y/(scale||1);"
                + "var el=null;"
                + "try{el=document.elementFromPoint?document.elementFromPoint(px,py):null;}catch(e){}"
                + "if(!el)return false;"
                + "if(el.closest&&el.closest('a,button,input,select,textarea,summary,label,[role=\"button\"],[role=\"link\"],[role=\"menuitem\"],[role=\"tab\"],[role=\"checkbox\"],[role=\"switch\"],[role=\"option\"],nav,header,footer,[contenteditable]'))return true;"
                + "return false;"
                + "}"
                + "window.AgonCopy={"
                + "under:function(x,y,scale){"
                + "if(onControl(x,y,scale))return false;"
                + "var block=blockAt(x,y,scale);"
                + "if(!block)return false;"
                + "var text=textOf(block);"
                + "if(!text)return false;"
                + "return report(text);"
                + "},"
                + "text:function(t){if(t)report(String(t).trim());},"
                + "select:function(x,y,scale){"
                + "var block=blockAt(x,y,scale);"
                + "if(!block)return;"
                + "try{"
                + "block.style.setProperty('-webkit-user-select','text','important');"
                + "block.style.setProperty('user-select','text','important');"
                + "var sel=window.getSelection();"
                + "if(sel){sel.removeAllRanges();var r=document.createRange();r.selectNodeContents(block);sel.addRange(r);}"
                + "}catch(e){}"
                + "}"
                + "};"
            + "})();";

    private static final String JS_NEW_CHAT_LISTENER =
            "(function(){"
            + "if(window.__arenaNewChatKey)return;window.__arenaNewChatKey=1;"
            + "document.addEventListener('keydown',function(e){"
            + "if(!(e.ctrlKey||e.metaKey)||e.shiftKey&&e.key!=='Tab'||e.altKey)return;"
            + "var k=(e.key||'').toLowerCase();"
            + "if(k==='n'){e.preventDefault();"
            + "try{if(window.ArenaNative&&ArenaNative.newChat)ArenaNative.newChat();}catch(x){}return;}"
            + "if(k==='tab'){e.preventDefault();"
            + "try{if(window.ArenaNative&&ArenaNative.cycleTab)"
            + "ArenaNative.cycleTab(e.shiftKey?-1:1);}catch(x){}return;}"
            + "if(k==='w'){e.preventDefault();"
            + "try{if(window.ArenaNative&&ArenaNative.closeTab)ArenaNative.closeTab();}"
            + "catch(x){}return;}"
            + "},true);})();";

    /** Runs {@link #JS_NEW_CHAT}; the callback receives the state word the script reports. */
    public static void startNewChat(WebView web, android.webkit.ValueCallback<String> state) {
        if (web == null) {
            if (state != null) state.onReceiveValue("none");
            return;
        }
        if (state != null) {
            web.addJavascriptInterface(new NewChatBridge(state), "AgonNewChat");
        }
        web.evaluateJavascript(JS_NEW_CHAT, null);
    }

    /** Receives the state word from {@link #JS_NEW_CHAT}. Runs on a WebView thread. */
    private static final class NewChatBridge {
        private final android.webkit.ValueCallback<String> target;

        NewChatBridge(android.webkit.ValueCallback<String> target) {
            this.target = target;
        }

        @android.webkit.JavascriptInterface
        public void state(final String value) {
            if (target == null) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    target.onReceiveValue(value);
                } catch (RuntimeException ignored) {
                    // the activity went away between the call and the answer
                }
            });
        }
    }

    /**
     * What the conversation is called, read out of arena's own page.
     *
     * {@code WebView.getTitle()} is useless here: arena is a single-page app and never
     * changes {@code document.title} per conversation, so every tab reports the same
     * string ("Arena | Benchmarking...") no matter which chat it is showing. On the device
     * that meant a tab called "Arena | Benchma..." for a conversation about something
     * else entirely - and a tab that never renames itself as the chat goes on.
     *
     * The name has to come from the page, and the page offers three levels of it:
     *
     *  1. an explicit heading - an {@code h1}/{@code h2} in the conversation area, or an
     *     element whose aria-label says so;
     *  2. the first thing the user wrote: the first block of text in the message list,
     *     which is what every chat app displays as a conversation name once it has one;
     *  3. nothing - in which case the app keeps the number it gave the tab.
     *
     * It reports through AgonTitle and re-reports when the page changes, throttled, so a
     * streaming answer cannot flood the bridge.
     */
    private static final String JS_TAB_TITLE =
            "(function(){"
            + "if(window.__arenaTabTitle)return;window.__arenaTabTitle=1;"
            + "function clean(s){"
            + "return (s||'').replace(/\\s+/g,' ').trim();}"
            + "function usable(s){"
            + "if(!s)return false;"
            + "var low=s.toLowerCase();"
            + "if(low==='arena'||low==='arena.ai'||low==='chat'||low==='new chat')return false;"
            // the page's own pitch, which arena uses as a document title on every route
            + "if(low.indexOf('benchmark')>=0||low.indexOf('arena |')>=0)return false;"
            + "if(s.length<3)return false;"
            + "return true;}"
            // 1. an explicit conversation heading
            + "function fromHeading(){"
            + "var hs=document.querySelectorAll('h1,h2,[role=heading]');"
            + "for(var i=0;i<hs.length;i++){"
            + "var el=hs[i];"
            + "if(el.querySelector('textarea,input,[contenteditable]'))continue;"
            + "var t=clean(el.innerText||el.textContent);"
            + "if(usable(t)&&t.length<=80)return t;}"
            + "return null;}"
            // 2. the first user message
            + "function fromFirstMessage(){"
            + "var ms=document.querySelectorAll("
            + "'[data-role=user],[data-author=user],[class*=user-message i],'"
            + "+'[class*=message i],[class*=prose i],article,p');"
            + "for(var i=0;i<ms.length&&i<400;i++){"
            + "var el=ms[i];"
            + "if(el.querySelector('textarea,input,button,[contenteditable]'))continue;"
            + "var r=el.getBoundingClientRect();"
            + "if(r.width<40||r.height<8)continue;"
            + "var t=clean(el.innerText||el.textContent);"
            + "if(!usable(t))continue;"
            // the first line of a message is its own subject line
            + "return t.split(/[.!?\\n]/)[0].slice(0,60);}"
            + "return null;}"
            + "var last='',sentAt=0,pending=null;"
            + "function report(){"
            + "var t=fromHeading()||fromFirstMessage()||'';"
            + "if(!t||t===last)return;"
            + "if(Date.now()-sentAt<800)return;"
            + "last=t;sentAt=Date.now();"
            + "try{if(window.AgonTitle&&AgonTitle.name)AgonTitle.name(t);}catch(e){}}"
            + "function soon(){if(pending)clearTimeout(pending);"
            + "pending=setTimeout(report,600);}"
            + "try{new MutationObserver(soon).observe(document.documentElement,"
            + "{childList:true,subtree:true,characterData:true});}catch(e){}"
            + "var passes=0;var t0=setInterval(function(){"
            + "if(++passes>8){clearInterval(t0);return;}"
            + "if(document.hidden)return;report();},700);"
            + "setTimeout(report,400);"
            + "})();";

    /** Installs the title reporter on a document. Idempotent. */
    public static void installTabTitle(WebView web, final android.webkit.ValueCallback<String> onName) {
        if (web == null) return;
        if (onName != null) {
            web.addJavascriptInterface(new TitleBridge(onName), "AgonTitle");
        }
        web.evaluateJavascript(JS_TAB_TITLE, null);
    }

    /** Receives a conversation name from {@link #JS_TAB_TITLE}. */
    private static final class TitleBridge {
        private final android.webkit.ValueCallback<String> target;

        TitleBridge(android.webkit.ValueCallback<String> target) {
            this.target = target;
        }

        @android.webkit.JavascriptInterface
        public void name(final String value) {
            if (target == null || value == null) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    target.onReceiveValue(value);
                } catch (RuntimeException ignored) {
                    // the tab went away between the call and the answer
                }
            });
        }
    }

    /**
     * The rule that turns text selection off, or leaves the page alone.
     *
     * It is a document-wide `user-select: none` plus the WebView-specific side of the same
     * thing, and each part is there for a different road into a selection:
     *
     *  - `-webkit-user-select` / `user-select` stop a drag or a long press from selecting;
     *  - `-webkit-touch-callout: none` stops the long-press callout on iOS-shaped engines;
     *  - a document-level `selectstart` listener is the last word. WebKit still allows a
     *    selection to *start* in some cases that the CSS alone does not cover - a triple
     *    tap, a text run inside a link, a click-drag over a chat bubble - and cancelling
     *    that event is what makes the setting actually mean off, rather than "off unless
     *    the page fights back".
     *
     * Inputs and editable areas are exempt throughout: the composer is where the user
     * types, and a caret there is not a selection.
     *
     * This rule used to live in {@link #buildFrameCss} only, and that was the bug: the
     * frame CSS is written only on the branch that reserves space for the app's bars, and
     * the app stopped reserving any. applyChromeInsets() therefore took its early-return
     * branch, which clears the injected stylesheet and never rewrote this rule - so the
     * setting silently did nothing on every device. It now goes in on both branches.
     */
    private static String selectCss(boolean selectable) {
        if (selectable) return "";
        return "*{-webkit-user-select:none !important;user-select:none !important;}"
                // Chat text stays copyable.
                //
                // "App-style text" means the app does not behave like a browser tab - a tap
                // does not start a selection you did not ask for. It must not mean a reply
                // cannot be copied: on a phone that is the one thing a user needs from a
                // message. So the guard below refuses a selection that a DRAG starts, and
                // lets through one that a deliberate long press starts.
                //
                // The composer is a box you have to be able to put a caret in, and arena's
                // composer is not always a real <textarea> - often it is a rich-text box,
                // or a div with role="textbox", inside a form that also holds the model and
                // attach controls. A document-wide `user-select: none` takes the caret away
                // from all of those, which is the "i cant move the line of entering in
                // chat" report: the tap lands, the page focuses the box, and no caret can
                // be placed to move it. So the whole composer subtree is exempt, not just
                // the two tag names.
                //
                // `:not([contenteditable=false])` because a bare [contenteditable] also
                // matches a box that was explicitly switched off, and text that a page
                // locked must stay locked.
                + "input,textarea,pre,code,[data-agon-pick],"
                + "form,form *,[role=\"textbox\"],[role=\"textbox\"] *,"
                + "[contenteditable]:not([contenteditable=false]),"
                + "[contenteditable]:not([contenteditable=false]) *{"
                + "-webkit-user-select:text !important;user-select:text !important;}";
    }

    /**
     * The matching listener: refuse the selections nobody asked for, allow the two that
     * are asked for (a held press, and anything in the composer).
     *
     * A held press does not just get "let through" any more. On the device the selection
     * was allowed and still produced nothing to copy, because whether a long press starts
     * one is the engine's decision and it also depends on where the finger landed. So the
     * app makes the selection itself: after ~450 ms of a still finger it takes the block
     * of text under that point, makes that block selectable, and selects it. That is a
     * real selection - it is highlighted, and Android offers its Copy menu over it.
     */
    private static String selectGuard(boolean selectable) {
        if (selectable) {
            return "try{if(window.__agonNoSelect){document.removeEventListener('selectstart',window.__agonNoSelect,true);window.__agonNoSelect=null;}if(window.__agonHold){document.removeEventListener('touchstart',window.__agonCopyTap,true);if(window.__agonPicked&&window.__agonPicked.style){window.__agonPicked.style.removeProperty('-webkit-user-select');window.__agonPicked.style.removeProperty('user-select');}window.__agonPicked=null;window.__agonCopyTap=null;}}catch(e){}";
        }
        return ""
                + "try{window.__agonLock=function(){"
                + "if(!window.__agonNoSelect){"
                + "window.__agonDown=0;"
                + "function markDown(){window.__agonDown=Date.now();}"
                + "document.addEventListener('touchstart',markDown,true);"
                + "document.addEventListener('mousedown',markDown,true);"
                + "function composer(t){"
                + "try{"
                + "if(!t)return false;"
                + "if(t.tagName==='INPUT'||t.tagName==='TEXTAREA')return true;"
                + "if(t.isContentEditable===true)return true;"
                + "if(t.closest&&t.closest('input,textarea,form,[role=\"textbox\"],[contenteditable]:not([contenteditable=false])'))return true;"
                + "}catch(e){}"
                + "return false;}"
                + "window.__agonNoSelect=function(e){"
                + "var t=e.target;"
                + "if(composer(t))return;"
                + "var held=window.__agonDown?Date.now()-window.__agonDown:0;"
                // 240 ms, not 320: a press that starts a selection and then becomes a drag
                // ("hold a word, then pull") is still a deliberate selection, and refusing
                // it cancelled the drag the user was in the middle of.
                + "if(held>240)return;"
                + "if(t&&t.closest&&t.closest('pre,code[data-agon-pick]'))return;"
                + "if(e.cancelable)e.preventDefault();};"
                + "document.addEventListener('selectstart',window.__agonNoSelect,true);"
                // Three taps unlocks selection on a phone; the SECOND long press over the
                // block the app selected copies it. That is the only page-side copy left:
                // the hold itself is timed in the WebView (SelectionSafeWebView), which
                // asks this page for the text under the finger.
                + "window.__agonCopyTap=function(e){"
                + "if(!window.__agonPicked)return;"
                + "try{"
                + "var t=(window.__agonPicked.innerText||window.__agonPicked.textContent||'')"
                + ".replace(/\\u00a0/g,' ').trim();"
                + "if(t&&window.ArenaNative&&ArenaNative.copied){ArenaNative.copied(t);return;}"
                + "}catch(x){}"
                + "};"
                + "document.addEventListener('touchstart',window.__agonCopyTap,true);"
                // No page-side hold timer any more.
                //
                // There were two: this one (a setTimeout that copied after 450 ms) and the
                // one timed in the WebView. Both fired on the same press, so a single hold
                // copied twice - and this one copied on the timer rather than on the lift,
                // so it fired even while the user was still dragging to extend a
                // selection. The WebView's is the one kept: it needs no script, and it
                // copies when the finger lifts. See SelectionSafeWebView.
                // Closers: the `if(!window.__agonNoSelect)` inside the lock, then the lock
                // function itself. (The `if(!window.__agonHold)` that used to wrap a page
                // hold is gone with the page hold.)
                + "}"
                + "};"
                // The page decides whether the lock is installed at all: three taps
                // unlock selection, and a re-injection must not undo that.
                + "if(!window.__agonSelectUnlocked)try{window.__agonLock();}catch(e){}"
                + "}catch(e){}";
    }

    /**
     * The re-check loop shared by the sidebar and header rules.
     *
     * A single pass at onPageStarted/onPageFinished is not enough: arena is a single-page
     * app, so at that moment the document is an empty shell and the side panel is drawn
     * some hundreds of milliseconds later - and again after every navigation inside the
     * app. One pass found nothing, so nothing was hidden and the panel appeared afterwards.
     * That is exactly the "it only hides after I toggle the switch off and on" report: the
     * toggle re-ran the pass once the panel existed.
     *
     * So `watchForPanel()` keeps looking, in two phases:
     *
     *  - fast: every 500 ms for the first ten seconds, which covers the page's first paint;
     *  - slow: every 2.5 s afterwards, which covers opening a conversation much later (that
     *    draws a fresh panel) without costing anything noticeable.
     *
     * A pass is a handful of cheap DOM reads and the rules are idempotent - the one they
     * hid is marked, and the stylesheet keeps it hidden - so extra passes are safe. The
     * slow phase is skipped while the page is in the background.
     */
    private static String watchHelpers(boolean hideSidebar, boolean hideHeader) {
        return "function scanNow(){"
            + (hideSidebar ? "scanSidebar();" : "")
            + (hideHeader ? "scanHeader();" : "")
            + "}"
            + "function panelGone(){"
            // The rules record success here, so a page that has already been cleaned does
            // not pay for the full scan on every pass.
            + "return !!(window.__agonPanelHidden||window.__agonHeaderHidden);}"
            + "function watchForPanel(){"
            + "if(window.__agonWatch)return;window.__agonWatch=1;"
            + "var fast=null,slow=null,left=20;"
            + "function pass(){"
            + "if(panelGone())return;"
            + "try{scanNow();}catch(e){}}"
            + "function tick(){"
            + "pass();"
            + "if(panelGone()||--left<=0){"
            + "if(fast){clearInterval(fast);fast=null;}"
            + "if(!slow){slow=setInterval(slowTick,2500);}}}"
            + "function slowTick(){"
            // Nothing to do while the page is in the background; the next foreground pass
            // is a quarter of a second away at worst.
            + "if(document.hidden)return;"
            + "pass();}"
            + "pass();"
            + "if(!panelGone()){fast=setInterval(tick,500);}}";
    }

    /** Text-based fallback for a sidebar whose class names say nothing. */
    private static final String JS_SIDEBAR =
            "function scanSidebar(){"
            + "var cands=document.body?document.body.querySelectorAll('nav,aside,div'):[];"
            + "for(var i=0;i<cands.length;i++){var el=cands[i];"
            + "if(!shown(el)||!empty(el))continue;"
            + "var r=el.getBoundingClientRect();"
            // Shape, not a desktop size: a sidebar is a column - taller than it is wide -
            // and it sits against one edge of the window. The old rule also required
            // width <= 420 CSS px, which is a desktop figure; on a phone the panel is a
            // slide-over the full width of the screen, so the rule could never match there
            // while it matched happily in a desktop-shaped emulator window. That is the
            // it-hides-in-the-emulator-but-not-on-my-phone report.
            + "if(r.height<r.width*0.9)continue;"
            + "if(r.left>window.innerWidth*0.2&&"
            + "r.right<window.innerWidth*0.8)continue;"
            + "var t=text(el);if(t.length>700)continue;"
            // The three fixed entries, then the account row, which is an email address once
            // signed in and a sign-in label before that.
            + "var words=['new chat','leaderboard','search'];"
            + "var hits=0;"
            + "for(var j=0;j<words.length;j++){if(t.indexOf(words[j])>=0)hits++;}"
            + "var account=/[\\w.+-]+@[\\w-]+\\.[\\w.-]+/.test(t)||t.indexOf('sign in')>=0"
            + "||t.indexOf('sign up')>=0||t.indexOf('log in')>=0||t.indexOf('login')>=0;"
            // Two of the three words plus an account row is a navigation panel; the words
            // alone are a start page and are left alone.
            + "if(hits<2||!account)continue;"
            + "if(el.querySelectorAll('a,button,[role=button],[role=link]').length<3)continue;"
            // ...and never hide anything that contains the composer or the main region.
            + "if(el.querySelector('main,textarea,form,[contenteditable]'))continue;"
            + "hide(el);window.__agonPanelHidden=1;return;}}";

    /**
     * The first statement of every hiding pass: give back whatever an earlier pass hid.
     *
     * The rules hide by writing an inline `display: none !important` onto the element, and
     * an inline style outlives the script that wrote it - it survives the panel being
     * re-rendered, and it survives the switch being turned off. That is why "Hide Arena's
     * sidebar" could be switched off and the panel stayed away: nothing in the app ever
     * removed the inline style. Every element the rules touch is tagged, so putting the
     * page back is one query and two property removals - and it is idempotent, which is
     * what makes it safe to run at the top of every pass.
     */
    private static String unwrapFromHide() {
        return "try{var hd=document.querySelectorAll('[data-agon-hid]');"
                + "for(var hi=0;hi<hd.length;hi++){"
                + "hd[hi].style.removeProperty('display');"
                + "hd[hi].removeAttribute('data-agon-hid');}}catch(e){}"
                + "window.__agonPanelHidden=0;window.__agonHeaderHidden=0;";
    }

    /** Gives every element a hiding pass removed back to the page, immediately. */
    private static final String JS_SHOW_PANELS =
            "(function(){try{"
            + unwrapFromHide()
            + "window.dispatchEvent(new Event('arena-insets'));"
            + "}catch(e){}})();";

    /** Text/size based fallback for the page's own top header. */
    private static final String JS_HEADER =
            "function scanHeader(){"
            + "var tops=document.body?document.body.querySelectorAll('header,nav,div'):[];"
            + "for(var i=0;i<tops.length;i++){var el=tops[i];"
            + "if(!shown(el)||!empty(el))continue;"
            + "var r=el.getBoundingClientRect();"
            + "if(r.top>10)continue;"
            + "if(r.height<24||r.height>110)continue;"
            + "if(r.width<window.innerWidth*0.6)continue;"
            + "if(el.querySelectorAll('a,img,button').length<1)continue;"
            + "hide(el);window.__agonHeaderHidden=1;return;}}";


    /** Starts a fresh conversation using arena's own "new chat" control. */
    public static void startNewChat(WebView web) {
        startNewChat(web, null);
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

    /**
     * Undoes the page-panel hiding on every warm tab.
     *
     * Called the moment the switch is turned off, before the reload that
     * MainActivity.applySetting() also does. The reload is the thorough answer, but it is
     * also the slow one - it throws away the conversation on screen - and it is not needed
     * for something that is one attribute lookup per document.
     */
    public void showPagePanels() {
        for (WebView w : views.values()) {
            if (w == null) continue;
            w.evaluateJavascript(JS_SHOW_PANELS, null);
        }
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
        // No usable connection (or a very slow one): show the copy saved from last time
        // instead of an empty page or an error screen, and let the normal load run behind
        // it. OnPageFinished replaces it the moment there is something better.
        final String startUrl = urlFor(tab);
        final boolean offline = !OfflinePages.online(activity);
        OfflinePages.applyCacheMode(w, !offline);
        if (offline && OfflinePages.hasSnapshot(activity, startUrl)) {
            CrashLog.note(activity, "offline: showing the saved copy of " + startUrl);
            restoredUrl.put(tab, startUrl);
            OfflinePages.restore(w, startUrl);
        } else {
            // Chat tab loads immediately; others start as soon as they are created so the
            // network work overlaps with the user reading the current screen.
            w.loadUrl(startUrl);
        }
        // No automatic "new chat" here any more.
        //
        // A window used to be asked for a fresh conversation 900 ms after it was created,
        // whether or not anyone was looking at it. Two things were wrong with that. It made
        // the tab flash arena's landing page over whatever the user had just opened, and it
        // threw away the page they were on - so a new tab could never hold the front page,
        // which is the one place arena shows its own history button and search. A tab now
        // keeps the page it was given: the root when it is created, and then whatever the
        // user opens in it.
        trim();
        return w;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView createWebView(int tab) {
        SelectionSafeWebView w = new SelectionSafeWebView(activity);
        w.setBackgroundColor(AppUi.color(activity, R.color.bg));
        w.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        // A hold is timed in Java as well as in the page; see SelectionSafeWebView.
        w.setHoldListener((x, y, chosen) -> {
            // What the user had already selected wins, and it is copied as-is.
            if (chosen != null && !chosen.trim().isEmpty()) {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).onPageCopied(chosen.trim());
                }
                return;
            }
            // A hold over a button, a link, the nav - or a control that sits on top of
            // the page - is not a request for text: it is a long press on a control, and
            // copying the caption next to it is the "clicking anywhere copies
            // everything" report. The page answers whether this point is text; when it
            // says no, nothing is copied and nothing is said, exactly as a long press on
            // a button should be.
            copyUnder(pointJs(x, y));
        });
        w.setLongClickable(true);
        // Priority follows VISIBILITY, not a permanent pin.
        //
        // This used to be RENDERER_PRIORITY_IMPORTANT with `false` ("do not drop priority
        // when the view is not visible"), applied to every tab and to the two tabs that are
        // preloaded in the background. That tells Android no renderer may ever be reclaimed,
        // so on a phone without much RAM the whole app competes with itself: the chat, the
        // leaderboard and search all stay at the highest priority while only one is on
        // screen. `true` means "behave normally when hidden", which is what lets the system
        // take memory back from the tabs nobody is looking at. applyRendererPriority() then
        // promotes whichever tab is actually showing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // window.open() MUST be allowed: with setSupportMultipleWindows(false) the WebView
        // silently drops every popup, WebChromeClient.onCreateWindow is never called, and
        // "Continue with Google" (which opens its account chooser in a popup) does nothing
        // at all - it just rebinds the page that is already showing. The popup itself is
        // hosted in-app by MainActivity.onCreateWindow, never by a browser.
        s.setSupportMultipleWindows(true);
        // The page itself must never be able to read the device: remote documents get the
        // web, not file:// or content:// access. Harmless on API 30+ (already the
        // default) and a real fence on the older ones this app still supports.
        s.setAllowFileAccess(false);
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
        // Never: an arena page is https, so an http subresource would be a downgrade, and
        // letting one through would let whoever controls it read the page it sits in.
        // An https page that embeds plain http (a preview link inside arena, a tunnel, a
        // local build) is a mixed-content block under NEVER_ALLOW: the frame stays empty
        // and a tap reads as "nothing happened". The page still decides what it loads.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Cache policy: ask the network, let the cache answer what it legitimately can.
        //
        // This used to be LOAD_CACHE_ELSE_NETWORK, which is not "fast": it answers from the
        // disk cache *even when the entry has expired*, so a stale Arena document was
        // painted and the app's requests for the current one could be satisfied from that
        // same stale copy - which is what "pages are stuck and never load" looks like once
        // the cache holds an old build of the site. Arena is a live app (a chat, a
        // leaderboard); a cached copy of it is worth very little and a stale one is a bug.
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
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
        // The answer to "what text is under this point", for the hold timed in Java.
        w.addJavascriptInterface(new WebBridge(activity, w), "AgonCopyNative");
        // Names the element under each press, for "Show what a tap lands on".
        w.addJavascriptInterface(new WebBridge(activity, w), "AgonTapWatch");
        // Answers pressEscape(): "did that close anything?" Only that one boolean crosses
        // the bridge, and it is read back on the UI thread.
        w.addJavascriptInterface(new EscapeBridge(), "AgonEscape");
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
                // Re-installed per document: a navigation replaces the page, and with it
                // every listener the previous document had. Both scripts are idempotent.
                installEnterSend(view);
                view.evaluateJavascript(JS_NEW_CHAT_LISTENER, null);
                // Three taps toggle "App-style text", which is the only way to unlock a
                // selection on a phone - there is no Ctrl to press.
                view.evaluateJavascript(JS_TRIPLE_TAP_SELECT, null);
                // A hold that the app timed in Java asks this for the text under the finger.
                view.evaluateJavascript(JS_COPY_API, null);
                // Names the element under every press, for the "random touches" switch.
                view.evaluateJavascript(JS_TAP_WATCH, null);
                // The chat tab's chip is a fixed label, but its name still matters: it is
                // what the history sheet and the window list show for the conversation that
                // is open. Every tab is asked, and only the extra tabs display it.
                installTabTitle(view, name -> rememberTitle(tab, name));
                // The page made it: if the "stuck" bar is up for this tab, drop it.
                if (activity instanceof MainActivity) ((MainActivity) activity).clearStalledLoad();
                // Keep this page for the next time there is no network ("everything is
                // saved in the app, so it still opens and feels fast"). Throttled per URL
                // inside OfflinePages, so firing it on every finish is cheap.
                if (url != null && url.startsWith("file://")) {
                    // The saved copy has been replayed; shut the file door again.
                    OfflinePages.lockFileAccess(view);
                } else if (url != null) {
                    restoredUrl.remove(tab);
                    // Not in the load callback: saveWebArchive() serialises the whole
                    // document, and doing that at the exact moment the page becomes usable
                    // is a visible stutter on a slow phone. Two seconds later nobody is
                    // waiting for it.
                    final WebView saving = view;
                    final String saveUrl = url;
                    view.postDelayed(() -> OfflinePages.save(saving, saveUrl), 2000L);
                }
                OfflinePages.applyCacheMode(view);
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
                // Same rule as the deprecated callback below, for API 23+: a main-frame
                // failure is answered with the saved copy instead of the error screen.
                if (request != null && request.isForMainFrame() && request.getUrl() != null) {
                    String failed = request.getUrl().toString();
                    if (OfflinePages.restore(view, failed)) {
                        restoredUrl.put(tab, failed);
                        CrashLog.note(activity, "load failed, showing the saved copy of "
                                + failed);
                        return;
                    }
                }
                delegate.onReceivedError(view, request, error);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                // The document itself failed to load. Rather than hand the user the error
                // screen, put back the copy this tab had ("sometime it doesnt load"): it is
                // what they were looking at a minute ago, and it is honest about being a
                // saved page because the retry bar stays up.
                if (failingUrl != null && OfflinePages.restore(view, failingUrl)) {
                    restoredUrl.put(tab, failingUrl);
                    CrashLog.note(activity, "load failed, showing the saved copy of "
                            + failingUrl);
                    return;
                }
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

    /** The answers pressEscape() and unstickPage() need, and nothing else. */
    private final class EscapeBridge {
        @android.webkit.JavascriptInterface
        public void report(final boolean dismissed) {
            main.post(() -> {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).onEscapeReport(dismissed);
                }
            });
        }

        /** unstickPage(): true means the floating toolbar is still there. */
        @android.webkit.JavascriptInterface
        public void stuck(final boolean stillStuck) {
            main.post(() -> {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).onStuckReport(stillStuck);
                }
            });
        }
    }

    /**
     * Nothing is ever evicted any more - every tab stays loaded.
     *
     * This used to drop the least recently used background tab once there were more than
     * MAX_WARM, which meant switching back to a tab threw its WebView away and rebuilt it
     * from scratch. On a phone that is a long blank page, and in the recording it looked
     * like the app had frozen. The whole point of the tabs is that they are warm, so the
     * memory cost is accepted instead: there are five tabs, each one page, and the user
     * asked for all of them to stay.
     */
    private void trim() {
        // Intentionally empty: every tab stays loaded. See the comment above.
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
            restoredUrl.remove(key);
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
        // Any tab that was showing a saved copy is no longer showing anything: the copies
        // were just deleted by the caller, and reloading one would load a missing file.
        restoredUrl.clear();
        if (currentTab >= 0 && views.containsKey(currentTab)) {
            loadInCurrent(realUrl(currentTab));
            return;
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

    /** Turns a serializeValue() string ("Hello", \"quoted\", null) into plain text. */
    static String unquoteJs(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.isEmpty() || "null".equals(v) || "undefined".equals(v)) return "";
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            // The JSON string form: undo the escapes a JSON decoder would undo. Written out
            // rather than parsed so it cannot fail on a stray backslash in a code block.
            StringBuilder out = new StringBuilder(v.length());
            for (int i = 1; i < v.length() - 1; i++) {
                char c = v.charAt(i);
                if (c != '\\' || i + 1 >= v.length() - 1) {
                    out.append(c);
                    continue;
                }
                char n = v.charAt(++i);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'u':
                        if (i + 4 < v.length()) {
                            try {
                                out.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                out.append("\\u");
                            }
                        }
                        break;
                    default: out.append(n);
                }
            }
            return out.toString();
        }
        return v;
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

    /**
     * JS that closes whatever overlay the page has put on top of itself.
     *
     * Arena's own panels - the feedback card ("Was this task successful?"), the model
     * menu, the file rail - are drawn inside the page, so the app cannot close them the
     * way it closes its own sheets. They all, however, answer Escape, which is why that
     * card prints "Esc" in its corner: the page has a keyboard listener for it.
     *
     * This does not press Escape on an element; it dispatches the same key events a
     * keyboard would, at whatever holds focus and at the document. `keyCode`/`which` are
     * included because plenty of handlers in the wild still read those and ignore `key`.
     *
     * It reports `true` if anything on the page was actually dismissed, so the caller
     * knows whether to consume the Back press or let it do its normal job.
     */
    public void pressEscape() {
        WebView w = current();
        if (w == null) return;
        w.evaluateJavascript(JS_ESCAPE, null);
    }

    /**
     * Un-sticks whatever the page has floated over itself.
     *
     * From the screen recording: after typing into the composer, a small toolbar of
     * icons (grid / smiley / hamburger) comes to rest over the sidebar, the composer stays
     * in its "editing" state, and from then on the page ignores taps. Those floating
     * controls are children of the document, positioned by the page itself, so nothing
     * the app draws can cover or remove them - the page has to let go of them.
     *
     * Three things make an overlay like that let go, and none of them can damage a page
     * that is behaving normally, which is why this is safe to run on any Back press:
     *
     *  1. blur: a floating toolbar is usually attached to the focused field. Taking focus
     *     away is the same thing as tapping somewhere else, without the tap.
     *  2. a pointerdown/pointerup pair on the nearest empty area: some menus (and any
     *     "click outside to close" handler) only listen for that.
     *  3. Escape, at the focused element and the document.
     *
     * It then reports whether a wide, short, floating strip of controls is still on
     * screen. `false` means the page is still stuck, and the caller is expected to do
     * something heavier than this.
     */
    public void unstickPage() {
        WebView w = current();
        if (w == null) return;
        w.evaluateJavascript(JS_UNSTICK, null);
    }

    /**
     * Escape, dispatched at the page.
     *
     * Arena's overlays are drawn inside the page, so the app cannot close them the way it
     * closes its own sheets. They all answer Escape - the "Was this task successful?" card
     * prints "Esc" in its corner precisely because the page listens for that key. A phone
     * has no such key, so Back dispatches it.
     *
     * The events carry `key` and the legacy `keyCode`/`which`, because real handlers in
     * the wild read either. A `<dialog open>` is closed explicitly, since the browser's own
     * Escape handling does not run for a synthetic event.
     *
     * It answers one boolean through AgonEscape.report(): whether something that was
     * covering the top of the screen is gone now. That is the only thing the activity
     * needs to know to decide whether Back has done its job or should behave as before.
     * Tested in jsdom against window, document and legacy-keyCode handlers.
     */
    /**
     * The page-side half of {@link #unstickPage()}.
     *
     * Only the toolbar *shape* is matched, never a class name or a piece of arena's
     * markup: a floating palette of three or more controls, wide-and-short or
     * tall-and-narrow, that is neither the page's own bottom bar nor anything holding an
     * editable field. That is what was left on screen in the recording - measured there
     * as a vertical pill of about 55x250 CSS px - and it is a shape rather than an
     * identifier because arena renames its classes freely.
     */
    private static final String JS_UNSTICK =
            "(function(){try{"
            + "function strip(el){"
            + "var cs=getComputedStyle(el);"
            + "if(cs.position!=='fixed'&&cs.position!=='absolute'&&"
            + "cs.position!=='sticky')return false;"
            + "if(cs.visibility==='hidden'||cs.display==='none')return false;"
            + "var r=el.getBoundingClientRect();"
            // Measured from the recording, not guessed: the float that got stuck is a
            // VERTICAL pill (keyboard / emoji / menu) about 55x250 CSS px over the left
            // edge. A first version only accepted a wide, short strip and therefore
            // matched nothing at all. A palette of controls can be either way round, so
            // both are accepted and neither is anchored to a class name.
            + "var wide=r.width>=110&&r.width<=620&&r.height>=30&&r.height<=110;"
            + "var tall=r.width>=28&&r.width<=130&&r.height>=110&&r.height<=420;"
            + "if(!wide&&!tall)return false;"
            // not the page's own bottom bar
            + "if(r.bottom>window.innerHeight-40)return false;"
            // not the page's main content region, and not a panel that holds an editable
            // field - an emoji picker with a search box is content, not a stray toolbar
            + "if(el.querySelector('main,textarea,form,[contenteditable],input'))return false;"
            // three or more separate controls: a byline or a chip has one or two
            + "var n=el.querySelectorAll('button,[role=\"button\"],a,svg').length;"
            + "return n>=3;}"
            + "function find(){"
            + "var els=document.querySelectorAll('body *');"
            + "for(var i=0;i<els.length;i++){"
            + "var el=els[i];"
            + "if(strip(el))return el;}"
            + "return null;}"
            // 1. let go of the focused field
            + "var ae=document.activeElement;"
            + "if(ae&&ae!==document.body&&ae.blur)try{ae.blur();}catch(e){}"
            // 2. a press on a genuinely EMPTY area: what closes a click-outside menu
            //
            // This used to fire pointerdown/up and mousedown/up at a fixed point on the left
            // edge, without looking at what was there. On a phone that point is often a
            // control - a row in a list, a toggle, the files panel - so pressing Back could
            // press a random button on the way past: the "random touches" report. The point
            // is now chosen by testing candidates and taking the first that is inert: the
            // page background itself, or a container with no controls in it. If nothing is
            // inert, no press is sent at all.
            + "try{"
            + "function inert(el){"
            + "if(!el||el===document.documentElement)return false;"
            + "if(el.closest&&el.closest("
            + "'a,button,input,select,textarea,label,summary,[role=\"button\"],"
            + "[role=\"link\"],[role=\"menuitem\"],[role=\"tab\"],"
            + "[role=\"checkbox\"],[role=\"switch\"],[contenteditable]'))return false;"
            + "var cur=el;"
            + "for(var up=0;up<4&&cur&&cur!==document.body;up++){"
            + "if(cur.querySelector&&cur.querySelector("
            + "'a,button,input,select,textarea,[role=\"button\"],[role=\"link\"]'))"
            + "return false;"
            + "cur=cur.parentElement;}"
            + "return true;}"
            + "var pts=[[6,Math.round(window.innerHeight*0.62)],"
            + "[Math.round(window.innerWidth*0.5),Math.round(window.innerHeight*0.06)],"
            + "[Math.round(window.innerWidth-6),Math.round(window.innerHeight*0.62)]];"
            + "var target=null;"
            + "for(var pi=0;pi<pts.length&&!target;pi++){"
            + "var cand=document.elementFromPoint?document.elementFromPoint(pts[pi][0],pts[pi][1]):null;"
            + "if(inert(cand))target=cand;}"
            + "if(target){"
            + "var p={bubbles:true,cancelable:true,composed:true,"
            + "clientX:pts[0][0],clientY:pts[0][1],pointerId:1,pointerType:'mouse',"
            + "isPrimary:true};"
            + "if(window.PointerEvent){"
            + "target.dispatchEvent(new PointerEvent('pointerdown',p));"
            + "target.dispatchEvent(new PointerEvent('pointerup',p));}"
            + "target.dispatchEvent(new MouseEvent('mousedown',p));"
            + "target.dispatchEvent(new MouseEvent('mouseup',p));}"
            + "}catch(e){}"
            // 3. Escape, at whatever has focus and at the document
            + "try{"
            + "var k={key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,"
            + "cancelable:true,composed:true};"
            + "(document.activeElement||document.body).dispatchEvent("
            + "new KeyboardEvent('keydown',k));"
            + "document.dispatchEvent(new KeyboardEvent('keydown',k));"
            + "}catch(e){}"
            + "AgonEscape.stuck(!!find());"
            + "}catch(e){try{AgonEscape.stuck(false);}catch(x){}}})();";

    private static final String JS_ESCAPE =
            "(function () {"
            + "  try {"
            + "    function overlays() {"
            + "      var n = 0, els = document.querySelectorAll('body *');"
            + "      for (var i = 0; i < els.length && i < 1500; i++) {"
            + "        var el = els[i], r = el.getBoundingClientRect();"
            + "        if (r.width < 200 || r.height < 120) continue;"
            + "        var cs = getComputedStyle(el);"
            + "        if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;"
            + "        if (cs.display === 'none' || cs.visibility === 'hidden') continue;"
            + "        if (r.top > window.innerHeight * 0.25) continue;"
            + "        n++;"
            + "      }"
            + "      return n;"
            + "    }"
            + "    var before = overlays();"
            + "    function press(node) {"
            + "      if (!node || !node.dispatchEvent) return;"
            + "      var init = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27,"
            + "                   bubbles: true, cancelable: true, composed: true };"
            + "      try {"
            + "        node.dispatchEvent(new KeyboardEvent('keydown', init));"
            + "        node.dispatchEvent(new KeyboardEvent('keyup', init));"
            + "      } catch (e) {"
            + "        var ev = document.createEvent('Event');"
            + "        ev.initEvent('keydown', true, true);"
            + "        ev.keyCode = 27; ev.which = 27;"
            + "        node.dispatchEvent(ev);"
            + "      }"
            + "    }"
            + "    press(document.activeElement || document.body);"
            + "    press(document.body);"
            + "    press(document);"
            + "    var dlg = document.querySelector('dialog[open]');"
            + "    if (dlg) { try { dlg.close(); } catch (e) {} }"
            + "    setTimeout(function () {"
            + "      try { AgonEscape.report(overlays() < before); } catch (e) {}"
            + "    }, 140);"
            + "  } catch (e) {"
            + "    try { AgonEscape.report(false); } catch (x) {}"
            + "  }"
            + "})();";

    /** Loads a URL in the tab on screen (the current window, if one is in front). */
    public void loadInCurrent(String url) {
        WebView w = current();
        if (w != null) w.loadUrl(url);
    }

    /**
     * A WebView whose text-selection menu carries no "Process text" / "Share" items.
     *
     * Why a subclass and not a plain call: setActionModeCallback() is a TextView API -
     * WebView has no such method, so calling it on a WebView cannot compile. Chromium
     * starts the selection menu through View.startActionMode(), which is what is wrapped
     * here: once the framework has populated the menu, the two outbound intents are
     * dropped, leaving the standard cut / copy / paste / select-all items. That keeps
     * editing inside fields intact while a long-press on a page can no longer hand the
     * selection to the Google app / Assistant.
     */
    static final class SelectionSafeWebView extends WebView {

        /** Told when the user holds a finger on the page, with no selection engine involved. */
        interface HoldListener {
            /**
             * A finger has been down on the page for {@link #HOLD_MS}.
             *
             * `selection` is what the page reports as already selected at that moment - a
             * word the user dragged over, twice-tapped, or picked with a handle. When it is
             * non-empty it is the copy: the user has already said which text they mean, and
             * replacing it with the whole paragraph under the finger is what made a hold
             * feel like it grabs everything.
             */
            void onHold(int x, int y, String selection);
        }

        /**
         * How long a still finger has to stay down to count as a deliberate copy.
         *
         * A quick tap, a scroll and a flick are all under this, and so is the moment a
         * page's own long-press menu appears. 520 ms is long enough that nothing accidental
         * reaches it and still short enough to feel like a deliberate press.
         */
        static final long HOLD_MS = 700L;
        /** How far the finger may drift and still be a hold rather than a scroll. */
        private static final float SLOP_DP = 10f;
        /**
         * A finger that could not stay STILL for this long is not a hold.
         *
         * The slop test compares the finger with where it started, so a slow, twitchy press
         * that wanders a centimetre and comes back counts as still - and then copies on the
         * lift, which is a tap the user never meant as a copy. Frame-to-frame distance is
         * what tells a resting finger from a moving one.
         */
        private static final long STILL_MS = 260L;
        private static final float STILL_DP = 4f;
        /**
         * A finger still down after this long is resting on the page, not asking for a copy.
         *
         * The copy happens on the lift (so a hold-then-drag can extend a selection first),
         * which means a finger parked on the screen - reading, thinking, holding the phone -
         * was copied the moment it came off. Past this, the press is abandoned.
         */
        private static final long REST_MS = 2500L;

        private HoldListener holdListener;

        /**
         * What the inside of a press is doing, in words the tests can read.
         *
         * The copy rules are timings in Java, and a phone build is the only place they used
         * to be checkable - which is how "just clicking anywhere copies everything" reached
         * a release. These flags are the same state, published: a short tap can be asserted
         * to never arm, a hold on a button never to arm, and a hold on empty space to arm
         * and copy on the lift only. Instrumentation, not behaviour: setting it changes
         * nothing that happens on screen.
         */
        static final class Trace {
            StringBuilder log = new StringBuilder();
            boolean press;
            boolean swallowed;
            boolean armed;
            boolean requested;
            boolean cancelled;

            synchronized void note(String what) {
                if (log.length() > 0) log.append(',');
                log.append(what);
            }

            synchronized String log() {
                return log.toString();
            }

            void reset() {
                log = new StringBuilder();
                press = false;
                swallowed = false;
                armed = false;
                requested = false;
                cancelled = false;
            }
        }

        private final Trace trace = new Trace();

        Trace trace() {
            return trace;
        }

        private final android.os.Handler holdHandler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        private final Runnable arm = this::armHold;
        private final float slop;
        private float downX;
        private float downY;
        private boolean holding;
        /** The point that is ready to copy when the finger lifts, or null when none is. */
        private android.graphics.Point armed;
        /** What the page said is already selected, read once per hold. */
        private String selection = "";
        /** Last MOVE position, for the frame-to-frame stillness test. */
        private float lastX;
        private float lastY;
        private long lastMoveAt;
        /** True while the finger has not wandered since it came down. */
        private boolean still;
        /**
         * Abandons a finger that has been resting on the page far too long.
         *
         * Posted on ACTION_DOWN, cleared by either cancel path: a press that is still down
         * when it fires is holding the phone, not asking for text.
         */
        private final Runnable rest = () -> {
            if (armed == null && !holding) return;
            trace.cancelled = true;
            trace.note("rest");
            cancelArmed();
            cancelHold();
        };

        SelectionSafeWebView(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            slop = SLOP_DP * density;
        }

        void setHoldListener(HoldListener listener) {
            holdListener = listener;
        }

        /**
         * The finger has been still long enough: remember the point and ask the page what is
         * selected there. Nothing is copied yet - see onTouchEvent.
         */
        private void armHold() {
            holding = false;
            armed = new android.graphics.Point(Math.round(downX), Math.round(downY));
            selection = "";
            trace.armed = true;
            trace.note("arm");
            // On the view, not on this class: evaluateJavascript is WebView's.
            SelectionSafeWebView.this.evaluateJavascript(SELECTION_PROBE, value -> {
                String text = unquoteJs(value);
                if (text != null && !text.isEmpty()) selection = text;
            });
        }

        private void cancelHold() {
            holding = false;
            holdHandler.removeCallbacks(arm);
        }

        /** The finger moved away from where it was pressed: this is a scroll, not a copy. */
        private void cancelArmed() {
            armed = null;
            selection = "";
        }

        /**
         * Android's own long press would otherwise be the only reaction to a hold, and it
         * does exactly nothing here: it does not select and it does not offer a menu.
         *
         * A hold is a deliberate request for the text under the finger, so it is timed here
         * in Java as well as in the page. Whichever of the two notices first does the copy,
         * which is why "i cant copy" cannot come back as silently as it kept doing: the
         * page's own timer needs JavaScript to be alive and the touches to arrive, and this
         * one needs only the touch.
         */
        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    lastX = downX;
                    lastY = downY;
                    lastMoveAt = event.getEventTime();
                    still = true;
                    holding = true;
                    trace.reset();
                    trace.press = true;
                    trace.note("down");
                    cancelArmed();
                    holdHandler.removeCallbacks(arm);
                    holdHandler.postDelayed(arm, HOLD_MS);
                    holdHandler.postDelayed(rest, REST_MS);
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    // Frame to frame: a wandering finger has not been still, however close
                    // it ends up to where it started.
                    float step = Math.max(Math.abs(event.getX() - lastX),
                            Math.abs(event.getY() - lastY));
                    long now = event.getEventTime();
                    if (step > STILL_DP * (slop / SLOP_DP)) {
                        still = false;
                        lastMoveAt = now;
                    } else if (still && now - lastMoveAt > STILL_MS) {
                        still = false;
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                    if (holding && (dx > slop || dy > slop || !still)) {
                        // The finger travelled before the hold fired: a scroll, a drag, or a
                        // tap with a shaky hand. None of them is a request to copy.
                        cancelHold();
                    }
                    if (armed != null && (dx > slop * 3 || dy > slop * 3)) {
                        // It was a hold, and then the finger kept going: the user is
                        // extending a selection by hand, so leave the copy to them.
                        cancelArmed();
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    cancelHold();
                    if (armed != null) {
                        final int x = armed.x;
                        final int y = armed.y;
                        final String chosen = selection;
                        cancelArmed();
                        if (holdListener != null) holdListener.onHold(x, y, chosen);
                        trace.requested = true;
                        trace.note("hold");
                        // The press became a copy, so the page must NOT also be tapped.
                        // Copying and then firing whatever was under the finger is how one
                        // long press both copied a reply and did what it was covering.
                        return true;
                    }
                    break;
                case android.view.MotionEvent.ACTION_CANCEL:
                    cancelHold();
                    cancelArmed();
                    break;
                default:
                    break;
            }
            return super.onTouchEvent(event);
        }

        @Override
        public android.view.ActionMode startActionMode(
                android.view.ActionMode.Callback callback, int type) {
            return super.startActionMode(new Filtered(callback), type);
        }

        @Override
        public android.view.ActionMode startActionMode(
                android.view.ActionMode.Callback callback) {
            return super.startActionMode(new Filtered(callback));
        }
    }

    /** Wraps the WebView's own selection callback so the menu can be pruned. */
    private static final class Filtered implements android.view.ActionMode.Callback {
        private final android.view.ActionMode.Callback inner;

        Filtered(android.view.ActionMode.Callback inner) {
            this.inner = inner;
        }

        @Override
        public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
            return inner.onCreateActionMode(mode, menu);
        }

        @Override
        public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
            boolean changed = inner.onPrepareActionMode(mode, menu);
            for (int i = menu.size() - 1; i >= 0; i--) {
                android.view.MenuItem item = menu.getItem(i);
                android.content.Intent intent = item.getIntent();
                if (intent == null) continue;
                String action = intent.getAction();
                if (android.content.Intent.ACTION_PROCESS_TEXT.equals(action)
                        || android.content.Intent.ACTION_SEND.equals(action)) {
                    menu.removeItem(item.getItemId());
                    changed = true;
                }
            }
            return changed;
        }

        @Override
        public boolean onActionItemClicked(android.view.ActionMode mode,
                                          android.view.MenuItem item) {
            return inner.onActionItemClicked(mode, item);
        }

        @Override
        public void onDestroyActionMode(android.view.ActionMode mode) {
            inner.onDestroyActionMode(mode);
        }
    }
}
