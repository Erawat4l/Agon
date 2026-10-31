package ai.arena.webapp;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * A short, local record of what sign-in actually saw - so the next question is answered
 * with evidence instead of another guess.
 *
 * The open question this exists for: does Arena ever send the user back to the app with a
 * handoff link ({@code arena.ai/auth/handoff?ticket=...}) after a sign-in in a real browser?
 * Everything about the browser route depends on that one fact, and nothing has ever
 * observed it on a device. Rather than assume either way, the app now writes down every URL
 * that looks like a possible handoff and every sign-in outcome, newest last.
 *
 * What it stores, deliberately:
 *  - the hosts and paths of candidate links, as text, with no ticket, code or token value;
 *  - nothing typed into a page, and nothing read out of Google's fields.
 * It never leaves the device: the diagnostics sheet prints it, the user copies it.
 */
final class SignInTrace {

    private static final String PREFS = "agon_signin_trace";
    private static final String K_LOG = "log";
    private static final String K_HANDOFF_SEEN = "handoff_seen";
    private static final String K_HANDOFF_URL = "handoff_url";
    private static final String K_HANDOFF_AT = "handoff_at";
    /** Short: this is a notebook, not a log file. */
    private static final int MAX_LINES = 24;

    private SignInTrace() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Appends one line, keeping only the newest {@link #MAX_LINES}. */
    static synchronized void note(Context c, String line) {
        if (c == null || line == null) return;
        SharedPreferences p = prefs(c);
        String old = p.getString(K_LOG, "");
        String all = old.isEmpty() ? line : old + "\n" + line;
        String[] lines = all.split("\n");
        if (lines.length > MAX_LINES) {
            StringBuilder kept = new StringBuilder();
            for (int i = lines.length - MAX_LINES; i < lines.length; i++) {
                if (kept.length() > 0) kept.append('\n');
                kept.append(lines[i]);
            }
            all = kept.toString();
        }
        p.edit().putString(K_LOG, all).apply();
    }

    /**
     * Records a URL that looks like a handoff, with its secrets stripped.
     *
     * A query parameter is written as {@code name=set} rather than its value: the point is
     * to know whether a {@code ticket}/{@code code}/{@code token} arrived, not to keep it.
     */
    static void noteHandoff(Context c, String url) {
        if (c == null || url == null) return;
        String safe = redact(url);
        prefs(c).edit()
                .putBoolean(K_HANDOFF_SEEN, true)
                .putString(K_HANDOFF_URL, safe)
                .putLong(K_HANDOFF_AT, System.currentTimeMillis())
                .apply();
        note(c, "handoff: " + safe);
    }

    /** True once any handoff-looking link has been seen on this device. */
    static boolean handoffSeen(Context c) {
        return c != null && prefs(c).getBoolean(K_HANDOFF_SEEN, false);
    }

    /** The last handoff URL seen, secrets stripped, or "" when none ever arrived. */
    static String lastHandoff(Context c) {
        return c == null ? "" : prefs(c).getString(K_HANDOFF_URL, "");
    }

    /** The whole notebook, newest last, or "" when nothing has been recorded yet. */
    static String log(Context c) {
        return c == null ? "" : prefs(c).getString(K_LOG, "");
    }

    static void clear(Context c) {
        if (c != null) prefs(c).edit().clear().apply();
    }

    /**
     * True for a URL that carries evidence of an Arena sign-in captured in a browser.
     *
     * Deliberately narrow: an arena host and one of the parameter names a handoff uses, or
     * a path that says handoff/callback. Anything else is an ordinary link.
     */
    static boolean looksLikeHandoff(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        String host = hostOf(lower);
        if (!(host.endsWith("arena.ai") || host.endsWith("lmarena.ai"))) return false;
        return lower.contains("ticket=") || lower.contains("code=") || lower.contains("token=")
                || lower.contains("session=") || lower.contains("handoff")
                || lower.contains("/callback");
    }

    /** Host and path, query parameters reduced to "named but not shown". */
    static String redact(String url) {
        if (url == null) return "";
        int cut = url.indexOf('?');
        if (cut < 0) return url;
        StringBuilder out = new StringBuilder(url.substring(0, cut)).append('?');
        boolean first = true;
        for (String pair : url.substring(cut + 1).split("&")) {
            if (pair.isEmpty()) continue;
            String name = pair.split("=")[0];
            if (!first) out.append('&');
            first = false;
            out.append(name).append('=').append(pair.indexOf('=') < 0 ? "" : "set");
        }
        return out.toString();
    }

    private static String hostOf(String url) {
        try {
            String host = android.net.Uri.parse(url).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception notAUrl) {
            return "";
        }
    }
}
