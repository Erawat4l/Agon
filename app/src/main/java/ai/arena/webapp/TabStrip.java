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
 * The tabs divide the width between the arrows evenly - one tab fills it, two split it -
 * and each one carries a cross to close it. The whole strip can be switched off in
 * Settings -> For developers ("Show the tab bar above the page"), which gives the page the
 * screen back.
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

    private final Context context;
    private final Host host;

    private final LinearLayout root;
    private final ImageView back;
    private final ImageView forward;
    private final ImageView plus;
    private final LinearLayout tabsBox;

    /** What the row currently shows, so an unchanged strip is left alone (no flicker). */
    private String signature = "";

    public TabStrip(Context context, Host host) {
        this.context = context;
        this.host = host;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackground(AppUi.chromeBg(context));
        // The whole strip is chrome. A WebView next door lets a long press select and copy,
        // and inside the strip that would let a tab's name be highlighted like page text.
        column.setLongClickable(false);
        column.setHapticFeedbackEnabled(false);
        column.setFocusable(false);

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

        // The chips share the width between the arrows rather than scrolling: one tab fills
        // the space, two split it, three thirds. A scroller made a tab's width depend on its
        // name, which is what left a strip of one wide chip and one narrow one.
        tabsBox = new LinearLayout(context);
        tabsBox.setOrientation(LinearLayout.HORIZONTAL);
        tabsBox.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                0, buttonSize(), 1f);
        tabsLp.leftMargin = dp(1);
        tabsLp.rightMargin = dp(1);
        row.addView(tabsBox, tabsLp);

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

    /**
     * Kept for the host, which still reports where the current tab sits.
     *
     * It no longer changes anything on screen: the arrows are tab navigation, and a tab
     * list does not have ends the way page history does - a dimmed ">" next to a full row
     * of tabs said "you cannot go further" when what it meant was "this is the last chip",
     * and it made the two arrows look like different kinds of control from each other.
     * Both arrows now look identical, always. A press that really has nowhere to go still
     * says so (see stepTab in MainActivity), so nothing is silently swallowed.
     */
    public void setArrowsEnabled(boolean hasPrevious, boolean hasNext) {
        back.setAlpha(1f);
        forward.setAlpha(1f);
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
            // A chip appearing gets a small settle, but the row itself is not scaled: a
            // scale on the container fights the equal-width weights above.
            if (!first) {
                for (int i = 0; i < tabsBox.getChildCount(); i++) {
                    View chip = tabsBox.getChildAt(i);
                    if ((Integer) chip.getTag() == current) AppUi.fadeIn(chip);
                }
            }
        } else {
            paintSelection(current);
        }
        setCanOpenTab(host.canOpenTab());
        setArrowsEnabled(host.canSelectPrevious(), host.canSelectNext());
    }

    /**
     * Draws the chips.
     *
     * Every tab gets the same share of the row - one tab fills it, two split it, three
     * thirds - because a tab strip whose chips are sized to their text leaves ragged gaps
     * and makes a short name look like a different kind of thing from a long one. The text
     * is the part that gives way (ellipsised), never the width.
     *
     * Each chip carries its own close cross, on the right, as a browser tab does. It is
     * only offered while more than one tab is open: closing the last one would leave the
     * app with no page.
     */
    private void buildChips(java.util.List<Integer> ids, int current) {
        tabsBox.removeAllViews();
        boolean closable = ids.size() > 1;
        for (Integer id : ids) {
            final int tabId = id;
            boolean active = tabId == current;

            LinearLayout chip = new LinearLayout(context);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setTag(tabId);
            chip.setPadding(dp(9), dp(5), closable ? dp(2) : dp(9), dp(5));
            chip.setBackground(chipBackground(active));
            chip.setContentDescription(host.tabName(tabId));
            chip.setOnClickListener(v -> {
                tap(v);
                host.onSelectTab(tabId);
            });

            TextView label = new TextView(context);
            // Browser chrome, not page content: a long press must never start a text
            // selection or the copy callout over the strip.
            label.setTextIsSelectable(false);
            label.setLongClickable(false);
            label.setText(host.tabName(tabId));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setIncludeFontPadding(false);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setTextColor(AppUi.color(context, active ? R.color.on_bg : R.color.on_bg_muted));
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            // The label must not swallow the tap: the whole chip is the target.
            label.setClickable(false);
            label.setFocusable(false);
            chip.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (closable) {
                ImageView close = new ImageView(context);
                close.setImageResource(R.drawable.ic_tab_close);
                close.setColorFilter(AppUi.color(context, R.color.on_bg_muted));
                close.setScaleType(ImageView.ScaleType.FIT_CENTER);
                close.setContentDescription(context.getString(R.string.strip_close_tab));
                int pad = dp(4);
                close.setPadding(pad, pad, pad, pad);
                close.setBackground(AppUi.pressable(context, Color.TRANSPARENT, 7f));
                close.setOnClickListener(v -> {
                    tap(v);
                    host.onCloseTab(tabId);
                });
                chip.addView(close, new LinearLayout.LayoutParams(dp(22), dp(22)));
            }

            // weight 1 each: equal shares of whatever width the row has, and the same share
            // for every tab however long its name is. Full strip height, so one tab is a
            // strip-high tab rather than a label floating in the middle of it.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.rightMargin = dp(5);
            tabsBox.addView(chip, lp);
        }
        paintSelection(current);
    }

    /** Colours the chips for the page on screen: accent outline on one, muted text on rest. */
    private void paintSelection(int current) {
        for (int i = 0; i < tabsBox.getChildCount(); i++) {
            View chip = tabsBox.getChildAt(i);
            if (!(chip.getTag() instanceof Integer)) continue;
            boolean active = (Integer) chip.getTag() == current;
            chip.setBackground(chipBackground(active));
            chip.setAlpha(active ? 1f : 0.92f);
            if (chip instanceof LinearLayout) {
                LinearLayout box = (LinearLayout) chip;
                for (int c = 0; c < box.getChildCount(); c++) {
                    View child = box.getChildAt(c);
                    if (child instanceof TextView) {
                        TextView label = (TextView) child;
                        label.setTextColor(AppUi.color(context,
                                active ? R.color.on_bg : R.color.on_bg_muted));
                        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                                active ? android.graphics.Typeface.BOLD
                                        : android.graphics.Typeface.NORMAL));
                    }
                }
            }
        }
    }

    /**
     * A chip's background: accent outline when it is the page on screen, quiet when not.
     *
     * A stroke needs something to sit on, so the inactive chip is filled with the bar's own
     * surface rather than being left transparent: on a dark bar a transparent chip with no
     * outline read as plain text, and the strip looked like a row of labels instead of a
     * row of tabs.
     */
    private Drawable chipBackground(boolean active) {
        GradientDrawable shape = roundedShape(dp(CHIP_RADIUS_DP));
        if (active) {
            shape.setColor(AppUi.color(context, R.color.accent_soft));
            shape.setStroke(Math.max(1, dp(0.9f)), AppUi.color(context, R.color.accent_chip));
        } else {
            shape.setColor(AppUi.color(context, R.color.surface2));
            shape.setStroke(Math.max(1, dp(0.6f)), AppUi.color(context, R.color.divider));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // The mask is what keeps the ripple inside the rounded corners.
            return new RippleDrawable(
                    ColorStateList.valueOf(AppUi.color(context, R.color.ripple)),
                    shape, roundedShape(dp(CHIP_RADIUS_DP)));
        }
        return shape;
    }

    private GradientDrawable roundedShape(float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
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
        // No scale animation on a tab: scaling a chip that shares the row makes its
        // neighbours jump, and on a wide chip the shrink is visible as the text sliding.
        // The ripple is the feedback (see chipBackground), plus the haptic below.
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
