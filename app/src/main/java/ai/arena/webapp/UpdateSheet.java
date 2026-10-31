package ai.arena.webapp;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;

/**
 * Bottom-sheet style "App Update" screen: release notes, an explicit Download
 * button, an explicit Install button, and clear status. Nothing happens without a tap.
 */
public final class UpdateSheet {

    public interface Host {
        void closeUpdateSheet();

        void onUpdateDownloaded(File apk);

        void launchInstaller(File apk);
    }

    private final Activity activity;
    private final Host host;
    private final View overlay;

    private final TextView statusLine;
    private final TextView notesView;
    private final TextView versionLine;
    private final TextView primaryButton;
    private final TextView secondaryButton;
    private final ProgressBar progressBar;
    private final LinearLayout badgeRow;

    private File downloadedApk;

    public UpdateSheet(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.overlay = build();
        this.statusLine = overlay.findViewWithTag("status");
        this.notesView = overlay.findViewWithTag("notes");
        this.versionLine = overlay.findViewWithTag("version");
        this.primaryButton = overlay.findViewWithTag("primary");
        this.secondaryButton = overlay.findViewWithTag("secondary");
        this.progressBar = overlay.findViewWithTag("progress");
        this.badgeRow = overlay.findViewWithTag("badges");
        this.settingsBox = overlay.findViewWithTag("settings");
        refresh();
    }

    public View view() {
        return overlay;
    }

    // ------------------------------------------------------------ build

