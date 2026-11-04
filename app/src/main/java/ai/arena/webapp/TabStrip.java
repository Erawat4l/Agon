package ai.arena.webapp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The browser strip above the page.
 *
 * <pre>
 *   &lt;   [ Chat ] [ New chat 2 ]   &gt;   (+)
 * </pre>
 *
 * The model is a browser's, not a menu's:
 *
 *  - every chip is a <em>conversation</em>, in the order the user made them;
 *  - {@code +} opens another one, and it opens empty rather than on the chat that was
 *    already on screen;
 *  - {@code <} and {@code >} move along that list, and dim at the ends. They are not page
 *    history: page history inside one tab is what the system Back button is for, and a
 *    browser-shaped arrow that goes "back" to a page you have already left is not what
 *    the arrow next to a tab strip means. A long press on either arrow still asks the page
 *    to go back/forward, so nothing was lost.
 *
 * There is deliberately no chip for Leaderboard or Search: those are the bottom bar's
 * destinations. The strip starts as just the arrows and a +, and the first + adds the
 * second tab beside the one that is already there.
 *
 * Everything visual lives here so the strip matches the app's palette in both themes.
 */
public final class TabStrip {

    /** What the strip needs from the activity; it knows nothing about WebViews itself. */
    public interface Host {
        /** The id of the tab on screen. */
        int currentTabId();

        /** Tab ids in display order. */
        java.util.List<Integer> tabIds();

        /** Name to show on a chip. */
        String tabName(int tabId);

        /** False at the last tab / first tab, so the arrows can dim. */
        boolean canSelectPrevious();

        boolean canSelectNext();

        /** False when another tab would exceed what the phone can keep warm. */
        boolean canOpenTab();

        void onSelectTab(int tabId);

        void onCloseTab(int tabId);

        /** The left arrow: the previous tab. */
        void onPreviousTab();

        /** The right arrow: the next tab. */
        void onNextTab();

        /** Long press on an arrow: page history, the thing the arrows no longer do. */
        void onPageBack();

        void onPageForward();

        void onNewTab();
    }

    private static final float CHIP_RADIUS_DP = 9f;
    private static final float BUTTON_RADIUS_DP = 10f;
    private static final int CHIP_MAX_WIDTH_DP = 132;

    private final Context context;
    private final Host host;

    private final LinearLayout root;
    private final ImageView back;
    private final ImageView forward;
    private final ImageView plus;
    private final HorizontalScrollView scroller;
    private final LinearLayout tabsBox;

    /** What the row currently shows, so an unchanged strip is left alone (no flicker). */
    private String signature = "";

