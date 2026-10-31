package ai.arena.webapp;

import android.app.Activity;
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
     * Where the sheet starts: arena's home page, unchanged.
     *
     * There is no /signin endpoint - an invented "?signin=1" query did nothing. Where the
     * *control* is depends on the surface: the home page is the marketing page and, per
     * the on-device report, has no log-in control at all; arena's leaderboard page carries
     * a plain "Log In" entry. So the driver first tries the page it is on (with the
     * shadow-DOM search and the menu / "Get started" routes) and only then moves to
     * {@link #ARENA_LAST_RESORT}.
     */
    private static final String ARENA_SIGN_IN = "https://arena.ai/";

    /** Fallback page that renders a plain "Log In" control even when signed out. */
    private static final String ARENA_LAST_RESORT = "https://arena.ai/leaderboard";


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
    /** True once the driver moved to the fallback page; the dialog search restarts there. */
    private boolean onFallback;
    /** The account window open over the provider page, if any. */
    private WebView accountView;
    /**
     * The layer underneath an account window: the provider page stays alive (it is the
     * window's opener and may receive the result) but out of sight.
     */
    private View underRoot;
    /** How many looks the dialog search has taken on the current page. */
    private int dialogRounds;
    /** Look-at-the-page rounds allowed before moving to the fallback page. */
    private static final int MAX_DIALOG_ROUNDS = 10;

    public SignInSheet(Activity activity, ViewGroup parent, Host host) {
        this.activity = activity;
        this.parent = parent;
        this.host = host;
    }

    public boolean isShowing() {
        return root != null && root.getParent() != null;
    }

    /**
     * True while this sheet owns the screen for a sign-in - the chooser, a provider page
     * or an account window.
     *
     * The activity needs this: a window the page opens *while* a sign-in is running is
     * part of the provider flow (Google's chooser opens its own window in some builds),
     * and it must be hosted here instead of being loaded into the tab that is behind it.
     */
    public boolean isSignInActive() {
        return root != null && root.getParent() != null;
    }

    /**
     * Hosts a window the page itself opened (window.open / target=_blank).
     *
     * This is the path that decides whether Google's sign-in works at all: the page
     * creates a popup and hands it the provider URL, so the popup has to be attached and
     * laid out before it renders anything. Two things used to go wrong here - the popup
     * was dropped when a sheet was already showing, and a lazy popup (one that only
     * starts loading once it is attached) was hosted but never told to load.
     */
    public void openAccountWindow(WebView popup, boolean lazy) {
        accountView = popup;
        // Same policy as the provider page: stay inside the app, and finish as soon as
        // the session comes back to arena.
        popup.setWebViewClient(policyClient());
        popup.setWebChromeClient(new AppChrome.Base(activity) {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                return openAccountWindow(resultMsg);
            }
        });

        // The chooser has served its purpose: the provider's own window is the surface now.
        // The parent check matters: a chooser that was removed earlier still reports itself
        // visible, and treating that as "the chooser is up" would destroy the provider page
        // that opened this window.
        if (chooser != null && chooser.getParent() != null
                && chooser.getVisibility() == View.VISIBLE) {
            dismissRoot();
        }
        // A provider page may already own the screen. It becomes the layer beneath (it
        // opened this window and may still receive the result), so the window is a layer
        // of its own instead of a second column squeezed into the same frame.
        if (root != null && root.getParent() != null) {
            releaseUnderRoot();
            parent.removeView(root);
            underRoot = root;
        }
        root = new FrameLayout(activity);
        root.setBackgroundColor(AppUi.color(activity, R.color.bg));
        parent.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
                activity.getString(R.string.signin_progress_for, pendingProvider), 11.5f,
                R.color.on_bg_muted, false);
        titles.addView(progressText);
        TextView close = AppUi.text(activity, "✕", 18f, R.color.on_bg_muted, false);
        close.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 6),
                AppUi.dp(activity, 12), AppUi.dp(activity, 6));
        close.setOnClickListener(v -> dismiss());
        header.addView(close);
        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(popup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // If the provider window stays blank, the honest next step is one tap away.
        TextView viaBrowser = AppUi.text(activity,
                activity.getString(R.string.signin_via_browser), 12.5f, R.color.on_bg_muted,
                false);
        viaBrowser.setGravity(Gravity.CENTER);
        viaBrowser.setPadding(0, AppUi.dp(activity, 10), 0, AppUi.dp(activity, 10));
        viaBrowser.setOnClickListener(v -> {
            AppUi.press(v);
            openBrowserChooser(ARENA_SIGN_IN);
        });
        column.addView(viaBrowser);

        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column.animate().alpha(1f).setDuration(AppUi.ANIM_FAST).start();
        web = popup;

        // A lazy window starts loading when it is attached - which is now, so ask for
        // the document explicitly and treat a still-empty window the same way.
        if (lazy) {
            String pending = popup.getUrl();
            if (pending != null && !pending.isEmpty()) popup.loadUrl(pending);
        }
        popup.postDelayed(() -> {
            if (popup != web) return;
            String url = popup.getUrl();
            if (url == null || url.isEmpty() || "about:blank".equals(url)) {
                setProgress(activity.getString(R.string.signin_popup_blank));
            }
        }, 1400);
    }

    /**
     * Hosts the window a page opened, given the chrome event it arrived with.
     *
     * The transport is filled in here (that is the only way a WebView can render a popup)
     * and the caller decides afterwards whether the URL belongs in this sheet.
     */
    private boolean openAccountWindow(android.os.Message resultMsg) {
        if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
            return false;
        }
        WebView popup = new WebView(activity);
        popup.setBackgroundColor(AppUi.color(activity, R.color.bg));
        WebSettings s = popup.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(Settings.chromeUserAgent(activity)
                ? AppChrome.userAgent(activity, s.getUserAgentString())
                : AppChrome.defaultUserAgent(activity));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
        popup.setWebViewClient(policyClient());
        popup.setWebChromeClient(new AppChrome.Base(activity) {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message message) {
                return openAccountWindow(message);
            }
        });
        ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
        resultMsg.sendToTarget();
        // Nothing renders until the new window is in the hierarchy: attach it right away
        // and let the transport fill in whichever URL it was created for.
        openAccountWindow(popup, true);
        return true;
    }

    /** The keep-it-inside-the-app navigation policy, shared by every provider page. */
    private WebViewClient policyClient() {
        return new WebViewClient() {
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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (view == accountView) refreshAccountProgress(view, url);
                // A window the page opened can itself be an arena page - that is where the
                // provider button lives when arena renders the dialog as a popup. Drive
                // the same dialog search on it instead of leaving a static page on screen.
                if (view == web && url != null && url.contains("arena.ai")) {
                    started = false;
                    driveSignIn();
                }
                if (view == accountView && pendingEmail != null && url != null
                        && url.contains("accounts.google")) {
                    // Pre-fill the account chooser with the account the user just picked,
                    // so the provider page does not ask a second time.
                    view.evaluateJavascript("(function(){try{"
                            + "var i=document.querySelector('input[type=email]');"
                            + "if(i&&!i.value){i.value=\''" + jsEscape(pendingEmail) + "\';"
                            + "i.dispatchEvent(new Event('input',{bubbles:true}));}}catch(e){}})();",
                            null);
                }
            }
        };
    }

    /** Reports what the provider window is showing, instead of a frozen "Signing in". */
    private void refreshAccountProgress(WebView view, String url) {
        if (view == null || url == null) return;
        String pageHost = "";
        try {
            pageHost = Uri.parse(url).getHost() == null ? "" : Uri.parse(url).getHost();
        } catch (Exception ignored) {
        }
        String shown = activity.getString(R.string.signin_progress_for,
                pageHost.isEmpty() ? pendingProvider : pageHost);
        setProgress(shown);
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
        // instead of asking again every time. Everything here is local (agon_profile).
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

        // Google's OAuth policy can stop sign-in from completing inside an embedded WebView
        // (403: disallowed_useragent). The provider rows above still start in-app, because
        // GitHub's flow works there and Google's chooser works on many builds - this row is
        // the labelled fallback for when it does not. The label says where the session
        // ends up, because that is the whole difference.
        TextView viaBrowser = AppUi.text(activity,
                activity.getString(R.string.signin_browser_row), 13f,
                R.color.on_bg, true);
        viaBrowser.setGravity(Gravity.CENTER);
        viaBrowser.setPadding(0, AppUi.dp(activity, 12), 0, AppUi.dp(activity, 12));
        viaBrowser.setBackground(AppUi.pressable(activity,
                AppUi.color(activity, R.color.surface2), 14f));
        viaBrowser.setOnClickListener(v -> {
            AppUi.press(v);
            openBrowserChooser(ARENA_SIGN_IN);
        });
        sheet.addView(viaBrowser);

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
        // Provider sign-in opens the account chooser with window.open(); dropping popups
        // here is what made "Continue with Google" appear to do nothing.
        s.setSupportMultipleWindows(true);
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
                if (started) return;
                // Arena's own page is up: open its sign-in dialog, then press the
                // provider the user chose. Without this, tapping GitHub only loaded the
                // home page, because there is no "?signin=1" endpoint to link to.
                driveSignIn();
            }
        });
        // The popup the page opens *is* Google's account chooser. WebView cannot build a
        // window on its own: without onCreateWindow it drops the popup silently, and that
        // is precisely what "Continue with Google does nothing" looked like.
        web.setWebChromeClient(new AppChrome.Base(activity) {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                return openAccountWindow(resultMsg);
            }
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
        if (chooser != null) chooser.setVisibility(View.GONE);
        root.bringToFront();
        // Drop any half-finished provider session so the page starts clean.
        CookieManager.getInstance().removeSessionCookies(null);
        // preconnect() is not a load: it warms the socket while the first frames are
        // still being laid out, which is most of the wait when the page has not been
        // visited in this WebView before.
        web.preconnect(startUrl);
        web.loadUrl(startUrl);
    }

    /**
     * Explicit "open it in a browser" route, for the cases Google will simply not serve
     * to an embedded view.
     *
     * Two engines, so no phone is left without one: a Custom Tab when a browser supports
     * them, otherwise a plain VIEW intent with the AppChooser. Anything else (no browser
     * at all) is reported, never silently swallowed - and this is only ever reached from
     * a row that says what it does, never from an ordinary link.
     */
    void openBrowserChooser(String url) {
        try {
            android.content.Intent view = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, Uri.parse(url));
            view.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(view);
            dismiss();
        } catch (Exception noBrowser) {
            try {
                openInBrowser(url);
            } catch (Exception error) {
                android.widget.Toast.makeText(activity, R.string.signin_no_browser,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * The one route that can finish Google's OAuth: a Custom Tab.
     *
     * Chrome is a real browser, so Google allows the flow there - but the session cookie
     * then belongs to Chrome, not to this app's WebView, and there is no public API to
     * copy it across. So this is offered as a deliberate exception (the user is told what
     * it does), not as the default, and the app never silently hands off an ordinary link.
     */
    void openInBrowser(String url) {
        try {
            androidx.browser.customtabs.CustomTabsIntent.Builder builder =
                    new androidx.browser.customtabs.CustomTabsIntent.Builder();
            builder.setShowTitle(true);
            builder.setShareState(
                    androidx.browser.customtabs.CustomTabsIntent.SHARE_STATE_OFF);
            builder.setUrlBarHidingEnabled(true);
            // Keep the app's colours so it does not look like a foreign app.
            int bg = AppUi.color(activity, R.color.bg);
            int surface = AppUi.color(activity, R.color.surface);
            builder.setDefaultColorSchemeParams(
                    new androidx.browser.customtabs.CustomTabColorSchemeParams.Builder()
                            .setToolbarColor(surface)
                            .setNavigationBarColor(bg)
                            .setSecondaryToolbarColor(surface)
                            .build());
            builder.setColorScheme(
                    androidx.browser.customtabs.CustomTabsIntent.COLOR_SCHEME_SYSTEM);
            builder.build().launchUrl(activity, android.net.Uri.parse(url));
            dismiss();
        } catch (Exception error) {
            // No Custom Tabs provider on the device: say so instead of doing nothing.
            android.widget.Toast.makeText(activity, R.string.signin_no_browser,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Opens Arena's sign-in dialog and presses the chosen provider.
     *
     * The chain has to be patient, because the control is not where the first build
     * assumed: the home page has no log-in control at all (on-device report: 25 controls,
     * 0 mentioning log in, Google or GitHub), the chat surface hides it behind an
     * account/menu button, and the leaderboard page renders a plain "Log In". So a step
     * that answers "retry" - it pressed a menu or "Get started" - is followed by another
     * look, and a page that offers nothing is replaced by {@link #ARENA_LAST_RESORT}.
     * Every ending that is not a dialog is stated in the header instead of leaving the
     * user in front of a silent page.
     */
    private void driveSignIn() {
        if (web == null) return;
        started = true;
        dialogRounds = 0;
        setProgress(activity.getString(R.string.signin_step_dialog));
        runDialogRound();
    }

    /** One look for the log-in control; keeps going on "retry", retreats on "none". */
    private void runDialogRound() {
        if (web == null) return;
        if (dialogRounds++ > MAX_DIALOG_ROUNDS) {
            fallBackToLastResort();
            return;
        }
        SignInDriver.step(RUNNER, SignInDriver.OPEN_DIALOG_JS, 3, 900, ui, first -> {
            if (web == null) return;
            if (SignInDriver.CLICKED.equals(first) || SignInDriver.ALREADY.equals(first)) {
                chooseProvider();
                return;
            }
            if (SignInDriver.RETRY.equals(first)) {
                // A menu opened (or the landing page's CTA was followed): what it revealed
                // needs a moment to render before it can be looked at.
                setProgress(activity.getString(R.string.signin_step_wait));
                ui.postDelayed(this::runDialogRound, 1500);
                return;
            }
            fallBackToLastResort();
        });
    }

    /** Presses the provider button the user chose. */
    private void chooseProvider() {
        setProgress(activity.getString(R.string.signin_step_provider, pendingProvider));
        SignInDriver.step(RUNNER, SignInDriver.chooseProviderScript(pendingProvider),
                5, 900, ui, second -> {
                    if (SignInDriver.NOTHING.equals(second) || second.startsWith("error")) {
                        setProgress(activity.getString(R.string.signin_step_manual,
                                pendingProvider));
                    }
                });
    }

    /**
     * Moves to the page that does have a Log in control.
     *
     * Only once: if the fallback also offers nothing, the header says to press the button
     * by hand rather than loading pages in a loop.
     */
    private void fallBackToLastResort() {
        if (web == null) return;
        if (onFallback) {
            setProgress(activity.getString(R.string.signin_step_manual, pendingProvider));
            return;
        }
        onFallback = true;
        started = false;
        setProgress(activity.getString(R.string.signin_step_moving));
        web.loadUrl(ARENA_LAST_RESORT);
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
        // Only a navigation that actually belongs to this sign-in is handled - the shared
        // policy object is also installed on popups, and a popup that closed must not be
        // able to finish or cancel the sign-in that is still running.
        if (root == null || (web == null && accountView == null)) return false;
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
     * declaring success.
     *
     * The cookie is the evidence when it can be read. When it cannot (arena's session may
     * live in a cookie that this app's cookie jar does not expose - storage partitioning
     * and third-party rules changed across Android versions), the same-origin proof is
     * used instead: ask arena itself whether this client is signed in and read the answer.
     */
    private void verifySession() {
        final String cookies = CookieManager.getInstance().getCookie("https://arena.ai");
        boolean hasCookie = hasSessionCookie(cookies);
        if (hasCookie) {
            finishSuccessfully();
            return;
        }
        probeSession();
    }

    /**
     * Asks arena's own endpoints whether the current client holds a session.
     *
     * Only an endpoint that answers "here is your user" counts; a 404 means the product
     * does not have that route, which is a next-candidate answer, not a failure. The
     * request runs off the main thread - a WebView callback is on the UI thread, and a
     * network call there throws NetworkOnMainThreadException instead of answering - and
     * the verdict is handed back to the UI thread.
     */
    private void probeSession() {
        final String[] candidates = {
                "https://arena.ai/api/auth/session",
                "https://arena.ai/api/auth/user"
        };
        Thread worker = new Thread(() -> {
            final String verdict = probeBlocking(candidates);
            ui.post(() -> {
                if ("in".equals(verdict)) {
                    finishSuccessfully();
                } else {
                    finishWithPageCheck();
                }
            });
        }, "arena-session-probe");
        worker.setDaemon(true);
        worker.start();
    }

    /** Blocking part of the probe: "in" when an endpoint describes a user, else "no". */
    private static String probeBlocking(String[] candidates) {
        for (String url : candidates) {
            try {
                java.net.HttpURLConnection connection =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setInstanceFollowRedirects(true);
                int code = connection.getResponseCode();
                java.io.InputStream stream = code >= 400
                        ? connection.getErrorStream() : connection.getInputStream();
                String body = stream == null ? "" : readAll(stream);
                connection.disconnect();
                String lower = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
                boolean userShape = lower.contains("\"user\"") || lower.contains("\"email\"")
                        || lower.contains("\"session\"") || lower.contains("\"id\":");
                boolean emptyShape = lower.isEmpty() || lower.equals("{}") || lower.equals("null")
                        || lower.contains("\"user\":null");
                if (code < 400 && userShape && !emptyShape) return "in";
            } catch (Exception ignored) {
                // No such route / no network: the next candidate, then the page.
            }
        }
        return "no";
    }

    private static String readAll(java.io.InputStream stream) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > 8192) break;
            }
            stream.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception error) {
            return "";
        }
    }

    /**
     * Last evidence available: does the page still offer a sign-in control?
     *
     * "No visible sign-in control" is the signed-in state of every arena surface that has
     * one, and the sheet is only here because a provider round-trip just came back to
     * arena. A page that positively still offers a sign-in stays open with that stated (no
     * false success), and a window with nothing to read ends the sign-in instead of
     * leaving an empty one on screen forever.
     */
    private void finishWithPageCheck() {
        if (web == null || web.getUrl() == null) {
            finishSuccessfully();
            return;
        }
        web.evaluateJavascript("(function(){try{"
                + "function seen(el){if(!el)return false;"
                + "try{if(el.closest&&el.closest('[hidden],[aria-hidden=true],"
                + "[style*=\"display: none\"],[style*=\"display:none\"]'))return false;}"
                + "catch(e){}"
                + "var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "function roots(){var out=[document];"
                + "var all=document.querySelectorAll('*');"
                + "for(var i=0;i<all.length&&i<2000;i++){"
                + "if(all[i].shadowRoot)out.push(all[i].shadowRoot);}"
                + "return out;}"
                + "function find(sel){var r=roots();"
                + "for(var i=0;i<r.length;i++){var l=r[i].querySelectorAll(sel);"
                + "for(var j=0;j<l.length;j++){if(seen(l[j]))return l[j];}}"
                + "return null;}"
                // An avatar / account button on screen is the signed-in UI.
                + "var avatar=find('img[alt*=\"avatar\" i],"
                + "[aria-label*=\"account\" i],[aria-label*=\"profile\" i]');"
                + "if(avatar)return 'in';"
                // A visible control that offers a sign-in means signed out.
                + "var all=document.querySelectorAll('button,a,[role=\"button\"],[aria-label]');"
                + "for(var i=0;i<all.length;i++){var el=all[i];"
                + "var t=((el.innerText||el.textContent||'')+' '"
                + "+(el.getAttribute('aria-label')||'')).toLowerCase();"
                + "if(t.length<40&&/log ?in|sign ?in/.test(t)&&seen(el))return 'out';}"
                + "return 'unknown';"
                + "}catch(e){return 'unknown';}})();", value -> {
            String result = value == null ? "" : value.replace("\"", "").trim();
            if ("in".equals(result)) {
                finishSuccessfully();
                return;
            }
            // Signed out, or the page does not say: keep it on screen and say what is
            // missing. Closing the sheet here would claim a sign-in that did not happen.
            if ("out".equals(result)) {
                // Positively signed out: say so and keep the page, so one more tap is all
                // that is missing.
                if (progressText != null) {
                    progressText.setText(activity.getString(R.string.signin_still_out));
                }
                return;
            }
            // The page shows no sign-in control of its own: the round-trip is done.
            finishSuccessfully();
        });
    }

    /**
     * True when the cookie jar holds one of the session cookies a site like arena.ai sets.
     *
     * Detecting "signed in" from the DOM ("is there still a Log in button?") is a guess
     * that breaks on every redesign. The cookie is the evidence: this covers Supabase SSR
     * (sb-<ref>-auth-token, sometimes chunked as .0/.1), Auth.js v5
     * (authjs.session-token, __Secure-authjs.session-token), NextAuth v4
     * (next-auth.session-token), Clerk (__session) and a plain session/auth cookie.
     * CookieManager.getCookie() includes HttpOnly cookies, unlike document.cookie.
     */
    static boolean hasSessionCookie(String cookies) {
        if (cookies == null || cookies.isEmpty()) return false;
        String all = cookies.toLowerCase(java.util.Locale.ROOT);
        String[] markers = {"sb-", "-auth-token", "authjs.session-token",
                "next-auth.session-token", "__session", "session", "sessionid",
                "auth_token", "access_token", "connect.sid"};
        for (String marker : markers) {
            if (all.contains(marker)) return true;
        }
        return false;
    }

    private void finishSuccessfully() {
        dismiss();
        if (host != null) host.onSignedIn();
    }

    /** Removes any existing sign-in layer without notifying the host. */
    private void dismissRoot() {
        final View dying = root;
        root = null;
        if (dying != null && dying.getParent() != null) parent.removeView(dying);
        destroyWebViewsIn(dying);
        releaseUnderRoot();
        chooser = null;
        web = null;
        accountView = null;
    }

    /** Destroys the layer that was kept beneath an account window. */
    private void releaseUnderRoot() {
        if (underRoot == null) return;
        if (underRoot.getParent() != null) parent.removeView(underRoot);
        destroyWebViewsIn(underRoot);
        underRoot = null;
    }

    /**
     * Destroys every WebView under a view that is going away.
     *
     * The sheet can hold up to three at once (the provider page, the account window the
     * page opened, and the chooser). Destroying only the newest one left the rest attached
     * to a removed view: no crash, but the sign-in pages kept running behind the app.
     */
    private static void destroyWebViewsIn(View view) {
        if (view == null) return;
        if (view instanceof WebView) {
            WebView webView = (WebView) view;
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Exception ignored) {
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                destroyWebViewsIn(group.getChildAt(i));
            }
        }
    }

    public void dismiss() {
        if (root == null || root.getParent() == null) return;
        final View dying = root;
        root = null;
        // Every WebView in the layer is destroyed - the provider page, the account window
        // and anything the page opened inside them - so nothing keeps loading behind a
        // window the user just closed.
        destroyWebViewsIn(dying);
        releaseUnderRoot();
        web = null;
        accountView = null;
        dying.animate().alpha(0f).setDuration(AppUi.ANIM_FAST).withEndAction(() -> {
            if (dying.getParent() != null) parent.removeView(dying);
            if (host != null) host.onDismissed();
        }).start();
    }
}
