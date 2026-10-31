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
            write(app, error);
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

    public static void clear(Context context) {
        try {
            file(context).delete();
        } catch (Throwable ignored) {
        }
    }
}
