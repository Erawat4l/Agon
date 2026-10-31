package ai.arena.webapp;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The Settings sheet: every switch the app has, in one scrollable list.
 *
 * Rows are grouped the way a native settings screen would: Appearance, Navigation,
 * the page itself, Updates, and Data. Every row is a real switch that applies
 * immediately (and reopens where you were), so nothing needs a restart.
 */
public final class SettingsSheet {

    /** What the activity has to do when a switch flips. */
    public interface Host {
        void closeSettings();

        void setAppearance(int mode);

        /** Reopens sheets after the activity is recreated for a theme change. */
        void onSettingsChanged(String key, boolean value);

        void onSignOut();

        void onClearData();

        void onResetSettings();

        void onDeveloperDiagnostics();

        void onSignIn();

        void onEnableDeveloperOptions();
    }

    /** Slides the sheet away and removes it. */
    public void dismiss() {
        if (overlay == null) return;
        final FrameLayout going = overlay;
        overlay = null;
        if (body != null) AppUi.slideDownAndRemove(body, going);
        else AppUi.slideDownAndRemove(going, parent);
        going.animate().alpha(0f).setDuration(AppUi.ANIM_FAST)
                .withEndAction(() -> {
                    if (going.getParent() != null) parent.removeView(going);
                }).start();
    }

    private final Activity activity;
    private final ViewGroup parent;
    private final Host host;
    private FrameLayout overlay;
    private ScrollView body;
    private LinearLayout list;

    public SettingsSheet(Activity activity, ViewGroup parent, Host host) {
        this.activity = activity;
        this.parent = parent;
        this.host = host;
    }

    public View view() {
        return overlay;
    }

