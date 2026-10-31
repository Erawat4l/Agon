package ai.arena.webapp;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** App-style "More" sheet: update, reload, share, open in browser, clear data. */
public final class MenuSheet {

    public interface Callback {
        void onReload();

        void onOpenUpdate();

        void onShare();

        void onOpenInBrowser();

        void onClearAppData();

        void onDismissed();
    }

    private MenuSheet() {
    }

    public static View show(final Activity activity, final ViewGroup parent,
                            final Callback callback) {
        final FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(AppUi.color(activity, R.color.scrim));
        root.setOnClickListener(v -> close(parent, root, callback));

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface), 26f, activity));
        sheet.setPadding(AppUi.dp(activity, 18), AppUi.dp(activity, 10),
                AppUi.dp(activity, 18), AppUi.dp(activity, 20));
        sheet.setOnClickListener(v -> {
        });

        View handle = new View(activity);
        handle.setBackground(AppUi.rounded(AppUi.color(activity, R.color.handle), 3f, activity));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                AppUi.dp(activity, 40), AppUi.dp(activity, 5));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = AppUi.dp(activity, 14);
        sheet.addView(handle, handleLp);

        TextView title = AppUi.text(activity, activity.getString(R.string.more_title),
                18f, R.color.on_bg, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = AppUi.dp(activity, 12);
        sheet.addView(title, titleLp);

        boolean updateAvailable = UpdateManager.isUpdateAvailable(activity);
        addRow(activity, sheet, R.string.app_update,
                updateAvailable
                        ? activity.getString(R.string.update_available_short,
                        UpdateManager.latestVersionName(activity))
                        : activity.getString(R.string.update_up_to_date,
                        UpdateManager.currentVersionName(activity)),
                R.drawable.ic_nav_update, updateAvailable, v -> {
                    close(parent, root, callback);
                    callback.onOpenUpdate();
                });

        addRow(activity, sheet, R.string.reload, null, R.drawable.ic_nav_reload, false, v -> {
            close(parent, root, callback);
            callback.onReload();
        });

        addRow(activity, sheet, R.string.share, null, R.drawable.ic_nav_share, false, v -> {
            close(parent, root, callback);
            callback.onShare();
        });

        addRow(activity, sheet, R.string.open_in_browser, null,
                R.drawable.ic_nav_open, false, v -> {
                    close(parent, root, callback);
                    callback.onOpenInBrowser();
                });

        addRow(activity, sheet, R.string.clear_data,
                activity.getString(R.string.clear_data_sub), R.drawable.ic_nav_trash, false, v -> {
                    close(parent, root, callback);
                    callback.onClearAppData();
                });

        TextView footer = AppUi.text(activity, activity.getString(R.string.menu_version,
                        UpdateManager.currentVersionName(activity),
                        UpdateManager.currentVersionCode(activity)),
                11.5f, R.color.on_bg_muted, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = AppUi.dp(activity, 16);
        sheet.addView(footer, footerLp);

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        root.addView(sheet, sheetLp);

        parent.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private static void close(ViewGroup parent, View root, Callback callback) {
        if (root.getParent() != null) parent.removeView(root);
        if (callback != null) callback.onDismissed();
    }

    private static void addRow(Activity activity, LinearLayout parent, int titleRes,
                               String subtitle, int iconRes, boolean accent,
                               View.OnClickListener click) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 12),
                AppUi.dp(activity, 12), AppUi.dp(activity, 12));
        row.setBackground(AppUi.pressable(activity, android.graphics.Color.TRANSPARENT, 14f));
        row.setOnClickListener(click);

        android.widget.ImageView icon = new android.widget.ImageView(activity);
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
