package ai.arena.webapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * In-app updates from the project's own public GitHub releases.
 *
 * Never installs anything silently: this class only *finds* an update, downloads the
 * APK next to the cache, and hands it to Android's package installer, which always
 * shows the system "Do you want to update this app?" prompt.
 */
public final class UpdateManager {

    // ---------------------------------------------------------------- configuration

    /** Repo that hosts the releases. Set in one place so forks can reuse this. */
    public static final String REPO_OWNER = "Erawat4l";
    public static final String REPO_NAME = "Arena-Ai-App";   // tools/set-repo.py changes this

    /** Which release channel to follow. */
    public enum Channel {
        /** Stable: newest published (non-prerelease) release. */
        STABLE,
        /** Beta: newest release of any kind, including pre-releases. */
        BETA
    }

    private static final String API_STABLE = "https://api.github.com/repos/" + REPO_OWNER
            + "/" + REPO_NAME + "/releases/latest";
    private static final String API_ALL = "https://api.github.com/repos/" + REPO_OWNER
            + "/" + REPO_NAME + "/releases?per_page=20";

    private static final String PREFS = "agon_update";
    private static final String K_LATEST_CODE = "latest_code";
    private static final String K_LATEST_NAME = "latest_name";
    private static final String K_LATEST_URL = "latest_url";
    private static final String K_LATEST_SIZE = "latest_size";
    private static final String K_LATEST_NOTES = "latest_notes";
    private static final String K_LATEST_PAGE = "latest_page";
    private static final String K_LATEST_ASSET = "latest_asset";
    private static final String K_LATEST_PUBLISHED = "latest_published";
    private static final String K_LATEST_TAG = "latest_tag";
    private static final String K_LAST_CHECK = "last_check";
    private static final String K_NOTIFIED_CODE = "notified_code";
    private static final String K_LAST_ERROR = "last_error";

    private static final int TIMEOUT_MS = 15000;

    private UpdateManager() {
    }

