package ai.arena.webapp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.File;
import java.security.MessageDigest;

/**
 * Makes the app feel instant, and keeps working when the network does not.
 *
 * Two mechanisms, because they solve different halves of the problem:
 *
 *  1. **The WebView's own HTTP cache, used deliberately.** A page that was opened before
 *     is answered from disk instead of from the network, so switching tabs and coming back
 *     later costs nothing. While there IS a connection the cache is consulted but the
 *     network still wins (LOAD_DEFAULT) - that is what keeps a chat list current. Only when
 *     there is no usable connection does the cache become the source of truth
 *     (LOAD_CACHE_ELSE_NETWORK), which is the difference between "the app is broken" and
 *     "here is what you saw last time".
 *
 *     This used to be LOAD_CACHE_ELSE_NETWORK *always*, which was a different bug: a stale
 *     document was served even when the network was fine, and the page looked frozen.
 *     Choosing per connectivity, and re-choosing when connectivity changes, is the fix.
 *
 *  2. **A saved copy of each tab.** The HTTP cache is opaque - it can be evicted at any
 *     time and it does not hold a whole document as one thing. saveWebArchive() does: it
 *     writes the fully rendered page, its inline styles and its state to one file. So the
 *     last good copy of each tab is kept, and used when a load fails outright. That is the
 *     answer to "sometime it doesnt load": instead of the error screen, the page the user
 *     already had comes back, and a retry runs behind it.
 *
 * Nothing here touches the signed-in session: snapshots live in the app's private cache
 * directory, are bounded in number, and are dropped when the app's data is cleared.
 */
public final class OfflinePages {

    /** One saved page per tab; six tabs is more than the strip ever shows. */
    private static final int KEEP = 8;

    /** A snapshot bigger than this is a download in disguise; do not store it. */
    private static final long MAX_SNAPSHOT_BYTES = 12L * 1024L * 1024L;

    /** Re-saving a tab every few seconds would fight the page; once every 3 min is enough. */
    private static final long MIN_INTERVAL_MS = 3L * 60L * 1000L;

    private OfflinePages() {
    }

    private static File dir(Context context) {
        File d = new File(context.getCacheDir(), "pages");
        if (!d.isDirectory() && !d.mkdirs()) return null;
        return d;
    }

    /** The file one URL's snapshot lives in. URL paths are not file names. */
    private static File fileFor(Context context, String url) {
        File d = dir(context);
        if (d == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(url.getBytes("UTF-8"));
            StringBuilder name = new StringBuilder("p-");
            for (int i = 0; i < 12; i++) name.append(String.format("%02x", hash[i]));
            return new File(d, name + ".mht");
        } catch (Exception e) {
            return null;
        }
    }

    /** True when a network that can actually reach the internet exists. */
    public static boolean online(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            // Never claim to be offline when the check itself fails: that would pin the
            // app to the cache for a reason the user cannot see.
            return true;
        }
    }

    /**
     * Points a WebView's cache at the right source for the current connection.
     *
     * Called on every load start and whenever connectivity changes, so a phone that drops
     * to a dead Wi-Fi and comes back to 4G corrects itself without a restart.
     */
    public static void applyCacheMode(WebView web, boolean online) {
        if (web == null) return;
        try {
            WebSettings s = web.getSettings();
            s.setCacheMode(online ? WebSettings.LOAD_DEFAULT
                    : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        } catch (Exception ignored) {
            // A destroyed WebView is not an error worth crashing over.
        }
    }

    public static void applyCacheMode(WebView web) {
        if (web == null) return;
        applyCacheMode(web, online(web.getContext()));
    }

    /** True when this URL has a saved copy, i.e. offline it can still be shown. */
    public static boolean hasSnapshot(Context context, String url) {
        if (url == null || !MainActivity.isArenaUrl(url)) return false;
        if (!Settings.offlinePages(context)) return false;
        File f = fileFor(context, url);
        return f != null && f.isFile() && f.length() > 0;
    }

    /**
     * Saves the page that is currently loaded, for the next time there is no network.
     *
     * Throttled per URL: the caller can fire it on every onPageFinished without turning a
     * chatty single-page app into a stream of disk writes.
     */
    public static void save(final WebView web, final String url) {
        if (web == null || url == null) return;
        if (!Settings.offlinePages(web.getContext())) return;
        if (!MainActivity.isArenaUrl(url)) return;
        if ("about:blank".equals(url)) return;
        final Context context = web.getContext();
        final File target = fileFor(context, url);
        if (target == null) return;
        long now = System.currentTimeMillis();
        if (target.isFile() && now - target.lastModified() < MIN_INTERVAL_MS) return;

        final File tmp = new File(target.getPath() + ".tmp");
        try {
            web.saveWebArchive(tmp.getAbsolutePath(), false, value -> {
                if (value == null) return;                  // save failed: keep the old copy
                if (tmp.length() <= 0 || tmp.length() > MAX_SNAPSHOT_BYTES) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return;
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(target);
                prune(context);
            });
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * Puts the saved copy back, and says whether it could.
     *
     * Loading the archive file itself (file://) is how a WebView replays a saved page, and
     * the tabs run with file access switched OFF (a remote page must not be able to read
     * the device). So the door is opened for exactly this one load and closed again by
     * {@link #lockFileAccess(WebView)} when the page has finished: the replay needs it open
     * for as long as the archive is being read, and not one moment longer.
     */
    public static boolean restore(WebView web, String url) {
        if (web == null || url == null) return false;
        if (!Settings.offlinePages(web.getContext())) return false;
        File f = fileFor(web.getContext(), url);
        if (f == null || !f.isFile() || f.length() == 0) return false;
        try {
            web.getSettings().setAllowFileAccess(true);
            web.loadUrl("file://" + f.getAbsolutePath());
            return true;
        } catch (Exception e) {
            lockFileAccess(web);
            return false;
        }
    }

    /**
     * Puts the WebView back the way the rest of the app expects it: no file access.
     *
     * Called once the restored document has finished loading. A page from arena.ai is
     * remote, and remote pages in this app never get file:// or content:// access.
     */
    public static void lockFileAccess(WebView web) {
        if (web == null) return;
        try {
            web.getSettings().setAllowFileAccess(false);
        } catch (Exception ignored) {
            // A destroyed WebView has no settings to change.
        }
    }

    /** Drops the oldest snapshots once there are more than {@link #KEEP}. */
    private static void prune(Context context) {
        File d = dir(context);
        if (d == null) return;
        File[] files = d.listFiles();
        if (files == null || files.length <= KEEP) return;
        java.util.Arrays.sort(files, (a, b) ->
                Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - KEEP; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    /** Removes every saved page. Used by "clear and reload", which means it. */
    public static void clear(Context context) {
        File d = dir(context);
        if (d == null) return;
        File[] files = d.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

}
