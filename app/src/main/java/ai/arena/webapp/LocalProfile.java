package ai.arena.webapp;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The account identity, kept on the device.
 *
 * What this can honestly do: remember which account you picked, which provider it came
 * from, and when Arena's own session last confirmed a sign-in, so the picker opens with
 * your account already at the top and the app can show "signed in as ..." without
 * asking again. What it deliberately does not do is store a Google/GitHub password or
 * mint its own session token: the session cookie belongs to arena.ai and is only ever
 * set by arena.ai after the provider page completes. Fabricating one would not log you
 * in - arena would reject it - so the honest flow is: pick the account once, finish the
 * provider page once, and the cookie (plus this profile) keeps you in from then on.
 */
public final class LocalProfile {

    private static final String PREFS = "arena_profile";
    private static final String K_EMAIL = "email";
    private static final String K_PROVIDER = "provider";
    private static final String K_SIGNED_IN_AT = "signed_in_at";
    private static final String K_LAST_CHECK = "session_check";

    private LocalProfile() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The account last used, or "" when the user has never signed in on this device. */
    public static String email(Context c) {
        return prefs(c).getString(K_EMAIL, "");
    }

    /** "Google" or "GitHub". */
    public static String provider(Context c) {
        return prefs(c).getString(K_PROVIDER, "");
    }

    public static boolean hasAccount(Context c) {
        return !email(c).isEmpty() || !provider(c).isEmpty();
    }

    public static long signedInAt(Context c) {
        return prefs(c).getLong(K_SIGNED_IN_AT, 0L);
    }

    /** Called when the picker hands an account over (before the provider page opens). */
    public static void remember(Context c, String email, String provider) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (email != null && !email.isEmpty()) e.putString(K_EMAIL, email);
        if (provider != null && !provider.isEmpty()) e.putString(K_PROVIDER, provider);
        e.putLong(K_LAST_CHECK, System.currentTimeMillis());
        e.apply();
    }

    /** Called once arena's own session is confirmed (the provider page finished). */
    public static void markSignedIn(Context c) {
        prefs(c).edit().putLong(K_SIGNED_IN_AT, System.currentTimeMillis()).apply();
    }

    /** Sign-out: the identity stays (so the picker can pre-select it), the session does not. */
    public static void clearSession(Context c) {
        prefs(c).edit().putLong(K_SIGNED_IN_AT, 0L).apply();
    }

    /** "Signed in as you@example.com" or "" when there is nothing to show. */
    public static String summary(Context c) {
        if (!hasAccount(c)) return "";
        String who = email(c).isEmpty() ? provider(c) : email(c);
        return c.getString(R.string.profile_summary, who);
    }
}
