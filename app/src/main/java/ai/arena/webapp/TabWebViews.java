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
        int id = openWindow();
        if (id < 0) return -1;
        WebView w = views.get(id);
        if (w != null) {
            // Loads immediately (ensure() did that); asking for a new chat here means the
            // tab is empty by the time anyone looks at it.
            w.postDelayed(() -> startNewChat(w, null), 700L);
        }
        return id;
    }

    /** Windows the user has actually opened (as opposed to one built ahead of time). */
    private final java.util.Set<Integer> usedWindows = new java.util.HashSet<>();

    /** Closes an extra window. The one on screen is never left as the current page. */
    public boolean closeWindow(int id) {
        if (!isWindow(id)) return false;
        WebView w = views.remove(id);
        if (w == null) return false;
        loaded.remove(id);
        reportedTitles.remove(id);
        container.removeView(w);
        w.destroy();
        if (currentTab == id) {
            currentTab = AppUi.TAB_CHAT;
            WebView chat = show(AppUi.TAB_CHAT);
            if (chat != null) chat.bringToFront();
        }
        return true;
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
                    // Arena's own menus still have to fit the window...
                    + "(function(){" + JS_POPUP_FIT + "})();"
                    // ...and the page's own panel still has to go, if that is what was asked.
                    + (hideSidebar || hideHeader ? "(function(){try{"
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
                // An app is not a web page: no accidental text selection and no long-press
                // copy callout. Inputs and editable areas keep normal behaviour.
                + (selectable ? "" : "*{-webkit-user-select:none !important;"
                + "user-select:none !important;-webkit-touch-callout:none !important;}"
                + "input,textarea,[contenteditable=\"" + "true" + "\"]{"
                + "-webkit-user-select:text !important;user-select:text !important;}")
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
            "var fitted=new WeakMap();"
            + "function fixPopups(){"
            + "var vh=window.innerHeight||document.documentElement.clientHeight;"
            + "var all=document.querySelectorAll('div,ul,section,article');"
            + "for(var i=0;i<all.length;i++){var el=all[i];"
            + "if(el===document.body||el===document.documentElement)continue;"
            + "var r=el.getBoundingClientRect();"
            + "if(r.width<120||r.height<80)continue;"
            + "if(r.height>=vh-24)continue;"                      // a panel, not a menu
            // A menu is short. A panel that takes half the window or more (a chat's
            // files/workspace rail) is not a menu: capping its height and giving it its
            // own scrollbar is what let it open looking empty, so it is left alone.
            + "if(r.height>vh*0.5)continue;"
            + "var cs=getComputedStyle(el);"
            + "if(cs.position!=='fixed'&&cs.position!=='absolute')continue;"
            + "if(el.querySelector('main,form,textarea,[contenteditable=\"true\"]'))continue;"
            + "var opts=el.querySelectorAll('button,[role=\"option\"],[role=\"menuitem\"],li,a');"
            + "if(opts.length<3)continue;"
            + "var overTop=r.top<4,overBottom=r.bottom>vh-4;"
            + "if(!overTop&&!overBottom){"
            + "if(fitted.has(el)){var saved=fitted.get(el);fitted.delete(el);"
            + "el.style.maxHeight=saved.mh;el.style.height=saved.h;el.style.overflowY=saved.oy;}"
            + "continue;}"
            + "if(!fitted.has(el))fitted.set(el,{mh:el.style.maxHeight,h:el.style.height,"
            + "oy:el.style.overflowY});"
            // Room on the side it is clipped from: below the top edge, or above the bottom.
            + "var room=overTop?Math.floor(r.bottom-8):Math.floor(vh-r.top-8);"
            + "if(room<120)continue;"
            + "el.style.maxHeight=room+'px';el.style.overflowY='auto';"
            + "el.style.setProperty('overscroll-behavior','contain');"
            + "}}"
            // A menu is rendered when it is opened, so run on every frame-batch cheaply and
            // pick it up right after each click/tap as well.
            + "fixPopups();"
            + "document.addEventListener('click',function(){"
            + "setTimeout(fixPopups,0);setTimeout(fixPopups,120);},true);"
            + "window.addEventListener('resize',function(){fixPopups();},true);"
            + "if(window.visualViewport)window.visualViewport.addEventListener('resize',"
            + "function(){fixPopups();});"
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
            // 1. arena's own control, when it is on screen
            + "var sel=['[aria-label*=\"new chat\" i]','[data-testid*=\"new-chat\" i]',"
            + "'button[title*=\"new chat\" i]','a[title*=\"new chat\" i]',"
            + "'a[href=\"/\"]','a[href$=\"/chat\"]'];"
            + "for(var i=0;i<sel.length;i++){"
            + "var el=document.querySelector(sel[i]);"
            + "if(el&&el.offsetParent!==null){el.click();tell('clicked');return;}}"
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
    private static final String JS_NEW_CHAT_LISTENER =
            "(function(){"
            + "if(window.__arenaNewChatKey)return;window.__arenaNewChatKey=1;"
            + "document.addEventListener('keydown',function(e){"
            + "if(e.key!=='n'&&e.key!=='N')return;"
            + "if(!(e.ctrlKey||e.metaKey)||e.shiftKey||e.altKey)return;"
            + "e.preventDefault();"
            + "try{if(window.ArenaNative&&ArenaNative.newChat)ArenaNative.newChat();}catch(x){}}"
            + ",true);})();";

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

    /** Text-based fallback for a sidebar whose class names say nothing. */
    private static final String JS_SIDEBAR =
            "var cands=document.body?document.body.querySelectorAll('nav,aside,div'):[];"
            + "for(var i=0;i<cands.length;i++){var el=cands[i];"
            + "if(!shown(el)||!empty(el))continue;"
            + "var r=el.getBoundingClientRect();"
            // Shape, not a desktop size: a sidebar is a column - taller than it is wide -
            // and it sits against one edge of the window. The old rule also required
            // width <= 420 CSS px, which is a desktop figure; on a phone the panel is a
            // slide-over the full width of the screen, so the rule could never match there
            // while it matched happily in a desktop-shaped emulator window. That is
            // the it-hides-in-the-emulator-but-not-on-my-phone report.
            + "if(r.height<r.width*0.9)continue;"
            + "if(r.left>window.innerWidth*0.2&&"
            + "r.right<window.innerWidth*0.8)continue;"
            + "var t=text(el);if(t.length>700)continue;"
            // The three fixed entries, then the account row, which is an email address once
            // signed in and a sign-in label before that. Requiring all four terms, including
            // the literal sign-in text, is why the sidebar was never hidden on a signed-in
            // phone.
            + "var words=['new chat','leaderboard','search'];"
            + "var hits=0;"
            + "for(var j=0;j<words.length;j++){if(t.indexOf(words[j])>=0)hits++;}"
            + "var account=/[\\w.+-]+@[\\w-]+\\.[\\w.-]+/.test(t)||t.indexOf('sign in')>=0"
            + "||t.indexOf('sign up')>=0||t.indexOf('log in')>=0||t.indexOf('login')>=0;"
            // Two of the three words plus an account row is a navigation panel; the words
            // alone are a start page and are left alone. On a narrow panel arena hides one
            // of them behind its own overflow control often enough that requiring all
            // three kept the panel on screen.
            + "if(hits<2||!account)continue;"
            + "if(el.querySelectorAll('a,button,[role=button],[role=link]').length<3)continue;"
            // ...and never hide anything that contains the composer or the main region.
            + "if(el.querySelector('main,textarea,form,[contenteditable]'))continue;"
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
        // A tab created for a *new chat* must not stop at whatever conversation the root
        // route restores: once its document exists, ask the page for a fresh one. This
        // runs once per tab, and only for tabs the user asked for (never for the chat tab
        // restored on launch, which must come back to what it was showing).
        if (isWindow(tab)) {
            w.postDelayed(() -> {
                if (views.get(tab) == w) startNewChat(w, null);
            }, 900L);
        }
        trim();
        return w;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView createWebView(int tab) {
        WebView w = new SelectionSafeWebView(activity);
        w.setBackgroundColor(AppUi.color(activity, R.color.bg));
        w.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
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
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
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
                // The chat tab's chip is a fixed label, but its name still matters: it is
                // what the history sheet and the window list show for the conversation that
                // is open. Every tab is asked, and only the extra tabs display it.
                installTabTitle(view, name -> rememberTitle(tab, name));
                // The page made it: if the "stuck" bar is up for this tab, drop it.
                if (activity instanceof MainActivity) ((MainActivity) activity).clearStalledLoad();
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
            // 2. a pointer press on an empty area: what closes a click-outside menu
            + "try{"
            + "var target=document.elementFromPoint?"
            + "document.elementFromPoint(8,Math.round(window.innerHeight*0.5)):document.body;"
            + "if(!target)target=document.body;"
            + "var p={bubbles:true,cancelable:true,composed:true,clientX:8,"
            + "clientY:Math.round(window.innerHeight*0.5),pointerId:1,pointerType:'touch',"
            + "isPrimary:true};"
            + "if(window.PointerEvent){"
            + "target.dispatchEvent(new PointerEvent('pointerdown',p));"
            + "target.dispatchEvent(new PointerEvent('pointerup',p));}"
            + "target.dispatchEvent(new MouseEvent('mousedown',p));"
            + "target.dispatchEvent(new MouseEvent('mouseup',p));"
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

        SelectionSafeWebView(Context context) {
            super(context);
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
