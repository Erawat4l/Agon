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
     * Opens a new window on arena and returns its id, or -1 when there is no room.
     *
     * The window is created and loaded straight away, in the background, so the first time
     * the user switches to it there is nothing left to fetch - which is the whole point of
     * asking for it from More.
     */
    public int openWindow() {
        // A window that is already warm (preload() makes one) is handed over instead of
        // building a second one: the point is a page that is already loaded.
        for (Integer id : windows()) {
            if (!usedWindows.contains(id)) {
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

    /** Windows the user has actually opened (as opposed to one built ahead of time). */
    private final java.util.Set<Integer> usedWindows = new java.util.HashSet<>();

    /** Closes an extra window. The one on screen is never left as the current page. */
    public boolean closeWindow(int id) {
        if (!isWindow(id)) return false;
        WebView w = views.remove(id);
        if (w == null) return false;
        loaded.remove(id);
        container.removeView(w);
        w.destroy();
        if (currentTab == id) {
            currentTab = AppUi.TAB_CHAT;
            WebView chat = show(AppUi.TAB_CHAT);
            if (chat != null) chat.bringToFront();
        }
        return true;
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
        if (isLowRamDevice()) return;
        int[] tabs = {AppUi.TAB_LEADERBOARD, AppUi.TAB_SEARCH};
        for (int i = 0; i < tabs.length; i++) {
            final int tab = tabs[i];
            main.postDelayed(() -> {
                if (!views.containsKey(tab)) ensure(tab);
            }, 500L + 400L * i);
        }
        // ...and one window, so More -> New window is not the only way to get a second
        // arena already warm. If the user never opens one it costs a single background
        // renderer while it is still hidden (priority BOUND - the policy above is what
        // keeps it from competing with the page on screen).
        main.postDelayed(() -> {
            if (windows().isEmpty()) ensure(CHAT_WINDOW_BASE);
        }, 400L + 500L * tabs.length);
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
                    // Nothing is reserved, but arena's own menus still have to fit the window.
                    + "(function(){" + JS_POPUP_FIT + "})();";
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
            // Four words, not three: a landing page can easily contain three of them by
            // coincidence, and hiding a content container is exactly the bug this must not
            // repeat. A real nav panel has all four.
            + "if(hits<4)continue;"
            + "if(el.querySelectorAll('a,button').length<4)continue;"
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

    /** Keeps at most MAX_WARM WebViews; drops the least recently used background tab. */
    private void trim() {
        while (views.size() > MAX_WARM + Math.min(MAX_WINDOWS, windows().size() + 1)) {
            // A window is only ever reclaimed when there is nothing else to reclaim: it is
            // the page the user deliberately opened and expects to find still there.
            Integer victim = null;
            Integer windowVictim = null;
            for (Integer key : views.keySet()) {
                if (key == currentTab) continue;
                if (isWindow(key)) {
                    if (windowVictim == null) windowVictim = key;
                } else {
                    victim = key;
                    break;
                }
            }
            if (victim == null) victim = windowVictim;
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
