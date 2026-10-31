package ai.arena.webapp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small helpers that make a WebView shell feel like a native app. */
public final class AppUi {

    public static final int TAB_CHAT = 0;
    public static final int TAB_LEADERBOARD = 1;
    public static final int TAB_SEARCH = 2;
    public static final int TAB_UPDATE = 3;
    public static final int TAB_MORE = 4;

    private AppUi() {
    }

    // ------------------------------------------------------------ metrics / colors

    public static int dp(Context c, float value) {
        return (int) (value * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static int color(Context c, int res) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return c.getResources().getColor(res, c.getTheme());
        }
        //noinspection deprecation
        return c.getResources().getColor(res);
    }

    public static boolean isLandscape(Context c) {
        return c.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE
                && c.getResources().getConfiguration().smallestScreenWidthDp >= 480;
    }

    public static boolean isDark(Context c) {
        return (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    public static GradientDrawable rounded(int fill, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, radiusDp));
        d.setColor(fill);
        return d;
    }

    /** Rounded background + press ripple, the look used across the shell. */
    public static Drawable pressable(Context c, int fill, float radiusDp) {
        GradientDrawable base = rounded(fill, radiusDp, c);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return new RippleDrawable(
                    ColorStateList.valueOf(color(c, R.color.ripple)), base, null);
        }
        return base;
    }

    // ------------------------------------------------------------ text

    public static TextView text(Context c, String value, float sizeSp, int colorRes,
                                boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color(c, colorRes));
        if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setIncludeFontPadding(false);
        return tv;
    }

    // ------------------------------------------------------------ chrome bits

    /** The circular logo + name used in the top bar. */
    public static View brandView(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(c);
        logo.setImageResource(R.mipmap.ic_launcher);
        row.addView(logo, new LinearLayout.LayoutParams(dp(c, 26), dp(c, 26)));

        TextView name = text(c, c.getString(R.string.app_name), 18f, R.color.on_bg, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 9);
        row.addView(name, lp);
        return row;
    }

    /** Small pill that says "Update" with a dot when one is available. */
    public static TextView updatePill(Context c) {
        TextView pill = text(c, c.getString(R.string.app_update), 13f, R.color.pill_text, true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        pill.setBackground(pressable(c, color(c, R.color.pill_bg), 18f));
        return pill;
    }

    public static ImageButton iconButton(Context c, int drawableRes, String description) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(drawableRes);
        b.setBackground(pressable(c, android.graphics.Color.TRANSPARENT, 20f));
        b.setColorFilter(color(c, R.color.on_bg_muted));
        b.setContentDescription(description);
        int pad = dp(c, 10);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(color(c, R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f)));
        v.setLayoutParams(lp);
        return v;
    }

    // ------------------------------------------------------------ nav rail

    public static class NavItem {
        public final int tab;
        public final int icon;
        public final String label;
        public final View view;
        final TextView labelView;
        final ImageView iconView;

        NavItem(int tab, int icon, String label, View view, TextView labelView, ImageView iconView) {
            this.tab = tab;
            this.icon = icon;
            this.label = label;
            this.view = view;
            this.labelView = labelView;
            this.iconView = iconView;
        }
    }

    /**
     * Compact left navigation rail. Always visible, like a tablet app, and it hides
     * itself automatically on narrow phones (MainActivity falls back to the top bar).
     */
    public static View buildRail(Context c, java.util.List<NavItem> out, View.OnClickListener click) {
        LinearLayout rail = new LinearLayout(c);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setBackgroundColor(color(c, R.color.surface));
        int pad = dp(c, 10);
        rail.setPadding(pad, pad, pad, pad);

        addNavItem(c, rail, out, TAB_CHAT, R.drawable.ic_nav_chat,
                c.getString(R.string.nav_chat), click);
        addNavItem(c, rail, out, TAB_LEADERBOARD, R.drawable.ic_nav_leaderboard,
                c.getString(R.string.nav_leaderboard), click);
        addNavItem(c, rail, out, TAB_SEARCH, R.drawable.ic_nav_search,
                c.getString(R.string.nav_search), click);
        addNavItem(c, rail, out, TAB_UPDATE, R.drawable.ic_nav_update,
                c.getString(R.string.nav_update), click);
        addNavItem(c, rail, out, TAB_MORE, R.drawable.ic_nav_more,
                c.getString(R.string.nav_more), click);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(c, 76), ViewGroup.LayoutParams.MATCH_PARENT);
        rail.setLayoutParams(lp);
        return rail;
    }

    /** Bottom navigation for phones (the rail is used on tablets/landscape). */
    public static View buildBottomBar(Context c, java.util.List<NavItem> out,
                                      View.OnClickListener click) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(color(c, R.color.surface));
        bar.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));

        addBottomItem(c, bar, out, TAB_CHAT, R.drawable.ic_nav_chat,
                c.getString(R.string.nav_chat), click);
        addBottomItem(c, bar, out, TAB_LEADERBOARD, R.drawable.ic_nav_leaderboard,
                c.getString(R.string.nav_leaderboard), click);
        addBottomItem(c, bar, out, TAB_SEARCH, R.drawable.ic_nav_search,
                c.getString(R.string.nav_search), click);
        addBottomItem(c, bar, out, TAB_UPDATE, R.drawable.ic_nav_update,
                c.getString(R.string.nav_update), click);
        addBottomItem(c, bar, out, TAB_MORE, R.drawable.ic_nav_more,
                c.getString(R.string.nav_more), click);
        return bar;
    }

    private static void addBottomItem(Context c, LinearLayout bar, java.util.List<NavItem> out,
                                      int tab, int icon, String label,
                                      View.OnClickListener click) {
        LinearLayout item = new LinearLayout(c);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(c, 7), 0, dp(c, 5));
        item.setBackground(pressable(c, android.graphics.Color.TRANSPARENT, 14f));
        item.setClickable(true);
        item.setFocusable(true);

        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        iv.setColorFilter(color(c, R.color.on_bg_muted));
        item.addView(iv, new LinearLayout.LayoutParams(dp(c, 21), dp(c, 21)));

        TextView tv = text(c, label, 10.5f, R.color.on_bg_muted, false);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dp(c, 3);
        item.addView(tv, tvLp);

        item.setTag(tab);
        item.setOnClickListener(click);
        bar.addView(item, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        out.add(new NavItem(tab, icon, label, item, tv, iv));
    }

    private static void addNavItem(Context c, LinearLayout rail, java.util.List<NavItem> out,
                                   int tab, int icon, String label, View.OnClickListener click) {
        LinearLayout item = new LinearLayout(c);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(c, 10), 0, dp(c, 10));
        item.setBackground(pressable(c, android.graphics.Color.TRANSPARENT, 16f));
        item.setClickable(true);
        item.setFocusable(true);

        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        iv.setColorFilter(color(c, R.color.on_bg_muted));
        item.addView(iv, new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)));

        TextView tv = text(c, label, 10.5f, R.color.on_bg_muted, false);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dp(c, 5);
        item.addView(tv, tvLp);

        item.setTag(tab);
        item.setOnClickListener(click);

        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        itemLp.bottomMargin = dp(c, 4);
        rail.addView(item, itemLp);

        out.add(new NavItem(tab, icon, label, item, tv, iv));
    }

    public static void setActive(java.util.List<NavItem> items, int activeTab,
                                 Context c, boolean updateAvailable) {
        for (NavItem item : items) {
            boolean active = item.tab == activeTab;
            int tint = color(c, active ? R.color.accent : R.color.on_bg_muted);
            if (item.tab == TAB_UPDATE && updateAvailable && !active) {
                tint = color(c, R.color.accent);
            }
            if (item.iconView != null) item.iconView.setColorFilter(tint);
            if (item.labelView != null) {
                item.labelView.setTextColor(tint);
                item.labelView.setTypeface(Typeface.create(
                        active ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            }
            if (item.tab == TAB_UPDATE && updateAvailable) {
                // "Marked" update button: accent tint + soft accent chip behind it.
                tint = color(c, R.color.accent);
                if (item.iconView != null) item.iconView.setColorFilter(tint);
                if (item.labelView != null) {
                    item.labelView.setTextColor(tint);
                    item.labelView.setTypeface(Typeface.create("sans-serif-medium",
                            Typeface.NORMAL));
                }
                if (item.view != null) {
                    item.view.setBackground(pressable(c,
                            color(c, R.color.accent_soft), 16f));
                }
                continue;
            }
            if (item.view != null) {
                item.view.setBackground(pressable(c, active
                        ? color(c, R.color.nav_active_bg) : android.graphics.Color.TRANSPARENT, 16f));
            }
            // The update entry carries a dot while a newer build exists.
            if (item.tab == TAB_UPDATE) {
                item.view.setContentDescription(updateAvailable
                        ? c.getString(R.string.nav_update_badge) : c.getString(R.string.nav_update));
            }
        }
    }

    // ------------------------------------------------------------ update card

    /**
     * The "App Update" item, styled as a card with a status dot so it visibly
     * announces itself when a newer release exists.
     */
    public static View updateCard(Context c, String versionName, boolean available,
                                  String statusText, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        card.setBackground(pressable(c, color(c, available ? R.color.accent_soft : R.color.surface2), 18f));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(click);

        FrameLayout iconWrap = new FrameLayout(c);
        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.ic_nav_update);
        icon.setColorFilter(color(c, available ? R.color.accent : R.color.on_bg_muted));
        int pad = dp(c, 10);
        icon.setPadding(pad, pad, pad, pad);
        icon.setBackground(rounded(color(c, available ? R.color.accent_chip : R.color.surface), 14f, c));
        iconWrap.addView(icon, new FrameLayout.LayoutParams(dp(c, 42), dp(c, 42)));

        if (available) {
            View dot = new View(c);
            dot.setBackground(rounded(color(c, R.color.accent), 6f, c));
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(c, 10), dp(c, 10));
            dotLp.gravity = Gravity.END | Gravity.TOP;
            iconWrap.addView(dot, dotLp);
        }
        card.addView(iconWrap);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = dp(c, 14);
        card.addView(texts, textLp);

        TextView title = text(c, c.getString(R.string.app_update), 15f, R.color.on_bg, true);
        texts.addView(title);

        String sub = available && versionName != null && !versionName.isEmpty()
                ? c.getString(R.string.update_available_short, versionName)
                : statusText;
        TextView subtitle = text(c, sub, 12.5f,
                available ? R.color.accent : R.color.on_bg_muted, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(c, 3);
        texts.addView(subtitle, subLp);

        TextView chevron = text(c, "›", 22f, R.color.on_bg_muted, false);
        card.addView(chevron);

        return card;
    }

    // ------------------------------------------------------------ misc

    public static ScrollView scroll(Context c, View child) {
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.addView(child, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }
}