    public TabStrip(Context context, Host host) {
        this.context = context;
        this.host = host;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackground(AppUi.chromeBg(context));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(6);
        row.setPadding(padH, dp(4), padH, dp(4));
        column.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        back = arrowButton(R.drawable.ic_nav_back, R.string.strip_back, true,
                () -> host.onPreviousTab(), () -> host.onPageBack());
        row.addView(back, new LinearLayout.LayoutParams(buttonSize(), buttonSize()));

        // Only the tab list scrolls. With five chips on a phone the arrows and the + would
        // otherwise be pushed off the edge precisely when they are needed.
        scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipToPadding(false);
        tabsBox = new LinearLayout(context);
        tabsBox.setOrientation(LinearLayout.HORIZONTAL);
        tabsBox.setGravity(Gravity.CENTER_VERTICAL);
        scroller.addView(tabsBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                0, buttonSize(), 1f);
        scrollLp.leftMargin = dp(1);
        scrollLp.rightMargin = dp(1);
        row.addView(scroller, scrollLp);

        forward = arrowButton(R.drawable.ic_nav_forward, R.string.strip_forward, true,
                () -> host.onNextTab(), () -> host.onPageForward());
        row.addView(forward, new LinearLayout.LayoutParams(buttonSize(), buttonSize()));

        plus = arrowButton(R.drawable.ic_nav_plus, R.string.strip_new_tab, false,
                () -> host.onNewTab(), null);
        plus.setOnLongClickListener(v -> {
            if (host.canOpenTab()) return false;
            tap(v);
            toast(context.getString(R.string.window_limit));
            return true;
        });
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(
                buttonSize(), buttonSize());
        plusLp.leftMargin = dp(4);
        row.addView(plus, plusLp);

        // The hairline belongs to the strip, not to the page: it is what separates browser
        // chrome from content, and it has to survive the page painting its own edges.
        View hairline = new View(context);
        hairline.setBackgroundColor(AppUi.color(context, R.color.divider));
        column.addView(hairline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f))));

        root = column;
        setArrowsEnabled(false, false);
    }

    public View view() {
        return root;
    }

    /** Dims an arrow that would go nowhere, without removing it and reflowing the row. */
    public void setArrowsEnabled(boolean hasPrevious, boolean hasNext) {
        back.setAlpha(hasPrevious ? 1f : 0.28f);
        forward.setAlpha(hasNext ? 1f : 0.28f);
    }

    /**
     * Re-applies the palette after a light/dark switch.
     *
     * The chip backgrounds and the + circle are built from colours at construction time,
     * so a switch has to rebuild them; clearing the signature is what makes refresh()
     * redraw instead of deciding nothing changed.
     */
    public void onThemeChanged() {
        signature = "";
        refresh();
    }

    /** Reflects whether one more tab would fit. */
    public void setCanOpenTab(boolean canOpen) {
        plus.setAlpha(canOpen ? 1f : 0.35f);
    }

    /**
     * Redraws the row.
     *
     * Rebuilt only when the *set* of tabs or the current tab changed. Page finishes and
     * every other harmless callback call this too; rebuilding on those would make the
     * chips blink and lose their scroll position.
     */
    public void refresh() {
        if (tabsBox == null || host == null) return;
        java.util.List<Integer> ids = host.tabIds();
        int current = host.currentTabId();
        StringBuilder key = new StringBuilder().append(current);
        for (Integer id : ids) key.append('|').append(id).append('=').append(host.tabName(id));
        String next = key.toString();

        if (!next.equals(signature)) {
            boolean first = signature.isEmpty();
            signature = next;
            buildChips(ids, current);
            if (!first) AppUi.pop(tabsBox);
        } else {
            paintSelection(current);
        }
        scrollToCurrent(current);
        setCanOpenTab(host.canOpenTab());
        setArrowsEnabled(host.canSelectPrevious(), host.canSelectNext());
    }

    private void buildChips(java.util.List<Integer> ids, int current) {
        tabsBox.removeAllViews();
        for (Integer id : ids) {
            TextView chip = new TextView(context);
            chip.setText(host.tabName(id));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setMaxWidth(dp(CHIP_MAX_WIDTH_DP));
            chip.setIncludeFontPadding(false);
            chip.setGravity(Gravity.CENTER);
            chip.setTag(id);
            chip.setPadding(dp(11), dp(6), dp(11), dp(6));
            final int tabId = id;
            chip.setOnClickListener(v -> {
                tap(v);
                host.onSelectTab(tabId);
            });
            // Long press closes a tab. Never the last one: an empty strip with no page
            // behind it is a state the app cannot show.
            if (ids.size() > 1) {
                chip.setOnLongClickListener(v -> {
                    tap(v);
                    host.onCloseTab(tabId);
                    return true;
                });
                chip.setContentDescription(context.getString(
                        R.string.strip_close_tab) + ": " + host.tabName(id));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(5);
            tabsBox.addView(chip, lp);
        }
        paintSelection(current);
    }

    /** Colours the chips for the page on screen: accent outline on one, muted text on rest. */
    private void paintSelection(int current) {
        for (int i = 0; i < tabsBox.getChildCount(); i++) {
            View child = tabsBox.getChildAt(i);
            if (!(child instanceof TextView) || !(child.getTag() instanceof Integer)) continue;
            TextView chip = (TextView) child;
            boolean active = (Integer) chip.getTag() == current;
            chip.setTextColor(AppUi.color(context,
                    active ? R.color.on_bg : R.color.on_bg_muted));
            chip.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            chip.setBackground(chipBackground(active));
            chip.setAlpha(active ? 1f : 0.92f);
        }
    }

    /** Filled, accent-outlined for the page on screen; plain and pressable for the others. */
    private Drawable chipBackground(boolean active) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(CHIP_RADIUS_DP));
        if (active) {
            shape.setColor(AppUi.color(context, R.color.accent_soft));
            shape.setStroke(Math.max(1, dp(0.8f)), AppUi.color(context, R.color.accent_chip));
        } else {
            shape.setColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return new RippleDrawable(
                    ColorStateList.valueOf(AppUi.color(context, R.color.ripple)), shape, null);
        }
        return shape;
    }

    /** Keeps the current chip in view after a switch, without jumping the list about. */
    private void scrollToCurrent(int current) {
        if (scroller == null || tabsBox == null) return;
        View match = null;
        for (int i = 0; i < tabsBox.getChildCount(); i++) {
            View child = tabsBox.getChildAt(i);
            if (child.getTag() instanceof Integer && (Integer) child.getTag() == current) {
                match = child;
                break;
            }
        }
        if (match == null) return;
        final View target = match;
        scroller.post(() -> {
            int start = scroller.getScrollX();
            int end = start + scroller.getWidth();
            if (target.getLeft() >= start + dp(6) && target.getRight() <= end - dp(6)) {
                return; // already fully visible: do not move the list under the finger
            }
            scroller.smoothScrollTo(Math.max(0, target.getLeft() - dp(10)), 0);
        });
    }

    /**
     * One arrow control.
     *
     * Tap and long press are different things here - along the tabs, and through the page's
     * own history - so both are wired, and the long press carries its own description for
     * anyone using TalkBack.
     */
    private ImageView arrowButton(int icon, int description, boolean muted,
                                  final Runnable onClick, final Runnable onLongClick) {
        ImageView v = new ImageView(context);
        v.setImageResource(icon);
        v.setColorFilter(AppUi.color(context, muted ? R.color.on_bg : R.color.on_accent));
        v.setContentDescription(context.getString(description));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int inset = dp(muted ? 7 : 9);
        v.setPadding(inset, inset, inset, inset);
        if (muted) {
            v.setBackground(AppUi.pressable(context, Color.TRANSPARENT, BUTTON_RADIUS_DP));
        } else {
            // The + is the one filled control in the strip: it is the action, the rest are
            // navigation, and the accent circle is how the app marks an action elsewhere.
            v.setBackground(pressableCircle(AppUi.color(context, R.color.accent)));
        }
        v.setOnClickListener(view -> {
            tap(view);
            onClick.run();
        });
        if (onLongClick != null) {
            v.setOnLongClickListener(view -> {
                tap(view);
                onLongClick.run();
                return true;
            });
        }
        return v;
    }

    /**
     * Press feedback for one tap.
     *
     * The shrink is the same one the bottom bar uses, plus a keyboard-scale haptic: these
     * are chrome controls, and on a phone a control that answers with nothing feels
     * broken even when it worked.
     */
    private void tap(View v) {
        AppUi.press(v);
        try {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (RuntimeException ignored) {
            // A device with haptics switched off globally; the visual press still shows.
        }
    }

    private Drawable pressableCircle(int fill) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(fill);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return new RippleDrawable(
                    ColorStateList.valueOf(AppUi.color(context, R.color.ripple)), shape, null);
        }
        return shape;
    }

    /** Toasts belong to the activity, not the strip, so this is the one-way call out. */
    private void toast(String text) {
        try {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT)
                    .show();
        } catch (RuntimeException ignored) {
            // a toast on a finishing activity is not worth crashing for
        }
    }

    private int buttonSize() {
        return dp(36);
    }

    private int dp(float value) {
        return AppUi.dp(context, value);
    }
}
