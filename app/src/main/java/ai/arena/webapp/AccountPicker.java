package ai.arena.webapp;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A Google-style account picker drawn with native Android views.
 *
 * This is the screen a real app shows when you tap "Continue with Google": a white
 * card, the provider mark, "Choose an account", and one row per account that already
 * exists on the phone, plus "Use another account". It is deliberately NOT a WebView:
 * opening provider HTML to "pick" an account is what made the flow feel like a
 * browser. The real provider page is only opened after an account is chosen.
 */
public final class AccountPicker {

    /** What the picker reports back. */
    public interface Host {
        /** An account on the device was chosen (already signed in on this phone). */
        void onAccountChosen(String email, String displayName);

        /** "Use another account" - the caller should open the provider's own page. */
        void onUseAnotherAccount();

        void onCancelled();
    }

    private final Activity activity;
    private final ViewGroup parent;
    private final Host host;
    private FrameLayout root;
    private boolean google;

    public AccountPicker(Activity activity, ViewGroup parent, Host host) {
        this.activity = activity;
        this.parent = parent;
        this.host = host;
    }

    public boolean isShowing() {
        return root != null && root.getParent() != null;
    }

    /** Shows the picker. {@code google} selects the provider branding and account type. */
    public void show(boolean google) {
        if (isShowing()) return;
        this.google = google;

        root = new FrameLayout(activity);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> dismiss());
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(AppUi.ANIM_FAST).start();

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(AppUi.rounded(Color.WHITE, 24f, activity));
        card.setPadding(AppUi.dp(activity, 22), AppUi.dp(activity, 24),
                AppUi.dp(activity, 22), AppUi.dp(activity, 18));
        card.setOnClickListener(v -> {
        });
        // Native pickers are light surfaces even in a dark app (as Google's own is).
        card.setElevation(AppUi.dp(activity, 12));

