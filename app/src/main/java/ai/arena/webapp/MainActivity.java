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
import android.net.NetworkInfo;
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
    private View errorView;
    private UpdateSheet updateSheet;
    private View menuSheet;
    private View tokenSheet;
    private SignInSheet signInSheet;
    private SettingsSheet settingsSheet;
    private ChatHistorySheet historySheet;

    private boolean navExpanded = false;
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
        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        contentHost.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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

        // No head panel and no top strip: arena's own header is the header. The page is
        // the whole screen apart from the bottom nav, which is reserved in CSS.
        navArea = buildNavArea();
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.BOTTOM;
        window.addView(navArea, navLp);

        setContentView(window);
        navArea.setVisibility(Settings.showNav(this) ? View.VISIBLE : View.GONE);

        tabs.show(AppUi.TAB_CHAT);
        if (savedInstanceState != null && tabs.current() != null) {
            tabs.current().restoreState(savedInstanceState);
        }
        applyChromeInsets();
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

        // First launch (or after uninstall): explain the one-time GitHub token, since
        // the release repo is private and the updater cannot check it otherwise.
        if (!UpdateManager.hasToken(this)) {
            window.postDelayed(this::promptForTokenFirstRun, 1600);
        }

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
     * Tells the page how much space the app's own chrome takes.
     *
     * The nav is drawn over the page (an overlay, not a row), so both the top bar and
     * the nav have to be reserved inside the page as padding: otherwise arena's
     * bottom-anchored composer and its fixed menus slide under the app's chrome -
     * the "page spread out of the UI / options are hidden" symptom.
     */
    void applyChromeInsets() {
        if (tabs == null) return;
        // Nothing is drawn over the top of the page any more.
        final int top = 0;
        // Measured once laid out; the first pass uses the known bar height. The nav is an
        // overlay over the page, so its height has to be reserved inside the page -
        // without it the last row of arena's own sidebar (its "Log in" button) sat
        // underneath the nav and could not be tapped.
        // Settings -> "Show the bottom bar": with it off the page gets the whole frame
        // and is not padded at the bottom either.
        final boolean navOn = Settings.showNav(this);
        final int bottom = !navOn
                ? 0
                : (navArea != null && navArea.getHeight() > 0
                ? navArea.getHeight()
                : AppUi.collapsedHeight(this) + AppUi.dp(this, 1));
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
        if (currentTab != AppUi.TAB_CHAT) {
            showTab(AppUi.TAB_CHAT);
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
        if (currentTab != AppUi.TAB_CHAT) showTab(AppUi.TAB_CHAT);
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
        historySheet.show(tabs.get(AppUi.TAB_CHAT));
    }

    /** Three taps: a fresh chat, using arena's own "new chat" affordance. */
    private void openNewChat() {
        if (currentTab != AppUi.TAB_CHAT) showTab(AppUi.TAB_CHAT);
        WebView chat = tabs.get(AppUi.TAB_CHAT);
        if (chat == null) return;
        TabWebViews.startNewChat(chat);
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
            AppUi.styleNav(this, nav, currentTab, UpdateManager.isUpdateAvailable(this),
                    navExpanded, true);
            showMenuSheet();
            return;
        }
        showTab(tab);
    }

    /** Switches the visible page (the tabs that are not sheets). */
    private void showTab(int tab) {
        if (tab == AppUi.TAB_UPDATE || tab == AppUi.TAB_MORE) return;
        currentTab = tab;
        hideError();
        boolean firstVisit = tabs.get(tab) == null;
        tabs.show(tab);
        TabWebViews.applyTheme(tabs.current(), AppUi.isDark(this));
        applyChromeInsets();
        AppUi.styleNav(this, nav, currentTab, UpdateManager.isUpdateAvailable(this),
                navExpanded, true);
        if (!firstVisit) {
            // Warm tab: already painted, just confirm freshness in the background.
            tabs.get(tab).evaluateJavascript(
                    "window.dispatchEvent(new Event('focus'))", null);
        }
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

    WebChromeClient newTabChrome() {
        return new AppChrome.Base(this) {

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // no progress bar: the page paints itself
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
            boolean provider = url.isEmpty() || AppChrome.isSignInHost(host);
            if (provider) {
                // The sheet owns the window from here: it attaches the popup (a lazy one
                // renders nothing until it is attached), keeps the navigation inside the
                // app and watches for the session coming back.
                ensureSignInSheet().openAccountWindow(popup, true);
            } else if (tabs != null) {
                popup.destroy();
                tabs.loadInCurrent(url);
            }
        });
    }

    /** The sign-in sheet, created but not shown. */
    private SignInSheet ensureSignInSheet() {
        if (signInSheet == null) signInSheet = new SignInSheet(this, window, signInHost);
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
        s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
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
        AppUi.styleNav(this, nav, currentTab, UpdateManager.isUpdateAvailable(this),
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
        if (tokenSheet != null) {
            dismissSheet(tokenSheet);
            tokenSheet = null;
        }
        startProviderSignIn(null);
    }

    /** Opens the provider's own login page inside the app, for the chosen account. */
    private void startProviderSignIn(String email) {
        ensureSignInSheet().showForEmail(email);
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
            public void onDiagnostics() {
                SignInDiagnostics.show(MainActivity.this, window, tabs.current());
            }

            @Override
            public void onClearAppData() {
                if (tabs != null) tabs.clearAndReload();
            }

            @Override
            public void setAppearance(int mode) {
                AppUi.setAppearance(MainActivity.this, mode);
                recreateWithAnimation();
            }

            @Override
            public void onDismissed() {
                menuSheet = null;
            }
        });
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
                if (tabs != null) tabs.clearAndReload();
            }

            @Override
            public void onClearData() {
                closeSettingsNow();
                if (tabs != null) tabs.clearAndReload();
            }

            @Override
            public void onResetSettings() {
                Settings.reset(MainActivity.this);
                closeSettingsNow();
                recreateWithAnimation();
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
            case Settings.K_HIDE_WEB_SIDEBAR:
            case Settings.K_HIDE_WEB_HEADER:
                // The rules are a <style> element that is replaced on every pass, so the
                // change lands immediately. Turning a hiding rule *off* also needs a
                // reload, because the JS fallback hid a container with an inline style.
                applyChromeInsets();
                if (!value && tabs != null) tabs.reloadAll();
                break;
            case Settings.K_DESKTOP_UA:
                if (tabs != null) tabs.applyUserAgentToAll();
                break;
            case Settings.K_NOTIFY_UPDATES:
                if (!value) UpdateNotifications.cancelAll(this);
                break;
            case Settings.K_NO_SELECT:
                applyChromeInsets();   // the CSS is replaced in place, no reload needed
                break;
            case Settings.K_KEEP_SCROLL:
                break;
            case Settings.K_SHOW_VERSION:
                rebuildNavArea();
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
        window.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || tabs == null) return;
            tabs.preload();
        }, 2500);
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
        int index = window.indexOfChild(navArea);
        window.removeView(navArea);
        navArea = buildNavArea();
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.BOTTOM;
        window.addView(navArea, Math.max(0, Math.min(index, window.getChildCount())), navLp);
        navArea.setVisibility(Settings.showNav(this) ? View.VISIBLE : View.GONE);
        AppUi.styleNav(this, nav, currentTab, UpdateManager.isUpdateAvailable(this),
                false, false);
        applyChromeInsets();
    }

    private void recreateWithAnimation() {
        window.animate().alpha(0f).setDuration(120).withEndAction(this::recreate).start();
    }

    // ------------------------------------------------------------------ token

    /**
     * One-time, friendly token prompt. Shown as an animated sheet; skipping is fine and
     * the app keeps working (the Update screen offers the same thing later).
     */
    void promptForTokenFirstRun() {
        if (isFinishing() || tokenSheet != null || UpdateManager.hasToken(this)) return;

        final FrameLayout scrim = AppUi.sheetScrim(this, null);
        tokenSheet = scrim;
        scrim.setOnClickListener(v -> {
            dismissSheet(scrim);
            tokenSheet = null;
        });

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(AppUi.dp(this, 22), AppUi.dp(this, 16),
                AppUi.dp(this, 22), AppUi.dp(this, 20));
        sheet.setBackground(AppUi.rounded(AppUi.color(this, R.color.surface), 24f, this));
        sheet.setOnClickListener(v -> {
        });

        sheet.addView(AppUi.text(this, getString(R.string.token_prompt_title), 19f,
                R.color.on_bg, true));
        TextView body = AppUi.text(this, getString(R.string.token_prompt_body), 13f,
                R.color.on_bg_muted, false);
        body.setPadding(0, AppUi.dp(this, 8), 0, AppUi.dp(this, 14));
        sheet.addView(body);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.update_token_hint);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        input.setPadding(AppUi.dp(this, 12), AppUi.dp(this, 11),
                AppUi.dp(this, 12), AppUi.dp(this, 11));
        input.setBackground(AppUi.rounded(AppUi.color(this, R.color.surface2), 12f, this));
        sheet.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView save = AppUi.text(this, getString(R.string.token_prompt_save), 15f,
                R.color.on_accent, true);
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, AppUi.dp(this, 14), 0, AppUi.dp(this, 14));
        save.setBackground(AppUi.pressable(this, AppUi.color(this, R.color.accent), 14f));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = AppUi.dp(this, 16);
        sheet.addView(save, saveLp);

        TextView skip = AppUi.text(this, getString(R.string.token_prompt_skip), 13.5f,
                R.color.on_bg_muted, false);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, AppUi.dp(this, 12), 0, 0);
        skip.setOnClickListener(v -> {
            dismissSheet(scrim);
            tokenSheet = null;
        });
        sheet.addView(skip);

        save.setOnClickListener(v -> {
            String token = input.getText().toString().trim();
            UpdateManager.setToken(MainActivity.this, token);
            AppUi.press(save);
            dismissSheet(scrim);
            tokenSheet = null;
            if (!token.isEmpty()) {
                UpdateCenter.forceCheck(MainActivity.this, MainActivity.this::onCheckFinished);
            }
        });

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        scrim.addView(sheet, sheetLp);
        window.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        AppUi.slideUp(sheet);
    }

    /** Animated dismissal shared by the token sheet and the auth popup. */
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

    /** Called by the Update sheet when it needs a token. */
    public void requestToken() {
        promptForTokenFirstRun();
    }

    private void refreshUpdateUi() {
        boolean available = UpdateManager.isUpdateAvailable(this);
        // No chip in a top bar (there is no top bar): the More icon carries a dot and
        // the More sheet spells the version out.
        if (nav != null) {
            AppUi.styleNav(this, nav, currentTab, available, navExpanded, false);
        }
        if (updateSheet != null) updateSheet.refresh();
    }

    private void refreshUi() {
        if (nav != null) {
            AppUi.styleNav(this, nav, currentTab, UpdateManager.isUpdateAvailable(this),
                    navExpanded, false);
        }
    }

    // ------------------------------------------------------------------ error view

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
                tabs.loadInCurrent(TabWebViews.urlFor(currentTab));
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

    // ------------------------------------------------------------------ navigation

    @Override
    public void onBackPressed() {
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
        if (tokenSheet != null) {
            dismissSheet(tokenSheet);
            tokenSheet = null;
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
        Uri data = intent.getData();
        if (data == null || data.getHost() == null) return;
        String host = data.getHost().toLowerCase();
        if (!host.endsWith("arena.ai") && !host.endsWith("lmarena.ai")) return;
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
        if (tabs != null && tabs.current() != null) tabs.current().saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        UpdateCenter.maybeAutoCheck(this, this::onCheckFinished);
        refreshUpdateUi();
        if (errorView != null && errorView.getVisibility() == View.VISIBLE && isOnline()) {
            hideError();
            if (tabs != null) tabs.reloadCurrent();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setStatusBarColors();
        refreshUpdateUi();
    }

    @Override
    protected void onDestroy() {
        if (tabs != null) tabs.destroyAll();
        super.onDestroy();
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
