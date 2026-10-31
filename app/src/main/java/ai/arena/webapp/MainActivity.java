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
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Arena as a native Android app: a polished WebView shell around https://arena.ai
 * with pull-to-refresh, uploads, downloads, deep links, and offline handling.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://arena.ai/";
    private static final String ACTION_RELOAD = "ai.arena.webapp.RELOAD";
    private static final int REQ_FILE = 4201;
    private static final int REQ_PERMISSIONS = 4202;

    private WebView web;
    private PullToRefreshLayout refresh;
    private View errorView;
    private ValueCallback<Uri[]> pendingFileCallback;
    private PermissionRequest pendingPermissionRequest;
    private long lastBackPressMs;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStatusBarColors();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColorCompat(R.color.bg));

        web = new WebView(this);
        web.setBackgroundColor(getColorCompat(R.color.bg));
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
            }
        });

        root.addView(refresh, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        configureWebView();
        handleIntent(getIntent());

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    private void setStatusBarColors() {
        Window window = getWindow();
        window.setStatusBarColor(getColorCompat(R.color.bg));
        window.setNavigationBarColor(getColorCompat(R.color.bg));
        int flags = window.getDecorView().getSystemUiVisibility();
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (!night) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private int getColorCompat(int id) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getResources().getColor(id, getTheme());
        }
        //noinspection deprecation
        return getResources().getColor(id);
    }

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

    /**
     * WebView's default UA contains "wv" and "Version/4.0", which makes some
     * sign-in providers refuse to load. Present a plain Chrome-like UA instead.
     */
    private String appUserAgent(String defaultUa) {
        String ua = defaultUa
                .replace("; wv", "")
                .replace("Version/4.0 ", "");
        return ua + " ArenaApp/1.0";
    }

    // ------------------------------------------------------------------ UI / errors

    private View buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(getColorCompat(R.color.bg));
        int pad = (int) (32 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                (int) (72 * getResources().getDisplayMetrics().density),
                (int) (72 * getResources().getDisplayMetrics().density));
        logoLp.bottomMargin = pad / 2;
        box.addView(logo, logoLp);

        TextView title = new TextView(this);
        title.setText(R.string.error_title);
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(getColorCompat(R.color.on_bg));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView body = new TextView(this);
        body.setText(R.string.error_body);
        body.setTextSize(15f);
        body.setGravity(Gravity.CENTER);
        body.setTextColor(getColorCompat(R.color.on_bg_muted));
        body.setPadding(0, pad / 4, 0, pad / 2);
        box.addView(body);

        Button retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setAllCaps(false);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                if (isOnline()) {
                    web.loadUrl(START_URL);
                } else {
                    showError();
                }
            }
        });
        box.addView(retry);

        Button browser = new Button(this);
        browser.setText(R.string.open_in_browser);
        browser.setAllCaps(false);
        browser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = web.getUrl() != null ? web.getUrl() : START_URL;
                openExternally(Uri.parse(url));
            }
        });
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

    // ------------------------------------------------------------------ navigation

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
        if (scheme.equals("http") || scheme.equals("https")) {
            return false; // keep browsing inside the app
        }
        // mailto:, tel:, intent:, market:, upi:, custom app schemes ...
        openExternally(uri);
        return true;
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();

        if (ACTION_RELOAD.equals(action)) {
            web.reload();
            return;
        }
        if (Intent.ACTION_SEND.equals(action)) {
            // Shared text arrives here; just bring Arena up.
            web.loadUrl(START_URL);
            return;
        }
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            Uri data = intent.getData();
            String host = data.getHost() == null ? "" : data.getHost().toLowerCase();
            if (host.endsWith("arena.ai") || host.endsWith("lmarena.ai")) {
                web.loadUrl(data.toString());
                return;
            }
        }
        // default: fresh start handled by caller
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getData() != null) {
            Uri data = intent.getData();
            String host = data.getHost() == null ? "" : data.getHost().toLowerCase();
            if (host.endsWith("arena.ai") || host.endsWith("lmarena.ai")) {
                web.loadUrl(data.toString());
                return;
            }
        }
        if (ACTION_RELOAD.equals(intent.getAction())) {
            web.reload();
            return;
        }
        web.loadUrl(START_URL);
    }

    // ------------------------------------------------------------------ back button

    @Override
    public void onBackPressed() {
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

    // ------------------------------------------------------------------ lifecycle

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
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(null);
            }
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
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
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
                    if (wanted.isEmpty()) {
                        request.grant(request.getResources());
                    } else if (allGranted) {
                        request.grant(request.getResources());
                    } else {
                        pendingPermissionRequest = request;
                        requestPermissions(wanted.toArray(new String[0]), REQ_PERMISSIONS);
                    }
                }
            });
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress >= 100) {
                refresh.setRefreshing(false);
            }
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
                DownloadManager dm = (DownloadManager)
                        getSystemService(Context.DOWNLOAD_SERVICE);
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
                + "if(i>0){ArenaNative.saveBase64(name,b.type||'application/octet-stream',s.substring(i+1));}};"
                + "fr.readAsDataURL(b);};x.onerror=function(){ArenaNative.failed('download');};"
                + "x.send();})();";
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

    /** Bridge used by the blob-download JavaScript above. */
    private class BlobBridge {
        @JavascriptInterface
        public void saveBase64(final String name, final String mime, final String base64) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    saveToDownloads(name == null || name.isEmpty() ? "arena-download" : name,
                            mime == null || mime.isEmpty() ? "application/octet-stream" : mime,
                            base64);
                }
            });
        }

        @JavascriptInterface
        public void failed(final String what) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    toast(R.string.download_failed);
                }
            });
        }
    }

    // ------------------------------------------------------------------ results

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE) return;
        if (pendingFileCallback == null) return;

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
}
