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

        TextView title = AppUi.heading(activity, activity.getString(R.string.app_name), 22f,
                R.color.on_bg);
        title.setPadding(AppUi.dp(activity, 12), 0, AppUi.dp(activity, 12),
                AppUi.dp(activity, 6));
        sheet.addView(title);

        boolean updateAvailable = UpdateManager.isUpdateAvailable(activity);
        // Four rows, no more: everything else lives inside Settings. The sheet had grown to
        // seven entries, several of which were a second copy of things Settings already
        // owns (clear data, appearance, reload).
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

        String accountSub = LocalProfile.hasAccount(activity)
                ? LocalProfile.summary(activity)
                : activity.getString(R.string.account_signin_sub);
        addRow(activity, sheet, R.string.account_signin,
                accountSub, R.drawable.ic_nav_account,
                false, v -> {
                    dismiss(parent, scrim, callback);
                    callback.onSignIn();
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
