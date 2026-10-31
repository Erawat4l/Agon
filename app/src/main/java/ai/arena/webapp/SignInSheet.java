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
    }

    private final Activity activity;
    private final ViewGroup parent;
    private final Host host;
    private FrameLayout root;
    private WebView web;
    private LinearLayout chooser;

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

        chooser = new LinearLayout(activity);
        chooser.setOrientation(LinearLayout.VERTICAL);
        chooser.addView(providerRow(R.drawable.ic_google, R.string.signin_google,
                "accounts.google.com", "https://arena.ai/"));
        chooser.addView(providerRow(R.drawable.ic_github, R.string.signin_github,
                "github.com", "https://arena.ai/"));
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
                             final String startUrl) {
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
            openProvider(hostFilter, startUrl);
        });
        return row;
    }

    /** Opens the provider's real login page inside the app, full-screen. */
    private void openProvider(String hostFilter, String startUrl) {
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
        titles.addView(AppUi.text(activity, hostFilter, 11.5f, R.color.on_bg_muted, false));

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
        });
        web.setWebChromeClient(new AppChrome.Base(activity) {
        });

        column.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column.animate().alpha(1f).setDuration(AppUi.ANIM_FAST).start();
        chooser.setVisibility(View.GONE);
        web.loadUrl(startUrl);
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
            AppChrome.openExternally(activity, uri);
            return true;
        }
        if (urlHost.endsWith("arena.ai") || urlHost.endsWith("lmarena.ai")) {
            // Back on Arena: the cookie jar now holds the session.
            CookieManager.getInstance().flush();
            dismiss();
            host.onSignedIn();
            return true;
        }
        return false; // provider pages, captchas, consent screens: stay inside
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
            host.onDismissed();
        }).start();
    }
}
