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
        String ua = clean(defaultUa);
        if (ua.trim().isEmpty()) ua = defaultUserAgent(activity);
        return ua.trim();
    }

    /**
     * What the WebView reports with the "Chrome user agent" switch turned off: a
     * vanilla WebView UA, no app token. Arena can still spot it, which is exactly why
     * the switch defaults to on.
     */
    public static String defaultUserAgent(Activity activity) {
        // Deliberately *not* cleaned: with the switch off the app should report exactly
        // what the WebView is, which is what some sites insist on (and others punish).
        String ua = android.webkit.WebSettings.getDefaultUserAgent(activity);
        return ua == null ? "" : ua;
    }

    /** Strips the WebView marker, the app token and the stock Chrome version token. */
    private static String clean(String uaIn) {
        String ua = uaIn == null ? "" : uaIn;
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

    /**
     * True for hosts an in-app sign-in legitimately passes through.
     *
     * Google is the one provider Arena accepts for an account; GitHub is not a sign-in
     * here at all (it is the account for Agent Mode connectors and app updates), so it is
     * not in this list. The remaining hosts are not decoration: Google's identity pages
     * load proof-of-work and script from gstatic, and the Cloudflare/reCAPTCHA/hCaptcha
     * challenges sit in front of arena itself. A host that is not here is still never
     * opened - it is simply loaded inside the app like any other page; this list only
     * decides what a *popup* is allowed to be.
     */
    public static boolean isSignInHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return h.endsWith("google.com") || h.endsWith("gstatic.com")
                || h.endsWith("googleusercontent.com")
                || h.endsWith("cloudflare.com") || h.endsWith("hcaptcha.com")
                || h.endsWith("recaptcha.net") || h.endsWith("arena.ai")
                || h.endsWith("lmarena.ai");
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

        // ------------------------------------------------------------------ script dialogs
        //
        // A WebView with no onJsAlert/onJsConfirm/onJsPrompt override answers every one of
        // them itself and immediately: alert() returns, confirm() returns *false*, prompt()
        // returns null. Nothing is drawn, so from the user's side the button they tapped
        // simply did nothing - and a confirm() that quietly answers "no" is how a delete
        // or a rename inside arena's own file panel looks broken. The dialogs are drawn
        // here in the app's own styling, one at a time (a page can block on a dialog, and
        // Android crashes the renderer if a second one is stacked on top).

        /** True while one script dialog owns the screen: only one may be up at a time. */
        private boolean dialogOpen;

        /**
         * The page's outstanding answer, if any.
         *
         * A script dialog *blocks the page*: until confirm()/cancel() is called on this
         * object, the document is frozen. So it is answered on every exit path - a button,
         * Back, or the dialog being torn down with the activity - and the check is idempotent
         * because answering twice is as bad as never answering.
         */
        private android.webkit.JsResult pendingResult;

        /** A prompt() is blocking the page, so nothing else may be drawn over it. */
        protected boolean isDialogOpen() {
            return dialogOpen;
        }

        private android.app.AlertDialog.Builder themed() {
            return new android.app.AlertDialog.Builder(activity);
        }

        /**
         * Shows one dialog and keeps {@link #pendingResult} as the page's answer.
         *
         * The dismissal path is the important one: an AlertDialog can be closed by Back,
         * without any button being pressed, and that left the page blocked for ever - the
         * "everything stopped responding after I opened the file panel" symptom.
         */
        private void showDialog(final android.app.AlertDialog.Builder builder,
                                final android.webkit.JsResult result) {
            if (dialogOpen) {
                // The page is blocked on the first dialog; a second one has nowhere to go,
                // so it is answered at once rather than stacked.
                result.cancel();
                return;
            }
            dialogOpen = true;
            pendingResult = result;
            final android.app.AlertDialog dialog;
            try {
                dialog = builder.create();
            } catch (RuntimeException e) {
                dialogOpen = false;
                answer(RESULT_CANCEL);
                return;
            }
            dialog.setOnDismissListener(d -> {
                dialogOpen = false;
                // Back, or the window going away: whatever has not been answered yet gets
                // the "no" the page would have got from a stock WebView, so it unfreezes.
                answer(RESULT_CANCEL);
            });
            // The tab underneath must not be tapped through a modal question.
            dialog.setCanceledOnTouchOutside(false);
            try {
                dialog.show();
            } catch (RuntimeException e) {
                dialogOpen = false;
                answer(RESULT_CANCEL);
            }
        }

        private static final int RESULT_CANCEL = 0;
        private static final int RESULT_CONFIRM = 1;

        /** Answers the page exactly once, whichever path got here first. */
        private void answer(int how) {
            answer(how, null);
        }

        private synchronized void answer(int how, String text) {
            final android.webkit.JsResult result = pendingResult;
            if (result == null) return;
            pendingResult = null;
            if (how == RESULT_CONFIRM && result instanceof android.webkit.JsPromptResult) {
                ((android.webkit.JsPromptResult) result).confirm(text == null ? "" : text);
                return;
            }
            if (how == RESULT_CONFIRM) result.confirm();
            else result.cancel();
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message,
                                 final android.webkit.JsResult result) {
            showDialog(themed().setMessage(message)
                    .setPositiveButton(R.string.dialog_ok, (d, w) -> answer(RESULT_CONFIRM)),
                    result);
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message,
                                   final android.webkit.JsResult result) {
            showDialog(themed().setMessage(message)
                    .setPositiveButton(R.string.dialog_ok, (d, w) -> answer(RESULT_CONFIRM))
                    .setNegativeButton(R.string.dialog_cancel,
                            (d, w) -> answer(RESULT_CANCEL)),
                    result);
            return true;
        }

        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message,
                                        final android.webkit.JsResult result) {
            // Leaving the page would throw away a draft: ask, instead of dropping it.
            return onJsConfirm(view, url, message, result);
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message,
                                  String defaultValue,
                                  final android.webkit.JsPromptResult result) {
            final android.widget.EditText field = new android.widget.EditText(activity);
            field.setText(defaultValue == null ? "" : defaultValue);
            field.setSingleLine(message != null && message.length() < 80);
            int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
            field.setPadding(pad, pad, pad, pad);
            showDialog(themed().setMessage(message).setView(field)
                    .setPositiveButton(R.string.dialog_ok,
                            (d, w) -> answer(RESULT_CONFIRM, field.getText().toString()))
                    .setNegativeButton(R.string.dialog_cancel,
                            (d, w) -> answer(RESULT_CANCEL)),
                    result);
            return true;
        }
    }

    /**
     * Letters, phone calls and SMS are the only things that may leave the app, because
     * a WebView cannot do them. Nothing here can ever open a browser: every http(s)
     * link is rendered inside the app by the caller.
     */
    /** Schemes that belong to the device (a WebView genuinely cannot send an e-mail). */
    public static boolean isDeviceScheme(String scheme) {
        return "mailto".equals(scheme) || "tel".equals(scheme) || "sms".equals(scheme)
                || "smsto".equals(scheme) || "geo".equals(scheme);
    }

    static void openNonWebScheme(Activity activity, Uri uri) {
        String scheme = uri == null || uri.getScheme() == null
                ? "" : uri.getScheme().toLowerCase();
        boolean allowed = scheme.equals("mailto") || scheme.equals("tel")
                || scheme.equals("sms") || scheme.equals("smsto")
                || scheme.equals("geo");
        if (!allowed) {
            // Anything else (market:, custom app schemes, ...) is simply ignored: the app
            // never leaves Arena, and it never shows the "this link needs another app"
            // notice that used to read like an error page.
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
