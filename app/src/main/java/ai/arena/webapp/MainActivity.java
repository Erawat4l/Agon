package ai.arena.webapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Arena as a native Android app.
 *
 * Chrome-style shell (top bar + persistent nav rail + sheets) around the Arena web
 * experience, plus an in-app updater fed by the project's own GitHub releases.
 */
public class MainActivity extends Activity implements UpdateSheet.Host {

    public static final String ACTION_RELOAD = "ai.arena.webapp.RELOAD";
    public static final String EXTRA_OPEN_UPDATE = "open_update";
    public static final String EXTRA_NEW_VERSION = "new_version";

    private static final String START_URL = "https://arena.ai/";
    private static final String URL_LEADERBOARD = "https://arena.ai/leaderboard";
    private static final String URL_SEARCH = "https://arena.ai/history/search";
    private static final int REQ_FILE = 4201;
    private static final int REQ_PERMISSIONS = 4202;
    private static final int REQ_NOTIFICATIONS = 4203;

    private WebView web;
    private PullToRefreshLayout refresh;
    private View errorView;
    private FrameLayout window;
    private LinearLayout topBar;
    private TextView updatePill;
    private LinearLayout rail;
    private final List<AppUi.NavItem> navItems = new ArrayList<>();
    private UpdateSheet updateSheet;
    private View menuSheet;

    private ValueCallback<Uri[]> pendingFileCallback;
    private PermissionRequest pendingPermissionRequest;
    private long lastBackPressMs;
    private int currentTab = AppUi.TAB_CHAT;

    // ------------------------------------------------------------------ lifecycle

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
                ViewGroup.LayoutParams.MATCH_PARENT, AppUi.dp(this, 58)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (isWide()) {
            rail = (LinearLayout) buildRail();
            body.addView(rail, new LinearLayout.LayoutParams(
                    AppUi.dp(this, 82), ViewGroup.LayoutParams.MATCH_PARENT));
            View sep = new View(this);
            sep.setBackgroundColor(AppUi.color(this, R.color.divider));
            body.addView(sep, new LinearLayout.LayoutParams(
                    Math.max(1, AppUi.dp(this, 0.6f)), ViewGroup.LayoutParams.MATCH_PARENT));
        }

