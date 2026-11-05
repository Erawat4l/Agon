package ai.arena.webapp;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Last-resort crash recorder.
 *
 * The app used to die instantly with no message (an out-of-bounds nav icon lookup in
 * onCreate). Now every uncaught exception is written to files/last_crash.txt and the
 * startup path shows it on screen, so a failure can be reported instead of guessed at.
 */
public final class CrashLog {

    private static final String FILE_NAME = "last_crash.txt";
    private static volatile boolean installed;

    private CrashLog() {
    }

    /** Wraps the existing handler so Android's own reporting still happens. */
    public static void install(final Context context) {
        if (installed) return;
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            // Settings -> "Show crash details": with the switch off the app reports
            // the crash the normal Android way and nothing is written to disk.
            if (Settings.crashScreen(app)) write(app, error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static boolean exists(Context context) {
        File f = file(context);
        return f.exists() && f.length() > 0;
    }

    /** Full trace of a throwable, with the app/device header we need to debug it. */
    public static String describe(Context context, Throwable error) {
        StringWriter buffer = new StringWriter();
        buffer.write("Arena " + UpdateManager.currentVersionName(context)
                + " (build " + UpdateManager.currentVersionCode(context) + ")\n");
        buffer.write("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                + "), " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
        buffer.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()) + "\n\n");
        PrintWriter writer = new PrintWriter(buffer);
        error.printStackTrace(writer);
        writer.flush();
        return buffer.toString();
    }

    /** Records a throwable without rethrowing it. */
    public static void write(Context context, Throwable error) {
        try {
            String text = describe(context, error);
            FileOutputStream out = new FileOutputStream(file(context));
            out.write(text.getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Throwable ignored) {
            // Never let the recorder become the crash.
        }
    }

    /**
     * Records a one-line note, not a crash.
     *
     * The app's own decisions about loading - "offline, showing the saved copy", "came back
     * online, refreshing" - are the first thing to look at when a page behaves oddly, and
     * there is no log to read on a phone. They are appended to the same file a crash is
     * written to, because that file is already reachable from the app's own menu.
     */
    public static void note(Context context, String line) {
        try {
            File f = file(context);
            // Under 400 bytes there is no crash trace worth keeping, so the note starts the
            // file; past 64 KB the oldest notes are dropped rather than growing for ever.
            boolean blank = f.length() < 400;
            if (f.length() > 64L * 1024L) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                blank = true;
            }
            FileOutputStream out = new FileOutputStream(f, !blank);
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            String text = (blank ? "" : "\n") + stamp + "  " + line + "\n";
            out.write(text.getBytes("UTF-8"));
            out.flush();
            out.close();
        } catch (Throwable ignored) {
            // A note is never allowed to be the reason something fails.
        }
    }

    public static String read(Context context) {
        try {
            File f = file(context);
            if (!f.exists()) return "";
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int read;
            int offset = 0;
            while (offset < data.length && (read = in.read(data, offset, data.length - offset)) > 0) {
                offset += read;
            }
            in.close();
            return new String(data, 0, offset, "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * One line "where + what", for a report that has to fit in a chat message.
     *
     * The stack trace is for the crash screen; this is what goes in the diagnostics sheet
     * so a failure in a sign-in step can be named instead of guessed at.
     */
    public static String lastLine(Context context) {
        String text = read(context);
        if (text.isEmpty()) return "none";
        String where = "";
        String what = "";
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("at ai.arena.webapp") && where.isEmpty()) {
                where = trimmed.substring(3);
            }
            if (what.isEmpty() && (trimmed.startsWith("java.") || trimmed.startsWith("android.")
                    || trimmed.contains("Exception") || trimmed.contains("Error"))) {
                what = trimmed;
            }
        }
        if (what.isEmpty() && where.isEmpty()) return "unreadable";
        return (what.isEmpty() ? "?" : what) + (where.isEmpty() ? "" : " @ " + where);
    }

    public static void clear(Context context) {
        try {
            file(context).delete();
        } catch (Throwable ignored) {
        }
    }
}
