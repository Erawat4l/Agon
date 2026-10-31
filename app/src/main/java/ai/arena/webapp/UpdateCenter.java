package ai.arena.webapp;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async orchestration for updates: never blocks the UI, never installs silently.
 */
public final class UpdateCenter {

    public interface CheckCallback {
        void onResult(UpdateManager.Result result);
    }

    public interface DownloadCallback {
        void onProgress(int percent);

        void onDone(File apk);

        void onError(String message);
    }

    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000; // 6 hours

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arena-update");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateCenter() {
    }

    public static void checkAsync(final Context context, final boolean manual,
                                  final CheckCallback callback) {
        final Context app = context.getApplicationContext();
        UpdateManager.Channel parsed;
        try {
            parsed = UpdateManager.Channel.valueOf(UpdateManager.channel(app));
        } catch (IllegalArgumentException e) {
            parsed = UpdateManager.Channel.STABLE;
        }
        final UpdateManager.Channel channel = parsed;
        IO.execute(() -> {
            final UpdateManager.Result result = UpdateManager.check(app, channel, manual);
            if (result.updateAvailable && !UpdateManager.alreadyNotified(app, result.latestCode)) {
                UpdateManager.markNotified(app, result.latestCode);
                UpdateNotifications.notifyUpdateFound(app, result.versionName,
                        UpdateManager.currentVersionName(app));
            }
            if (callback != null) {
                MAIN.post(() -> callback.onResult(result));
            }
        });
    }

    /** Cheap, silent check used when the app opens: skips network if recently checked. */
    public static void maybeAutoCheck(final Context context) {
        maybeAutoCheck(context, null);
    }

    /**
     * Same as above but reports the outcome (only when a check actually ran), so the
     * Update pill / nav badge can refresh as soon as the answer arrives.
     */
    public static void maybeAutoCheck(final Context context, final CheckCallback callback) {
        final Context app = context.getApplicationContext();
        if (!UpdateManager.autoCheck(app)) return;
        long age = System.currentTimeMillis() - UpdateManager.lastCheck(app);
        if (age >= 0 && age < AUTO_INTERVAL_MS) return;
        checkAsync(app, false, callback);
    }

    /** Forces a check regardless of the throttle (pull-to-refresh, "Check now"). */
    public static void forceCheck(Context context, CheckCallback callback) {
        UpdateManager.prefs(context.getApplicationContext()).edit()
                .putLong("last_check", 0).apply();
        checkAsync(context, false, callback);
    }

    public static void downloadAsync(final Context context, final DownloadCallback callback) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                File apk = UpdateManager.downloadApk(app, percent -> {
                    if (callback != null) MAIN.post(() -> callback.onProgress(percent));
                });
                if (callback != null) MAIN.post(() -> callback.onDone(apk));
            } catch (final Exception e) {
                final String msg = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                if (callback != null) MAIN.post(() -> callback.onError(msg));
            }
        });
    }

    /**
     * Hands the APK to Android's package installer. The system always asks the user
     * to confirm — this app can never install silently.
     */
    public static void installApk(Activity activity, File apk) {
        File copy = ApkInstaller.copyToShareable(activity, apk);
        ApkInstaller.launch(activity, copy);
    }

    public static void toastOnMain(Context context, String text) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> android.widget.Toast
                .makeText(app, text, android.widget.Toast.LENGTH_LONG).show());
    }
}
