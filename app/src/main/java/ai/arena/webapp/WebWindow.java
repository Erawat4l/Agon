package ai.arena.webapp;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A window the page opened for something that is *not* arena: a local preview, a docs page,
 * a link out of a message.
 *
 * Why this exists: every window.open() used to be funnelled into the sign-in sheet, because
 * a window with no URL yet is indistinguishable from Google's lazy sign-in popup at the
 * moment it is created. A preview link therefore opened inside a sheet titled "Signing in",
 * over a window whose own navigation policy exists to keep a *sign-in* on screen - so a
 * local server that could not be reached, or a navigation it did not recognise, simply left
 * a blank frame: the "nothing happens when I click it" report.
 *
 * Here the window is a plain in-app browser surface instead: it loads wherever it is told,
 * it renders local addresses, and when the document fails it says so and offers a retry
 * instead of leaving white space. It stays on top of the tabs (which keep running
 * underneath) and Back closes it.
 */
public final class WebWindow {

    private final Activity activity;
    private final ViewGroup parent;
    private final FrameLayout root;
    private final TextView title;
    private final TextView status;
    private final WebView web;

    private WebWindow(Activity activity, ViewGroup parent, String url) {
        this.activity = activity;
        this.parent = parent;

        root = new FrameLayout(activity);
        root.setBackgroundColor(AppUi.color(activity, R.color.bg));

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(AppUi.color(activity, R.color.bg));

        // ---- bar: the address, a close button, and the page's own state
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(AppUi.color(activity, R.color.surface));
        bar.setPadding(AppUi.dp(activity, 14), AppUi.dp(activity, 10),
                AppUi.dp(activity, 8), AppUi.dp(activity, 10));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        bar.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        title = AppUi.text(activity, hostOf(url), 13.5f, R.color.on_bg, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(title);

        status = AppUi.text(activity, activity.getString(R.string.webwindow_loading),
                11f, R.color.on_bg_muted, false);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(status);

        TextView close = AppUi.text(activity, "\u2715", 18f, R.color.on_bg_muted, false);
        close.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 6),
                AppUi.dp(activity, 12), AppUi.dp(activity, 6));
        close.setOnClickListener(v -> dismiss());
        bar.addView(close);

        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- the page itself
        web = new WebView(activity);
        web.setBackgroundColor(AppUi.color(activity, R.color.bg));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // A window the page opens may open another one (a preview that logs in, for
        // instance). Without this the second one is dropped silently.
        s.setSupportMultipleWindows(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Files on this device stay out of reach of a remote document, as everywhere else.
        // Plain http is allowed only for localhost through the network security config -
        // that is a local dev server, and refusing it is why a preview link looked dead.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(Settings.chromeUserAgent(activity)
                ? AppChrome.userAgent(activity, s.getUserAgentString())
                : AppChrome.defaultUserAgent(activity));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return openOutside(view, request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openOutside(view, Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (url != null) title.setText(hostOf(url));
                status.setText(activity.getString(R.string.webwindow_loading));
                status.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // A failed load reports through onReceivedError and then often finishes on
                // about:blank, so an empty document is not "loaded".
                if (url != null && !"about:blank".equals(url)) {
                    status.setText(activity.getString(R.string.webwindow_ready));
                    status.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) failed();
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                failed();
            }
        });
        web.setWebChromeClient(new AppChrome.Base(activity) {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message message) {
                // A window opened from inside a window: hand it a WebView of its own, and
                // let the same class host it once it has a URL.
                if (message == null || !(message.obj instanceof WebView.WebViewTransport)) {
                    return false;
                }
                final WebView popup = new WebView(activity);
                popup.getSettings().setJavaScriptEnabled(true);
                popup.getSettings().setDomStorageEnabled(true);
                popup.getSettings().setUserAgentString(web.getSettings().getUserAgentString());
                ((WebView.WebViewTransport) message.obj).setWebView(popup);
                message.sendToTarget();
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    @SuppressWarnings("deprecation")
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        // Its first real navigation is the window the user asked for.
                        if (url != null && url.startsWith("http")) {
                            load(url);
                            popup.post(popup::destroy);
                            return true;
                        }
                        return false;
                    }
                });
                return true;
            }
        });
        web.setDownloadListener(new Downloads(activity));

        column.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Opens a window for {@code url}, or reuses the one already open.
     *
     * The window is a sibling of the tabs, so it covers whatever is behind it without
     * touching the tabs' own state: closing it returns the user exactly where they were.
     */
    public static WebWindow open(Activity activity, ViewGroup parent, String url) {
        WebWindow window = new WebWindow(activity, parent, url);
        parent.addView(window.root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.root.setTranslationZ(AppUi.dp(activity, 20));
        }
        window.load(url);
        window.root.setAlpha(0f);
        window.root.animate().alpha(1f).setDuration(AppUi.ANIM_FAST).start();
        return window;
    }

    public View view() {
        return root;
    }

    /** Loads a URL in this window, refusing only what a WebView must never render. */
    public void load(String url) {
        if (url == null || url.isEmpty()) return;
        String lower = url.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            web.loadUrl(url);
            return;
        }
        if (lower.startsWith("about:")) return;
        // Anything else is a device scheme the app already knows how to hand over.
        if (AppChrome.isDeviceScheme(Uri.parse(url).getScheme())) {
            AppChrome.openNonWebScheme(activity, Uri.parse(url));
        }
    }

    /** Stays inside this window for the web, and hands the rest to the app's own policy. */
    private boolean openOutside(WebView view, Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (scheme.startsWith("http")) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (AppChrome.isInternal(uri) && host.endsWith("arena.ai")) {
                // An arena link: the app has a tab for that, and the window was a detour.
                dismiss();
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).openInTab(uri.toString());
                }
                return true;
            }
            return false;   // a normal page in a normal window
        }
        if (AppChrome.isDeviceScheme(scheme)) {
            AppChrome.openNonWebScheme(activity, uri);
        }
        return true;
    }

    /** The document did not load: say so, and put the fix one tap away. */
    private void failed() {
        status.setText(activity.getString(R.string.webwindow_failed));
        status.setTextColor(AppUi.color(activity, R.color.accent));
        status.setVisibility(View.VISIBLE);
        title.setOnClickListener(v -> {
            String url = web.getUrl();
            if (url != null) load(url);
        });
    }

    public void dismiss() {
        if (root.getParent() == null) return;
        AppUi.slideDownAndRemove(root, parent);
        web.stopLoading();
        web.destroy();
    }

    /** The address without its scheme, so the bar reads like a location, not a URL. */
    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "";
        String out = url.replaceFirst("^https?://", "");
        int slash = out.indexOf('/');
        if (slash > 0) out = out.substring(0, slash);
        return out;
    }
}
