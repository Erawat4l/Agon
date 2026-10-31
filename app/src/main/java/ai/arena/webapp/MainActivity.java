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
import android.widget.ImageButton;
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
    private PullToRefreshLayout refresh;
    private AppUi.Nav nav;
    private LinearLayout expander;
    private ImageView expanderIcon;
    private View topBar;
    private TextView updateChip;
    private View errorView;
    private UpdateSheet updateSheet;
    private View menuSheet;
    private View tokenSheet;
    private SignInSheet signInSheet;

    private boolean navExpanded = true;
    private int currentTab = AppUi.TAB_CHAT;
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
        setStatusBarColors();
        UpdateNotifications.ensureChannels(this);

        window = new FrameLayout(this);
        window.setBackgroundColor(AppUi.color(this, R.color.bg));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        window.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AppUi.dp(this, 48)));

        contentFrame = new FrameLayout(this);
        refresh = new PullToRefreshLayout(this);
        refresh.setRefreshTarget(tabsScrollable());
        refresh.addView(contentFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refresh.setOnRefreshListener(new PullToRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                hideError();
                if (tabs != null) tabs.reloadCurrent();
                UpdateCenter.maybeAutoCheck(MainActivity.this, MainActivity.this::onCheckFinished);
            }
        });

        FrameLayout contentHost = new FrameLayout(this);
        contentHost.addView(refresh, new FrameLayout.LayoutParams(
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
                refresh.setRefreshing(false);
            }

            @Override
            public void onMainFrameError(WebView view) {
                refresh.setRefreshing(false);
                showError();
            }

            @Override
            public void onLoadProgress(WebView view, int progress) {
                if (progress >= 100) refresh.setRefreshing(false);
            }
        });

        // Collapsible navigation: icons always visible, labels slide in/out.
        View navArea = buildNavArea();
        column.addView(navArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(window);

        tabs.show(AppUi.TAB_CHAT);
        TabWebViews.applyTheme(tabs.current(), AppUi.isDark(this));
        refresh.setRefreshTarget(tabs.current());
        tabs.preload();
        if (savedInstanceState != null && tabs.current() != null) {
            tabs.current().restoreState(savedInstanceState);
        }
        applyChromeInsets();
        refreshUi();
        handleIntent(getIntent());
        refreshUpdateUi();
        askNotificationPermission();

        // First launch (or after uninstall): explain the one-time GitHub token, since
        // the release repo is private and the updater cannot check it otherwise.
        if (!UpdateManager.hasToken(this)) {
            window.postDelayed(this::promptForTokenFirstRun, 1200);
        }

        // First frames: fade the shell in so the app never appears to "snap" together.
        window.setAlpha(0f);
        window.animate().alpha(1f).setDuration(180).start();
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

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(AppUi.color(this, R.color.surface));
        int pad = AppUi.dp(this, 14);
        bar.setPadding(pad, 0, AppUi.dp(this, 8), 0);

        // Deliberately minimal: brand on the left, only contextual actions on the right.
        bar.addView(AppUi.brandView(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        updateChip = AppUi.updateChip(this);
        updateChip.setOnClickListener(v -> openUpdateSheet());
        updateChip.setVisibility(View.GONE);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.rightMargin = AppUi.dp(this, 6);
        bar.addView(updateChip, chipLp);

        ImageButton more = new ImageButton(this);
        more.setImageResource(R.drawable.ic_nav_more);
        more.setBackground(AppUi.pressable(this, android.graphics.Color.TRANSPARENT, 20f));
        more.setColorFilter(AppUi.color(this, R.color.on_bg_muted));
        more.setContentDescription(getString(R.string.nav_more));
        int p = AppUi.dp(this, 9);
        more.setPadding(p, p, p, p);
        more.setOnClickListener(v -> {
            AppUi.press(v);
            showMenuSheet();
        });
        bar.addView(more, new LinearLayout.LayoutParams(
                AppUi.dp(this, 40), AppUi.dp(this, 40)));

        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View line = AppUi.divider(this);
        FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, AppUi.dp(this, 0.6f)));
        lineLp.gravity = Gravity.BOTTOM;
        wrap.addView(line, lineLp);
        topBar = wrap;
        return wrap;
    }

    /** Nav bar plus a slim handle: tap the arrow to collapse/expand the labels. */
    private View buildNavArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setBackgroundColor(AppUi.color(this, R.color.surface));

        View topLine = AppUi.divider(this);
        area.addView(topLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, AppUi.dp(this, 0.6f))));

        expander = new LinearLayout(this);
        expander.setOrientation(LinearLayout.HORIZONTAL);
        expander.setGravity(Gravity.CENTER);
        expander.setContentDescription(getString(R.string.nav_expand_hint));
        expander.setOnClickListener(v -> toggleNav());
        expanderIcon = AppUi.icon(this, R.drawable.ic_nav_chevron, R.color.on_bg_muted, 18);
        expander.addView(expanderIcon);
        area.addView(expander, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AppUi.dp(this, 18)));

        nav = AppUi.buildNav(this, false, v -> selectTab((Integer) v.getTag()));
        area.addView(nav.bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AppUi.expandedHeight(this)));
        return area;
    }

    /** Tells the page how much space the app's own chrome takes. */
    void applyChromeInsets() {
        if (tabs == null || tabs.current() == null || nav == null) return;
        // The page is already laid out below the top bar and above the nav bar, so only
        // the nav height needs to be reserved inside the page for its fixed elements.
        int bottom = nav.bar.getHeight() + AppUi.dp(this, 18);
        TabWebViews.applyChromeInsets(tabs.current(), 0, bottom);
    }

    private void toggleNav() {
        navExpanded = !navExpanded;
        animateNav();
    }

    private void animateNav() {
        ViewGroup.LayoutParams lp = nav.bar.getLayoutParams();
        int target = navExpanded ? AppUi.expandedHeight(this) : AppUi.collapsedHeight(this);
        lp.height = target;
        nav.bar.setLayoutParams(lp);
        nav.bar.animate().setDuration(AppUi.ANIM_FAST).start();
        expanderIcon.animate().rotation(navExpanded ? 180f : 0f)
                .setDuration(AppUi.ANIM_FAST).start();
        AppUi.styleNav(this, nav, currentTab, UpdateManager.isUpdateAvailable(this),
                navExpanded, true);
        applyChromeInsets();
    }

    // ------------------------------------------------------------------ tabs

    private void selectTab(int tab) {
        if (tab == AppUi.TAB_MORE || tab == AppUi.TAB_UPDATE) {
            // Update lives inside the More sheet; both entries open it.
            showMenuSheet();
            return;
        }
        currentTab = tab;
        hideError();
        boolean firstVisit = tabs.get(tab) == null;
        tabs.show(tab);
        TabWebViews.applyTheme(tabs.current(), AppUi.isDark(this));
        applyChromeInsets();
        resetRefreshState();
        refresh.setRefreshTarget(tabs.current());
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
                if (activity.refresh != null) activity.refresh.setRefreshing(false);
                TabWebViews.applyTheme(view, AppUi.isDark(activity));
                activity.applyChromeInsets();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    if (activity.refresh != null) activity.refresh.setRefreshing(false);
                    activity.showError();
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (activity.refresh != null) activity.refresh.setRefreshing(false);
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
                MainActivity a = MainActivity.this;
                if (newProgress >= 100 && a.refresh != null) a.refresh.setRefreshing(false);
            }

            /**
             * Sign-in flows (Google, Apple, GitHub) use window.open / target=_blank.
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
     * Pages that call window.open (some providers do, bypassing the sheet's own
     * navigation) are routed into the same in-app sign-in flow.
     */
    private void showAuthPopup(WebView parent, android.os.Message resultMsg) {
        if (signInSheet == null) startSignIn();
        // The page expects a transport: give it a real WebView so the login keeps
        // working, then host that WebView inside the sign-in sheet.
        WebView popup = new WebView(this);
        android.webkit.WebSettings s = popup.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(AppChrome.userAgent(this, s.getUserAgentString()));
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);

        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popup);
        resultMsg.sendToTarget();
        signInSheet.showProviderView(popup);
    }

    // ------------------------------------------------------------------ web glue

    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (scheme.equals("http") || scheme.equals("https")) {
            // Keep Arena and its sign-in providers in-app; hand the rest to other apps.
            if (AppChrome.isInternal(uri)) return false;
            AppChrome.openExternally(this, uri);
            return true;
        }
        AppChrome.openExternally(this, uri);
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
    void startSignIn() {
        if (signInSheet == null) signInSheet = new SignInSheet(this, window, new SignInSheet.Host() {
            @Override
            public void onSignedIn() {
                // The provider round-trip set the session cookie: refresh the tabs so
                // they pick up the signed-in UI immediately.
                Toast.makeText(MainActivity.this, R.string.signin_success,
                        Toast.LENGTH_SHORT).show();
                if (tabs != null) {
                    WebView w = tabs.current();
                    if (w != null) {
                        TabWebViews.applyTheme(w, AppUi.isDark(MainActivity.this));
                        w.evaluateJavascript("location.reload()", null);
                    }
                }
            }

            @Override
            public void onDismissed() {
                // keep the instance; it is cheap and reusable
            }
        });
        signInSheet.show();
    }

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
        if (updateChip != null) {
            if (available) {
                updateChip.setText(getString(R.string.update_pill,
                        UpdateManager.latestVersionName(this)));
                if (updateChip.getVisibility() != View.VISIBLE) {
                    updateChip.setVisibility(View.VISIBLE);
                    AppUi.pop(updateChip);
                }
            } else {
                updateChip.setVisibility(View.GONE);
            }
        }
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
        logo.setImageResource(R.mipmap.ic_launcher);
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

    /** Called when switching tabs: a half-finished pull must not carry over. */
    private void resetRefreshState() {
        if (refresh == null) return;
        refresh.setRefreshing(false);
        refresh.cancelRefreshIfUnfinished();
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
        if (data != null && data.getHost() != null) {
            String host = data.getHost().toLowerCase();
            if (host.endsWith("arena.ai") || host.endsWith("lmarena.ai")) {
                tabs.loadInCurrent(data.toString());
            }
        }
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
