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

        /** Opens a second arena window on a fresh chat, in the background. */
        void onNewWindow();

        /** Switches to an already-open window (its id is a TabWebViews id). */
        void onSwitchWindow(int windowId);

        /** Closes an open window. */
        void onCloseWindow(int windowId);

        void onDismissed();
    }

    /** One open window, as the sheet needs to draw it. */
    public static final class Window {
        public final int id;
        public final String title;

        public Window(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private MenuSheet() {
    }

    public static View show(final Activity activity, final ViewGroup parent,
                            final Callback callback,
                            final java.util.List<Window> openWindows,
                            final int activeId) {
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

        // ---- windows: a second (third, fourth) arena, kept loaded
        //
        // The row is last, as asked. It opens a window that starts loading immediately in
        // the background, so switching to it later is instant - and the windows that are
        // already open are listed right underneath, so two conversations can be swapped
        // between without either one reloading.
        addRow(activity, sheet, R.string.menu_new_window,
                activity.getString(R.string.menu_new_window_sub),
                R.drawable.ic_nav_window, false, v -> {
                    dismiss(parent, scrim, callback);
                    callback.onNewWindow();
                });

        if (openWindows != null && !openWindows.isEmpty()) {
            TextView heading = AppUi.text(activity,
                    activity.getString(R.string.menu_windows_open), 11.5f,
                    R.color.on_bg_muted, true);
            heading.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 10),
                    AppUi.dp(activity, 12), AppUi.dp(activity, 4));
            sheet.addView(heading);
            for (int i = 0; i < openWindows.size(); i++) {
                final Window window = openWindows.get(i);
                addWindowRow(activity, sheet, parent, scrim, callback, window,
                        window.id == activeId, i + 1);
            }
        }

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

    /** Takes the sheet off screen without telling the callback it was dismissed. */
    private static void remove(final ViewGroup parent, final View scrim) {
        if (scrim.getParent() == null) return;
        scrim.animate().alpha(0f).setDuration(AppUi.ANIM_FAST)
                .withEndAction(() -> {
                    if (scrim.getParent() != null) parent.removeView(scrim);
                }).start();
    }

    private static void dismiss(final ViewGroup parent, final View scrim,
                                final Callback callback) {
        scrim.animate().alpha(0f).setDuration(AppUi.ANIM_FAST)
                .withEndAction(() -> {
                    if (scrim.getParent() != null) parent.removeView(scrim);
                    if (callback != null) callback.onDismissed();
                }).start();
    }

    /**
     * One open window: tap the row to switch to it, tap the X to close it.
     *
     * Drawn like a row so the sheet stays one list, but with two targets - a switch and a
     * close - which is why it is not built with addRow().
     */
    private static void addWindowRow(final Activity activity, final LinearLayout parent,
                                     final ViewGroup scrim, final View scrimView,
                                     final Callback callback, final Window window,
                                     final boolean current, final int number) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 9),
                AppUi.dp(activity, 6), AppUi.dp(activity, 9));
        row.setBackground(AppUi.pressable(activity, Color.TRANSPARENT, 14f));

        TextView index = AppUi.text(activity, String.valueOf(number), 11.5f,
                current ? R.color.accent : R.color.on_bg_muted, true);
        index.setGravity(Gravity.CENTER);
        index.setBackground(AppUi.rounded(AppUi.color(activity,
                current ? R.color.accent_chip : R.color.surface2), 10f, activity));
        row.addView(index, new LinearLayout.LayoutParams(AppUi.dp(activity, 26),
                AppUi.dp(activity, 26)));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = AppUi.dp(activity, 12);
        row.addView(texts, textLp);

        TextView label = AppUi.text(activity, window.title, 14f,
                current ? R.color.accent : R.color.on_bg, false);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(label);

        if (current) {
            texts.addView(AppUi.text(activity,
                    activity.getString(R.string.menu_window_current), 11f,
                    R.color.on_bg_muted, false));
        }

        row.setOnClickListener(v -> {
            AppUi.press(v);
            // No onDismissed here: that callback means "the sheet closed by itself", and
            // the caller uses it to forget the sheet instance. Switching windows keeps
            // that bookkeeping the caller's job.
            remove(parent, scrimView);
            callback.onSwitchWindow(window.id);
        });

        TextView close = AppUi.text(activity, "\u2715", 14f, R.color.on_bg_muted, false);
        close.setPadding(AppUi.dp(activity, 10), AppUi.dp(activity, 6),
                AppUi.dp(activity, 10), AppUi.dp(activity, 6));
        close.setOnClickListener(v -> {
            AppUi.press(v);
            // The sheet is rebuilt by the activity, which is showing the new list.
            callback.onCloseWindow(window.id);
        });
        row.addView(close);

        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
