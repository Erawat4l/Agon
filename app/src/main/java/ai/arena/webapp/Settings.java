package ai.arena.webapp;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Every user-facing switch in one place.
 *
 * The point is that nothing about the app is hard-coded any more: if a feature annoys
 * you (a duplicated nav, a hiding rule, notifications, auto-checks) it has an off switch
 * in Settings, and it stays off across launches.
 */
public final class Settings {

    private static final String PREFS = "arena_settings";

    /** Nav bar: show the four icons over the page. */
    public static final String K_SHOW_NAV = "show_nav";
    /** Hide arena's own sidebar/history panel inside the page. */
    public static final String K_HIDE_WEB_SIDEBAR = "hide_web_sidebar";
    /** Hide the page's own header when arena draws one. */
    public static final String K_HIDE_WEB_HEADER = "hide_web_header";
    /** Chrome-like user agent instead of the WebView one (no "Verify you are human"). */
    public static final String K_DESKTOP_UA = "chrome_user_agent";
    /** Notification when an update is published. */
    public static final String K_NOTIFY_UPDATES = "notify_updates";
    /** Silent 6-hourly update checks. */
    public static final String K_AUTO_CHECK = "auto_check";
    /** Multi-tap gestures on the Chat icon. */
    public static final String K_CHAT_TAPS = "chat_taps";
    /** Keep a tab's scroll position when switching away and back. */
    public static final String K_KEEP_SCROLL = "keep_scroll";
    /** Crash guard: show the trace on screen instead of closing. */
    public static final String K_CRASH_SCREEN = "crash_screen";
    /** The app behaves like an app: no text selection, no long-press copy menu. */
    public static final String K_NO_SELECT = "no_select";
    /** Version name + build number in the nav bar footer. */
    public static final String K_SHOW_VERSION = "show_version";

    private Settings() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean get(Context context, String key, boolean fallback) {
        return prefs(context).getBoolean(key, fallback);
    }

    public static void set(Context context, String key, boolean value) {
        prefs(context).edit().putBoolean(key, value).apply();
        if (K_AUTO_CHECK.equals(key)) {
            // Keep the switch on the Update screen in step with this one.
            UpdateManager.setAutoCheck(context, value);
        }
    }

    // ------------------------------------------------------------------ defaults

    public static boolean showNav(Context c) {
        return get(c, K_SHOW_NAV, true);
    }

    public static boolean hideWebSidebar(Context c) {
        return get(c, K_HIDE_WEB_SIDEBAR, true);
    }

    public static boolean hideWebHeader(Context c) {
        return get(c, K_HIDE_WEB_HEADER, false);
    }

    public static boolean chromeUserAgent(Context c) {
        return get(c, K_DESKTOP_UA, true);
    }

    public static boolean notifyUpdates(Context c) {
        return get(c, K_NOTIFY_UPDATES, true);
    }

    public static boolean autoCheck(Context c) {
        return get(c, K_AUTO_CHECK, true);
    }

    public static boolean chatTaps(Context c) {
        return get(c, K_CHAT_TAPS, true);
    }

    public static boolean keepScroll(Context c) {
        return get(c, K_KEEP_SCROLL, true);
    }

    public static boolean crashScreen(Context c) {
        return get(c, K_CRASH_SCREEN, true);
    }

    /** True when text should behave like a web page (selection allowed). */
    public static boolean selectText(Context c) {
        return !get(c, K_NO_SELECT, true);
    }

    public static boolean showVersion(Context c) {
        return get(c, K_SHOW_VERSION, false);
    }

    /** Wipes the switches back to their defaults (used by "reset settings"). */
    public static void reset(Context c) {
        prefs(c).edit().clear().apply();
    }
}