        ImageView logo = new ImageView(activity);
        logo.setImageResource(google ? R.drawable.ic_google : R.drawable.ic_github);
        logo.setColorFilter(Color.BLACK);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                AppUi.dp(activity, 34), AppUi.dp(activity, 34));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(logo, logoLp);

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.signin_choose));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 21f);
        title.setTextColor(0xFF202124);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = AppUi.dp(activity, 14);
        card.addView(title, titleLp);

        TextView sub = new TextView(activity);
        sub.setText(activity.getString(R.string.signin_choose_sub));
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        sub.setTextColor(0xFF5F6368);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = AppUi.dp(activity, 6);
        subLp.bottomMargin = AppUi.dp(activity, 16);
        card.addView(sub, subLp);

        int found = 0;
        if (google) {
            found = addDeviceAccounts(card);
        }
        if (found == 0) {
            TextView empty = new TextView(activity);
            // Not an error: the chooser below still signs you in through the provider's
            // own page, which lists the account on its side.
            empty.setText(activity.getString(R.string.signin_no_accounts_soft));
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setTextColor(0xFF5F6368);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(AppUi.dp(activity, 6), AppUi.dp(activity, 10),
                    AppUi.dp(activity, 6), AppUi.dp(activity, 14));
            card.addView(empty);
        }

        card.addView(row(google ? activity.getString(R.string.signin_add_account)
                        : activity.getString(R.string.signin_use_account,
                        activity.getString(R.string.signin_github)),
                null, true, v -> {
                    dismiss(false);
                    host.onUseAnotherAccount();
                }));

        TextView note = new TextView(activity);
        note.setText(activity.getString(R.string.signin_native_note));
        note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        note.setTextColor(0xFF80868B);
        note.setPadding(0, AppUi.dp(activity, 10), 0, 0);
        card.addView(note);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = AppUi.dp(activity, 20);
        cardLp.rightMargin = AppUi.dp(activity, 20);
        root.addView(card, cardLp);

        parent.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate().scaleX(1f).scaleY(1f).setDuration(AppUi.ANIM_MED).start();
    }

    /** Adds one row per Google account on the device. Returns how many were added. */
    private int addDeviceAccounts(LinearLayout card) {
        String[] accounts = accountsOnDevice();
        if (accounts.length == 0) return 0;

        TextView label = new TextView(activity);
        label.setText(activity.getString(R.string.signin_device_account));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f);
        label.setTextColor(0xFF5F6368);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(AppUi.dp(activity, 4), 0, 0, AppUi.dp(activity, 6));
        card.addView(label);

        for (final String email : accounts) {
            card.addView(row(email, initials(email), false, v -> {
                dismiss(false);
                host.onAccountChosen(email, email);
            }));
        }
        return accounts.length;
    }

    /**
     * Google accounts known to Android, if any.
     *
     * Every lookup here can throw when the app has no GET_ACCOUNTS grant on a recent
     * Android version, so each one is guarded and the answer is simply "the accounts we
     * could see". Finding none is not an error: the picker then offers "Continue with
     * Google", which is why signing in no longer depends on a Google account being set
     * up on the phone.
     */
    private String[] accountsOnDevice() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();

        AccountManager am = null;
        try {
            am = AccountManager.get(activity);
        } catch (Exception ignored) {
            // no account service at all: the empty list below is a valid answer
        }
        if (am != null) {
            // 1. The supported lookup for Google accounts.
            try {
                for (Account a : am.getAccountsByType("com.google")) {
                    addName(names, a);
                }
            } catch (Exception ignored) {
                // no permission: try the broader lookup next
            }
            // 2. Anything else whose type mentions Google (some ROMs use a variant type).
            try {
                for (Account a : am.getAccounts()) {
                    if (a.type != null && a.type.toLowerCase(java.util.Locale.ROOT)
                            .contains("google")) {
                        addName(names, a);
                    }
                }
            } catch (Exception ignored) {
                // same as above: keep whatever the first lookup found
            }
        }
        return names.toArray(new String[0]);
    }

    /** Keeps only entries that look like an e-mail address (a Google account name does). */
    private static void addName(java.util.Set<String> names, Account account) {
        if (account == null || account.name == null) return;
        String name = account.name.trim();
        if (name.contains("@")) names.add(name);
    }

    private static String initials(String email) {
        if (email == null || email.isEmpty()) return "?";
        String name = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        name = name.replace('.', ' ').replace('_', ' ').trim();
        if (name.isEmpty()) return "?";
        String[] parts = name.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length && out.length() < 2; i++) {
            if (!parts[i].isEmpty()) out.append(Character.toUpperCase(parts[i].charAt(0)));
        }
        return out.length() == 0 ? "?" : out.toString();
    }

    /** A material-style list row: avatar, label, optional sub-label, ripple. */
    private View row(String label, String avatar, boolean accent, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AppUi.dp(activity, 6), AppUi.dp(activity, 10),
                AppUi.dp(activity, 6), AppUi.dp(activity, 10));
        row.setBackground(AppUi.pressable(activity, Color.TRANSPARENT, 14f));
        row.setOnClickListener(click);

        if (avatar != null) {
            TextView badge = new TextView(activity);
            badge.setText(avatar);
            badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            badge.setTextColor(Color.WHITE);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            badge.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(colorFor(avatar));
            badge.setBackground(circle);
            row.addView(badge, new LinearLayout.LayoutParams(
                    AppUi.dp(activity, 34), AppUi.dp(activity, 34)));
        } else {
            ImageView icon = new ImageView(activity);
            icon.setImageResource(accent
                    ? (google ? R.drawable.ic_google : R.drawable.ic_github)
                    : R.drawable.ic_nav_account);
            icon.setColorFilter(accent ? Color.BLACK : 0xFF5F6368);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    AppUi.dp(activity, 22), AppUi.dp(activity, 22));
            iconLp.leftMargin = AppUi.dp(activity, 6);
            iconLp.rightMargin = AppUi.dp(activity, 6);
            row.addView(icon, iconLp);
        }

        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f);
        text.setTextColor(0xFF202124);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = AppUi.dp(activity, avatar == null ? 10 : 14);
        row.addView(text, textLp);

        return row;
    }

    /** Stable pastel per account, so two accounts never look the same. */
    private static int colorFor(String key) {
        int hash = Math.abs(key.hashCode());
        int[] palette = {0xFF1A73E8, 0xFFD93025, 0xFF188038, 0xFF9334E6, 0xFFE37400};
        return palette[hash % palette.length];
    }

    public void dismiss() {
        dismiss(true);
    }

    private void dismiss(boolean notify) {
        if (root == null || root.getParent() == null) return;
        final View dying = root;
        root = null;
        dying.animate().alpha(0f).setDuration(AppUi.ANIM_FAST)
                .withEndAction(() -> {
                    if (dying.getParent() != null) parent.removeView(dying);
                }).start();
        if (notify) host.onCancelled();
    }
}
