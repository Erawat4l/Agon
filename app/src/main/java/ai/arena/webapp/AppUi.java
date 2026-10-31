package ai.arena.webapp;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native UI kit: palette, icons, animated nav, cards, press feedback. */
public final class AppUi {

    public static final int TAB_CHAT = 0;
    public static final int TAB_LEADERBOARD = 1;
    public static final int TAB_SEARCH = 2;
    public static final int TAB_UPDATE = 3;
    public static final int TAB_MORE = 4;

    /** Time for nav/expander transitions — quick enough to feel instant. */
    public static final int ANIM_FAST = 180;
    public static final int ANIM_MED = 240;

    public static final String PREFS = "arena_ui";
    public static final String K_APPEARANCE = "appearance";
    public static final int APPEARANCE_SYSTEM = 0;
    public static final int APPEARANCE_LIGHT = 1;
    public static final int APPEARANCE_DARK = 2;

    private AppUi() {
    }

    // ------------------------------------------------------------ appearance

    public static int appearance(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(K_APPEARANCE, APPEARANCE_SYSTEM);
    }

    /**
     * Night mode currently in effect. When the user picked Light/Dark, MainActivity has
     * already forced that into the resources via attachBaseContext(), so reading the
     * configuration is always correct - including inside WebViews.
     *
     * Must be asked of the activity/application context: a configuration context made
     * by wrap() carries the forced uiMode, a bare base context does not, so this is
     * only guarded against a null.
     */
    public static boolean isDark(Context c) {
        if (c == null) return false;
        return (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int color(Context c, int res) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return c.getResources().getColor(res, c.getTheme());
        }
        //noinspection deprecation
        return c.getResources().getColor(res);
    }