        FrameLayout content = new FrameLayout(this);
        body.addView(content, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        web = new WebView(this);
        web.setBackgroundColor(AppUi.color(this, R.color.bg));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        refresh = new PullToRefreshLayout(this);
        refresh.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refresh.setTarget(web);
        refresh.setOnRefreshListener(new PullToRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                hideError();
                web.reload();
                UpdateCenter.maybeAutoCheck(MainActivity.this, MainActivity.this::onCheckFinished);
            }
        });
        content.addView(refresh, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (!isWide()) {
            View bottomBar = AppUi.buildBottomBar(this, navItems, v -> {
                int tab = (Integer) v.getTag();
                selectTab(tab, true);
            });
            View topLine = new View(this);
            topLine.setBackgroundColor(AppUi.color(this, R.color.divider));
            column.addView(topLine, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, AppUi.dp(this, 0.6f))));
            column.addView(bottomBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        content.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(window);

        configureWebView();
        selectTab(AppUi.TAB_CHAT, false);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }

        handleIntent(getIntent());
        refreshUpdateUi();
        askNotificationPermission();
    }

    private boolean isWide() {
        return getResources().getConfiguration().screenWidthDp >= 600;
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
        w.setNavigationBarColor(AppUi.color(this, R.color.bg));
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

    // ------------------------------------------------------------------ shell

    private View buildTopBar() {
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(AppUi.color(this, R.color.surface));
        int pad = AppUi.dp(this, 14);
        topBar.setPadding(pad, 0, AppUi.dp(this, 8), 0);

        topBar.addView(AppUi.brandView(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((LinearLayout) topBar.getChildAt(0)).setGravity(Gravity.CENTER_VERTICAL);

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        updatePill = AppUi.updatePill(this);
        updatePill.setOnClickListener(v -> openUpdateSheet());
        topBar.addView(updatePill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View reload = AppUi.iconButton(this, R.drawable.ic_nav_reload,
                getString(R.string.reload));
        ((android.widget.ImageButton) reload).setOnClickListener(v -> {
            hideError();
            web.reload();
        });
        LinearLayout.LayoutParams reloadLp = new LinearLayout.LayoutParams(
                AppUi.dp(this, 42), AppUi.dp(this, 42));
        reloadLp.leftMargin = AppUi.dp(this, 6);
        topBar.addView(reload, reloadLp);

        View more = AppUi.iconButton(this, R.drawable.ic_nav_more, getString(R.string.nav_more));
        ((android.widget.ImageButton) more).setOnClickListener(v -> showMenuSheet());
        topBar.addView(more, new LinearLayout.LayoutParams(
                AppUi.dp(this, 42), AppUi.dp(this, 42)));

        View bottom = new View(this);
        bottom.setBackgroundColor(AppUi.color(this, R.color.divider));
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, AppUi.dp(this, 0.6f)));
        lineLp.gravity = Gravity.BOTTOM;
        wrap.addView(bottom, lineLp);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AppUi.dp(this, 58)));
        topBarHolder = wrap;
        return wrap;
    }

    private View topBarHolder;

    private View buildRail() {
        View railView = AppUi.buildRail(this, navItems, v -> {
            int tab = (Integer) v.getTag();
            selectTab(tab, true);
        });
        // Extra accent dot on the update icon when a new build exists.
        return railView;
    }

    private void selectTab(int tab, boolean navigate) {
        currentTab = tab;
        if (tab == AppUi.TAB_UPDATE) {
            openUpdateSheet();
        } else if (tab == AppUi.TAB_MORE) {
            showMenuSheet();
        } else if (navigate) {
            String url = tab == AppUi.TAB_LEADERBOARD ? URL_LEADERBOARD
                    : tab == AppUi.TAB_SEARCH ? URL_SEARCH : START_URL;
            hideError();
            web.loadUrl(url);
        }
        AppUi.setActive(navItems, tab, this, UpdateManager.isUpdateAvailable(this));
    }

    // ------------------------------------------------------------------ update UI

    void onCheckFinished(UpdateManager.Result result) {
        refreshUpdateUi();
        if (result != null && result.ok && !result.updateAvailable
                && UpdateManager.currentVersionCode(this) >= result.latestCode) {
            UpdateNotifications.cancelAll(this);
        }
    }

    /** Called by the update sheet so the badge reacts immediately. */
    public void onUpdateStateChanged(boolean available) {
        refreshUpdateUi();
    }

    private void refreshUpdateUi() {
        boolean available = UpdateManager.isUpdateAvailable(this);
        if (updatePill != null) {
            updatePill.setVisibility(available ? View.VISIBLE : View.GONE);
            updatePill.setText(available
                    ? getString(R.string.update_pill, UpdateManager.latestVersionName(this))
                    : getString(R.string.app_update));
        }
        if (updatePill != null) {
            updatePill.setBackground(AppUi.pressable(this, AppUi.color(this,
                    available ? R.color.accent : R.color.pill_bg), 18f));
            updatePill.setTextColor(AppUi.color(this,
                    available ? R.color.on_accent : R.color.pill_text));
        }
        AppUi.setActive(navItems, currentTab, this, available);
        if (updateSheet != null) updateSheet.refresh();
    }

    // ------------------------------------------------------------------ webview

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView() {
        WebSettings s = web.getSettings();
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
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(appUserAgent(s.getUserAgentString()));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new BlobBridge(), "ArenaNative");
        web.setWebViewClient(new AppWebViewClient());
        web.setWebChromeClient(new AppChromeClient());
        web.setDownloadListener(new AppDownloadListener());
        web.setLongClickable(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
    }

    /** WebView's default UA gets refused by some identity providers. */
    private String appUserAgent(String defaultUa) {
        String ua = defaultUa.replace("; wv", "").replace("Version/4.0 ", "");
        return ua + " ArenaApp/" + UpdateManager.currentVersionName(this);
    }

    private void openExternally(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(R.string.no_browser);
        }
    }

    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (scheme.equals("http") || scheme.equals("https")) return false;
        openExternally(uri);
        return true;
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
                web.loadUrl(data.toString());
            }
        }
    }

    // ------------------------------------------------------------------ sheets

    private void openUpdateSheet() {
        if (updateSheet == null) {
            updateSheet = new UpdateSheet(this, this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addView(updateSheet.view(), lp);
        } else {
            updateSheet.refresh();
        }
        // Fresh look every time it opens: kick a silent check.
        UpdateCenter.maybeAutoCheck(this, this::onCheckFinished);
    }

    @Override
    public void closeUpdateSheet() {
        if (updateSheet != null) {
            if (updateSheet.view().getParent() != null) window.removeView(updateSheet.view());
            updateSheet = null;
        }
        if (currentTab == AppUi.TAB_UPDATE) {
            currentTab = AppUi.TAB_CHAT;
            AppUi.setActive(navItems, currentTab, this, UpdateManager.isUpdateAvailable(this));
        }
    }

    @Override
    public void onUpdateDownloaded(File apk) {
        toast(R.string.update_downloaded);
    }

    @Override
    public void launchInstaller(File apk) {
        // System installer always asks the user; never silent.
        UpdateCenter.installApk(this, apk);
    }

    private void showMenuSheet() {
        if (menuSheet != null) {
            if (menuSheet.getParent() != null) window.removeView(menuSheet);
            menuSheet = null;
        }
        menuSheet = MenuSheet.show(this, window, new MenuSheet.Callback() {
            @Override
            public void onReload() {
                hideError();
                web.reload();
            }

            @Override
            public void onOpenUpdate() {
                openUpdateSheet();
            }

            @Override
            public void onShare() {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, START_URL);
                startActivity(Intent.createChooser(share, getString(R.string.share)));
            }

            @Override
            public void onOpenInBrowser() {
                openExternally(Uri.parse(web.getUrl() != null ? web.getUrl() : START_URL));
            }

            @Override
            public void onClearAppData() {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                web.clearHistory();
                web.clearCache(true);
                web.loadUrl(START_URL);
            }

            @Override
            public void onDismissed() {
                menuSheet = null;
                if (currentTab == AppUi.TAB_MORE) {
                    currentTab = AppUi.TAB_CHAT;
                    AppUi.setActive(navItems, currentTab, MainActivity.this,
                            UpdateManager.isUpdateAvailable(MainActivity.this));
                }
            }
        });
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
                AppUi.dp(this, 72), AppUi.dp(this, 72));
        logoLp.bottomMargin = pad / 2;
        box.addView(logo, logoLp);

        TextView title = AppUi.text(this, getString(R.string.error_title), 20f,
                R.color.on_bg, true);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView body = AppUi.text(this, getString(R.string.error_body), 15f,
                R.color.on_bg_muted, false);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, pad / 4, 0, pad / 2);
        box.addView(body);

        Button retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> {
            hideError();
            if (isOnline()) {
                web.loadUrl(START_URL);
            } else {
                showError();
            }
        });
        box.addView(retry);

        Button browser = new Button(this);
        browser.setText(R.string.open_in_browser);
        browser.setAllCaps(false);
        browser.setOnClickListener(v -> openExternally(
                Uri.parse(web.getUrl() != null ? web.getUrl() : START_URL)));
        box.addView(browser);

        return box;
    }

    private void showError() {
        if (errorView != null) errorView.setVisibility(View.VISIBLE);
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

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ clients

    private class AppWebViewClient extends WebViewClient {

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
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (isOnline()) hideError();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            refresh.setRefreshing(false);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                refresh.setRefreshing(false);
                showError();
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description,
                                    String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
            refresh.setRefreshing(false);
            showError();
        }
    }

    private class AppChromeClient extends WebChromeClient {

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
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

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                List<String> wanted = new ArrayList<>();
                for (String res : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                        wanted.add(android.Manifest.permission.RECORD_AUDIO);
                    } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                        wanted.add(android.Manifest.permission.CAMERA);
                    }
                }
                boolean allGranted = true;
                for (String p : wanted) {
                    if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                    }
                }
                if (wanted.isEmpty() || allGranted) {
                    request.grant(request.getResources());
                } else {
                    pendingPermissionRequest = request;
                    requestPermissions(wanted.toArray(new String[0]), REQ_PERMISSIONS);
                }
            });
        }
    }

    private class AppDownloadListener implements android.webkit.DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (url == null) return;
            if (url.startsWith("data:")) {
                downloadDataUri(url, contentDisposition);
                return;
            }
            if (url.startsWith("blob:")) {
                downloadBlob(url, contentDisposition);
                return;
            }
            try {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) request.addRequestHeader("Cookie", cookie);
                request.setTitle(fileName);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    toast(R.string.download_started);
                }
            } catch (Exception e) {
                toast(R.string.download_failed);
            }
        }
    }

    // ------------------------------------------------------------------ downloads

    private void downloadBlob(String blobUrl, String contentDisposition) {
        String name = URLUtil.guessFileName(blobUrl, contentDisposition, null);
        String js = "(function(){"
                + "var url=" + JSONObject.quote(blobUrl) + ";"
                + "var name=" + JSONObject.quote(name) + ";"
                + "var x=new XMLHttpRequest();x.open('GET',url,true);x.responseType='blob';"
                + "x.onload=function(){var b=x.response;var fr=new FileReader();"
                + "fr.onloadend=function(){var s=fr.result;var i=s.indexOf(',');"
                + "if(i>0){ArenaNative.saveBase64(name,b.type||'application/octet-stream',"
                + "s.substring(i+1));}};fr.readAsDataURL(b);};"
                + "x.onerror=function(){ArenaNative.failed('download');};x.send();})();";
        web.evaluateJavascript(js, null);
    }

    private void downloadDataUri(String dataUri, String contentDisposition) {
        try {
            int comma = dataUri.indexOf(',');
            if (comma < 0) {
                toast(R.string.download_failed);
                return;
            }
            String meta = dataUri.substring(5, comma);
            String mime = meta.split(";")[0];
            if (mime.isEmpty()) mime = "application/octet-stream";
            String data = dataUri.substring(comma + 1);
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            String name = URLUtil.guessFileName("download." + (ext == null ? "bin" : ext),
                    contentDisposition, mime);
            if (!meta.contains("base64")) {
                data = Base64.encodeToString(data.getBytes("UTF-8"), Base64.NO_WRAP);
            }
            saveToDownloads(name, mime, data);
        } catch (Exception e) {
            toast(R.string.download_failed);
        }
    }

    private void saveToDownloads(String fileName, String mime, String base64) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);
                Uri target = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) {
                    toast(R.string.download_failed);
                    return;
                }
                OutputStream out = getContentResolver().openOutputStream(target);
                if (out == null) {
                    toast(R.string.download_failed);
                    return;
                }
                out.write(bytes);
                out.close();
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) {
                    toast(R.string.download_failed);
                    return;
                }
                File file = new File(dir, fileName);
                FileOutputStream out = new FileOutputStream(file);
                out.write(bytes);
                out.close();
                android.media.MediaScannerConnection.scanFile(this,
                        new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
            }
            toast(R.string.download_started);
        } catch (Exception e) {
            toast(R.string.download_failed);
        }
    }

    private class BlobBridge {
        @JavascriptInterface
        public void saveBase64(final String name, final String mime, final String base64) {
            runOnUiThread(() -> saveToDownloads(
                    name == null || name.isEmpty() ? "arena-download" : name,
                    mime == null || mime.isEmpty() ? "application/octet-stream" : mime,
                    base64));
        }

        @JavascriptInterface
        public void failed(final String what) {
            runOnUiThread(() -> toast(R.string.download_failed));
        }
    }

    // ------------------------------------------------------------------ results

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
        if (requestCode == REQ_NOTIFICATIONS) {
            // If the user declines, update notifications simply stay quiet;
            // the in-app badge still works.
            return;
        }
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

    // ------------------------------------------------------------------ navigation

    @Override
    public void onBackPressed() {
        if (updateSheet != null) {
            closeUpdateSheet();
            return;
        }
        if (menuSheet != null) {
            window.removeView(menuSheet);
            menuSheet = null;
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) {
            openUpdateSheet();
            return;
        }
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            web.reload();
            return;
        }
        if (intent != null && intent.getData() != null) {
            String host = intent.getData().getHost();
            host = host == null ? "" : host.toLowerCase();
            if (host.endsWith("arena.ai") || host.endsWith("lmarena.ai")) {
                web.loadUrl(intent.getData().toString());
                return;
            }
        }
        web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onPause() {
        // Deliberately not calling web.onPause(): that freezes JS timers and can
        // interrupt an in-flight streaming reply when you switch apps.
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
            web.reload();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("ArenaNative");
            web.destroy();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ intents

    /** Intent that opens the app straight on the update sheet (used by notifications). */
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
