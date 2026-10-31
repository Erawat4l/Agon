package ai.arena.webapp;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Animated "More" sheet: update, settings, reload, account, theme, clear data. */
public final class MenuSheet {

    public interface Callback {
        void onReload();

        void onOpenUpdate();

        void onSignIn();

        void onSettings();

        void onClearAppData();

        void setAppearance(int mode);

        void onDismissed();
    }

    private MenuSheet() {
    }

    public static View show(final Activity activity, final ViewGroup parent,
                            final Callback callback) {
        final FrameLayout scrim = AppUi.sheetScrim(activity, null);
        scrim.setOnClickListener(v -> dismiss(parent, scrim, callback));

        final LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface), 26f, activity));
        sheet.setPadding(AppUi.dp(activity, 16), AppUi.dp(activity, 10),
                AppUi.dp(activity, 16), AppUi.dp(activity, 18));
        sheet.setOnClickListener(v -> {
        });

        View handle = new View(activity);
        handle.setBackground(AppUi.rounded(AppUi.color(activity, R.color.handle), 3f, activity));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                AppUi.dp(activity, 38), AppUi.dp(activity, 4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = AppUi.dp(activity, 14);
        sheet.addView(handle, handleLp);

        boolean updateAvailable = UpdateManager.isUpdateAvailable(activity);
        addRow(activity, sheet, R.string.app_update,
                updateAvailable
                        ? activity.getString(R.string.update_available_short,
                        UpdateManager.latestVersionName(activity))
                        : activity.getString(R.string.update_up_to_date,
                        UpdateManager.currentVersionName(activity)),
                R.drawable.ic_nav_update, updateAvailable, v -> {
                    dismiss(parent, scrim, callback);
                    callback.onOpenUpdate();
                });

        addRow(activity, sheet, R.string.menu_settings,
                activity.getString(R.string.menu_settings_sub), R.drawable.ic_nav_settings,
                false, v -> {
                    dismiss(parent, scrim, callback);
                    callback.onSettings();
                });

        addRow(activity, sheet, R.string.reload, null, R.drawable.ic_nav_reload, false, v -> {
            dismiss(parent, scrim, callback);
            callback.onReload();
        });

        addRow(activity, sheet, R.string.account_signin,
                activity.getString(R.string.account_signin_sub), R.drawable.ic_nav_account,
                false, v -> {
                    dismiss(parent, scrim, callback);
                    callback.onSignIn();
                });

        // Appearance: three-state chip row (System / Light / Dark).
        addAppearanceRow(activity, sheet, callback);

        addRow(activity, sheet, R.string.clear_data,
                activity.getString(R.string.clear_data_sub), R.drawable.ic_nav_trash, false, v -> {
                    dismiss(parent, scrim, callback);
                    callback.onClearAppData();
                });

        TextView footer = AppUi.text(activity, activity.getString(R.string.menu_version,
                        UpdateManager.currentVersionName(activity),
                        UpdateManager.currentVersionCode(activity)),
                11.5f, R.color.on_bg_muted, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = AppUi.dp(activity, 14);
        sheet.addView(footer, footerLp);

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        scrim.addView(sheet, sheetLp);

        parent.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        AppUi.slideUp(sheet);
        return scrim;
    }

    private static void dismiss(final ViewGroup parent, final View scrim,
                                final Callback callback) {
        scrim.animate().alpha(0f).setDuration(AppUi.ANIM_FAST)
                .withEndAction(() -> {
                    if (scrim.getParent() != null) parent.removeView(scrim);
                    if (callback != null) callback.onDismissed();
                }).start();
    }

    private static void addAppearanceRow(Activity activity, LinearLayout parent,
                                         Callback callback) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 12),
                AppUi.dp(activity, 12), AppUi.dp(activity, 12));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_nav_theme);
        icon.setColorFilter(AppUi.color(activity, R.color.on_bg_muted));
        int p = AppUi.dp(activity, 8);
        icon.setPadding(p, p, p, p);
        icon.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface2), 12f, activity));
        row.addView(icon, new LinearLayout.LayoutParams(AppUi.dp(activity, 38),
                AppUi.dp(activity, 38)));

        TextView label = AppUi.text(activity, activity.getString(R.string.appearance), 15f,
                R.color.on_bg, false);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = AppUi.dp(activity, 14);
        row.addView(label, labelLp);

        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        int mode = AppUi.appearance(activity);
        chips.addView(chip(activity, R.string.appearance_system, mode == AppUi.APPEARANCE_SYSTEM,
                () -> callback.setAppearance(AppUi.APPEARANCE_SYSTEM)));
        chips.addView(chip(activity, R.string.appearance_light, mode == AppUi.APPEARANCE_LIGHT,
                () -> callback.setAppearance(AppUi.APPEARANCE_LIGHT)));
        chips.addView(chip(activity, R.string.appearance_dark, mode == AppUi.APPEARANCE_DARK,
                () -> callback.setAppearance(AppUi.APPEARANCE_DARK)));
        row.addView(chips);

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static View chip(Activity activity, int labelRes, boolean selected,
                             Runnable onClick) {
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

    private static void addRow(Activity activity, LinearLayout parent, int titleRes,
                               String subtitle, int iconRes, boolean accent,
                               View.OnClickListener click) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 11),
                AppUi.dp(activity, 12), AppUi.dp(activity, 11));
        row.setBackground(AppUi.pressable(activity, Color.TRANSPARENT, 14f));
        row.setOnClickListener(click);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(AppUi.color(activity, accent ? R.color.accent : R.color.on_bg_muted));
        int p = AppUi.dp(activity, 8);
        icon.setPadding(p, p, p, p);
        icon.setBackground(AppUi.rounded(AppUi.color(activity,
                accent ? R.color.accent_chip : R.color.surface2), 12f, activity));
        row.addView(icon, new LinearLayout.LayoutParams(AppUi.dp(activity, 38),
                AppUi.dp(activity, 38)));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = AppUi.dp(activity, 14);
        row.addView(texts, textLp);

        TextView label = AppUi.text(activity, activity.getString(titleRes), 15f,
                accent ? R.color.accent : R.color.on_bg, false);
        texts.addView(label);
        if (subtitle != null) {
            TextView sub = AppUi.text(activity, subtitle, 12f, R.color.on_bg_muted, false);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = AppUi.dp(activity, 2);
            texts.addView(sub, subLp);
        }
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