    private View build() {
        android.widget.FrameLayout root = AppUi.sheetScrim(activity,
                v -> host.closeUpdateSheet());
        root.setTag("sheet-root");

        // Scrollable, because the settings rows + release notes are taller than a phone
        // screen on their own: a clipped sheet hid the token row and the buttons.
        android.widget.ScrollView scroller = new android.widget.ScrollView(activity);
        scroller.setFillViewport(true);
        scroller.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        scroller.setTag("sheet-scroll");

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface), 26f, activity));
        sheet.setPadding(AppUi.dp(activity, 22), AppUi.dp(activity, 10),
                AppUi.dp(activity, 22), AppUi.dp(activity, 22));
        sheet.setOnClickListener(v -> { /* swallow taps */ });
        scroller.addView(sheet, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View handle = new View(activity);
        handle.setBackground(AppUi.rounded(AppUi.color(activity, R.color.handle), 3f, activity));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                AppUi.dp(activity, 40), AppUi.dp(activity, 5));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = AppUi.dp(activity, 16);
        sheet.addView(handle, handleLp);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.ImageView icon = new android.widget.ImageView(activity);
        icon.setImageResource(R.mipmap.ic_launcher);
        header.addView(icon, new LinearLayout.LayoutParams(AppUi.dp(activity, 42),
                AppUi.dp(activity, 42)));

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.leftMargin = AppUi.dp(activity, 14);
        header.addView(titles, titlesLp);

        TextView title = AppUi.text(activity, activity.getString(R.string.update_sheet_title),
                20f, R.color.on_bg, true);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titles.addView(title);

        TextView version = AppUi.text(activity, "", 13f, R.color.on_bg_muted, false);
        version.setTag("version");
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = AppUi.dp(activity, 3);
        titles.addView(version, vLp);

        TextView close = AppUi.text(activity, "✕", 20f, R.color.on_bg_muted, false);
        close.setPadding(AppUi.dp(activity, 10), AppUi.dp(activity, 8),
                AppUi.dp(activity, 4), AppUi.dp(activity, 8));
        close.setOnClickListener(v -> host.closeUpdateSheet());
        header.addView(close);

        sheet.addView(header);

        // status badges row (channel + auto-check)
        LinearLayout badges = new LinearLayout(activity);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.setGravity(Gravity.CENTER_VERTICAL);
        badges.setTag("badges");
        LinearLayout.LayoutParams badgesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgesLp.topMargin = AppUi.dp(activity, 14);
        sheet.addView(badges, badgesLp);

        TextView status = AppUi.text(activity, "", 13f, R.color.on_bg_muted, false);
        status.setTag("status");
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = AppUi.dp(activity, 16);
        sheet.addView(status, statusLp);

        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setTag("progress");
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AppUi.dp(activity, 6));
        barLp.topMargin = AppUi.dp(activity, 12);
        sheet.addView(bar, barLp);

        LinearLayout settings = new LinearLayout(activity);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setTag("settings");
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        settingsLp.topMargin = AppUi.dp(activity, 8);
        sheet.addView(settings, settingsLp);

        TextView notesTitle = AppUi.text(activity, activity.getString(R.string.update_whats_new),
                13f, R.color.on_bg, true);
        LinearLayout.LayoutParams notesTitleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        notesTitleLp.topMargin = AppUi.dp(activity, 18);
        sheet.addView(notesTitle, notesTitleLp);

        TextView notes = AppUi.text(activity, "", 13f, R.color.on_bg_muted, false);
        notes.setTag("notes");
        notes.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        notes.setPadding(0, AppUi.dp(activity, 6), 0, 0);
        sheet.addView(notes);

        // Buttons
        TextView primary = AppUi.text(activity, "", 15f, R.color.on_accent, true);
        primary.setGravity(Gravity.CENTER);
        primary.setPadding(0, AppUi.dp(activity, 14), 0, AppUi.dp(activity, 14));
        primary.setTag("primary");
        primary.setBackground(AppUi.pressable(activity, AppUi.color(activity, R.color.accent), 16f));
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.topMargin = AppUi.dp(activity, 20);
        sheet.addView(primary, pLp);

        TextView secondary = AppUi.text(activity, "", 14f, R.color.on_bg, false);
        secondary.setGravity(Gravity.CENTER);
        secondary.setPadding(0, AppUi.dp(activity, 13), 0, AppUi.dp(activity, 13));
        secondary.setTag("secondary");
        secondary.setBackground(AppUi.pressable(activity, AppUi.color(activity, R.color.surface2), 16f));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = AppUi.dp(activity, 10);
        sheet.addView(secondary, sLp);

        android.widget.FrameLayout.LayoutParams sheetLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        root.addView(scroller, sheetLp);
        AppUi.slideUp(scroller);
        return root;
    }

    private LinearLayout settingsBox;

    // ------------------------------------------------------------ state

    public void refresh() {
        AppUi.fadeIn(overlay);
        final int installed = UpdateManager.currentVersionCode(activity);
        final int latest = UpdateManager.latestVersionCode(activity);
        boolean available = UpdateManager.isUpdateAvailable(activity);
        String latestName = UpdateManager.latestVersionName(activity);
        String installedName = UpdateManager.currentVersionName(activity);

        versionLine.setText(activity.getString(R.string.update_versions,
                installedName, UpdateManager.lastCheck(activity) == 0
                        ? activity.getString(R.string.update_never_checked)
                        : relativeTime(UpdateManager.lastCheck(activity))));

        // badges: channel + auto check + token state
        badgeRow.removeAllViews();
        badgeRow.addView(chip(activity.getString(
                UpdateManager.Channel.BETA.name().equals(UpdateManager.channel(activity))
                        ? R.string.update_channel_beta : R.string.update_channel_stable)));
        badgeRow.addView(chip(activity.getString(UpdateManager.autoCheck(activity)
                ? R.string.update_auto_on : R.string.update_auto_off)));
        badgeRow.addView(chip(UpdateManager.hasToken(activity)
                ? activity.getString(R.string.update_token_set)
                : activity.getString(R.string.update_private_hint)));

        settingsBox.removeAllViews();
        settingsBox.addView(settingRow(
                activity.getString(R.string.update_setting_channel),
                activity.getString(UpdateManager.Channel.BETA.name()
                        .equals(UpdateManager.channel(activity))
                        ? R.string.update_channel_beta : R.string.update_channel_stable),
                v -> {
                    boolean beta = !UpdateManager.Channel.BETA.name()
                            .equals(UpdateManager.channel(activity));
                    UpdateManager.setChannel(activity, beta
                            ? UpdateManager.Channel.BETA : UpdateManager.Channel.STABLE);
                    refresh();
                    runCheck();
                }));
        settingsBox.addView(settingRow(
                activity.getString(R.string.update_setting_auto),
                activity.getString(UpdateManager.autoCheck(activity)
                        ? R.string.on : R.string.off),
                v -> {
                    UpdateManager.setAutoCheck(activity, !UpdateManager.autoCheck(activity));
                    refresh();
                }));
        settingsBox.addView(settingRow(
                activity.getString(R.string.update_setting_token),
                activity.getString(UpdateManager.hasToken(activity)
                        ? R.string.update_token_change : R.string.update_token_add),
                v -> {
                    if (activity instanceof MainActivity) {
                        ((MainActivity) activity).requestToken();
                    } else {
                        promptForToken();
                    }
                }));

        if (downloadedApk != null && downloadedApk.exists()) {
            statusLine.setText(activity.getString(R.string.update_ready_to_install));
            progressBar.setVisibility(View.GONE);
            notesView.setText(UpdateManager.latestNotes(activity));
            primaryButton.setText(activity.getString(R.string.update_install_now));
            primaryButton.setEnabled(true);
            primaryButton.setAlpha(1f);
            primaryButton.setOnClickListener(v -> host.launchInstaller(downloadedApk));
            secondaryButton.setText(activity.getString(R.string.update_release_page));
            secondaryButton.setOnClickListener(v -> openReleasePage());
            return;
        }

        if (available) {
            statusLine.setText(activity.getString(R.string.update_available_long,
                    latestName, installedName));
            notesView.setText(UpdateManager.latestNotes(activity).isEmpty()
                    ? activity.getString(R.string.update_no_notes)
                    : UpdateManager.latestNotes(activity));
            primaryButton.setText(activity.getString(R.string.update_download,
                    humanSize(UpdateManager.latestApkSize(activity))));
            primaryButton.setEnabled(true);
            primaryButton.setAlpha(1f);
            primaryButton.setOnClickListener(v -> startDownload());
            secondaryButton.setText(activity.getString(R.string.update_later));
            secondaryButton.setOnClickListener(v -> host.closeUpdateSheet());
        } else {
            String error = UpdateManager.lastError(activity);
            statusLine.setText(error != null
                    ? activity.getString(R.string.update_error, error)
                    : activity.getString(R.string.update_up_to_date, installedName));
            notesView.setText(activity.getString(R.string.update_no_notes_hint));
            primaryButton.setText(activity.getString(R.string.update_check_now));
            primaryButton.setEnabled(true);
            primaryButton.setAlpha(1f);
            primaryButton.setOnClickListener(v -> checkNow());
            secondaryButton.setText(activity.getString(R.string.update_release_page));
            secondaryButton.setOnClickListener(v -> openReleasePage());
        }
    }

    private View chip(String label) {
        TextView chip = AppUi.text(activity, label, 11.5f, R.color.on_bg_muted, false);
        chip.setPadding(AppUi.dp(activity, 10), AppUi.dp(activity, 5),
                AppUi.dp(activity, 10), AppUi.dp(activity, 5));
        chip.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface2), 12f, activity));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = AppUi.dp(activity, 8);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void checkNow() {
        if (!UpdateManager.hasToken(activity)) {
            promptForToken();
            return;
        }
        runCheck();
    }

    private void runCheck() {
        statusLine.setText(activity.getString(R.string.update_checking));
        primaryButton.setEnabled(false);
        primaryButton.setAlpha(0.6f);
        UpdateCenter.forceCheck(activity, result -> {
            refresh();
            if (result.ok && result.updateAvailable) {
                ((MainActivity) activity).onUpdateStateChanged(true);
            }
        });
    }

    private void startDownload() {
        statusLine.setText(activity.getString(R.string.update_downloading));
        primaryButton.setEnabled(false);
        primaryButton.setAlpha(0.6f);
        secondaryButton.setText(activity.getString(R.string.update_cancel));
        secondaryButton.setOnClickListener(v -> host.closeUpdateSheet());
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);

        UpdateCenter.downloadAsync(activity, new UpdateCenter.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                progressBar.setProgress(percent);
                statusLine.setText(activity.getString(R.string.update_downloading_percent, percent));
                UpdateNotifications.notifyProgress(activity, percent + "%", percent);
            }

            @Override
            public void onDone(File apk) {
                downloadedApk = apk;
                UpdateNotifications.cancelProgress(activity);
                host.onUpdateDownloaded(apk);
                refresh();
            }

            @Override
            public void onError(String message) {
                UpdateNotifications.cancelProgress(activity);
                progressBar.setVisibility(View.GONE);
                statusLine.setText(activity.getString(R.string.update_error, message));
                primaryButton.setEnabled(true);
                primaryButton.setAlpha(1f);
                secondaryButton.setText(activity.getString(R.string.update_release_page));
                secondaryButton.setOnClickListener(v -> openReleasePage());
            }
        });
    }

    /**
     * Opens the release page INSIDE the app.
     *
     * This used to fire an ACTION_VIEW intent, which is exactly how the user ended up
     * in Chrome. The app never hands an http(s) link to another app now: the page is
     * shown in an in-app WebView sheet, with the app's own bar and a close button.
     */
    private void openReleasePage() {
        String url = UpdateManager.latestReleasePath(activity);
        showInAppPage(activity, url);
    }

    /** Minimal in-app browser sheet: one WebView, app chrome, no external app. */
    static void showInAppPage(final Activity activity, String url) {
        final android.widget.FrameLayout root =
                (android.widget.FrameLayout) activity.findViewById(android.R.id.content);
        if (root == null) return;

        final android.widget.FrameLayout pageOverlay = AppUi.sheetScrim(activity, null);
        android.webkit.WebView page = new android.webkit.WebView(activity);
        page.setBackgroundColor(AppUi.color(activity, R.color.bg));
        android.webkit.WebSettings settings = page.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(AppChrome.userAgent(activity,
                settings.getUserAgentString()));
        page.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView view,
                                                    android.webkit.WebResourceRequest request) {
                // Stay inside: GitHub links open in the same in-app page.
                return false;
            }

            @Override
            public void onReceivedError(android.webkit.WebView view, android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    android.widget.Toast.makeText(activity,
                            R.string.update_release_unreachable,
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });

        android.widget.LinearLayout column = new android.widget.LinearLayout(activity);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        column.setBackgroundColor(AppUi.color(activity, R.color.bg));

        android.widget.LinearLayout bar = new android.widget.LinearLayout(activity);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(AppUi.color(activity, R.color.surface));
        bar.setPadding(AppUi.dp(activity, 14), AppUi.dp(activity, 10),
                AppUi.dp(activity, 10), AppUi.dp(activity, 10));
        bar.addView(AppUi.text(activity, activity.getString(R.string.update_release_page_title),
                15f, R.color.on_bg, true), new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.TextView close = AppUi.text(activity, "\u2715", 18f,
                R.color.on_bg_muted, false);
        close.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 6),
                AppUi.dp(activity, 12), AppUi.dp(activity, 6));
        close.setOnClickListener(v -> {
            page.stopLoading();
            page.destroy();
            AppUi.slideDownAndRemove(pageOverlay, root);
        });
        bar.addView(close);
        column.addView(bar, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(page, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = android.view.Gravity.BOTTOM;
        pageOverlay.addView(column, lp);
        root.addView(pageOverlay, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageOverlay.setAlpha(0f);
        pageOverlay.animate().alpha(1f).setDuration(AppUi.ANIM_FAST).start();
        page.loadUrl(url);
    }

    /** Asks for a GitHub token (only needed for private repos) and checks again. */
    private void promptForToken() {
        final android.widget.EditText input = new android.widget.EditText(activity);
        input.setHint(R.string.update_token_hint);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        input.setSingleLine(false);
        input.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 10),
                AppUi.dp(activity, 12), AppUi.dp(activity, 10));
        input.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface2), 12f, activity));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = AppUi.dp(activity, 20);
        box.setPadding(pad, pad, pad, pad);

        box.addView(AppUi.text(activity, activity.getString(R.string.update_token_title),
                17f, R.color.on_bg, true));
        TextView help = AppUi.text(activity, activity.getString(R.string.update_token_help),
                12.5f, R.color.on_bg_muted, false);
        help.setPadding(0, AppUi.dp(activity, 8), 0, AppUi.dp(activity, 12));
        box.addView(help);
        box.addView(input);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                .setView(box)
                .setPositiveButton(R.string.update_token_save, null)
                .setNegativeButton(R.string.update_token_skip, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String value = input.getText().toString().trim();
                    UpdateManager.setToken(activity, value);
                    dialog.dismiss();
                    refresh();
                    if (!value.isEmpty()) runCheck();
                }));
        dialog.show();
    }

    /** Small text row used to flip update settings without leaving the sheet. */
    private View settingRow(String label, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AppUi.dp(activity, 12), 0, AppUi.dp(activity, 12));
        row.setOnClickListener(click);
        row.setClickable(true);

        TextView l = AppUi.text(activity, label, 13.5f, R.color.on_bg, false);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = AppUi.text(activity, value, 13f, R.color.accent, false);
        row.addView(v);
        return row;
    }

    // ------------------------------------------------------------ helpers

    public static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String relativeTime(long millis) {
        long age = System.currentTimeMillis() - millis;
        if (age < 60_000) return "just now";
        if (age < 3_600_000) return (age / 60_000) + "m ago";
        if (age < 86_400_000) return (age / 3_600_000) + "h ago";
        return (age / 86_400_000) + "d ago";
    }
}
