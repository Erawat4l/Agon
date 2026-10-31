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

    /**
     * A clean Chrome user agent.
     *
     * Cloudflare Turnstile ("Verify you are human") and Google's sign-in both treat a
     * WebView UA ("; wv") as a bot/unsupported browser, which is where the robot check
     * came from. This strips the WebView marker and the app token, keeps the real
     * Android version so the page still serves mobile content, and adds no vendor
     * suffix that a bot-detection rule could key on.
     */
    public static String userAgent(Activity activity, String defaultUa) {
        String ua = defaultUa == null ? "" : defaultUa;
        ua = ua.replace("; wv)", ")").replace("; wv ", " ").replace("Version/4.0 ", "");
        // Drop any previous app suffix so the string stays byte-stable across updates.
        int marker = ua.indexOf(" ArenaApp/");
        if (marker > 0) ua = ua.substring(0, marker);
        if (ua.trim().isEmpty()) {
            ua = "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; " + Build.MODEL
                    + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
        }
        return ua.trim();
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

    /**
     * Letters, phone calls and SMS are the only things that may leave the app, because
     * a WebView cannot do them. Nothing here can ever open a browser: every http(s)
     * link is rendered inside the app by the caller.
     */
    static void openNonWebScheme(Activity activity, Uri uri) {
        String scheme = uri == null || uri.getScheme() == null
                ? "" : uri.getScheme().toLowerCase();
        boolean allowed = scheme.equals("mailto") || scheme.equals("tel")
                || scheme.equals("sms") || scheme.equals("smsto")
                || scheme.equals("geo");
        if (!allowed) {
            // Anything else (market:, custom app schemes, ...) stays inside Arena as a
            // notice, so tapping a link can never dump the user in another app.
            android.widget.Toast.makeText(activity, R.string.link_stays_in_app,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
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