    // ---------------------------------------------------------------- state

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int currentVersionCode(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    public static String currentVersionName(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    public static int latestVersionCode(Context c) {
        return prefs(c).getInt(K_LATEST_CODE, 0);
    }

    public static String latestVersionName(Context c) {
        return prefs(c).getString(K_LATEST_NAME, "");
    }

    public static String latestNotes(Context c) {
        return prefs(c).getString(K_LATEST_NOTES, "");
    }

    /**
     * The release the Update screen talks about: the newest one GitHub returns for the
     * chosen channel, not the repository's release list.
     *
     * It used to be a fixed ".../releases", which is why "open release page" landed on a
     * list instead of the build the sheet was offering. The stored html_url of the release
     * that was actually checked is used when it is a github.com URL, and the list is only
     * the fallback for "never checked successfully".
     */
    public static String latestReleasePath(Context c) {
        String page = prefs(c).getString(K_LATEST_PAGE, "");
        String fallback = "https://github.com/" + REPO_OWNER + "/" + REPO_NAME + "/releases";
        if (page == null || page.isEmpty()) return fallback;
        if (!page.startsWith("https://github.com/")) return fallback;
        return page;
    }

    /** File name of the APK asset in the newest release ("" when unknown). */
    public static String latestApkName(Context c) {
        return prefs(c).getString(K_LATEST_ASSET, "");
    }

    /** Tag of the newest release, used for the "latest" line. */
    public static String latestTag(Context c) {
        return prefs(c).getString(K_LATEST_TAG, "");
    }

    /** Publish time of the newest release, or 0 when unknown. */
    public static long latestPublishedAt(Context c) {
        return prefs(c).getLong(K_LATEST_PUBLISHED, 0L);
    }

    public static String latestReleaseUrl(Context c) {
        return prefs(c).getString(K_LATEST_URL, "");
    }

    public static long latestApkSize(Context c) {
        return prefs(c).getLong(K_LATEST_SIZE, 0);
    }

    public static long lastCheck(Context c) {
        return prefs(c).getLong(K_LAST_CHECK, 0);
    }

    public static String lastError(Context c) {
        return prefs(c).getString(K_LAST_ERROR, null);
    }

    /** Name + size of the newest release APK, for the "Update available" prompt. */
    public static String latestApkLabel(Context c) {
        long size = latestApkSize(c);
        return size > 0 ? UpdateSheet.humanSize(size) : "";
    }

    /** True when the most recent check found a release newer than this install. */
    public static boolean isUpdateAvailable(Context c) {
        int latest = latestVersionCode(c);
        return latest > 0 && latest > currentVersionCode(c) && !latestReleaseUrl(c).isEmpty();
    }

    /** True when the badge / notification has already been raised for that version. */
    public static boolean alreadyNotified(Context c, int versionCode) {
        return prefs(c).getInt(K_NOTIFIED_CODE, -1) >= versionCode;
    }

    public static void markNotified(Context c, int versionCode) {
        prefs(c).edit().putInt(K_NOTIFIED_CODE, versionCode).apply();
    }

    public static void reset(Context c) {
        prefs(c).edit().clear().apply();
    }

    public static String channel(Context c) {
        return prefs(c).getString("channel", Channel.STABLE.name());
    }

    public static void setChannel(Context c, Channel channel) {
        prefs(c).edit().putString("channel", channel.name()).apply();
    }

    public static boolean autoCheck(Context c) {
        return prefs(c).getBoolean("auto_check", true);
    }

    public static void setAutoCheck(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto_check", on).apply();
        // One switch, two surfaces: the Update screen and Settings must never disagree.
        Settings.prefs(c).edit().putBoolean(Settings.K_AUTO_CHECK, on).apply();
    }

    // ---------------------------------------------------------------- result

    public static final class Result {
        public final boolean ok;
        public final boolean updateAvailable;
        public final int latestCode;
        public final String versionName;
        public final String notes;
        public final String releasePage;
        public final String error;

        Result(boolean ok, boolean updateAvailable, int latestCode, String versionName,
               String notes, String releasePage, String error) {
            this.ok = ok;
            this.updateAvailable = updateAvailable;
            this.latestCode = latestCode;
            this.versionName = versionName;
            this.notes = notes;
            this.releasePage = releasePage;
            this.error = error;
        }
    }

    // ---------------------------------------------------------------- check

    /**
     * Blocking network call — run it off the main thread (see UpdateService).
     *
     * @param channel release channel to follow
     * @param manual  true when the user tapped "Check now": failures are reported
     */
    public static Result check(Context context, Channel channel, boolean manual) {
        Context app = context.getApplicationContext();
        try {
            JSONObject release = fetchRelease(app, channel);
            if (release == null) {
                storeFailure(app, "no_release");
                return new Result(false, false, 0, null, null, null, "no_release");
            }

            String tag = release.optString("tag_name", "");
            int code = parseVersionCode(tag);
            if (code <= 0) {
                int[] fromName = parseVersion(release.optString("name", ""));
                code = fromName[0];
            }
            String versionName = code > 0 ? versionLabel(tag, release.optString("name", ""))
                    : tag;

            // Pick the .apk asset (the debug build is attached by CI).
            JSONArray assets = release.optJSONArray("assets");
            String apkUrl = null;
            String apkName = "";
            long apkSize = 0;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name", "");
                    if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                        // Only GitHub's own download URL is accepted. The app installs
                        // whatever comes back from here, so a release (or anything that
                        // could edit one) must not be able to point it at another host.
                        String candidate = a.optString("browser_download_url", "");
                        apkUrl = isGitHubDownload(candidate) ? candidate : "";
                        apkSize = a.optLong("size", 0);
                        apkName = name;
                        break;
                    }
                }
            }
            String page = release.optString("html_url", "");
            String notes = cleanBody(release.optString("body", ""));

            SharedPreferences.Editor e = prefs(app).edit()
                    .putInt(K_LATEST_CODE, Math.max(code, 0))
                    .putString(K_LATEST_NAME, versionName == null ? "" : versionName)
                    .putString(K_LATEST_URL, apkUrl == null ? "" : apkUrl)
                    .putLong(K_LATEST_SIZE, apkSize)
                    .putString(K_LATEST_NOTES, notes)
                    .putString(K_LATEST_PAGE, page == null ? "" : page)
                    .putString(K_LATEST_ASSET, apkName == null ? "" : apkName)
                    .putString(K_LATEST_TAG, tag == null ? "" : tag)
                    .putLong(K_LATEST_PUBLISHED, publishedAt(release))
                    .putLong(K_LAST_CHECK, System.currentTimeMillis())
                    .remove(K_LAST_ERROR);
            e.apply();

            boolean available = code > 0 && code > currentVersionCode(app)
                    && apkUrl != null && !apkUrl.isEmpty();
            return new Result(true, available, code, versionName, notes, page, null);
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            storeFailure(app, msg);
            return new Result(false, false, 0, null, null, null, msg);
        }
    }

    /** GitHub's "published_at"/"created_at" as millis; 0 when it cannot be read. */
    private static long publishedAt(JSONObject release) {
        String raw = release.optString("published_at", "");
        if (raw.isEmpty()) raw = release.optString("created_at", "");
        if (raw.isEmpty()) return 0L;
        try {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date parsed = format.parse(raw);
            return parsed == null ? 0L : parsed.getTime();
        } catch (Exception notADate) {
            return 0L;
        }
    }

    private static void storeFailure(Context app, String message) {
        prefs(app).edit().putString(K_LAST_ERROR, message)
                .putLong(K_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    private static JSONObject fetchRelease(Context context, Channel channel) throws Exception {
        boolean beta = channel == Channel.BETA;
        String body = httpGet(beta ? API_ALL : API_STABLE, context);
        if (beta) {
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rel = arr.getJSONObject(i);
                if (!rel.optBoolean("draft", false)) return rel;
            }
            return null;
        }
        return new JSONObject(body);
    }

    private static String httpGet(String spec, Context context) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(spec).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setRequestProperty("User-Agent", "ArenaAndroid/1.0");
        // No credentials at all: the releases are public, so GitHub answers anonymously.
        // Nothing about this device is sent and nothing needs to be stored.
        int status = conn.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? conn.getInputStream() : conn.getErrorStream();
        String body = stream == null ? "" : readAll(stream);
        conn.disconnect();
        if (status == 404) {
            // Only two things answer 404 for a public repository: no release published yet,
            // or the repository was renamed/deleted. Say which one to check.
            throw new IllegalStateException(
                    "no release found for " + REPO_OWNER + "/" + REPO_NAME
                            + " - publish one (publish.bat), or check the repository name");
        }
        if (status == 403 || status == 429) {
            // GitHub's anonymous rate limit: 60 API calls an hour per IP address.
            throw new IllegalStateException(
                    "GitHub is rate-limiting this network - try again in a few minutes");
        }
        if (status != 200) {
            throw new IllegalStateException("GitHub API HTTP " + status);
        }
        return body;
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedInputStream bin = new BufferedInputStream(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = bin.read(buf)) > 0) out.write(buf, 0, n);
        bin.close();
        return out.toString("UTF-8");
    }

    // ---------------------------------------------------------------- download

    public interface Progress {
        void onProgress(int percent);
    }

    /**
     * Downloads the release APK to cache. Returns the file, or null on failure.
     * The asset URL is only accepted when it points at GitHub itself, and the redirect to
     * GitHub's CDN is followed by hand so no request header follows it there.
     */
    public static File downloadApk(Context context, Progress progress) throws Exception {
        Context app = context.getApplicationContext();
        String url = latestReleaseUrl(app);
        if (url == null || url.isEmpty()) throw new IllegalStateException("no_apk");
        if (!isGitHubDownload(url)) throw new IllegalStateException("untrusted apk host");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Accept", "application/octet-stream");
        conn.setRequestProperty("User-Agent", "ArenaAndroid/1.0");

        // Release assets redirect to a CDN. Following the redirect by hand keeps the request
        // headers of the first hop from leaking into the second.
        int status = conn.getResponseCode();
        String location = conn.getHeaderField("Location");
        if (status >= 300 && status < 400 && location != null) {
            conn.disconnect();
            conn = (HttpURLConnection) new URL(new URL(url), location).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "ArenaAndroid/1.0");
            conn.setInstanceFollowRedirects(true);
            status = conn.getResponseCode();
        }
        if (status != 200) {
            conn.disconnect();
            throw new IllegalStateException("download HTTP " + status);
        }

        long total = conn.getContentLengthLong();
        File dir = new File(app.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("no_cache_dir");
        File out = new File(dir, "Agon-" + latestVersionCode(app) + ".apk");
        if (out.exists() && total > 0 && out.length() == total) {
            conn.disconnect();
            if (progress != null) progress.onProgress(100);
            return out;
        }

        InputStream in = new BufferedInputStream(conn.getInputStream());
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[16384];
        long done = 0;
        int lastPercent = -1;
        int n;
        while ((n = in.read(buf)) > 0) {
            fos.write(buf, 0, n);
            done += n;
            if (total > 0 && progress != null) {
                int pct = (int) (done * 100 / total);
                if (pct != lastPercent) {
                    lastPercent = pct;
                    progress.onProgress(pct);
                }
            }
        }
        fos.flush();
        fos.close();
        in.close();
        conn.disconnect();

        if (out.length() == 0) throw new IllegalStateException("empty download");
        return out;
    }

    /**
     * True for an https URL on GitHub's own release hosts.
     *
     * The APK is handed to Android's package installer, so this is the last thing between
     * a network answer and an install prompt: it must be github.com, api.github.com or
     * objects.githubusercontent.com (where release assets actually live) - not a URL that
     * merely contains the word, and never plain http.
     */
    static boolean isGitHubDownload(String url) {
        if (url == null) return false;
        try {
            URL parsed = new URL(url);
            if (!"https".equalsIgnoreCase(parsed.getProtocol())) return false;
            String host = parsed.getHost() == null
                    ? "" : parsed.getHost().toLowerCase(Locale.US);
            return host.equals("github.com")
                    || host.endsWith(".github.com")
                    || host.equals("githubusercontent.com")
                    || host.endsWith(".githubusercontent.com");
        } catch (Exception notAUrl) {
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** "v1.2.0" / "1.2" / "release-7" -> a comparable version code. */
    static int parseVersionCode(String tag) {
        int[] v = parseVersion(tag);
        if (v[0] == 0 && v[1] == 0 && v[2] == 0) return 0;
        return pack(v[0], v[1], v[2]);
    }

    /**
     * Prefers dotted versions ("v1.2.3" out of "release v1.2.3 notes"); a lone
     * number is treated as a build number ("release-7" -> 0.0.7) so it can never
     * masquerade as a huge version bump.
     */
    static int[] parseVersion(String text) {
        int[] out = {0, 0, 0};
        if (text == null || text.isEmpty()) return out;

        java.util.regex.Matcher dotted = java.util.regex.Pattern
                .compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
                .matcher(text);
        if (dotted.find()) {
            out[0] = safeInt(dotted.group(1));
            out[1] = safeInt(dotted.group(2));
            out[2] = dotted.group(3) == null ? 0 : safeInt(dotted.group(3));
            return out;
        }
        java.util.regex.Matcher single = java.util.regex.Pattern
                .compile("(\\d+)").matcher(text);
        if (single.find()) {
            out[2] = safeInt(single.group(1));
        }
        return out;
    }

    private static int safeInt(String s) {
        try {
            long v = Long.parseLong(s);
            return v > 999 ? 999 : (int) v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** major*1,000,000 + minor*1,000 + patch — headroom for CI run numbers. */
    static int pack(int major, int minor, int patch) {
        return major * 1000000 + minor * 1000 + patch;
    }

    static String versionLabel(String tag, String name) {
        String t = tag == null ? "" : tag.trim();
        if (t.toLowerCase(Locale.US).startsWith("v") && t.length() > 1) t = t.substring(1);
        if (parseVersionCode(tag) > 0) return t;
        return name == null || name.isEmpty() ? t : name;
    }

    /** Release notes: drop HTML comments, trim runaway length. */
    private static String cleanBody(String body) {
        if (body == null) return "";
        String text = body.replaceAll("(?s)<!--.*?-->", "").trim();
        if (text.length() > 1200) text = text.substring(0, 1200) + "…";
        return text;
    }
}