    public void show() {
        if (overlay != null) return;
        overlay = AppUi.sheetScrim(activity, v -> host.closeSettings());

        final ScrollView scroller = new ScrollView(activity);
        body = scroller;
        scroller.setFillViewport(true);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface), 26f, activity));
        sheet.setPadding(AppUi.dp(activity, 20), AppUi.dp(activity, 10),
                AppUi.dp(activity, 20), AppUi.dp(activity, 24));
        sheet.setOnClickListener(v -> {
        });
        scroller.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View handle = new View(activity);
        handle.setBackground(AppUi.rounded(AppUi.color(activity, R.color.handle), 3f, activity));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                AppUi.dp(activity, 40), AppUi.dp(activity, 5));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = AppUi.dp(activity, 12);
        sheet.addView(handle, handleLp);

        // Header: title + close
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = AppUi.heading(activity,
                activity.getString(R.string.settings_title), 23f, R.color.on_bg);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = AppUi.text(activity, "\u2715", 18f, R.color.on_bg_muted, false);
        close.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 6),
                AppUi.dp(activity, 4), AppUi.dp(activity, 6));
        close.setOnClickListener(v -> host.closeSettings());
        header.addView(close);
        sheet.addView(header);

        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = AppUi.dp(activity, 6);
        sheet.addView(list, listLp);

        buildRows();

        final FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        overlay.addView(scroller, sheetLp);
        parent.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Cap the sheet at 90% of the screen and let the list scroll inside it, so a long
        // settings list can never push its own header off the top of the screen.
        overlay.post(() -> {
            int max = (int) (overlay.getHeight() * 0.9f);
            if (max > 0 && scroller.getHeight() > max) {
                sheetLp.height = max;
                scroller.setLayoutParams(sheetLp);
            }
        });
        AppUi.slideUp(scroller);
    }

    /** Repaints the list in place (a section appeared or a value changed elsewhere). */
    public void refresh() {
        if (list != null) buildRows();
    }

    /** Rebuilds the list; called after a toggle so the labels stay truthful. */
    private void buildRows() {
        list.removeAllViews();

        section(R.string.settings_appearance);
        appearanceRow();

        // Sign-in lives here too. Before, it was only in the More sheet, so a user who had
        // not signed in yet could open Settings and find no way in.
        section(R.string.settings_account);
        action(R.string.settings_sign_in, R.string.settings_sign_in_sub,
                R.drawable.ic_nav_account, v -> host.onSignIn());

        section(R.string.settings_app);
        action(R.string.settings_sign_out, R.string.settings_sign_out_sub,
                R.drawable.ic_nav_account, v -> host.onSignOut());
        action(R.string.settings_clear, R.string.settings_clear_sub,
                R.drawable.ic_nav_trash, v -> host.onClearData());

        // The page switches and "reset settings" are developer tools, not user ones: a
        // person opening Settings wants appearance, their account, and a way to clear data.
        if (!Settings.developerOptions(activity)) {
            section(R.string.settings_developer_off);
            action(R.string.settings_developer_enable, R.string.settings_developer_enable_sub,
                    R.drawable.ic_nav_settings, v -> host.onEnableDeveloperOptions());
        }

        if (Settings.developerOptions(activity)) developerSection();

        TextView footer = AppUi.text(activity, activity.getString(R.string.settings_footer,
                        UpdateManager.currentVersionName(activity),
                        UpdateManager.currentVersionCode(activity)),
                11.5f, R.color.on_bg_muted, false);
        footer.setGravity(Gravity.CENTER);
        // Seven taps unlocks developer options - the same deliberate gesture Android uses,
        // so nobody finds them by accident and nothing in the sheet advertises them.
        footer.setOnClickListener(new View.OnClickListener() {
            private int taps;
            private long firstTap;

            @Override
            public void onClick(View v) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - firstTap > 4000) {
                    taps = 0;
                    firstTap = now;
                }
                taps++;
                int left = 7 - taps;
                if (left <= 0) {
                    taps = 0;
                    Settings.setDeveloperOptions(activity, true);
                    android.widget.Toast.makeText(activity,
                            R.string.settings_developer_unlocked,
                            android.widget.Toast.LENGTH_SHORT).show();
                    buildRows();
                    return;
                }
                if (left <= 4) {
                    android.widget.Toast.makeText(activity,
                            activity.getString(R.string.settings_developer_left, left),
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = AppUi.dp(activity, 16);
        list.addView(footer, footerLp);
    }

    /**
     * Everything an ordinary user does not need to see.
     *
     * Only reachable after the unlock gesture. Same controls as the rest of the sheet, so
     * nothing here is a second implementation that can drift: it is the full switch set,
     * plus the sign-in trace and the diagnostics report, which is what testing on a device
     * actually needs.
     */
    private void developerSection() {
        section(R.string.settings_developer);
        action(R.string.settings_developer_diag, R.string.settings_developer_diag_sub,
                R.drawable.ic_nav_settings, v -> host.onDeveloperDiagnostics());
        // One row per switch. The sidebar and header rows were each listed twice here,
        // which meant two switches with the same label set the same key.
        toggle(Settings.K_HIDE_WEB_SIDEBAR, R.string.settings_hide_sidebar,
                R.string.settings_hide_sidebar_sub, Settings.hideWebSidebar(activity));
        toggle(Settings.K_HIDE_WEB_HEADER, R.string.settings_hide_header,
                R.string.settings_hide_header_sub, true);
        toggle(Settings.K_SHOW_NAV, R.string.settings_nav_bar, R.string.settings_nav_bar_sub,
                true);
        toggle(Settings.K_CHAT_TAPS, R.string.settings_chat_taps,
                R.string.settings_chat_taps_sub, true);
        toggle(Settings.K_KEEP_SCROLL, R.string.settings_keep_scroll,
                R.string.settings_keep_scroll_sub, true);
        toggle(Settings.K_NO_SELECT, R.string.settings_selectable,
                R.string.settings_selectable_sub, true);
        toggle(Settings.K_DESKTOP_UA, R.string.settings_ua,
                R.string.settings_ua_sub, true);
        toggle(Settings.K_CRASH_SCREEN, R.string.settings_crash,
                R.string.settings_crash_sub, true);
        toggle(Settings.K_NOTIFY_UPDATES, R.string.settings_notify,
                R.string.settings_notify_sub, true);
        toggle(Settings.K_AUTO_CHECK, R.string.settings_auto_check,
                R.string.settings_auto_check_sub, true);
        toggle(Settings.K_SHOW_VERSION, R.string.settings_show_version,
                R.string.settings_show_version_sub, false);
        action(R.string.settings_reset, R.string.settings_reset_sub,
                R.drawable.ic_nav_reload, v -> host.onResetSettings());
        action(R.string.settings_developer_lock, R.string.settings_developer_lock_sub,
                R.drawable.ic_nav_close, v -> {
                    Settings.setDeveloperOptions(activity, false);
                    buildRows();
                });
    }

    private void section(int titleRes) {
        TextView label = AppUi.text(activity, activity.getString(titleRes), 11.5f,
                R.color.accent, true);
        label.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AppUi.dp(activity, 16);
        lp.bottomMargin = AppUi.dp(activity, 2);
        list.addView(label, lp);
    }

    /** System / Light / Dark chips, applied immediately. */
    private void appearanceRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AppUi.dp(activity, 8), 0, AppUi.dp(activity, 4));

        TextView label = AppUi.text(activity, activity.getString(R.string.settings_theme),
                15f, R.color.on_bg, false);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout chips = new LinearLayout(activity);
        int mode = AppUi.appearance(activity);
        chips.addView(chip(R.string.appearance_system, mode == AppUi.APPEARANCE_SYSTEM,
                () -> host.setAppearance(AppUi.APPEARANCE_SYSTEM)));
        chips.addView(chip(R.string.appearance_light, mode == AppUi.APPEARANCE_LIGHT,
                () -> host.setAppearance(AppUi.APPEARANCE_LIGHT)));
        chips.addView(chip(R.string.appearance_dark, mode == AppUi.APPEARANCE_DARK,
                () -> host.setAppearance(AppUi.APPEARANCE_DARK)));
        row.addView(chips);
        list.addView(row);
    }

    private View chip(int labelRes, boolean selected, Runnable onClick) {
        TextView chip = AppUi.text(activity, activity.getString(labelRes), 12f,
                selected ? R.color.on_accent : R.color.on_bg_muted, selected);
        chip.setPadding(AppUi.dp(activity, 10), AppUi.dp(activity, 6),
                AppUi.dp(activity, 10), AppUi.dp(activity, 6));
        chip.setBackground(AppUi.pressable(activity,
                selected ? AppUi.color(activity, R.color.accent)
                        : AppUi.color(activity, R.color.surface2), 12f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = AppUi.dp(activity, 6);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            AppUi.press(v);
            onClick.run();
        });
        return chip;
    }

    /** One switch row: title, explanation, and a real toggle on the right. */
    private void toggle(final String key, int titleRes, int subRes, boolean defaultValue) {
        final boolean value = Settings.get(activity, key, defaultValue);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AppUi.dp(activity, 11), 0, AppUi.dp(activity, 11));
        row.setBackground(AppUi.pressable(activity, Color.TRANSPARENT, 12f));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(AppUi.text(activity, activity.getString(titleRes), 15f,
                R.color.on_bg, false));
        TextView sub = AppUi.text(activity, activity.getString(subRes), 12f,
                R.color.on_bg_muted, false);
        sub.setPadding(0, AppUi.dp(activity, 2), AppUi.dp(activity, 12), 0);
        texts.addView(sub);

        row.addView(switchView(value), new LinearLayout.LayoutParams(
                AppUi.dp(activity, 46), AppUi.dp(activity, 26)));
        row.setOnClickListener(v -> {
            boolean next = !Settings.get(activity, key, defaultValue);
            Settings.set(activity, key, next);
            AppUi.press(v);
            host.onSettingsChanged(key, next);
            buildRows();   // repaint so the knob matches the stored value
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AppUi.dp(activity, 2);
        list.addView(row, lp);
    }

    /** A dependency-free switch: rounded track + animated knob. */
    private View switchView(boolean on) {
        FrameLayout track = new FrameLayout(activity);
        track.setBackground(AppUi.rounded(AppUi.color(activity,
                on ? R.color.accent : R.color.surface2), 13f, activity));

        View knob = new View(activity);
        knob.setBackground(AppUi.rounded(Color.WHITE, 10f, activity));
        int size = AppUi.dp(activity, 20);
        FrameLayout.LayoutParams knobLp = new FrameLayout.LayoutParams(size, size);
        knobLp.gravity = Gravity.CENTER_VERTICAL;
        knobLp.leftMargin = AppUi.dp(activity, on ? 23 : 3);
        track.addView(knob, knobLp);
        return track;
    }

    /** A row that runs an action instead of toggling a stored value. */
    private void action(int titleRes, int subRes, int iconRes, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AppUi.dp(activity, 10), 0, AppUi.dp(activity, 10));
        row.setBackground(AppUi.pressable(activity, Color.TRANSPARENT, 12f));
        row.setOnClickListener(click);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(AppUi.color(activity, R.color.on_bg_muted));
        int p = AppUi.dp(activity, 7);
        icon.setPadding(p, p, p, p);
        icon.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface2), 10f,
                activity));
        row.addView(icon, new LinearLayout.LayoutParams(AppUi.dp(activity, 32),
                AppUi.dp(activity, 32)));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = AppUi.dp(activity, 12);
        row.addView(texts, textLp);
        texts.addView(AppUi.text(activity, activity.getString(titleRes), 15f,
                R.color.on_bg, false));
        texts.addView(AppUi.text(activity, activity.getString(subRes), 12f,
                R.color.on_bg_muted, false));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AppUi.dp(activity, 2);
        list.addView(row, lp);
    }
}
