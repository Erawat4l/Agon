package ai.arena.webapp;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * In-app sign-in: an account chooser styled like a native bottom sheet, plus a hidden
 * WebView running the provider's real login page.
 *
 * Flow: pick "Continue with Google" -> the provider page opens full-screen inside the
 * app (never a browser) -> when it redirects back to arena.ai the sheet closes and the
 * main tab is already signed in because it shares the cookie jar.
 */
public final class SignInSheet {

    public interface Host {
        void onSignedIn();

        void onDismissed();

        /** The user asked for the native account picker for this provider. */
        void onPickAccount(boolean google);
    }

    /**
     * Arena's home page. There is no /signin endpoint: the sign-in is a dialog on the
     * home page, so the sheet starts there and {@link SignInDriver} opens the dialog and
     * presses the right provider button. (An invented "?signin=1" query did nothing, so a
     * provider tap only ever showed the marketing page.)
     */
    private static final String ARENA_SIGN_IN = "https://arena.ai/";


    private final Activity activity;
    private final ViewGroup parent;
    private final Host host;
    private FrameLayout root;
    private WebView web;
    private LinearLayout chooser;
    private String pendingEmail;
    private String pendingProvider = "Google";
    private final android.os.Handler ui = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private boolean started;
    private TextView progressText;

    public SignInSheet(Activity activity, ViewGroup parent, Host host) {
        this.activity = activity;
        this.parent = parent;
        this.host = host;
    }

    public boolean isShowing() {
        return root != null && root.getParent() != null;
    }

