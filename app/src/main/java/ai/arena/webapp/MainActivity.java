package ai.arena.webapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Arena as a native app.
 *
 * Speed comes from warm tabs: every tab is a persistent WebView created on demand and
 * kept alive, so tapping a nav item only flips visibility (no reload, no white flash).
 * Sign-in from Google/Apple happens in an in-app popup WebView, never a browser.
 */
public class MainActivity extends Activity implements UpdateSheet.Host {

    public static final String ACTION_RELOAD = "ai.arena.webapp.RELOAD";
    public static final String EXTRA_OPEN_UPDATE = "open_update";
    public static final String EXTRA_NEW_VERSION = "new_version";

    private static final int REQ_FILE = 4201;
    private static final int REQ_PERMISSIONS = 4202;
    private static final int REQ_NOTIFICATIONS = 4203;
    private static final int REQ_INSTALL_UNKNOWN = 4204;

    private FrameLayout window;
    private FrameLayout contentFrame;
    private TabWebViews tabs;
    private AppUi.Nav nav;
    private View navArea;
    /** The browser strip above the page: back, the open tabs, forward, and a new tab. */
    private TabStrip tabStrip;
    /** Real system navigation-bar / gesture inset, read from the window, never assumed. */
    private int systemNavInset = 0;
    /** The bar the page must stay clear of: the app's own bar plus the system gesture area. */
    private int chromeBottom = 0;
    private View errorView;
    /** The floating-window logic, and the only place that touches PiP state. */
    private Pip pip;
    /** The "no tabs open" screen, shown when the last tab is closed. Built on first use. */
    private View emptyView;
    private ConnectivityManager.NetworkCallback connectivity;
    private UpdateSheet updateSheet;
    private View menuSheet;
    private SignInSheet signInSheet;
    private SettingsSheet settingsSheet;
    private ChatHistorySheet historySheet;

    /** A load that has not finished after this long is treated as stuck. */
    private static final long STALL_MS = 9000L;
    /** When each tab's current load started (elapsedRealtime), for the stall bar. */
    private final java.util.HashMap<Integer, Long> stalledAt = new java.util.HashMap<>();
    /** The last progress each tab reported, so a stalled load can be judged later. */
    private final java.util.HashMap<Integer, Integer> stalledProgress = new java.util.HashMap<>();
    /** The one-and-only "this page is stuck" bar, built on first use. */
    private View stalledBar;

    private boolean navExpanded = false;
    /** Saved-state key: the user had closed every tab before this rotation. */
    private static final String STATE_NO_TABS = "agon_no_tabs";
    private int currentTab = AppUi.TAB_CHAT;
    private int tabBeforeUpdate = AppUi.TAB_CHAT;
    private static final long CHAT_TAP_WINDOW_MS = 380;
    private final android.os.Handler tapHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int chatTaps;

    /** Runs once the tap burst ends: 1 = chat, 2 = sidebar, 3+ = new chat. */
    private final Runnable flushChatTaps = () -> {
        int taps = chatTaps;
        chatTaps = 0;
        if (isFinishing() || isDestroyed()) return;
        if (taps == 1) {
            showChat();
        } else if (taps == 2) {
            openSidebar();
        } else if (taps >= 3) {
            openNewChat();
        }
    };
    private long lastBackPressMs;
    private File pendingInstallApk;

    private ValueCallback<Uri[]> pendingFileCallback;
    /** The page whose Escape was last attempted, so Back is only spent once per screen. */
    private int escapeUsedFor = -1;
    /** True once the page has answered the current Escape attempt (or timed out). */
    private boolean escapeSettled = false;
    /** The page whose stuck-overlay repair was last attempted, so it happens once. */
    private int unstuckTriedFor = -1;
    /** True once the page has answered the un-stick attempt (or timed out). */
    private boolean stuckSettled = false;

    /** A window the page opened for a non-arena address (a preview, a docs link). */
    private WebWindow webWindow;
    private PermissionRequest pendingPermissionRequest;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void attachBaseContext(Context base) {
        // Honours the in-app Light / Dark / System preference for every resource lookup.
        super.attachBaseContext(AppUi.wrap(base));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Catch anything the startup path throws: a crash must show its trace on screen
        // (and in files/last_crash.txt) instead of closing the app with no message.
        CrashLog.install(this);
        // Only when the user has unlocked developer options: this exposes chrome://inspect
        // over adb for whatever is open in a tab, which is how a panel that renders empty
        // can be looked at instead of guessed about. Never on for an ordinary build.
        if (Settings.developerOptions(this)) {
            try {
                WebView.setWebContentsDebuggingEnabled(true);
            } catch (RuntimeException ignored) {
            }
        }
        try {
            buildShell(savedInstanceState);
        } catch (Throwable error) {
            CrashLog.write(this, error);
            if (Settings.crashScreen(this)) {
                showCrashScreen(error);
            } else {
                throw error;
            }
        }
    }

    /** The real startup path; wrapped by onCreate so a failure is reportable. */
    @SuppressLint("SetJavaScriptEnabled")
    private void buildShell(Bundle savedInstanceState) {
        setStatusBarColors();
        UpdateNotifications.ensureChannels(this);

        window = new FrameLayout(this);
        window.setBackgroundColor(AppUi.color(this, R.color.bg));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        window.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // No pull-to-refresh: dragging the page reloaded it, which the user explicitly
        // does not want. A reload is one tap away in More -> Reload.
        contentFrame = new FrameLayout(this);

        FrameLayout contentHost = new FrameLayout(this);
        contentHost.addView(contentFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        watchConnectivity();
        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        contentHost.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Chrome closes the window when the last tab goes; an app cannot, so it draws the
        // one thing that is true - there are no tabs - and one way out of it.
        emptyView = buildEmptyView();
        emptyView.setVisibility(View.GONE);
        contentHost.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The strip is built before the tabs exist, so its first refresh() happens from
        // refreshChrome() once buildShell has finished wiring everything up.
        tabStrip = buildTabStrip();
        column.addView(tabStrip.view(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tabStrip.view().setVisibility(Settings.showTabStrip(this) ? View.VISIBLE : View.GONE);
        column.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Warm-tab host. Assigned after construction, so the host callbacks must not
        // touch "tabs" while the first WebView is being built inside the constructor.
        tabs = new TabWebViews(this, contentFrame, new TabWebViews.Host() {
            @Override
            public TabWebViews.AppWebViewClientFactory clientFactory() {
                return MainActivity.this::newTabClient;
            }

            @Override
            public TabWebViews.WebChromeClientFactory chromeFactory() {
                return MainActivity.this::newTabChrome;
            }

            @Override
            public android.webkit.DownloadListener downloadListener() {
                return new Downloads(MainActivity.this);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // TabWebViews.withLoadHook() already injects the insets on
                // onPageStarted/onPageFinished; recalculate when the bar heights change
                // (expander toggles, pill appearing).
                applyChromeInsets();
                // A finished load is where canGoBack/canGoForward settle (a redirect, a
                // pushState), so the arrows are re-read here rather than polled.
                refreshStrip();
                // Only now is the network free: warm the other tabs in the background so
                // the first tap on them is still instant, without competing with the
                // first paint (which used to make startup feel slow).
                schedulePreload();
            }

            @Override
            public void onMainFrameError(WebView view) {
                showError();
            }

            @Override
            public void onLoadProgress(WebView view, int progress) {
                // progress is drawn by the page itself
            }
        });

        // No head panel and no top strip: arena's own header is the header.
        //
        // The nav used to be an *overlay* pinned to the bottom of the window, with the page
        // told to reserve its height in CSS. That never worked: the WebView still filled the
        // whole column, so arena's own bottom row - the model picker, the attach button, the
        // send button - sat *behind* the bar, unseeable and untappable, and the only thing
        // left to tap was the top edge of it ("can't even click on model or add file").
        //
        // As the last row of the column it is real estate: the page ends where the bar
        // begins, nothing is covered, and no CSS has to guess. It is still the last child of
        // `column`, so sheets and the error view overlay the whole window above it.
        navArea = buildNavArea();
        column.addView(navArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(window);
        navArea.setVisibility(Settings.showNav(this) ? View.VISIBLE : View.GONE);

        // The app's bar is an overlay drawn at the bottom of the window. Where the system
        // navigation bar / gesture pill is *also* there (3-button navigation, most Android
        // 9-13 phones), the two overlapped and the icons sat on top of the system buttons -
        // visible in the screenshots as the app's bar merged into the gesture area.
        //
        // Android only reports that inset to a view that asks for it, so ask, then pad the
        // bar by exactly that much and reserve the same amount inside the page.
        window.setOnApplyWindowInsetsListener((v, insets) -> {
            // `nav` is a field (the bottom bar view); this is only a measurement.
            int systemInset = 0;
            int imeInset = 0;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.navigationBars());
                systemInset = bars.bottom;
                imeInset = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
                systemInset = insets.getSystemWindowInsetBottom();
            }
            if (systemInset != systemNavInset) {
                systemNavInset = systemInset;
                if (navArea != null) navArea.setPadding(0, 0, 0, systemInset);
                applyChromeInsets();
            }
            // The bar lives in the layout now, so when the keyboard covers its area it can
            // go away for that moment instead of sitting on top of the keyboard. The page
            // gets the room back automatically because the row is gone.
            boolean keyboard = imeInset > systemInset;
            if (navArea != null) {
                int want = keyboard ? View.GONE : (Settings.showNav(MainActivity.this)
                        ? View.VISIBLE : View.GONE);
                if (navArea.getVisibility() != want) navArea.setVisibility(want);
            }
            return insets;
        });
        window.requestApplyInsets();

        pip = new Pip(this);
        // A rotation rebuilds every tab (they are WebViews, not saved state), so the one
        // thing that has to be carried across is whether the user had closed them all:
        // otherwise a rotation would hand back the chat tab that was just closed.
        boolean noTabs = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_NO_TABS, false);
        if (noTabs) {
            showEmpty();
        } else {
            tabs.show(AppUi.TAB_CHAT);
        }
        if (savedInstanceState != null && tabs.current() != null) {
            tabs.current().restoreState(savedInstanceState);
        }
        applyChromeInsets();
        refreshStrip();
        refreshUi();
        handleIntent(getIntent());

        // Startup order matters for perceived speed: the page is already scrolling, so
        // anything that is not needed for the first frame waits for it. The update check
        // and the notification permission prompt used to run inside onCreate, where they
        // competed with the first render.
        window.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            refreshUpdateUi();
            askNotificationPermission();
        }, 900);