    public static void setAppearance(Context c, int mode) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(K_APPEARANCE, mode).apply();
    }

    /** Context whose resources honour the light/dark/system preference. */
    public static Context wrap(Context base) {
        int mode = appearance(base);
        if (mode == APPEARANCE_SYSTEM) return base;
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (mode == APPEARANCE_DARK
                ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(config);
    }

    // ------------------------------------------------------------ metrics

    public static int dp(Context c, float value) {
        return (int) (value * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static boolean isTablet(Context c) {
        return c.getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    // ------------------------------------------------------------ drawables

    public static GradientDrawable rounded(int fill, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, radiusDp));
        d.setColor(fill);
        return d;
    }

    public static Drawable pressable(Context c, int fill, float radiusDp) {
        GradientDrawable base = rounded(fill, radiusDp, c);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return new RippleDrawable(ColorStateList.valueOf(color(c, R.color.ripple)), base, null);
        }
        return base;
    }

    public static TextView text(Context c, String value, float sizeSp, int colorRes, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color(c, colorRes));
        if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setIncludeFontPadding(false);
        return tv;
    }

    public static ImageView icon(Context c, int res, int colorRes, float sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(color(c, colorRes));
        iv.setLayoutParams(new ViewGroup.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    // ------------------------------------------------------------ animation helpers

    public static void fadeIn(View v) {
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 10));
        v.animate().alpha(1f).translationY(0f)
                .setDuration(ANIM_MED).setInterpolator(new DecelerateInterpolator()).start();
    }

    public static void pop(View v) {
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(ANIM_FAST)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    public static void press(View v) {
        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).start();
        v.postDelayed(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start(), 90);
    }

    // ------------------------------------------------------------ nav definitions

    public static final class NavItem {
        public final int tab;
        public final int icon;
        public final String label;
        public final View view;
        final TextView labelView;
        final ImageView iconView;
        final View dot;

        NavItem(int tab, int icon, String label, View view, TextView labelView, ImageView iconView,
                View dot) {
            this.tab = tab;
            this.icon = icon;
            this.label = label;
            this.view = view;
            this.labelView = labelView;
            this.iconView = iconView;
            this.dot = dot;
        }
    }

    public static final class Nav {
        public final LinearLayout bar;
        public final java.util.List<NavItem> items = new java.util.ArrayList<>();

        Nav(LinearLayout bar) {
            this.bar = bar;
        }
    }

    private static int tabLabel(int tab) {
        switch (tab) {
            case TAB_LEADERBOARD: return R.string.nav_leaderboard;
            case TAB_SEARCH: return R.string.nav_search;
            case TAB_UPDATE: return R.string.nav_update;
            case TAB_MORE: return R.string.nav_more;
            default: return R.string.nav_chat;
        }
    }

    /** Compact collapsed height of the nav bar (icons only). */
    public static int collapsedHeight(Context c) {
        return dp(c, 54);
    }

    public static int expandedHeight(Context c) {
        return dp(c, 64);
    }

    /** Icon+label navigation bar used at the bottom of phones and the left rail. */
    public static Nav buildNav(Context c, boolean vertical, View.OnClickListener click) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(color(c, R.color.surface));
        int p = dp(c, vertical ? 8 : 4);
        bar.setPadding(p, p, p, p);

        Nav nav = new Nav(bar);
        // Update lives in the More sheet, not in the bar: the bar is the four places
        // you actually navigate to.
        int[] tabs = {TAB_CHAT, TAB_LEADERBOARD, TAB_SEARCH, TAB_MORE};
        for (int tab : tabs) {
            nav.items.add(addNavItem(c, bar, nav, tab, NavIcons.forTab(tab), vertical, click));
        }
        return nav;
    }

    private static NavItem addNavItem(Context c, LinearLayout bar, Nav nav, int tab, int iconRes,
                                      boolean vertical, View.OnClickListener click) {
        LinearLayout item = new LinearLayout(c);
        item.setOrientation(vertical ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(c, 6), 0, dp(c, 6));
        item.setBackground(pressable(c, Color.TRANSPARENT, 14f));
        item.setClickable(true);
        item.setFocusable(true);

        FrameLayout iconWrap = new FrameLayout(c);
        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setColorFilter(color(c, R.color.on_bg_muted));
        iconWrap.addView(iv, new FrameLayout.LayoutParams(dp(c, 21), dp(c, 21)));

        View dot = new View(c);
        dot.setBackground(rounded(color(c, R.color.accent), 5f, c));
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(c, 8), dp(c, 8));
        dotLp.gravity = Gravity.END | Gravity.TOP;
        dotLp.rightMargin = -dp(c, 4);
        dot.setVisibility(View.GONE);
        iconWrap.addView(dot, dotLp);
        item.addView(iconWrap, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));

        TextView label = text(c, c.getString(tabLabel(tab)), 10.5f, R.color.on_bg_muted, false);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = vertical
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (vertical) {
            labelLp.leftMargin = dp(c, 10);
        } else {
            labelLp.topMargin = dp(c, 3);
        }
        label.setVisibility(vertical ? View.VISIBLE : View.GONE);
        item.addView(label, labelLp);

        item.setTag(tab);
        item.setOnClickListener(v -> {
            press(v);
            click.onClick(v);
        });

        LinearLayout.LayoutParams itemLp = vertical
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (vertical) itemLp.bottomMargin = dp(c, 2);
        bar.addView(item, itemLp);

        return new NavItem(tab, iconRes, c.getString(tabLabel(tab)), item, label, iv, dot);
    }

    /** Applies active tint, the update dot and (optionally) label visibility. */
    public static void styleNav(Context c, Nav nav, int activeTab, boolean updateAvailable,
                                boolean labelsVisible, boolean animateLabels) {
        for (NavItem item : nav.items) {
            boolean active = item.tab == activeTab;
            // Update has no tab any more, so the More entry carries the signal.
            boolean flagged = item.tab == TAB_MORE && updateAvailable;
            int tint = color(c, (active || flagged) ? R.color.accent : R.color.on_bg_muted);

            item.iconView.setColorFilter(tint);
            item.iconView.setScaleX(active ? 1.08f : 1f);
            item.iconView.setScaleY(active ? 1.08f : 1f);

            item.labelView.setTextColor(tint);
            item.labelView.setTypeface(Typeface.create(
                    (active || flagged) ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            item.labelView.setText(flagged && item.tab != TAB_UPDATE
                    ? c.getString(R.string.nav_more_update) : item.label);

            int target = labelsVisible ? View.VISIBLE : View.GONE;
            if (item.labelView.getVisibility() != target) {
                if (animateLabels) {
                    item.labelView.animate().cancel();
                    item.labelView.setVisibility(View.VISIBLE);
                    item.labelView.animate().alpha(labelsVisible ? 1f : 0f)
                            .setDuration(ANIM_FAST).start();
                    if (!labelsVisible) {
                        item.labelView.postDelayed(
                                () -> item.labelView.setVisibility(View.GONE), ANIM_FAST);
                    }
                } else {
                    item.labelView.setVisibility(target);
                    item.labelView.setAlpha(labelsVisible ? 1f : 0f);
                }
            }

            item.dot.setVisibility(flagged ? View.VISIBLE : View.GONE);
            item.view.setBackground(pressable(c, active
                    ? color(c, R.color.nav_active_bg) : Color.TRANSPARENT, 14f));
        }
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(color(c, R.color.divider));
        return v;
    }

    /** Bottom-sheet container with its own scrim; animated in and out. */
    public static FrameLayout sheetScrim(Context c, View.OnClickListener dismiss) {
        FrameLayout scrim = new FrameLayout(c);
        scrim.setBackgroundColor(color(c, R.color.scrim));
        scrim.setOnClickListener(dismiss);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(ANIM_MED).start();
        return scrim;
    }

    /** Slides a sheet up from the bottom. */
    public static void slideUp(View sheet) {
        sheet.post(() -> {
            sheet.setTranslationY(sheet.getHeight() == 0
                    ? dp(sheet.getContext(), 320) : sheet.getHeight());
            sheet.animate().translationY(0f).setDuration(ANIM_MED)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    public static void slideDownAndRemove(View sheet, ViewGroup parent) {
        sheet.animate().translationY(sheet.getHeight()).setDuration(ANIM_FAST)
                .withEndAction(() -> {
                    if (sheet.getParent() != null) parent.removeView(sheet);
                }).start();
    }
}
