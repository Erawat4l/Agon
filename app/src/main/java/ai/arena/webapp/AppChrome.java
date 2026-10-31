package ai.arena.webapp;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

/** Browser chrome: user agent, progress, in-app popups for sign-in. */
public final class AppChrome {

    private AppChrome() {
    }

    /** WebView's stock UA is refused by some identity providers; present a Chrome-ish one. */
    public static String userAgent(Activity activity, String defaultUa) {
        String ua = defaultUa == null ? "" : defaultUa.replace("; wv", "").replace("Version/4.0 ", "");
        return ua + " ArenaApp/" + UpdateManager.currentVersionName(activity);
    }

    /** True for URLs that belong inside the app. */
    public static boolean isInternal(Uri uri) {
        if (uri == null) return false;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.startsWith("http")) return false;
        return host.endsWith("arena.ai") || host.endsWith("lmarena.ai")
                || host.endsWith("google.com") || host.endsWith("googleusercontent.com")
                || host.endsWith("gstatic.com") || host.endsWith("apple.com")
                || host.endsWith("github.com") || host.endsWith("githubusercontent.com")
                || host.endsWith("cloudflare.com") || host.endsWith("hcaptcha.com")
                || host.endsWith("recaptcha.net") || host.endsWith("stripe.com");
    }

    /** Base chrome client: file chooser + permissions + window.open capture. */
    public abstract static class Base extends WebChromeClient {

        protected final Activity activity;

        protected Base(Activity activity) {
            this.activity = activity;
        }

        @Override
        public void onPermissionRequest(final android.webkit.PermissionRequest request) {
            activity.runOnUiThread(() -> ((MainActivity) activity)
                    .handleWebPermissionRequest(request));
        }

        @Override
        public boolean onShowFileChooser(WebView webView,
                                         android.webkit.ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            return ((MainActivity) activity).handleFileChooser(callback, params);
        }
    }

    /** Opens a link outside the app when no in-app handler applies. */
    static void openExternally(Activity activity, Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            android.widget.Toast.makeText(activity, R.string.no_browser,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    static boolean isApi30() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }
}