        // First frames: fade the shell in so the app never appears to "snap" together.
        window.setAlpha(0f);
        window.animate().alpha(1f).setDuration(180).start();
    }

    /**
     * Replaces the whole window with the trace of a fatal startup failure, plus a
     * button that copies it, so the user can paste it back instead of describing it.
     */
    private void showCrashScreen(final Throwable error) {
        try {
            final String trace = CrashLog.describe(this, error);

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(AppUi.color(this, R.color.bg));
            int pad = AppUi.dp(this, 18);
            box.setPadding(pad, pad, pad, pad);

            TextView title = AppUi.text(this, getString(R.string.crash_title), 18f,
                    R.color.on_bg, true);
            box.addView(title);

            TextView body = AppUi.text(this, getString(R.string.crash_body), 13f,
                    R.color.on_bg_muted, false);
            body.setPadding(0, AppUi.dp(this, 8), 0, AppUi.dp(this, 12));
            box.addView(body);

            TextView code = AppUi.text(this, trace, 11f, R.color.on_bg, false);
            code.setTypeface(android.graphics.Typeface.MONOSPACE);
            code.setTextIsSelectable(true);
            code.setPadding(AppUi.dp(this, 12), AppUi.dp(this, 12),
                    AppUi.dp(this, 12), AppUi.dp(this, 12));
            code.setBackground(AppUi.rounded(AppUi.color(this, R.color.surface2), 12f, this));

            android.widget.ScrollView scroller = new android.widget.ScrollView(this);
            scroller.addView(code, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(scroller, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            TextView copy = AppUi.text(this, getString(R.string.crash_copy), 15f,
                    R.color.on_accent, true);
            copy.setGravity(Gravity.CENTER);
            copy.setPadding(0, AppUi.dp(this, 14), 0, AppUi.dp(this, 14));
            copy.setBackground(AppUi.pressable(this, AppUi.color(this, R.color.accent), 14f));
            copy.setOnClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Arena crash", trace));
                    toast(R.string.crash_copied);
                }
            });
            LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            copyLp.topMargin = AppUi.dp(this, 14);
            box.addView(copy, copyLp);

            TextView retry = AppUi.text(this, getString(R.string.crash_retry), 13.5f,
                    R.color.on_bg_muted, false);
            retry.setGravity(Gravity.CENTER);
            retry.setPadding(0, AppUi.dp(this, 12), 0, 0);
            retry.setOnClickListener(v -> recreate());
            box.addView(retry);

            window = new FrameLayout(this);
            window.setBackgroundColor(AppUi.color(this, R.color.bg));
            window.addView(box, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(window);
        } catch (Throwable ignored) {
            // If even the crash screen cannot build, fall back to a plain view.
            TextView fallback = new TextView(this);
            fallback.setText(CrashLog.describe(this, error));
            setContentView(fallback);
        }
    }

    /** The scrollable currently on screen; re-pointed on every tab switch. */
    private View tabsScrollable() {
        return tabs == null || tabs.current() == null ? contentFrame : tabs.current();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_NOTIFICATIONS);
        }
    }

    private void setStatusBarColors() {
        Window w = getWindow();
        w.setStatusBarColor(AppUi.color(this, R.color.surface));
        w.setNavigationBarColor(AppUi.color(this, R.color.surface));
        int flags = w.getDecorView().getSystemUiVisibility();
        if (AppUi.isDark(this)) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    // ------------------------------------------------------------------ chrome

    /** The head panel is gone: arena draws its own header, so the app draws none. */
    /**
     * Slim, icon-only nav: Chat, Rankings, Search, More.
     *
     * The old bar repeated arena's own sidebar entries ("New Chat", "Leaderboard",
     * "Search") as labels *and* had a collapse handle, so the app drew a second copy of
     * arena's navigation. Now it is one compact row of icons; no expander, no labels.
     */
    private View buildNavArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setBackground(AppUi.chromeBg(this));

        View topLine = AppUi.divider(this);
        area.addView(topLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, AppUi.dp(this, 0.6f))));

        nav = AppUi.buildNav(this, false, v -> selectTab((Integer) v.getTag()));
        area.addView(nav.bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AppUi.collapsedHeight(this)));
        // Settings -> "Show version in the bar": a small build label under the icons.
        if (Settings.showVersion(this)) {
            TextView version = AppUi.text(this, getString(R.string.menu_version,
                            UpdateManager.currentVersionName(this),
                            UpdateManager.currentVersionCode(this)),
                    10f, R.color.on_bg_muted, false);
            version.setGravity(Gravity.CENTER);
            area.addView(version, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        // Labels stay hidden: the icon plus its content description is enough, and the
        // names exist inside arena's own sidebar.
        navExpanded = false;
        return area;
    }

    /**
     * Builds the browser strip and points it at the real tab model.
     *
     * The strip is deliberately thin: it asks which tab is in front, which ones exist and
     * what they are called, and reports taps back here. Selecting, closing and the arrows
     * all go through the same methods the nav and the More sheet use, so the strip cannot
     * disagree with them about what is on screen.
     */
    private TabStrip buildTabStrip() {
        return new TabStrip(this, new TabStrip.Host() {
            @Override
            public int currentTabId() {
                return pageId();
            }

            @Override
            public java.util.List<Integer> tabIds() {
                // The open conversations, in the order they were made. Leaderboard and
                // Search are deliberately not here: they are the bottom bar's
                // destinations, and a chip for them made the strip read as a second copy
                // of the nav.
                return openTabs();
            }

            @Override
            public String tabName(int tabId) {
                return tabLabel(tabId);
            }

            @Override
            public boolean canSelectPrevious() {
                return tabIndex(pageId()) > 0;
            }

            @Override
            public boolean canSelectNext() {
                java.util.List<Integer> ids = tabIds();
                int index = tabIndex(pageId());
                // A page that is not in the list yet (the moment between opening a tab and
                // it appearing) may still move right.
                return index < 0 ? ids.size() > 1 : index < ids.size() - 1;
            }

            @Override
            public boolean canOpenTab() {
                return tabs != null && tabs.windows().size() < TabWebViews.MAX_WINDOWS;
            }

            @Override
            public void onSelectTab(int tabId) {
                selectPage(tabId);
            }

            @Override
            public boolean isClosable(int tabId) {
                // Chrome's rule, per tab: a cross only where closing it would leave at least
                // one tab. With two tabs open both are closable - including the default chat
                // tab, which is the case that reported "it just goes to the home page".
                java.util.List<Integer> ids = openTabs();
                return ids.size() > 1 && ids.contains(tabId);
            }

            @Override
            public void onCloseTab(int tabId) {
                closeTabById(tabId);
            }

            @Override
            public void onPreviousTab() {
                stepTab(-1);
            }

            @Override
            public void onNextTab() {
                stepTab(1);
            }

            @Override
            public void onPageBack() {
                goBackInPage();
            }

            @Override
            public void onPageForward() {
                goForwardInPage();
            }

            @Override
            public void onNewTab() {
                newChatTab();
            }
        });
    }

    /** Where a page id sits in the strip, or -1 when it is not one of the open tabs. */
    /**
     * Every tab, left to right, as the strip draws them.
     *
     * One method, so the chips and the arrows can never disagree about the order - which is
     * exactly what was wrong: the strip listed the tabs and the arrows counted them
     * separately, and with a single conversation tab on screen the count came out as one
     * item, so the left arrow had nowhere to go and the right one dimmed itself.
     */
    private java.util.List<Integer> openTabs() {
        // The browser's row, straight from the tab layer: chat leftmost when it exists,
        // then the user's windows. It is no longer built here from "the chat tab plus the
        // windows", because the chat tab can be closed - and an empty row has to be able
        // to say so.
        if (tabs == null) return new java.util.ArrayList<>();
        return tabs.tabs();
    }

    /** Where a page id sits in that order, or -1 when it is not one of the open tabs. */
    private int tabIndex(int id) {
        return openTabs().indexOf(id);
    }

    /**
     * A chip's name: what the conversation is actually called.
     *
     * The page reports the name of the conversation it is showing (arena never changes its
     * document title per chat, so WebView.getTitle() returns the same marketing string for
     * every tab). Only when the page has nothing to say - a brand new tab, a page that has
     * not rendered a message yet - does the chip fall back to a number.
     */
    private String tabLabel(int id) {
        if (id == AppUi.TAB_CHAT) return getString(R.string.strip_tab_chat);
        String spoken = tabs == null ? null : tabs.conversationTitle(id);
        if (spoken != null) return spoken;
        String title = tabs == null ? null : tabs.windowTitle(id);
        if (title != null) return title;
        java.util.List<Integer> open = tabs == null ? null : tabs.windows();
        int index = open == null ? -1 : open.indexOf(id);
        return index < 0 ? getString(R.string.strip_tab_chat)
                : getString(R.string.strip_tab_new, index + 2);
    }

    /** Puts any page id on screen: the chat tab, or one of the extra conversation tabs. */
    private void selectPage(int id) {
        if (id == AppUi.TAB_CHAT) {
            currentTab = AppUi.TAB_CHAT;
            showChat();
            refreshStrip();
            return;
        }
        if (TabWebViews.isWindow(id)) switchWindow(id);
    }

    /**
     * Moves one tab left or right; the strip's arrows are exactly this.
     *
     * The current page is looked up in the same list the chips are drawn from, and a page
     * that is not in it (a popup, or a window that closed under us) counts as the leftmost
     * tab rather than as "nowhere" - so the first press always moves somewhere.
     */
    private void stepTab(int direction) {
        if (tabs == null) return;
        // One implementation for the arrows and for Ctrl/Cmd+Tab, in TabWebViews: the row is
        // chat-tab-first, the step wraps, and a single tab means nowhere to go.
        //
        // `pageId()` is what used to break this: it answers "the chat tab" whenever no
        // window is in front, so with one extra tab open and the chat on screen `<` computed
        // the same tab it was already showing and the press did nothing. The row and the
        // tab on screen are two different questions, and now both are asked properly.
        int next = tabs.steppedTab(pageId(), direction < 0 ? -1 : 1);
        if (next < 0) return;
        selectPage(next);
    }

    /**
     * The + button, and Ctrl/Cmd+N from the page: another conversation tab, opened empty.
     *
     * The new tab is created, put on screen and asked for a fresh chat. It is a new chat
     * rather than a copy of the current one - copying the current URL is what made the
     * previous build open the same conversation twice.
     */
    private void newChatTab() {
        if (tabs == null) return;
        // With no tabs at all, "+" opens the first one rather than a second one beside
        // nothing: there is no chat tab to put a window next to.
        if (tabs.isEmpty()) {
            openChatTabFromEmpty();
            return;
        }
        int id = tabs.openWindow();
        if (id < 0) {
            toast(R.string.window_limit);
            return;
        }
        switchWindow(id);
        refreshStrip();
    }

    /**
     * A press landed on something: say what, when the switch is on.
     *
     * The point of the readout is the difference between the two halves. `tag`/`role` is
     * what the finger actually touched; `hit` is the control the press will reach, which is
     * a button or a link if one wraps that element. When the user says "that is not what I
     * tapped", the box and the name together say whether the press was where they think it
     * was, or whether something invisible is sitting on top of the control they wanted.
     */
    void onPageTap(String json) {
        if (isFinishing() || isDestroyed()) return;
        if (!Settings.tapWatch(this)) return;
        String text;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            String hit = o.optString("hit", "");
            String hitRole = o.optString("hitRole", "");
            String hitName = o.optString("hitName", "");
            String tag = o.optString("tag", "");
            int x = o.optInt("x");
            int y = o.optInt("y");
            int wPx = o.optInt("w");
            int hPx = o.optInt("h");
            String what = hitName.isEmpty() ? (hitRole.isEmpty() ? hit : hitRole) : hitName;
            if (what.length() > 40) what = what.substring(0, 40) + "…";
            text = "<" + hit + "> " + what + "   @" + x + "," + y
                    + (wPx > 0 ? "  " + wPx + "×" + hPx : "")
                    + (tag.equals(hit) ? "" : "   (felt: <" + tag + ">)");
        } catch (Exception e) {
            text = json;
        }
        toastText(text);
    }

    /** Ctrl/Cmd+Tab from the page: the same step the strip's arrows take. */
    void onCycleTabShortcut(int direction) {
        if (isFinishing() || isDestroyed()) return;
        stepTab(direction < 0 ? -1 : 1);
    }

    /**
     * Closes the tab with the cross that was pressed - the tab it was drawn on, and nothing
     * else.
     *
     * The chat tab is a tab like any other now, so there is one path for every chip:
     * TabWebViews.closeTab removes that tab's WebView and destroys it. If it was the tab on
     * screen, its neighbour takes the screen; if it was the last one, the app shows the
     * empty-tabs screen rather than inventing a replacement tab.
     *
     * What this replaced: the chat tab could not be closed at all. Its cross reloaded it to
     * arena's front page and either showed that same tab again or its neighbour, so pressing
     * the x on the default tab put arena's home page on screen - read, correctly, as "it
     * didn't close, it just went home".
     */
    private void closeTabById(int id) {
        if (tabs == null) return;
        if (tabs.isEmpty()) return;
        // Both questions are asked BEFORE the tab goes: afterwards there is no neighbour to
        // name and no way to tell whether the closed tab was the one on screen.
        boolean wasCurrent = pageId() == id;
        if (!tabs.closeTab(id)) return;
        int next = tabs.lastClosedNeighbour();

        if (menuSheet != null) {
            // The More sheet lists the windows by name; one of them just went away.
            AppUi.slideDownAndRemove(menuSheet, window);
            menuSheet = null;
        }

        if (tabs.isEmpty()) {
            currentTab = AppUi.TAB_CHAT;
            showEmpty();
            return;
        }

        // A tab was closed; the app decides what is on screen now, in one place.
        //
        // The tab layer used to swap to the neighbour by itself, inside closeTab(), and this
        // method then asked it which tab was in front - which was the neighbour, so the code
        // "show the neighbour" branch ran even when the closed tab was in the background.
        // Bringing a background tab to the front is the "home page" the user saw: the wrong
        // page came forward while the tab they aimed at was simply gone.
        if (wasCurrent) {
            if (next >= 0 && tabs.get(next) != null) {
                tabs.show(next);
            } else {
                tabs.show(tabs.tabs().get(0));
            }
            currentTab = AppUi.TAB_CHAT;
            hideEmpty();
            applyChromeInsets();
            AppUi.styleNav(this, nav, AppUi.TAB_CHAT, updateDotWanted(), navExpanded, true);
        }
        // Whether or not it was in front, the strip redraws and the nav follows the page that
        // is now in front. Nothing is loaded: every remaining tab is warm.
        refreshStrip();
        refreshUi();
    }

    /** Ctrl/Cmd+W from the page: close the tab on screen, whatever it is. */
    void onCloseTabShortcut() {
        if (isFinishing() || isDestroyed()) return;
        closeTabById(pageId());
    }

    /** Called from the page when Ctrl/Cmd+N is pressed there: a new chat, here. */
    void onNewChatShortcut() {
        if (isFinishing() || isDestroyed()) return;
        // A new chat in the tab on screen. Ctrl/Cmd+N *inside a page* is that page's own
        // "new chat", so it must not open a second tab the user did not ask for - the strip's
        // + is the thing that opens tabs.
        openNewChat();
    }

    /**
     * A page reported a new conversation name.
     *
     * Debounced: a streaming answer changes the page's text constantly, and the strip only
     * needs to be redrawn once the name has settled. The name also goes into the window
     * list in More, so that is refreshed with it.
     */
    void onTabTitleChanged() {
        if (titleRefreshPending) return;
        titleRefreshPending = true;
        tapHandler.postDelayed(() -> {
            titleRefreshPending = false;
            if (isFinishing() || isDestroyed()) return;
            refreshStrip();
        }, 350);
    }

    /** True while a title-driven strip refresh is already scheduled. */
    private boolean titleRefreshPending;

    /** Redraws the strip and its arrows; cheap and safe to call from any callback. */
    void refreshStrip() {
        if (tabStrip == null) return;
        tabStrip.refresh();
        WebView page = tabs == null ? null : tabs.current();
        boolean canBack = page != null && page.canGoBack();
        boolean canForward = page != null && page.canGoForward();
        tabStrip.setArrowsEnabled(canBack, canForward);
    }

    /**
     * Puts text on the clipboard and says what was copied.
     *
     * One place, because both callers must agree about what a copy looks like: a hold,
     * which picks the block under the finger and hands it over through WebBridge.copied,
     * and the page's own long press, which hands over the same thing when its clipboard
     * call was refused. (This used to have a third caller - More -> "Copy selected text",
     * which read the page's selection on demand. That row is gone; the app copies by
     * itself now, so an entry that asked the user to select something first was a worse
     * way to do what a hold already does.)
     */
    private void putOnClipboard(String text) {
        if (text == null || text.trim().isEmpty()) {
            toast(R.string.copy_empty);
            return;
        }
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast(R.string.copy_empty);
                return;
            }
            // No ClipDescription extras: nothing here asks Android to put the text on the
            // clipboard preview bar for ever, and the clip carries no hint about what
            // screen it came from.
            cm.setPrimaryClip(ClipData.newPlainText("Arena", text));
            String shown = text.length() > 24 ? text.substring(0, 24).trim() + "…" : text;
            toastText(getString(R.string.copy_done) + "  " + shown);
        } catch (RuntimeException e) {
            toast(R.string.copy_empty);
        }
    }

    /**
     * The page copied a block for the user after a long press.
     *
     * Called from WebBridge, so it arrives on the UI thread already. The text is written
     * natively whatever the page thinks it did: the page's clipboard call is the part that
     * can silently fail, and Android's own ClipData cannot.
     */
    void onPageCopied(String text) {
        if (isFinishing() || isDestroyed()) return;
        putOnClipboard(text);
    }

    /**
     * The page says a control was held but never pressed: press it.
     *
     * Nothing is announced. The user held a button, so the button gets pressed - which is
     * what they meant - instead of the app quietly copying the text around it, and instead
     * of the press vanishing and the button having to be tapped again.
     */
    void onPagePressIgnored(float cssX, float cssY) {
        if (isFinishing() || isDestroyed()) return;
        if (tabs == null) return;
        // The page measures in CSS pixels; a MotionEvent is in device pixels.
        float density = getResources().getDisplayMetrics().density;
        tabs.tapAt(Math.round(cssX * density), Math.round(cssY * density));
    }

    /**
     * The page could not copy at all - no clipboard access and execCommand refused.
     *
     * The block is left selected, so the useful instruction is the one that still works:
     * Android's own Copy on that selection. It must not name a menu row, because the row
     * that used to read the selection ("More -> Copy selected text") is gone.
     */
    void onCopyUnavailable() {
        if (isFinishing() || isDestroyed()) return;
        toast(R.string.copy_needs_menu);
    }

    /** Back *inside* the page, which is what the left arrow means in a browser. */
    private void goBackInPage() {
        WebView page = tabs == null ? null : tabs.current();
        if (page == null) return;
        if (page.canGoBack()) {
            page.goBack();
        } else {
            toast(R.string.strip_at_start);
        }
        refreshStrip();
    }

    private void goForwardInPage() {
        WebView page = tabs == null ? null : tabs.current();
        if (page == null) return;
        if (page.canGoForward()) page.goForward();
        refreshStrip();
    }

    /**
     * Tells the page how much space the app's own chrome takes.
     *
     * The nav is drawn over the page (an overlay, not a row), so both the top bar and
     * the nav have to be reserved inside the page as padding: otherwise arena's
     * bottom-anchored composer and its fixed menus slide under the app's chrome -
     * the "page spread out of the UI / options are hidden" symptom.
     */
    void applyChromeInsets() {
        if (tabs == null) return;
        // Nothing is drawn over the top of the page any more. The strip *is* drawn above
        // the page, but as a row of the column, so the WebView already ends below it; the
        // page must not be padded for it as well.
        final int top = 0;
        // Measured once laid out; the first pass uses the known bar height. The nav is an
        // overlay over the page, so its height has to be reserved inside the page -
        // without it the last row of arena's own sidebar (its "Log in" button) sat
        // underneath the nav and could not be tapped.
        // Settings -> "Show the bottom bar": with it off the page gets the whole frame
        // and is not padded at the bottom either.
        // Nothing of the app's own is drawn over the page any more: the nav is the last row of
        // the column, so the WebView physically ends above it and the page needs no reserved
        // space at the bottom. Reserving it as well double-counted the bar and pushed arena's
        // composer and its model/attach row up by a whole bar height on their own.
        final int bottom = 0;
        chromeBottom = bottom;
        tabs.applyInsetsToAll(top, bottom);
    }


    // ------------------------------------------------------------------ tabs

    /**
     * Chat is a multi-tap control, the way a native app would do it:
     *  1 tap  - go to the chat you are in (no reload, just show the tab)
     *  2 taps - open arena's own sidebar with the conversation history
     *  3 taps - start a new chat
     * The taps are counted in a short window so a double tap never also fires once.
     */
    private void onChatTap() {
        if (!Settings.chatTaps(this)) {
            // Gestures off: the Chat icon is a plain tab, exactly like the others.
            tapHandler.removeCallbacks(flushChatTaps);
            chatTaps = 0;
            showChat();
            return;
        }
        chatTaps++;
        tapHandler.removeCallbacks(flushChatTaps);
        tapHandler.postDelayed(flushChatTaps, CHAT_TAP_WINDOW_MS);
    }

    /** One tap: show the chat tab that is already warm. */
    private void showChat() {
        // A window may be in front of the chat tab while the nav still highlights Chat:
        // the nav says "Chat" and tapping it must put the chat itself back on screen.
        if (currentTab != AppUi.TAB_CHAT || pageId() != AppUi.TAB_CHAT) {
            currentTab = AppUi.TAB_CHAT;
            hideChatSheets();
            hideEmpty();
            // The chat tab can be closed now, so "show the chat" may mean "open one":
            // openChatTab() builds it on arena's front page when it is not there and shows
            // it when it is. show() would happily build a tab and leave the screen blank.
            tabs.openChatTab();
            applyChromeInsets();
            AppUi.styleNav(this, nav, AppUi.TAB_CHAT, updateDotWanted(),
                    navExpanded, true);
            refreshStrip();
            return;
        }
        // Already on chat: just make sure nothing is covering it.
        hideChatSheets();
    }

    /**
     * Two taps: the chat history.
     *
     * Arena's own sidebar is hidden (it duplicated the app's navigation), so the history
     * is shown as a native sheet instead: the conversation list is read out of the live
     * page, drawn with the app's styling, and a tap opens that conversation.
     */
    private void openSidebar() {
        // The chat tab is only forced when the chat is not what is in front. A conversation
        // TAB is a chat too, and its own page carries the history button - so switching to
        // the chat tab first would take the list away from the page the user is reading.
        if (!onConversationTab() && pageId() != AppUi.TAB_CHAT) showTab(AppUi.TAB_CHAT);
        if (historySheet == null) {
            historySheet = new ChatHistorySheet(this, window, new ChatHistorySheet.Host() {
                @Override
                public void openConversation(String url) {
                    if (tabs != null) tabs.loadInCurrent(url);
                }

                @Override
                public void newChat() {
                    openNewChat();
                }

                @Override
                public void onDismissed() {
                    // keep the instance; reopening is cheap
                }
            });
        }
        if (historySheet.isShowing()) {
            historySheet.dismiss();
            return;
        }
        // The list comes from the page on screen, whichever tab that is: arena renders its
        // history inside a conversation, and the chat tab may be sitting on the front page.
        WebView front = tabs == null ? null : tabs.current();
        historySheet.show(front != null ? front : tabs.get(AppUi.TAB_CHAT));
    }

    /** Three taps: a fresh chat, using arena's own "new chat" affordance. */
    private void openNewChat() {
        // "A new chat" belongs to the tab the user is looking at: three taps on Chat while a
        // conversation tab is in front means a new conversation in that tab, not a quiet
        // one in the tab behind it that the user would never see.
        if (tabs == null) return;
        WebView page = tabs.current();
        if (page == null) {
            // Nothing open: a new chat needs a tab, so open the first one.
            openChatTabFromEmpty();
            page = tabs.current();
            if (page == null) return;
        }
        TabWebViews.startNewChat(page);
        toast(R.string.new_chat_started);
    }

    private void hideChatSheets() {
        closeSettingsNow();
        if (historySheet != null && historySheet.isShowing()) historySheet.dismiss();
        if (updateSheet != null) closeUpdateSheet();
        if (menuSheet != null) {
            AppUi.slideDownAndRemove(menuSheet, window);
            menuSheet = null;
        }
    }

    private void selectTab(int tab) {
        if (tab == AppUi.TAB_CHAT) {
            onChatTap();
            return;
        }
        // Switching tabs from under an open sheet would leave the sheet floating over a
        // different page. Close it first; the nav then reflects the page really shown.
        closeSettingsNow();
        if (historySheet != null && historySheet.isShowing()) historySheet.dismiss();
        if (updateSheet != null) closeUpdateSheet();
        if (menuSheet != null) {
            AppUi.slideDownAndRemove(menuSheet, window);
            menuSheet = null;
        }
        if (tab == AppUi.TAB_MORE) {
            // More is a sheet, not a page: keep the page (and its nav highlight).
            AppUi.styleNav(this, nav, currentTab, updateDotWanted(),
                    navExpanded, true);
            showMenuSheet();
            return;
        }
        showTab(tab);
    }

    /**
     * Hides the app's own rows while a floating video is up.
     *
     * The strip and the nav are rows of the same column as the WebView, so they would be
     * part of the floating window - a 200-pixel-tall tab strip above a video. They are put
     * back the moment the window closes, and their stored settings decide what "back"
     * means, so a user who has switched the strip off does not get it handed to them.
     */
    private void pipChromeVisible(boolean visible) {
        if (tabStrip != null) {
            tabStrip.view().setVisibility(visible && Settings.showTabStrip(this)
                    ? View.VISIBLE : View.GONE);
        }
        if (navArea != null) {
            navArea.setVisibility(visible && Settings.showNav(this)
                    ? View.VISIBLE : View.GONE);
        }
        applyChromeInsets();
    }

    /**
     * The user is leaving: the app goes into its floating window.
     *
     * onUserLeaveHint is the Home button and the Recents gesture. `isChangingConfigurations()`
     * is what keeps a rotation, which also calls this, from opening a floating window.
     *
     * On Android 12+ Android enters by itself (Pip.sync sets autoEnterEnabled), so this is
     * the path for older phones - the same moment, without the animated transition.
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (pip == null || isChangingConfigurations()) return;
        if (!Settings.pipApp(this)) return;
        pip.enter();
    }

    /** Android's own report of entering and leaving the floating window. */
    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        if (pip == null) return;
        // The app's own rows come out of the window and go back in; the page is untouched,
        // because the window is the app, not a video lifted out of it.
        pipChromeVisible(!inPip);
    }

    /**
     * The id of the page on screen.
     *
     * currentTab is the *nav* highlight (it stays on Chat while a window is open, because a
     * window is not a place in the nav); the tab layer is what knows which window is really
     * in front. Everything that needs "the page the user is looking at" asks here.
     */
    private int pageId() {
        if (tabs == null) return currentTab;
        WebView shown = tabs.current();
        int id = tabs.tabOf(shown);
        return id >= 0 ? id : currentTab;
    }

    /**
     * Opens another arena window on a fresh chat, in the background.
     *
     * It is created straight away and starts loading, so the first switch to it costs
     * nothing. The four-window ceiling is not arbitrary: each window is its own Chromium
     * renderer, and a phone that already reported lag cannot keep six of them.
     */
    /** Puts an open window (or any page id) on screen. */
    private void switchWindow(int id) {
        if (tabs == null) return;
        if (tabs.get(id) == null) return;
        hideError();
        hideEmpty();
        closeWebWindow();
        closeSettingsNow();
        WebView page;
        try {
            page = tabs.show(id);
        } catch (RuntimeException gone) {
            // The window's renderer died between the tap and now. A dead WebView cannot be
            // brought to front, and letting the exception escape left the whole app frozen
            // on the old page - the "switch sticks" report. Drop the dead window and say so.
            CrashLog.write(this, gone);
            tabs.closeTab(id);
            refreshStrip();
            toast(R.string.strip_window_gone);
            return;
        }
        if (page == null) return;
        if (page.getParent() == null) {
            // Same situation one step earlier: the view exists but is not in the layout,
            // so bringing it to front would leave a blank frame.
            tabs.closeTab(id);
            refreshStrip();
            toast(R.string.strip_window_gone);
            return;
        }
        TabWebViews.applyTheme(page, AppUi.isDark(this));
        applyChromeInsets();
        // The nav keeps Chat lit: a window is another chat, not another destination.
        AppUi.styleNav(this, nav, AppUi.TAB_CHAT, updateDotWanted(), navExpanded, true);
        refreshStrip();
        // A popup raised by the page (a preview, a doc) covers the window just switched to,
        // and leaving it up reads as the switch having done nothing.
        dismissOverPageSheets();
        // No toast: switching between tabs is a browser gesture now, and one that answers
        // every tap with a message is noise. The chip that lights up is the feedback.
    }

    /**
     * Closes anything the app has floated over the page, leaving the page itself alone.
     *
     * Sheets are built over a snapshot of what the user was looking at; once the page
     * underneath is a different one, they belong to a page that is no longer on screen.
     */
    private void dismissOverPageSheets() {
        if (historySheet != null && historySheet.isShowing()) historySheet.dismiss();
        if (updateSheet != null) closeUpdateSheet();
        if (menuSheet != null) {
            AppUi.slideDownAndRemove(menuSheet, window);
            menuSheet = null;
        }
    }

    /** What a window is called: its page title, or its number in the list. */
    private String windowName(int id) {
        String title = tabs == null ? null : tabs.windowTitle(id);
        if (title != null) return title;
        java.util.List<Integer> open = tabs == null ? null : tabs.windows();
        int index = open == null ? -1 : open.indexOf(id);
        return index < 0 ? getString(R.string.strip_tab_chat)
                : getString(R.string.strip_tab_new, index + 2);
    }

    /** The open windows, in the order the More sheet lists them. */
    private java.util.List<MenuSheet.Window> windowList() {
        java.util.List<MenuSheet.Window> out = new java.util.ArrayList<>();
        if (tabs == null) return out;
        for (Integer id : tabs.windows()) {
            // A window the app cannot put on screen is not a window worth listing.
            if (tabs.get(id) == null) continue;
            out.add(new MenuSheet.Window(id, windowName(id)));
        }
        return out;
    }

    /**
     * Is there a conversation tab in front of the chat?
     *
     * The nav's Chat icon stays lit while one is, because a window is another chat, not
     * another destination - so "the page on screen" and "the chat tab" are different
     * questions, and this is how the rest of the activity tells them apart.
     */
    private boolean onConversationTab() {
        int id = pageId();
        return TabWebViews.isWindow(id) && tabs != null && tabs.get(id) != null;
    }

    /** Switches the visible page (the tabs that are not sheets). */
    private void showTab(int tab) {
        if (tab == AppUi.TAB_UPDATE || tab == AppUi.TAB_MORE) return;
        // A destination opens in the tab the user is looking at.
        //
        // Tapping Ask/Search while a conversation tab is in front used to swap the page
        // over to the chat tab and load the destination there: the user asked for search in
        // tab 2 and watched tab 1 change instead, losing whatever tab 2 was showing. The
        // destination is loaded into the window that is on screen, so the tab they are in
        // is the tab that follows them. The chat tab is only used when the chat itself is
        // what is in front.
        if (tab != AppUi.TAB_CHAT && onConversationTab()) {
            String url = TabWebViews.urlFor(tab);
            // The nav highlight follows the destination; which TAB is in front is the tab
            // layer's business, and showChat() already asks it rather than trusting this.
            currentTab = tab;
            hideError();
            closeWebWindow();
            tabs.loadInCurrent(url);
            TabWebViews.applyTheme(tabs.current(), AppUi.isDark(this));
            applyChromeInsets();
            AppUi.styleNav(this, nav, tab, updateDotWanted(), navExpanded, true);
            refreshStrip();
            return;
        }
        currentTab = tab;
        hideError();
        hideEmpty();
        // A destination needs a tab to load into. With every tab closed, the first press on
        // Ask/Search opens one - the page has to live somewhere, and a strip with no chips
        // over a live page would look broken.
        if (tabs.isEmpty()) tabs.openChatTab();
        boolean firstVisit = tabs.get(tab) == null;
        // A window the page opened covers the tabs; switching tab means the user is done
        // with it, and leaving it there would look like the nav did nothing.
        closeWebWindow();
        tabs.show(tab);
        TabWebViews.applyTheme(tabs.current(), AppUi.isDark(this));
        applyChromeInsets();
        AppUi.styleNav(this, nav, currentTab, updateDotWanted(),
                navExpanded, true);
        if (!firstVisit) {
            // Warm tab: already painted, just confirm freshness in the background.
            tabs.get(tab).evaluateJavascript(
                    "window.dispatchEvent(new Event('focus'))", null);
        }
        refreshStrip();
    }

    // ------------------------------------------------------------------ web clients

    WebViewClient newTabClient() {
        final MainActivity activity = this;
        return new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (activity.isOnline()) activity.hideError();
                // Navigating forward drops the forward history, and the strip has to show
                // that immediately or the arrow would point at a page that no longer exists.
                activity.refreshStrip();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                TabWebViews.applyTheme(view, AppUi.isDark(activity));
                activity.applyChromeInsets();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    activity.showError();
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                activity.showError();
            }

            // Android 9+: if the page's renderer dies, drop the dead WebView so the
            // next tab switch rebuilds it, instead of leaving a blank rectangle.
            @Override
            public boolean onRenderProcessGone(WebView view,
                                               android.webkit.RenderProcessGoneDetail detail) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && activity.tabs != null) {
                    activity.tabs.drop(view);
                }
                return true;
            }
        };
    }

    /**
     * Notices a page that stopped loading without finishing, and offers a retry.
     *
     * "I click a chat and it is stuck, it never loads" has two common causes that look
     * identical to a user: a request that quietly died (a dead connection, a blocked
     * subresource) and a page whose JavaScript threw. Neither ends in onPageFinished, so
     * nothing in the app reacted. This watches progress instead: a load that stops short of
     * 100 for {{WAIT}} seconds raises a small, dismissible bar with one button.
     */
    private void watchStalledLoad(WebView view, int progress) {
        final int tab = tabs == null ? -1 : tabs.tabOf(view);
        if (tab < 0) return;
        stalledProgress.put(tab, progress);
        if (progress >= 100) {
            stalledAt.remove(tab);
            hideStalledBar();
            return;
        }
        if (stalledAt.containsKey(tab)) return;
        stalledAt.put(tab, android.os.SystemClock.elapsedRealtime());
        view.postDelayed(() -> {
            Long began = stalledAt.get(tab);
            if (began == null) return;
            if (android.os.SystemClock.elapsedRealtime() - began < STALL_MS) return;
            stalledAt.remove(tab);
            Integer last = stalledProgress.get(tab);
            if (last != null && last >= 100) return;
            showStalledBar(view);
        }, STALL_MS + 500L);
    }

    /** One small bar: the page is stuck, tap once to load it again. */
    private void showStalledBar(final WebView stuck) {
        if (stalledBar == null) {
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(AppUi.color(this, R.color.surface));
            bar.setPadding(AppUi.dp(this, 14), AppUi.dp(this, 10),
                    AppUi.dp(this, 8), AppUi.dp(this, 10));
            TextView label = AppUi.text(this, getString(R.string.load_stalled), 12.5f,
                    R.color.on_bg, false);
            bar.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView retry = AppUi.text(this, getString(R.string.load_stalled_retry), 12.5f,
                    R.color.on_bg, true);
            retry.setPadding(AppUi.dp(this, 12), AppUi.dp(this, 6),
                    AppUi.dp(this, 12), AppUi.dp(this, 6));
            retry.setOnClickListener(v -> {
                AppUi.press(v);
                hideStalledBar();
                // A tab showing a saved copy must be sent back to the live URL: reloading
                // it would only re-read the same file and stay stuck.
                if (stuck == null) return;
                String shown = stuck.getUrl();
                int tab = tabs == null ? -1 : tabs.tabOf(stuck);
                if (tab >= 0 && (shown == null || shown.startsWith("file://"))) {
                    stuck.loadUrl(tabs.realUrl(tab));
                } else {
                    stuck.reload();
                }
            });
            bar.addView(retry);
            TextView close = AppUi.text(this, "✕", 15f, R.color.on_bg_muted, false);
            close.setPadding(AppUi.dp(this, 10), AppUi.dp(this, 4),
                    AppUi.dp(this, 10), AppUi.dp(this, 4));
            close.setOnClickListener(v -> hideStalledBar());
            bar.addView(close);
            stalledBar = bar;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP;
            lp.topMargin = AppUi.dp(this, 56);
            window.addView(stalledBar, lp);
        }
        // Never over a sign-in layer: the bar belongs to the page underneath, and once the
        // sign-in sheet is raised it would sit on top of it.
        if (signInSheet != null && signInSheet.isSignInActive()) return;
        stalledBar.setVisibility(View.VISIBLE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            stalledBar.setTranslationZ(AppUi.dp(this, 6));
        }
        stalledBar.bringToFront();
    }

    /** Called by the tab layer when a page finishes, so a stale bar never lingers. */
    void clearStalledLoad() {
        stalledAt.clear();
        hideStalledBar();
        if (nav != null && tabs != null) {
            for (int tab : tabs.warmTabs()) AppUi.setNavLoading(nav, tab, false);
        }
    }

    private void hideStalledBar() {
        if (stalledBar != null) stalledBar.setVisibility(View.GONE);
    }

    WebChromeClient newTabChrome() {
        return new AppChrome.Base(this) {

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // no progress bar: the page paints itself - but a page that goes quiet
                // half-loaded is the "stuck, never loads" report, so say so on screen and
                // offer the one thing that unsticks it.
                watchStalledLoad(view, newProgress);
                // A ring on the nav item the load belongs to, so a slow page looks busy
                // instead of broken.
                final int tab = tabs == null ? -1 : tabs.tabOf(view);
                if (tab >= 0 && nav != null) {
                    AppUi.setNavLoading(nav, tab, newProgress < 100);
                }
            }

            /**
             * Sign-in flows (Google's does) use window.open / target=_blank.
             * Instead of leaving the app, show them in an in-app popup WebView that
             * shares the same cookie jar, so the login lands back in the main tab.
             */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture,
                                          android.os.Message resultMsg) {
                showAuthPopup(view, resultMsg);
                return true;
            }
        };
    }

    // ------------------------------------------------------------------ in-app auth

    /**
     * A page opened a window (window.open / target=_blank).
     *
     * Provider sign-in does exactly this: "Continue with Google" opens its account
     * chooser as a popup. The popup
     * is hosted in a fresh in-app WebView that shares this app's cookie jar, so the
     * session it sets is the session the arena tab uses - nothing ever leaves the app.
     *
     * A small guard on top: a popup that opens on the *provider's own* site (or one the
     * page created without a URL yet, which is what a lazy popup looks like) is shown in
     * the sign-in window; anything else (a window opened by an ordinary page) is placed in
     * the main tab instead, so a stray popup can never cover the app.
     */
    private void showAuthPopup(WebView parent, android.os.Message resultMsg) {
        // A sign-in is already on screen: whatever the page opens belongs to it (Google's
        // chooser opens its own window in some builds), so it is hosted by the sheet. It
        // is also never destroyed in that case - destroying a window the user is looking
        // at is how "the account chooser flashed and vanished" happens.
        if (signInSheet != null && signInSheet.isSignInActive()) {
            final WebView popup = buildPopupWebView();
            if (resultMsg != null && resultMsg.obj instanceof WebView.WebViewTransport) {
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
            }
            signInSheet.openAccountWindow(popup, true);
            return;
        }
        final WebView popup = buildPopupWebView();
        if (resultMsg != null && resultMsg.obj instanceof WebView.WebViewTransport) {
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
        }
        // The transport hands the popup its first URL asynchronously; ask for it next frame.
        popup.post(() -> {
            String url = popup.getUrl();
            if (url == null) url = "";
            // A popup with no URL yet is a lazy window: it only loads once it is attached,
            // and it is always part of whatever flow opened it.
            String host = "";
            try {
                host = Uri.parse(url).getHost() == null ? "" : Uri.parse(url).getHost();
            } catch (Exception ignored) {
            }
            // A phone cannot reach the machine that opened this window. 127.0.0.1 and
            // localhost are the PHONE itself, and nothing this app runs listens there - so a
            // preview address copied from a laptop (localhost:3000, 127.0.0.1:8080) would
            // load into an empty window forever. Say what it is and show the addresses that
            // can work, instead of leaving an empty frame.
            if (AppChrome.isPhoneLoopback(host)) {
                openLocalHelp(url, host, this::openWebWindow);
                popup.destroy();
                return;
            }
            boolean provider = url.isEmpty() || AppChrome.isSignInHost(host);
            if (provider) {
                // The sheet owns the window from here: it attaches the popup (a lazy one
                // renders nothing until it is attached), keeps the navigation inside the
                // app and watches for the session coming back.
                ensureSignInSheet().openAccountWindow(popup, true);
            } else if (!isInternalWindow(url)) {
                // An ordinary window.open() to somewhere that is not a sign-in provider:
                // the user asked to open it, so it opens - in a small browser window of its
                // own, on top of the tabs.
                //
                // This used to be `popup.destroy(); tabs.loadInCurrent(url)` for everything
                // not on a provider host. That is the "clicking a local preview link does
                // nothing" report: an `about:blank` window was given to the *chat tab*
                // (replacing the conversation the user was reading with a blank document),
                // and a local address died silently because the app forbids cleartext
                // http - no request, no error, no page. A window has to survive to load.
                popup.destroy();
                openWebWindow(url);
            } else {
                // Still about:blank, or an arena page: host it and let it reveal its URL.
                // A lazy window only starts loading once it is attached, so an empty one
                // that is thrown away here is exactly how a click goes nowhere.
                ensureSignInSheet().openAccountWindow(popup, true);
            }
        });
    }

    /**
     * Explains an address that can only work on the machine that wrote it down.
     *
     * `localhost:3000` in a dev server's preview link means "the laptop running it", and on
     * a phone `localhost` is the phone. The request fails, the window stays empty, and
     * nothing says why - which is exactly how "the preview does not open in the app" is
     * reported. So the app lists this phone's own addresses: the wifi one is what the laptop
     * should use instead, and the loopback entry is there for a server actually on the phone.
     *
     * @param open called with the address the user picked
     */
    void openLocalHelp(String url, String host, java.util.function.Consumer<String> open) {
        String path = "";
        int slash = url.indexOf('/', url.indexOf("//") + 2);
        if (slash >= 0) path = url.substring(slash);
        // The port matters: 192.168.1.20:3000 is the server, 192.168.1.20 is not.
        final String suffix = LanAddresses.portOf(url) + path;
        java.util.List<LanAddresses.Entry> entries = LanAddresses.onDevice(this);
        android.app.AlertDialog.Builder builder =
                new android.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.local_help_title, host))
                        .setMessage(getString(R.string.local_help_body,
                                getString(R.string.local_help_hint)))
                        .setNegativeButton(getString(R.string.local_help_close), null);
        if (!entries.isEmpty()) {
            String[] labels = new String[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                labels[i] = entries.get(i).label + "\n" + entries.get(i).url + suffix;
            }
            builder.setItems(labels, (dialog, which) -> open.accept(entries.get(which).url + suffix));
        }
        builder.show();
    }

    /** True for an address that belongs in a tab, not in a window of its own. */
    private static boolean isInternalWindow(String url) {
        if (url == null || url.isEmpty()) return true;
        if ("about:blank".equals(url)) return true;
        try {
            return AppChrome.isInternal(Uri.parse(url));
        } catch (Exception notAUrl) {
            return true;
        }
    }

    /** The window the page opened for a non-arena address, one at a time. */
    private void openWebWindow(String url) {
        if (webWindow != null) {
            webWindow.dismiss();
            webWindow = null;
        }
        webWindow = WebWindow.open(this, window, url);
    }

    /** True while a window the page opened owns the screen. */
    boolean hasWebWindow() {
        return webWindow != null;
    }

    /** Closes that window; Back uses this before it touches a tab's history. */
    void closeWebWindow() {
        if (webWindow == null) return;
        webWindow.dismiss();
        webWindow = null;
    }

    /** An arena link tapped inside that window comes back to a tab. */
    void openInTab(String url) {
        if (tabs == null || url == null) return;
        closeWebWindow();
        tabs.loadInCurrent(url);
    }

    /** The sign-in sheet, created but not shown. */
    private SignInSheet ensureSignInSheet() {
        if (signInSheet == null) signInSheet = new SignInSheet(this, window, signInHost);
        // The bar is chrome for the page that is now covered; leaving it up put a strip of
        // the tab's UI on top of the sign-in screen.
        if (signInSheet.isSignInActive()) hideStalledBar();
        return signInSheet;
    }

    /**
     * The WebView that hosts a popup.
     *
     * Same cookie jar, same user agent and the same "stay inside the app" navigation
     * policy as a tab; only the chrome client differs, because a popup may itself open a
     * window (Google's second step does).
     */
    private WebView buildPopupWebView() {
        WebView popup = new WebView(this);
        popup.setBackgroundColor(AppUi.color(this, R.color.bg));
        android.webkit.WebSettings s = popup.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        // An https page that embeds plain http (a preview link inside arena, a tunnel, a
        // local build) is a mixed-content block under NEVER_ALLOW: the frame stays empty
        // and a tap reads as "nothing happened". The page still decides what it loads.
        s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(Settings.chromeUserAgent(this)
                ? AppChrome.userAgent(this, s.getUserAgentString())
                : AppChrome.defaultUserAgent(this));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
        popup.setWebViewClient(newTabClient());
        popup.setWebChromeClient(new AppChrome.Base(this) {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message message) {
                showAuthPopup(view, message);
                return true;
            }
        });
        return popup;
    }

    // ------------------------------------------------------------------ web glue

    /**
     * Everything the page navigates to stays in this WebView.
     *
     * This used to check a host whitelist and send every other host to ACTION_VIEW -
     * that is exactly what kept opening Chrome, because arena links out to plenty of
     * hosts. Links the user taps inside arena belong inside arena, so they load here.
     * The only exceptions are a web-app-link OAuth callback (handled by loading the
     * fallback URL it carries) and the handful of device intents nobody wants in a
     * WebView (mailto:, tel:, sms:).
     */
    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String raw = uri.toString();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();

        if (scheme.startsWith("http")) {
            // A link that points at localhost is a dev server's preview, written on a
            // machine where "localhost" was the server. On a phone it is the phone, and
            // nothing answers there: the tab would sit on a blank page forever. Name the
            // problem and offer the addresses that can work.
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (AppChrome.isPhoneLoopback(host)) {
                openLocalHelp(uri.toString(), host,
                        picked -> tabs.loadInCurrent(picked));
                return true;
            }
            return false;   // load it right here
        }
        if (scheme.equals("intent")) {
            // intent://host/path#Intent;scheme=https;package=com.google.android.gms;end
            String inner = raw.substring(raw.indexOf(':') + 1);
            int hash = inner.indexOf('#');
            String path = hash >= 0 ? inner.substring(0, hash) : inner;
            String fragment = hash >= 0 ? inner.substring(hash) : "";
            String innerScheme = "https";
            int schemeAt = fragment.indexOf("scheme=");
            if (schemeAt >= 0) {
                String rest = fragment.substring(schemeAt + 7);
                int semi = rest.indexOf(';');
                innerScheme = semi >= 0 ? rest.substring(0, semi) : rest;
            }
            if (innerScheme.startsWith("http")) {
                tabs.loadInCurrent(innerScheme + "://" + path);
            }
            return true;
        }
        // mailto:, tel:, sms:, geo: - Android's own handlers for things a page cannot do.
        // Anything else is an app-link/OAuth style URL: if it carries an http(s) fallback
        // it is loaded here, otherwise it is quietly ignored. A toast used to say "this
        // link needs another app" and the user read that as an error page, so nothing is
        // shown at all now - the app simply never leaves Arena.
        if (AppChrome.isDeviceScheme(scheme)) {
            AppChrome.openNonWebScheme(this, uri);
            return true;
        }
        String fallback = uri.getQueryParameter("url");
        if (fallback == null) fallback = uri.getQueryParameter("continue");
        if (fallback == null) fallback = uri.getQueryParameter("redirect_uri");
        if (fallback != null && fallback.startsWith("http")) {
            tabs.loadInCurrent(fallback);
        }
        return true;
    }

    WebBridge bridgeForCurrentTab() {
        return new WebBridge(this, tabs.current());
    }

    void handleWebPermissionRequest(final PermissionRequest request) {
        runOnUiThread(() -> {
            List<String> wanted = new ArrayList<>();
            for (String res : request.getResources()) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                    wanted.add(android.Manifest.permission.RECORD_AUDIO);
                } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                    wanted.add(android.Manifest.permission.CAMERA);
                }
            }
            boolean granted = true;
            for (String p : wanted) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) granted = false;
            }
            if (wanted.isEmpty() || granted) {
                request.grant(request.getResources());
            } else {
                pendingPermissionRequest = request;
                requestPermissions(wanted.toArray(new String[0]), REQ_PERMISSIONS);
            }
        });
    }

    boolean handleFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
        pendingFileCallback = callback;
        try {
            Intent intent = params.createIntent();
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQ_FILE);
            return true;
        } catch (Exception e) {
            pendingFileCallback = null;
            toast(R.string.upload_failed);
            return false;
        }
    }

    // ------------------------------------------------------------------ sheets

    private void openUpdateSheet() {
        if (updateSheet != null) return;
        updateSheet = new UpdateSheet(this, this);
        window.addView(updateSheet.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        UpdateCenter.maybeAutoCheck(this, this::onCheckFinished);
    }

    @Override
    public void closeUpdateSheet() {
        if (updateSheet == null) return;
        final View v = updateSheet.view();
        updateSheet = null;
        AppUi.slideDownAndRemove(v, window);
        if (currentTab == AppUi.TAB_UPDATE) currentTab = tabBeforeUpdate;
        AppUi.styleNav(this, nav, currentTab, updateDotWanted(),
                navExpanded, true);
    }

    @Override
    public void onUpdateDownloaded(File apk) {
        toast(R.string.update_downloaded);
    }

    @Override
    public void launchInstaller(File apk) {
        // The package installer cannot read the download cache, so move a copy into
        // the FileProvider-backed files dir before prompting.
        pendingInstallApk = ApkInstaller.copyToShareable(this, apk);
        installNow();
    }

    /** Hands the APK to the system installer, asking for the toggle first if needed. */
    private void installNow() {
        if (pendingInstallApk == null) return;
        if (ApkInstaller.needsInstallPermission(this)) {
            ApkInstaller.openInstallPermissionSettings(this, REQ_INSTALL_UNKNOWN);
            return;
        }
        ApkInstaller.launch(this, pendingInstallApk);
    }

    // ------------------------------------------------------------------ sign in

    /**
     * Google/Apple sign-in the way an app does it: a native-looking account chooser,
     * then the provider's own login page inside the app. Never a browser.
     */
    /**
     * More -> Sign in: the provider sheet - Google, plus the account this device already
     * used - and from there straight into the provider's own page.
     */
    void startSignIn() {
        // Any sheet that was open (More, Update, the token prompt) goes away first, so the
        // next surface is never buried under another overlay - that is why a sign-in
        // started from the Update sheet looked like it did nothing.
        closeSettingsNow();
        if (updateSheet != null) closeUpdateSheet();
        if (menuSheet != null) {
            AppUi.slideDownAndRemove(menuSheet, window);
            menuSheet = null;
        }
        startProviderSignIn(null);
    }

    /** Opens the provider's own login page inside the app, for the chosen account. */
    private void startProviderSignIn(String email) {
        ensureSignInSheet().showForEmail(email);
        // The sign-in layer is full-screen and covers the page, so its stalled-bar chrome
        // has nothing left to talk about and must not stay on top of it.
        hideStalledBar();
    }

    /** What the sign-in sheet calls back into. */
    private final SignInSheet.Host signInHost = new SignInSheet.Host() {
        @Override
        public void onSignedIn() {
            // The provider round-trip set the session cookie: remember it locally and
            // refresh the tabs so they pick up the signed-in UI immediately.
            LocalProfile.markSignedIn(MainActivity.this);
            Toast.makeText(MainActivity.this, R.string.signin_success,
                    Toast.LENGTH_SHORT).show();
            if (tabs != null) {
                // Arena re-renders its own UI when it notices the session, so a
                // reload of the whole page is not needed (and a reload here is what
                // made the app feel slow right after signing in).
                tabs.applyThemeToAll(AppUi.isDark(MainActivity.this));
                WebView w = tabs.current();
                if (w != null) {
                    w.evaluateJavascript(
                            "window.dispatchEvent(new Event('focus'));", null);
                }
            }
        }

        @Override
        public void onDismissed() {
            // keep the instance; it is cheap and reusable
        }
    };

    private void showMenuSheet() {
        if (menuSheet != null) return;
        menuSheet = MenuSheet.show(this, window, new MenuSheet.Callback() {
            @Override
            public void onReload() {
                hideError();
                if (tabs != null) tabs.reloadCurrent();
            }

            @Override
            public void onSwitchWindow(int windowId) {
                switchWindow(windowId);
            }

            @Override
            public void onCloseWindow(int windowId) {
                // One path for closing, so the strip and the page can never disagree about
                // which windows exist.
                closeTabById(windowId);
            }

            @Override
            public void onOpenUpdate() {
                openUpdateSheet();
            }

            @Override
            public void onSignIn() {
                startSignIn();
            }

            @Override
            public void onSettings() {
                showSettingsSheet();
            }

            @Override
            public void onDismissed() {
                menuSheet = null;
            }
        }, windowList(), pageId());
    }

    /**
     * Opens Settings. It is a sheet over the current page (not a tab), so every switch
     * can be flipped while looking at the thing it changes.
     */
    void showSettingsSheet() {
        if (settingsSheet != null) return;
        settingsSheet = new SettingsSheet(this, window, new SettingsSheet.Host() {
            @Override
            public void closeSettings() {
                if (settingsSheet != null) settingsSheet.dismiss();
                settingsSheet = null;
            }

            @Override
            public void setAppearance(int mode) {
                AppUi.setAppearance(MainActivity.this, mode);
                recreateWithAnimation();
            }

            @Override
            public void onSettingsChanged(String key, boolean value) {
                applySetting(key, value);
            }

            @Override
            public void onSignOut() {
                closeSettingsNow();
                // Signed out means signed out: the saved pages are copies of someone's
                // private chats and must not survive it.
                OfflinePages.clear(MainActivity.this);
                if (tabs != null) tabs.clearAndReload();
            }

            @Override
            public void onClearData() {
                closeSettingsNow();
                OfflinePages.clear(MainActivity.this);
                if (tabs != null) tabs.clearAndReload();
            }

            @Override
            public void onResetSettings() {
                Settings.reset(MainActivity.this);
                closeSettingsNow();
                recreateWithAnimation();
            }

            @Override
            public void onSignIn() {
                closeSettingsNow();
                startSignIn();
            }

            @Override
            public void onEnableDeveloperOptions() {
                Settings.setDeveloperOptions(MainActivity.this, true);
                Toast.makeText(MainActivity.this, R.string.settings_developer_unlocked,
                        Toast.LENGTH_SHORT).show();
                if (settingsSheet != null) settingsSheet.refresh();
            }

            @Override
            public void onDeveloperDiagnostics() {
                closeSettingsNow();
                SignInDiagnostics.show(MainActivity.this, window,
                        tabs == null ? null : tabs.current());
            }
        });
        settingsSheet.show();
    }

    private void closeSettingsNow() {
        if (settingsSheet != null) settingsSheet.dismiss();
        settingsSheet = null;
    }

    /** Applies one switch immediately, without recreating the activity. */
    @SuppressWarnings("unused")
    private void applySetting(String key, boolean value) {
        switch (key) {
            case Settings.K_SHOW_NAV:
                applyNavVisibility();
                break;
            case Settings.K_SHOW_TAB_STRIP:
                if (tabStrip != null) {
                    tabStrip.view().setVisibility(value ? View.VISIBLE : View.GONE);
                    if (value) refreshStrip();
                }
                // The page does not need to know: the strip is a row of the column, so the
                // WebView simply gets the height back.
                applyChromeInsets();
                break;
            case Settings.K_KEEP_SCROLL:
                // The strip names the open tabs, and a renamed/closed window changes that
                // list - so it is refreshed after every settings pass below as well.
                refreshStrip();
                break;
            case Settings.K_HIDE_WEB_SIDEBAR:
            case Settings.K_HIDE_WEB_HEADER:
                // The rules are a <style> element that is replaced on every pass, so the
                // change lands immediately. Turning a hiding rule *off* is the case that
                // did not work: the JS rule hides by writing an inline display:none, and an
                // inline style outlives both the script and the setting - the panel stayed
                // hidden with the switch off ("i disabled the button but it still hides
                // that"). So the panels are given back explicitly, on every warm tab, and
                // the stylesheet is rewritten without the rule.
                if (!value && tabs != null) tabs.showPagePanels();
                applyChromeInsets();
                break;
            case Settings.K_DESKTOP_UA:
                if (tabs != null) tabs.applyUserAgentToAll();
                break;
            case Settings.K_NOTIFY_UPDATES:
                if (!value) UpdateNotifications.cancelAll(this);
                break;
            case Settings.K_OFFLINE_PAGES:
                // Off means off: the copies are private chat pages, so they go now.
                if (!value) OfflinePages.clear(this);
                break;
            case Settings.K_NO_SELECT:
                applyChromeInsets();   // the CSS is replaced in place, no reload needed
                break;
            case Settings.K_TAP_WATCH:
                // Nothing to reload: the watcher is installed on every page already and
                // only reports. The app decides whether to show what it reports, which is
                // this switch - so it works the moment it is flipped, either way.
                if (value) toast(R.string.tap_watch_on);
                break;
            case Settings.K_SHOW_VERSION:
                rebuildNavArea();
                break;
            case Settings.K_PIP_APP:
                // Re-applies the parameters, which is what turns enter-on-home on and off
                // for the next time the user leaves. An app already in the window is left
                // there: dropping it out from under the user is a worse surprise than a
                // switch that takes effect next time.
                if (pip != null) pip.sync();
                break;
            default:
                break;
        }
    }

    private boolean preloadScheduled;

    /** Warms the background tabs once, well after the first page is on screen. */
    private void schedulePreload() {
        if (preloadScheduled || tabs == null) return;
        preloadScheduled = true;
        // Right away, not seconds later: the other pages used to be built 2.5 s after the
        // chat finished loading, which on a phone meant a tap on Leaderboard in the first
        // few seconds still opened a cold page ("leaderboard and search do not preload").
        window.post(() -> {
            if (isFinishing() || isDestroyed() || tabs == null) return;
            tabs.preload();
        });
    }

    /** Shows/hides the bottom bar and hands the freed space back to the page. */
    private void applyNavVisibility() {
        if (navArea == null) return;
        navArea.setVisibility(Settings.showNav(this) ? View.VISIBLE : View.GONE);
        applyChromeInsets();
    }

    /** Rebuilds the bar (used when the version label is switched on/off). */
    private void rebuildNavArea() {
        if (window == null || navArea == null) return;
        ViewGroup parent = (ViewGroup) navArea.getParent();
        if (parent == null) return;
        int index = parent.indexOfChild(navArea);
        parent.removeView(navArea);
        navArea = buildNavArea();
        parent.addView(navArea, Math.max(0, Math.min(index, parent.getChildCount())),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        navArea.setVisibility(Settings.showNav(this) ? View.VISIBLE : View.GONE);
        AppUi.styleNav(this, nav, currentTab, updateDotWanted(),
                false, false);
        applyChromeInsets();
    }

    private void recreateWithAnimation() {
        window.animate().alpha(0f).setDuration(120).withEndAction(this::recreate).start();
    }

    /** Animated dismissal shared by the auth popup and the other sheets. */
    private void dismissSheet(final View sheet) {
        if (sheet.getParent() == null) return;
        sheet.animate().alpha(0f).setDuration(AppUi.ANIM_FAST)
                .withEndAction(() -> {
                    if (sheet.getParent() != null) window.removeView(sheet);
                }).start();
    }

    // ------------------------------------------------------------------ update UI

    void onCheckFinished(UpdateManager.Result result) {
        refreshUpdateUi();
        if (result != null && result.ok && !result.updateAvailable
                && UpdateManager.currentVersionCode(this) >= result.latestCode) {
            UpdateNotifications.cancelAll(this);
        }
    }

    public void onUpdateStateChanged(boolean available) {
        refreshUpdateUi();
    }

    /**
     * Whether the bar should mark More as having an update.
     *
     * Every build is published to GitHub, so this used to be lit almost permanently and
     * read as a notification that could not be cleared. It is now a Settings switch
     * (Settings -> "Dot on the More icon", off by default) and the update itself is still
     * found inside More either way.
     */
    private boolean updateDotWanted() {
        return Settings.showUpdateDot(this) && UpdateManager.isUpdateAvailable(this);
    }

    private void refreshUpdateUi() {
        boolean available = UpdateManager.isUpdateAvailable(this);
        // No chip in a top bar (there is no top bar): the More icon carries a dot and
        // the More sheet spells the version out.
        if (nav != null) {
            AppUi.styleNav(this, nav, currentTab, updateDotWanted(), navExpanded, false);
        }
        if (updateSheet != null) updateSheet.refresh();
    }

    private void refreshUi() {
        if (nav != null) {
            AppUi.styleNav(this, nav, currentTab, updateDotWanted(), navExpanded, false);
        }
        refreshStrip();
    }

    // ------------------------------------------------------------------ error view

    /**
     * What the app shows when the last tab has been closed.
     *
     * Not a tab, not a landing page: a blank panel with one button that opens a new tab.
     * Chrome closes its window at this point, which an app cannot do without looking like
     * a crash - so this screen is the honest equivalent, and it is the only place in the
     * app that says "there is nothing open".
     */
    private View buildEmptyView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(AppUi.color(this, R.color.bg));
        int pad = AppUi.dp(this, 32);
        box.setPadding(pad, pad, pad, pad);

        TextView title = AppUi.text(this, getString(R.string.empty_tabs_title), 19f,
                R.color.on_bg, true);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView body = AppUi.text(this, getString(R.string.empty_tabs_body), 14f,
                R.color.on_bg_muted, false);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, AppUi.dp(this, 8), 0, AppUi.dp(this, 20));
        box.addView(body);

        TextView button = AppUi.text(this, getString(R.string.empty_tabs_open), 15f,
                R.color.on_accent, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(AppUi.dp(this, 26), AppUi.dp(this, 13),
                AppUi.dp(this, 26), AppUi.dp(this, 13));
        button.setBackground(AppUi.pressable(this, AppUi.color(this, R.color.accent), 14f));
        button.setOnClickListener(v -> {
            AppUi.press(v);
            openChatTabFromEmpty();
        });
        box.addView(button);
        return box;
    }

    /** Draws the no-tabs screen (nothing is loaded, nothing is closed). */
    private void showEmpty() {
        if (emptyView == null) return;
        if (errorView != null) errorView.setVisibility(View.GONE);
        emptyView.setAlpha(0f);
        emptyView.setVisibility(View.VISIBLE);
        emptyView.bringToFront();
        emptyView.animate().alpha(1f).setDuration(AppUi.ANIM_FAST).start();
        if (nav != null) {
            AppUi.styleNav(this, nav, AppUi.TAB_CHAT, updateDotWanted(), navExpanded, true);
        }
        refreshStrip();
    }

    /** Puts the no-tabs screen away; the next tab shows through. */
    private void hideEmpty() {
        if (emptyView == null || emptyView.getVisibility() != View.VISIBLE) return;
        emptyView.setVisibility(View.GONE);
    }

    /** The empty screen's one button, and the strip's + when nothing is open. */
    void openChatTabFromEmpty() {
        if (tabs == null) return;
        hideEmpty();
        tabs.openChatTab();
        currentTab = AppUi.TAB_CHAT;
        applyChromeInsets();
        AppUi.styleNav(this, nav, AppUi.TAB_CHAT, updateDotWanted(), navExpanded, true);
        refreshStrip();
    }

    private View buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(AppUi.color(this, R.color.bg));
        int pad = AppUi.dp(this, 32);
        box.setPadding(pad, pad, pad, pad);

        ImageView logo = new ImageView(this);
        // The full mark on transparent, not the launcher tile: on the error screen the
        // charcoal square would look like a stray box on the app's warm background.
        logo.setImageResource(R.drawable.agon_logo);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                AppUi.dp(this, 56), AppUi.dp(this, 56));
        logoLp.bottomMargin = pad / 2;
        box.addView(logo, logoLp);

        TextView title = AppUi.text(this, getString(R.string.error_title), 19f,
                R.color.on_bg, true);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView body = AppUi.text(this, getString(R.string.error_body), 14f,
                R.color.on_bg_muted, false);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, pad / 4, 0, pad / 2);
        box.addView(body);

        Button retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> {
            hideError();
            if (isOnline() && tabs != null) {
                tabs.loadInCurrent(tabs.realUrl(currentTab));
            } else {
                showError();
            }
        });
        box.addView(retry);

        TextView hint = AppUi.text(this, getString(R.string.error_hint), 12.5f,
                R.color.on_bg_muted, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, AppUi.dp(this, 14), 0, 0);
        box.addView(hint);
        return box;
    }

    private void showError() {
        if (errorView == null) return;
        errorView.setVisibility(View.VISIBLE);
        AppUi.fadeIn(errorView);
    }

    private void hideError() {
        if (errorView != null) errorView.setVisibility(View.GONE);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    /** A toast whose text is already built (a format string that needs arguments). */
    private void toastText(CharSequence text) {
        if (text == null) return;
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ navigation

    @Override
    public void onBackPressed() {
        if (webWindow != null) {
            closeWebWindow();
            return;
        }
        if (historySheet != null && historySheet.isShowing()) {
            historySheet.dismiss();
            return;
        }
        if (settingsSheet != null) {
            closeSettingsNow();
            return;
        }
        if (signInSheet != null && signInSheet.isShowing()) {
            signInSheet.dismiss();
            return;
        }
        if (updateSheet != null) {
            closeUpdateSheet();
            return;
        }
        if (menuSheet != null) {
            AppUi.slideDownAndRemove(menuSheet, window);
            menuSheet = null;
            return;
        }
        // Before leaving the page: arena's own overlays draw an "Esc" in their corner
        // (the "Was this task successful?" card does), because on a desktop that key
        // closes them. A phone has no Escape key, so Back is the key - and it is tried
        // once, here, before it is used for navigation. If the page reports that nothing
        // was covering the screen, the next Back behaves exactly as it always did.
        if (tabs != null && escapeUsedFor != pageId()) {
            final int askingPage = pageId();
            escapeUsedFor = askingPage;
            escapeSettled = false;
            // A page that never answers must not swallow the press for ever.
            window.postDelayed(() -> {
                if (!escapeSettled) escapeSettled = true;
            }, 500);
            tabs.pressEscape();
            return;
        }
        // Still stuck after the first attempt (the recording shows exactly this: a
        // toolbar parked over the sidebar and nothing responding). One press is allowed
        // to do the heavier thing rather than leaving the user with a page they cannot
        // use: drop the page's floats and focus, and if that does not help either, reload
        // the tab. The reload is the same thing the menu's Reload does, so it loses
        // nothing that a normal Reload would not - and a page in that state has no
        // unsent work worth protecting.
        if (tabs != null && unstuckTriedFor != pageId()) {
            unstuckTriedFor = pageId();
            stuckSettled = false;
            window.postDelayed(() -> {
                if (!stuckSettled) stuckSettled = true;
            }, 700);
            tabs.unstickPage();
            return;
        }
        if (tabs != null && tabs.canGoBack()) {
            tabs.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressMs < 2000) {
            super.onBackPressed();
        } else {
            lastBackPressMs = now;
            toast(R.string.exiting);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) {
            openUpdateSheet();
            return;
        }
        // This activity is exported, so any app on the phone can hand it an ACTION_VIEW
        // intent. Only an https link *to* arena may go any further, and the host must be
        // exactly arena.ai / lmarena.ai (or a real subdomain of them) - "notarena.ai" and
        // "arena.ai.evil.example" both used to pass a plain endsWith() test.
        Uri data = intent.getData();
        if (data == null || data.getHost() == null) return;
        if (!"https".equalsIgnoreCase(data.getScheme())) return;
        String host = data.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!isArenaHost(host)) {
            // Someone else's link: it is not loaded, and it is not recorded either.
            SignInTrace.note(this, "ignored non-arena link: " + host);
            return;
        }
        // Record every arena link the OS ever hands us: this is the only place a browser
        // sign-in could come back into the app, so if nothing is noted here after a real
        // browser sign-in, the handoff does not exist and the browser row must not promise
        // it. Secrets are stripped before anything is stored.
        SignInTrace.note(this, "arena link: " + SignInTrace.redact(data.toString()));
        // A sign-in that started in a browser comes back as a link with a ticket on it.
        // That link is not an ordinary link: it has to be opened inside the app's own
        // WebView so Arena sets the session cookie here, which is the whole point of the
        // handoff. The sheet owns it, because the sheet is what finishes the sign-in.
        if (isSignInHandoff(data)) {
            ensureSignInSheet().takeBrowserHandoff(data);
            return;
        }
        if (tabs != null) tabs.loadInCurrent(data.toString());
    }

    /** https + arena's own host. Used before any URL touched by a page is opened. */
    static boolean isArenaUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String target = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            return isArenaHost(target);
        } catch (Exception notAUrl) {
            return false;
        }
    }

    /** "arena.ai", "www.arena.ai", "lmarena.ai" - and nothing that merely ends with it. */
    static boolean isArenaHost(String host) {
        if (host == null) return false;
        for (String base : new String[]{"arena.ai", "lmarena.ai"}) {
            if (host.equals(base) || host.endsWith("." + base)) return true;
        }
        return false;
    }

    /**
     * True for a link that finishes a browser sign-in.
     *
     * Arena's handoff is a return to its own host carrying a ticket (or, on some builds, an
     * auth code) instead of an ordinary page path. The path test is first so an ordinary
     * arena link that merely has a query string is still treated as a link.
     */
    private static boolean isSignInHandoff(Uri data) {
        if (data == null) return false;
        String path = data.getPath() == null ? "" : data.getPath().toLowerCase();
        boolean authPath = path.contains("handoff") || path.contains("auth/") 
                || path.contains("callback") || path.equals("/login")
                || path.contains("/signin");
        String query = data.getQuery() == null ? "" : data.getQuery().toLowerCase();
        boolean ticket = query.contains("ticket=") || query.contains("code=")
                || query.contains("token=") || query.contains("session=");
        return authPath || ticket;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (tabs != null) outState.putBoolean(STATE_NO_TABS, tabs.isEmpty());
        if (tabs != null && tabs.current() != null) tabs.current().saveState(outState);
    }

    /**
     * Watches the connection, because the app's behaviour depends on it.
     *
     * Two device reports have the same root: a phone that thinks it is online while the
     * connection cannot reach anything. "The sign-in icon only appears after a refresh" and
     * "sometimes the app does not load" are both a page that was fetched, failed, and was
     * never asked for again. So: when the connection actually comes back, reload what is on
     * screen instead of waiting for the user to notice.
     */
    private void watchConnectivity() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            if (connectivity != null) {
                try {
                    cm.unregisterNetworkCallback(connectivity);
                } catch (Exception ignored) {
                }
            }
            connectivity = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    runOnUiThread(() -> onConnectivityGained());
                }
            };
            cm.registerNetworkCallback(
                    new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build(), connectivity);
        } catch (Exception e) {
            // registerNetworkCallback is API 24+ and may be refused; the app works without.
            connectivity = null;
        }
    }

    /** A network with internet capability appeared: pick up where the page left off. */
    private void onConnectivityGained() {
        if (tabs == null) return;
        CrashLog.note(this, "connection available again");
        if (errorView != null && errorView.getVisibility() == View.VISIBLE) {
            hideError();
            tabs.loadInCurrent(tabs.realUrl(currentTab));
            return;
        }
        WebView page = tabs.current();
        OfflinePages.applyCacheMode(page, true);
        // Only when the page is showing a saved copy or never finished: a page that is
        // fine must not be reloaded just because the phone switched from Wi-Fi to data.
        if (page != null && page.getUrl() != null && page.getUrl().startsWith("file://")) {
            // It is showing a saved copy; now that there is a connection, fetch the real one.
            tabs.loadInCurrent(tabs.realUrl(currentTab));
            return;
        }
        if (stalledBar != null && stalledBar.getVisibility() == View.VISIBLE) {
            tabs.reloadCurrent();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Android reads the PiP parameters at the moment the user leaves, so they are kept
        // current here rather than only at startup: a switch flipped in Settings, or a
        // screen rotated, must be what happens on the way home.
        if (pip != null) pip.sync();
        UpdateCenter.maybeAutoCheck(this, this::onCheckFinished);
        refreshUpdateUi();
        // The page may have navigated while the app was in the background (a sign-in
        // redirect, a link handed back by another app): the arrows would otherwise still
        // describe the page as it was when the user left.
        refreshStrip();
        if (errorView != null && errorView.getVisibility() == View.VISIBLE && isOnline()) {
            hideError();
            // realUrl(), not reload: if the tab fell back to a saved copy, reloading would
            // replay the same local file and the page would stay exactly as broken.
            if (tabs != null) tabs.loadInCurrent(tabs.realUrl(currentTab));
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setStatusBarColors();
        refreshUpdateUi();
        // The system theme can switch under the app (day/night) and the strip builds its
        // chip colours from resources, so it has to be repainted, not just re-measured.
        if (tabStrip != null) tabStrip.onThemeChanged();
        // The floating window's shape is the app's own, and the app just changed shape.
        if (pip != null) pip.sync();
    }

    @Override
    protected void onDestroy() {
        // Leaving the callback registered would keep this activity alive for the life of
        // the process, and reload a WebView that no longer exists.
        if (connectivity != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager)
                        getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(connectivity);
            } catch (Exception ignored) {
            }
            connectivity = null;
        }
        closeWebWindow();
        if (tabs != null) tabs.destroyAll();
        super.onDestroy();
    }

    /**
     * Called by the page after a Back press was turned into an Escape.
     *
     * `dismissed` is the page's own answer to "was something covering the screen, and is
     * it gone now". When it says yes the press has done its job - the overlay is closed,
     * the chat is usable, and the user is exactly where they were. When it says no the
     * press is spent and the next one falls through to navigation, which is the old
     * behaviour: Back still leaves the page rather than being swallowed.
     */
    /** The page's answer to unstickPage(): false means it is still stuck. */
    void onStuckReport(boolean stillStuck) {
        stuckSettled = true;
        if (stillStuck && tabs != null) {
            // Last resort, on the same press: a reload is the only thing that reliably
            // rebuilds a page whose own floating controls have stopped responding.
            tabs.reloadCurrent();
        }
        unstuckTriedFor = -1;
    }

    void onEscapeReport(boolean dismissed) {
        escapeSettled = true;
        if (!dismissed) {
            // Nothing was closed, so Back is what it always was. Doing it now means a
            // single press never becomes a no-op.
            escapeUsedFor = -1;
        }
    }

    // ------------------------------------------------------------------ results

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_INSTALL_UNKNOWN) {
            // Back from the "install unknown apps" toggle: retry, so the user only has
            // to confirm Android's own update prompt.
            installNow();
            return;
        }
        if (requestCode != REQ_FILE || pendingFileCallback == null) return;

        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            String dataString = data.getDataString();
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                results = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    results[i] = clipData.getItemAt(i).getUri();
                }
            } else if (dataString != null) {
                results = new Uri[]{Uri.parse(dataString)};
            }
        }
        pendingFileCallback.onReceiveValue(results);
        pendingFileCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) return;
        if (requestCode != REQ_PERMISSIONS || pendingPermissionRequest == null) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) granted = false;
        }
        try {
            if (granted) {
                pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            } else {
                pendingPermissionRequest.deny();
            }
        } catch (Exception ignored) {
        }
        pendingPermissionRequest = null;
    }

    // ------------------------------------------------------------------ intents

    public static Intent intentForUpdate(Context context, String newVersion) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.putExtra(EXTRA_OPEN_UPDATE, true);
        intent.putExtra(EXTRA_NEW_VERSION, newVersion);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }
}