    /**
     * Presentation of a window the page itself opened (window.open / target=_blank).
     * The caller creates the WebView, hands it the page's transport, and we just host it.
     */
    public void showProviderView(WebView providerView) {
        if (isShowing()) return;
        root = AppUi.sheetScrim(activity, v -> dismiss());
        root.setOnClickListener(v -> dismiss());

        final LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(AppUi.color(activity, R.color.bg));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(AppUi.color(activity, R.color.surface));
        header.setPadding(AppUi.dp(activity, 14), AppUi.dp(activity, 10),
                AppUi.dp(activity, 10), AppUi.dp(activity, 10));
        header.addView(AppUi.text(activity, activity.getString(R.string.signin_in_progress),
                15f, R.color.on_bg, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = AppUi.text(activity, "✕", 18f, R.color.on_bg_muted, false);
        close.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 6),
                AppUi.dp(activity, 12), AppUi.dp(activity, 6));
        close.setOnClickListener(v -> dismiss());
        header.addView(close);
        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = providerView;
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (pendingEmail != null && url != null && url.contains("accounts.google")) {
                    // Pre-fill the account chooser with the account the user just picked,
                    // so the provider page does not ask a second time.
                    view.evaluateJavascript("(function(){try{"
                            + "var i=document.querySelector('input[type=email]');"
                            + "if(i&&!i.value){i.value=\'" + jsEscape(pendingEmail) + "\';"
                            + "i.dispatchEvent(new Event('input',{bubbles:true}));}}catch(e){}})();",
                            null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleProviderUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleProviderUrl(Uri.parse(url));
            }
        });
        column.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column.animate().alpha(1f).setDuration(AppUi.ANIM_FAST).start();
    }

    /** True once the provider page is on screen. */
    public boolean isInProviderPage() {
        return web != null && web.getVisibility() == View.VISIBLE;
    }

    public void show() {
        showForEmail(null);
    }

    /**
     * Opens the provider page for a chosen account.
     *
     * @param email Google account picked in the native chooser, or null to start a
     *              fresh provider session ("Use another account")
     */
    public void showForEmail(String email) {
        pendingEmail = email;
        if (isShowing()) return;

        root = AppUi.sheetScrim(activity, v -> dismiss());
        root.setOnClickListener(v -> dismiss());

        final LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(AppUi.dp(activity, 20), AppUi.dp(activity, 10),
                AppUi.dp(activity, 20), AppUi.dp(activity, 20));
        sheet.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface), 26f, activity));
        sheet.setOnClickListener(v -> {
        });

        View handle = new View(activity);
        handle.setBackground(AppUi.rounded(AppUi.color(activity, R.color.handle), 3f, activity));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                AppUi.dp(activity, 38), AppUi.dp(activity, 4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = AppUi.dp(activity, 16);
        sheet.addView(handle, handleLp);

        TextView title = AppUi.text(activity, activity.getString(R.string.signin_choose),
                19f, R.color.on_bg, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        sheet.addView(title);

        TextView sub = AppUi.text(activity, activity.getString(R.string.signin_choose_sub),
                13f, R.color.on_bg_muted, false);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = AppUi.dp(activity, 6);
        subLp.bottomMargin = AppUi.dp(activity, 18);
        sheet.addView(sub, subLp);

        // The identity saved on this device, so the picker opens knowing who you are
        // instead of asking again every time. Everything here is local (arena_profile).
        if (LocalProfile.hasAccount(activity)) {
            LinearLayout who = new LinearLayout(activity);
            who.setOrientation(LinearLayout.HORIZONTAL);
            who.setGravity(Gravity.CENTER_VERTICAL);
            who.setPadding(AppUi.dp(activity, 14), AppUi.dp(activity, 10),
                    AppUi.dp(activity, 14), AppUi.dp(activity, 10));
            who.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface2), 16f,
                    activity));
            LinearLayout.LayoutParams whoLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            whoLp.bottomMargin = AppUi.dp(activity, 12);
            who.setLayoutParams(whoLp);

            LinearLayout whoTexts = new LinearLayout(activity);
            whoTexts.setOrientation(LinearLayout.VERTICAL);
            who.addView(whoTexts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            whoTexts.addView(AppUi.text(activity, LocalProfile.summary(activity), 13.5f,
                    R.color.on_bg, true));
            String stateText = LocalProfile.signedInAt(activity) > 0
                    ? activity.getString(R.string.profile_session_saved)
                    : activity.getString(R.string.profile_session_pending);
            whoTexts.addView(AppUi.text(activity, stateText, 11.5f,
                    R.color.on_bg_muted, false));

            TextView forget = AppUi.text(activity,
                    activity.getString(R.string.profile_forget), 12f,
                    R.color.on_bg_muted, false);
            forget.setPadding(AppUi.dp(activity, 10), AppUi.dp(activity, 6),
                    AppUi.dp(activity, 4), AppUi.dp(activity, 6));
            forget.setOnClickListener(v -> {
                LocalProfile.clearSession(activity);
                AppUi.press(v);
                if (root != null && root.getParent() != null) {
                    root.getParent().requestLayout();
                }
                dismiss();
            });
            who.addView(forget);
            sheet.addView(who);
        }

        chooser = new LinearLayout(activity);
        chooser.setOrientation(LinearLayout.VERTICAL);
        // Google: the native account picker comes first (MainActivity.startSignIn).
        // providerRow(icon, label, host, url, nativePicker). "false" = no native step:
        // GitHub has no account list on the device, so its own page opens directly.
        chooser.addView(providerRow(R.drawable.ic_google, R.string.signin_google,
                "Google", ARENA_SIGN_IN, true));
        // GitHub has no account list on the device, so its login page opens directly.
        chooser.addView(providerRow(R.drawable.ic_github, R.string.signin_github,
                "GitHub", ARENA_SIGN_IN, false));
        sheet.addView(chooser);

        TextView note = AppUi.text(activity, activity.getString(R.string.signin_note), 11.5f,
                R.color.on_bg_muted, false);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = AppUi.dp(activity, 16);
        sheet.addView(note, noteLp);

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        root.addView(sheet, sheetLp);
        parent.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        AppUi.slideUp(sheet);
    }

    private View providerRow(int iconRes, int labelRes, final String hostFilter,
                             final String startUrl, final boolean nativePicker) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AppUi.dp(activity, 14), AppUi.dp(activity, 13),
                AppUi.dp(activity, 14), AppUi.dp(activity, 13));
        row.setBackground(AppUi.pressable(activity, AppUi.color(activity, R.color.surface2), 16f));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = AppUi.dp(activity, 10);
        row.setLayoutParams(rowLp);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        int p = AppUi.dp(activity, 9);
        icon.setPadding(p, p, p, p);
        icon.setBackground(AppUi.rounded(AppUi.color(activity, R.color.bg), 12f, activity));
        row.addView(icon, new LinearLayout.LayoutParams(
                AppUi.dp(activity, 40), AppUi.dp(activity, 40)));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = AppUi.dp(activity, 14);
        row.addView(texts, textLp);

        texts.addView(AppUi.text(activity, activity.getString(labelRes), 15f,
                R.color.on_bg, true));
        TextView hint = AppUi.text(activity, activity.getString(R.string.signin_tap_account),
                12f, R.color.on_bg_muted, false);
        hint.setPadding(0, AppUi.dp(activity, 2), 0, 0);
        texts.addView(hint);

        TextView chevron = AppUi.text(activity, "›", 22f, R.color.on_bg_muted, false);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            AppUi.press(v);
            if (nativePicker && host != null) {
                // Hand control to the activity's native chooser; it calls back into
                // showForEmail() once the user has picked an account.
                host.onPickAccount("Google".equals(hostFilter));
            } else {
                openProvider(hostFilter, startUrl);
            }
        });
        return row;
    }

    /** Escapes a string for the inside of a single-quoted JS literal (no quotes added). */
    private static String jsEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '\\': out.append("\\\\"); break;
                case '\'': out.append("\\'"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                default: out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * The WebView that hosts the provider page, so a window.open() from the page can be
     * handed the *same* renderer instead of a second one being created.
     */
    public WebView providerWebView() {
        return web;
    }

    /** Remembers the account once the provider page has completed a session. */
    public void rememberSignIn(String email, String provider) {
        LocalProfile.remember(activity, email, provider);
        LocalProfile.markSignedIn(activity);
    }

    /** Opens the provider's real login page inside the app, full-screen. */
    private void openProvider(String hostFilter, String startUrl) {
        pendingProvider = hostFilter;
        started = false;
        // Save the choice locally *before* the provider page runs: if the page is slow or
        // the user leaves, the next attempt already knows which account this is.
        LocalProfile.remember(activity, pendingEmail, hostFilter);
        // A real full-screen layer over the window (not a bottom sheet): provider login
        // pages set frame-ancestors restrictions and refuse to render in a cramped,
        // dimmed, click-capturing container, which is why "login just did not finish".
        dismissRoot();
        final LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(AppUi.color(activity, R.color.bg));
        column.setAlpha(0f);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(AppUi.color(activity, R.color.surface));
        header.setPadding(AppUi.dp(activity, 14), AppUi.dp(activity, 10),
                AppUi.dp(activity, 10), AppUi.dp(activity, 10));

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titles.addView(AppUi.text(activity, activity.getString(R.string.signin_in_progress),
                15f, R.color.on_bg, true));
        progressText = AppUi.text(activity,
                activity.getString(R.string.signin_progress_for, hostFilter), 11.5f,
                R.color.on_bg_muted, false);
        titles.addView(progressText);

        TextView close = AppUi.text(activity, "✕", 18f, R.color.on_bg_muted, false);
        close.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 6),
                AppUi.dp(activity, 12), AppUi.dp(activity, 6));
        close.setOnClickListener(v -> dismiss());
        header.addView(close);
        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(activity);
        web.setBackgroundColor(AppUi.color(activity, R.color.bg));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(AppChrome.userAgent(activity, s.getUserAgentString()));
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleProviderUrl(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleProviderUrl(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String webUrl) {
                super.onPageFinished(view, webUrl);
                if (webUrl == null || !webUrl.contains("arena.ai")) return;
                // Arena's own page is up: open its sign-in dialog, then press the
                // provider the user chose. Without this, tapping GitHub only loaded the
                // home page, because there is no "?signin=1" endpoint to link to.
                driveSignIn();
            }
        });
        web.setWebChromeClient(new AppChrome.Base(activity) {
        });

        column.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root = new FrameLayout(activity);
        root.setBackgroundColor(AppUi.color(activity, R.color.bg));
        root.setClickable(true);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.bringToFront();
        column.animate().alpha(1f).setDuration(AppUi.ANIM_FAST).start();
        chooser.setVisibility(View.GONE);
        // Drop any half-finished provider session so the page starts clean.
        CookieManager.getInstance().removeSessionCookies(null);
        web.loadUrl(startUrl);
        if (pendingEmail != null) {
            // Pre-select the account the user tapped in the native chooser, so Google
            // shows "Continue as ..." instead of the full account list again.
            web.postDelayed(() -> web.evaluateJavascript(
                    "try{localStorage.setItem('arena-account','" + pendingEmail + "');}"
                            + "catch(e){}", null), 400);
        }
    }

    /**
     * Opens Arena's sign-in dialog and presses the chosen provider.
     *
     * Both steps retry: arena renders the dialog a beat after the page settles, and a
     * click can be swallowed while a hydration pass is running. If the dialog never
     * appears the user is told to press arena's own "Log in" button, which is right in
     * front of them in this same WebView - never a dead end.
     */
    private void driveSignIn() {
        if (started || web == null) return;
        started = true;
        setProgress(activity.getString(R.string.signin_step_dialog));
        SignInDriver.step(RUNNER, SignInDriver.OPEN_DIALOG_JS, 6, 900, ui, first -> {
            if (web == null) return;
            if (SignInDriver.NOTHING.equals(first)) {
                // No "Log in" control matched: let the page be the page, and say so.
                setProgress(activity.getString(R.string.signin_step_manual,
                        pendingProvider));
                return;
            }
            setProgress(activity.getString(R.string.signin_step_provider, pendingProvider));
            SignInDriver.step(RUNNER, SignInDriver.chooseProviderScript(pendingProvider),
                    6, 900, ui, second -> {
                if (SignInDriver.NOTHING.equals(second)) {
                    setProgress(activity.getString(R.string.signin_step_manual,
                            pendingProvider));
                }
            });
        });
    }

    /** Runs JS in the provider WebView on the UI thread. */
    private final SignInDriver.Runner RUNNER = (js, host, callback) -> {
        final WebView target = web;
        if (target == null) return;
        ui.post(() -> {
            try {
                target.evaluateJavascript(js, callback);
            } catch (Exception ignored) {
                if (callback != null) callback.onReceiveValue("none");
            }
        });
    };

    private void setProgress(String text) {
        if (progressText != null) progressText.setText(text);
    }

    /**
     * Keeps the login pages inside this sheet, and closes it as soon as Google/GitHub
     * hands control back to arena.ai - that means the session cookie is set.
     */
    private boolean handleProviderUrl(Uri uri) {
        if (uri == null) return false;
        String urlHost = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();

        if (!scheme.startsWith("http")) {
            // A provider's app-link callback: ignore it instead of leaving the app, so
            // the sign-in page stays right where the user can see it.
            return true;
        }
        // Any navigation back to Arena is checked by asking the page directly: a URL can
        // return to arena.ai while still signed out (that is why "Signed in" used to pop
        // up with the Log in button still on screen). The session cookie is the evidence.
        if (urlHost.endsWith("arena.ai") || urlHost.endsWith("lmarena.ai")) {
            CookieManager.getInstance().flush();
            verifySession();
            return true;
        }
        return false; // provider pages, captchas, consent screens: stay inside
    }

    /**
     * After a redirect back to Arena, check that a session actually exists before
     * declaring success. Looks for a session cookie, then for the signed-in UI (an
     * account avatar or a Sign out entry) if cookies are not readable.
     */
    private void verifySession() {
        final String cookies = CookieManager.getInstance().getCookie("https://arena.ai");
        boolean hasCookie = cookies != null
                && (cookies.contains("session") || cookies.contains("auth")
                || cookies.contains("token") || cookies.contains("user"));
        if (hasCookie) {
            finishSuccessfully();
            return;
        }
        if (web == null) {
            finishSuccessfully();
            return;
        }
        web.evaluateJavascript("(function(){"
                + "var t=(document.body&&document.body.innerText)||'';"
                + "var signedOut=/log in|sign in|login/i.test(t);"
                + "var avatar=!!document.querySelector("
                + "'img[alt*=\"avatar\" i],[aria-label*=\"account\" i],"
                + "'+ 'button img[src*=\"avatar\"]');"
                + "return signedOut? 'out' : (avatar? 'in' : 'unknown');"
                + "})();", value -> {
            String result = value == null ? "" : value.replace("\"", "").trim();
            if ("out".equals(result)) {
                // Still signed out: keep the page (no false toast) and let the user retry.
                if (progressText != null) {
                    progressText.setText(activity.getString(R.string.signin_still_out));
                }
                if (web != null) web.loadUrl(ARENA_SIGN_IN);
                return;
            }
            finishSuccessfully();
        });
    }

    private void finishSuccessfully() {
        dismiss();
        if (host != null) host.onSignedIn();
    }

    /** Removes any existing sign-in layer without notifying the host. */
    private void dismissRoot() {
        if (root != null && root.getParent() != null) parent.removeView(root);
        if (web != null) {
            web.stopLoading();
            try {
                web.destroy();
            } catch (Exception ignored) {
            }
            web = null;
        }
        root = null;
    }

    public void dismiss() {
        if (root == null || root.getParent() == null) return;
        final View dying = root;
        root = null;
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        dying.animate().alpha(0f).setDuration(AppUi.ANIM_FAST).withEndAction(() -> {
            if (dying.getParent() != null) parent.removeView(dying);
            if (host != null) host.onDismissed();
        }).start();
    }
}
