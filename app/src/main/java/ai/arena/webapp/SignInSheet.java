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
    /**
     * True once the provider's own page (accounts.google.com) is what the user is looking
     * at. From then on the sign-in is finished by hand on that page, and the sheet must
     * say so instead of searching for an Arena dialog that cannot be there.
     */
    private boolean providerPageShown;
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
                activity.getString(R.string.signin_browser_row), 12.5f, R.color.on_bg_muted,
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
                if (view == web && url != null && url.contains("arena.ai") && !started) {
                    // Only a page that arrived before any sign-in started needs the dialog
                    // opened. Clearing the flag here on a page that *is* the return leg
                    // would restart the search and skip the verification below.
                    driveSignIn();
                }
                // The round-trip is back on Arena: ask this page whether it is signed in.
                // Only once the signing-in has started - a plain page load is not a
                // sign-in and must never be reported as one.
                if ((view == web || view == accountView) && started
                        && url != null && url.contains("arena.ai")) {
                    // SignInSheet.this, not this: inside the anonymous client `this` is the
                    // client, and a bare this::verifySession does not compile.
                    ui.postDelayed(SignInSheet.this::verifySession, 1200);
                }
                // A page that positively offers a sign-in means there is no session, even
                // once signing in has finished: clearing the local "signed in" mark here is
                // what stops the menu claiming an account the page says is signed out.
                if (!started && (view == web || view == accountView)) {
                    ui.postDelayed(SignInSheet.this::clearLocalMarkIfSignedOut, 1500);
                }
            }
        };
    }

    /**
     * Says so in the header when the page on screen is Google refusing to sign in here.
     *
     * Google answers an embedded WebView it distrusts with "This browser or app may not be
     * secure" (or a disallowed_useragent error), and no script in the app can get past it.
     * The header says so, and the browser row underneath is the way through.
     */
    private void reportIfProviderRefused(WebView view) {
        if (view == null) return;
        // The script is the test: the words Google uses when it will not sign in here are
        // "This browser or app may not be secure" and the disallowed_useragent error.
        final String js = "(function(){try{var t=(document.body&&document.body.innerText)"
                + "||'';return /may not be secure|disallowed_useragent/i.test(t)?'1':'0';}"
                + "catch(e){return '0';}})();";
        try {
            view.evaluateJavascript(js, value -> {
                if (value != null && value.contains("1") && progressText != null) {
                    progressText.setText(activity.getString(
                            R.string.signin_provider_blocked, pendingProvider));
                }
            });
        } catch (Exception ignored) {
            // A page that cannot be read is left with the neutral wording.
        }
    }

    /**
     * True for a page that belongs to Google's identity flow rather than to Arena.
     *
     * arena.ai opens accounts.google.com for the account step, and nothing on that host is
     * an Arena control: no Log in button, no provider buttons, no dialog.
     */
    static boolean isProviderPage(String url) {
        if (url == null) return false;
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception notAUrl) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        // accounts.google.com, and the country hosts Google actually serves
        // (accounts.google.co.in on the phone this was reported from).
        return host.startsWith("accounts.google.");
    }

    /** Reports what the provider window is showing, instead of a frozen "Signing in". */
    private void refreshAccountProgress(WebView view, String url) {
        if (view == null || url == null) return;
        // Google's page is not "opening its sign-in page" - it *is* the sign-in page. The
        // header said the former while an email box sat on screen, which reads like the
        // app is stuck.
        if (isProviderPage(url)) {
            providerPageShown = true;
            started = true;
            setProgress(activity.getString(R.string.signin_provider_open, pendingProvider));
            return;
        }
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

        // Once this device has signed in before, say so: the next attempt shows Google's
        // account list instead of an address form, and that is the fast path worth knowing
        // about.
        final String subText = activity.getString(LocalProfile.signedInAt(activity) > 0
                ? R.string.signin_choose_sub_returning
                : R.string.signin_choose_sub);
        TextView sub = AppUi.text(activity, subText, 13f, R.color.on_bg_muted, false);
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
        // One provider, because Arena only offers one: Google. GitHub is not an Arena
        // sign-in here - it is the account that connects the Agent Mode connectors and
        // authorises app updates - so offering it as a second way into Arena's session
        // was simply wrong. The account this device already used stays listed above.
        chooser.addView(providerRow(R.drawable.ic_google, R.string.signin_google,
                "Google", ARENA_SIGN_IN, R.string.signin_google_hint));
        sheet.addView(chooser);

        // Google's OAuth policy can stop sign-in from completing inside an embedded WebView
        // (403: disallowed_useragent). The row above still starts in-app, because Google's
        // chooser works on many builds - this row is the labelled fallback for when it does
        // not, and the app then finishes the sign-in from the ticket the browser brings
        // back (takeBrowserHandoff), so the session still ends up in the app.
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
                             final String startUrl, int hintRes) {
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
        TextView hint = AppUi.text(activity, activity.getString(hintRes),
                12f, R.color.on_bg_muted, false);
        hint.setPadding(0, AppUi.dp(activity, 2), 0, 0);
        texts.addView(hint);

        TextView chevron = AppUi.text(activity, "›", 22f, R.color.on_bg_muted, false);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            AppUi.press(v);
            // Straight into the provider page: Arena's own dialog lists the account, so
            // there is no second chooser in front of it.
            openProvider(hostFilter, startUrl);
        });
        return row;
    }


    /** Opens the provider's real login page inside the app, full-screen. */
    private void openProvider(String hostFilter, String startUrl) {
        pendingProvider = hostFilter;
        started = false;
        // A new attempt starts on Arena's page, so the "Google is asking for the account"
        // state from the previous one must not survive it.
        providerPageShown = false;
        onFallback = false;
        dialogRounds = 0;
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
                if (webUrl == null) return;
                if (isProviderPage(webUrl)) {
                    providerPageShown = true;
                    started = true;
                    setProgress(activity.getString(R.string.signin_provider_open,
                            pendingProvider));
                    reportIfProviderRefused(view);
                    return;
                }
                if (!webUrl.contains("arena.ai")) return;
                if (started) return;
                // Arena's own page is up: open its sign-in dialog, then press the
                // provider the user chose. Without this the sheet only ever loaded the
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

        // The way out of a Google page that refuses to finish here. Google sometimes stops
        // an embedded WebView outright ("This browser or app may not be secure"), and that
        // is not something the app can argue with - but it must be one visible tap away
        // instead of a page the user cannot leave.
        TextView viaBrowser = AppUi.text(activity,
                activity.getString(R.string.signin_browser_row), 12.5f, R.color.on_bg_muted,
                false);
        viaBrowser.setGravity(Gravity.CENTER);
        viaBrowser.setPadding(0, AppUi.dp(activity, 10), 0, AppUi.dp(activity, 10));
        viaBrowser.setOnClickListener(v -> {
            AppUi.press(v);
            openBrowserChooser(ARENA_SIGN_IN);
        });
        column.addView(viaBrowser, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        // Deliberately *not* wiping the cookie jar here. A half-finished Google page is
        // rare, and clearing session cookies threw away the one good thing on this
        // device: a Google session already established for this app. With it intact,
        // accounts.google.com renders its "choose an account" list and the sign-in is that
        // one tap; without it, every attempt starts by asking for the address again.
        web.loadUrl(startUrl);
    }

    /**
     * Sends the sign-in to Arena's site in a real browser, and handles the ticket that
     * comes back.
     *
     * Why this is the reliable route: Google refuses an embedded WebView
     * (disallowed_useragent), and a session created in Chrome belongs to Chrome - the app
     * cannot read Chrome's cookies. So "signed in in Chrome, still signed out in Agon" is
     * exactly what a browser-first sign-in leaves behind, however the user got there.
     *
     * What actually closes that gap is Arena's handoff, the same one its own "open in the
     * app" links use: signing in is supposed to send the browser back to
     * /auth/handoff?ticket=..., and Agon treats a link that returns with a ticket as
     * something only this app can finish - it opens the ticket in its own WebView, which
     * turns it into an ordinary session cookie inside Agon. So the browser is opened on
     * that path with the ticket passed through, and when the user comes back the ticket
     * arrives here via {@link #handleProviderUrl}.
     *
     * A phone with no browser is told so, never left with a silent tap.
     */
    void openBrowserChooser(String url) {
        try {
            android.content.Intent view = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, Uri.parse(url));
            view.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(view);
            // The sheet stays: the user comes back to it with the ticket, and it is what
            // finishes the sign-in.
            if (progressText != null) {
                progressText.setText(activity.getString(R.string.signin_browser_open));
            }
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
     * The same route through a Custom Tab, when no plain browser handles the intent.
     *
     * Keeps the app's colours so it does not look like a foreign app, and keeps the sheet
     * underneath for the returning ticket.
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
            if (progressText != null) {
                progressText.setText(activity.getString(R.string.signin_browser_open));
            }
        } catch (Exception error) {
            // No Custom Tabs provider on the device: say so instead of doing nothing.
            android.widget.Toast.makeText(activity, R.string.signin_no_browser,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Finishes a sign-in that started in a browser.
     *
     * A ticket from Arena's handoff is proof of a sign-in that already happened: loading
     * it in the provider WebView makes Arena set its own session cookie here, and the
     * normal "@link #verifySession} then confirms it. Without a ticket (an older build, or
     * a deep link with only a code) the URL is simply loaded and the page is checked.
     */
    public void takeBrowserHandoff(Uri uri) {
        finishBrowserHandoff(uri);
    }

    private void finishBrowserHandoff(Uri uri) {
        if (uri == null) return;
        if (!isShowing()) {
            // The sheet is gone (the activity was recreated while the browser was on top):
            // open the provider layer so there is somewhere for the session to land.
            show();
            if (!isShowing()) {
                // The chooser is up instead of a page: open the provider page directly.
                openProvider(pendingProvider, ARENA_SIGN_IN);
            }
        }
        if (web == null) {
            openProvider(pendingProvider, ARENA_SIGN_IN);
            return;
        }
        if (progressText != null) {
            progressText.setText(activity.getString(R.string.signin_browser_returned));
        }
        // A ticket only exists because a sign-in happened in the browser, so every page
        // this load produces is part of that sign-in - without this flag onPageFinished
        // never verified it and the sheet kept saying "signed out" after a good handoff.
        started = true;
        providerPageShown = false;
        web.loadUrl(uri.toString());
        // The page sets the cookie while it renders; verify once it has had a moment.
        ui.postDelayed(this::verifySession, 2500);
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
        if (providerPageShown || isProviderPage(web.getUrl())) {
            // Google's page owns the sign-in now. Searching it for Arena's dialog is what
            // burned every round and ended on the fallback page.
            providerPageShown = true;
            started = true;
            setProgress(activity.getString(R.string.signin_provider_open, pendingProvider));
            return;
        }
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
            if (SignInDriver.ON_PROVIDER.equals(first)) {
                // Google's own page is on screen: Arena's dialog is not on this host, so
                // searching it again only ever ended in the fallback, which is what left
                // the phone on "accounts.google.com is opening its own sign-in page".
                started = true;
                providerPageShown = true;
                setProgress(activity.getString(R.string.signin_provider_open,
                        pendingProvider));
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
     * Keeps the login pages inside this sheet, and closes it as soon as Google hands
     * control back to arena.ai - that means the session cookie is set.
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
        // Back on Arena's own host: this is where the round-trip ends, and where it is
        // decided. Two cases must not be confused -
        //  - the arena page the user is *looking at* is never blocked (returning true here
        //    used to cancel the load and leave a blank window, which is what a sign-in that
        //    "did nothing" looked like);
        //  - the check runs from onPageFinished instead, against the page that actually
        //    rendered, so the verdict is based on real markup.
        if (urlHost.endsWith("arena.ai") || urlHost.endsWith("lmarena.ai")) {
            CookieManager.getInstance().flush();
            return false;
        }
        return false; // provider pages, captchas, consent screens: stay inside
    }

    /**
     * Asks the page whether this WebView is signed in, instead of guessing from cookies.
     *
     * CookieManager cannot read HttpOnly cookies, and every modern session cookie is
     * HttpOnly: the jar reported 3094 chars on a phone that had just signed in through
     * Chrome and still said "session cookie: absent". So the cookie is a bonus check, and
     * the real evidence is one of two things the page itself states:
     *
     *  1. a session cookie that *is* readable (older builds, and non-HttpOnly cookies);
     *  2. the page offers no way to sign in - every surface has a "Log in" control while
     *     signed out, so its absence, plus a document that actually rendered, is a
     *     signed-in page.
     *
     * A page that positively still offers a sign-in stays open and says so, and a page
     * that says nothing either way is not called a success.
     */
    private void verifySession() {
        final String cookies = CookieManager.getInstance().getCookie("https://arena.ai");
        if (hasSessionCookie(cookies)) {
            finishSuccessfully();
            return;
        }
        finishWithPageCheck();
    }

    /**
     * The page is the evidence.
     *
     * Runs on whichever WebView finished the round-trip (the provider page or the account
     * window the page opened), and reports the document it read in the header so the
     * answer is visible rather than implied.
     */
    private void finishWithPageCheck() {
        final WebView target = web;
        if (target == null || target.getUrl() == null) {
            finishSuccessfully();
            return;
        }
        target.evaluateJavascript(SignInDriver.SIGNED_OUT_JS, value -> {
            String result = SignInDriver.clean(value);
            if ("out".equals(result)) {
                // Positively signed out: say so and keep the page, so one more tap is all
                // that is missing.
                if (progressText != null) {
                    progressText.setText(activity.getString(R.string.signin_still_out));
                }
                return;
            }
            // "in", or nothing to read on a page that rendered: the round-trip is done.
            finishSuccessfully();
        });
    }

    /**
     * Keeps the local profile honest: a page that still offers a sign-in is signed out.
     *
     * The local record is what the menu and the sheet show, so it must not outlive the
     * session it describes. Only an explicit "signed out" clears it - a page that says
     * nothing is left alone, so a slow page can never erase a real session.
     */
    private void clearLocalMarkIfSignedOut() {
        final WebView target = web;
        if (target == null || target.getUrl() == null) return;
        target.evaluateJavascript(SignInDriver.SIGNED_OUT_JS, value ->
                {
                    if ("out".equals(SignInDriver.clean(value))) {
                        LocalProfile.clearSession(activity);
                    }
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
