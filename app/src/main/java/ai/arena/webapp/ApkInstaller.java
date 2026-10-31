package ai.arena.webapp;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import androidx.core.content.FileProvider;

/** Thin wrapper around FileProvider + the system package installer. */
public final class ApkInstaller {

    private ApkInstaller() {
    }

    /**
     * The package installer cannot read files from the app's cache on recent Android
     * versions, so copy the download into the FileProvider-backed files dir.
     */
    public static File copyToShareable(Activity activity, File source) {
        try {
            if (source == null || !source.exists()) return source;
            File dir = new File(activity.getFilesDir(), "updates");
            if (!dir.exists()) dir.mkdirs();
            File target = new File(dir, source.getName());
            if (target.exists() && target.length() == source.length()) return target;

            InputStream in = new FileInputStream(source);
            OutputStream out = new FileOutputStream(target);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            in.close();
            return target;
        } catch (Exception e) {
            return source;
        }
    }

    public static void launch(Activity activity, File apk) {
        if (apk == null || !apk.exists()) {
            Toast.makeText(activity, R.string.update_no_file, Toast.LENGTH_LONG).show();
            return;
        }

        // Android 8+: the user must allow this app to install unknown packages.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.update_allow_install, Toast.LENGTH_LONG).show();
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
            } catch (Exception ignored) {
            }
            return;
        }

        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show();
        }
    }
}
