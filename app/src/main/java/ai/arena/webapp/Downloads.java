package ai.arena.webapp;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Downloads: http(s) via DownloadManager, data:/blob: via the media store. */
public final class Downloads implements DownloadListener {

    private final Activity activity;

    public Downloads(Activity activity) {
        this.activity = activity;
    }

    @Override
    public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                String mimeType, long contentLength) {
        if (url == null) return;
        if (url.startsWith("data:")) {
            downloadDataUri(url, contentDisposition);
            return;
        }
        if (url.startsWith("blob:")) {
            WebViewHolder.bridge(activity).downloadBlob(url, contentDisposition);
            return;
        }
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            android.app.DownloadManager.Request request =
                    new android.app.DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimeType);
            request.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            request.setTitle(fileName);
            request.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                toast(R.string.download_started);
            }
        } catch (Exception e) {
            toast(R.string.download_failed);
        }
    }

    private void downloadDataUri(String dataUri, String contentDisposition) {
        try {
            int comma = dataUri.indexOf(',');
            if (comma < 0) {
                toast(R.string.download_failed);
                return;
            }
            String meta = dataUri.substring(5, comma);
            String mime = meta.split(";")[0];
            if (mime.isEmpty()) mime = "application/octet-stream";
            String data = dataUri.substring(comma + 1);
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            String name = URLUtil.guessFileName("download." + (ext == null ? "bin" : ext),
                    contentDisposition, mime);
            if (!meta.contains("base64")) {
                data = Base64.encodeToString(data.getBytes("UTF-8"), Base64.NO_WRAP);
            }
            saveToDownloads(activity, name, mime, data);
        } catch (Exception e) {
            toast(R.string.download_failed);
        }
    }

    static void saveToDownloads(Activity activity, String fileName, String mime, String base64) {
        try {
            // One path segment, always: the bridge is reachable from any script in the tab
            // and this is the only thing it can aim outside the Downloads folder.
            fileName = WebBridge.safeFileName(fileName);
            if (fileName == null) fileName = "arena-download";
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);
                Uri target = activity.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) {
                    toast(activity, R.string.download_failed);
                    return;
                }
                OutputStream out = activity.getContentResolver().openOutputStream(target);
                if (out == null) {
                    toast(activity, R.string.download_failed);
                    return;
                }
                out.write(bytes);
                out.close();
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) {
                    toast(activity, R.string.download_failed);
                    return;
                }
                File file = new File(dir, fileName);
                FileOutputStream out = new FileOutputStream(file);
                out.write(bytes);
                out.close();
                android.media.MediaScannerConnection.scanFile(activity,
                        new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
            }
            toast(activity, R.string.download_started);
        } catch (Exception e) {
            toast(activity, R.string.download_failed);
        }
    }

    private void toast(int res) {
        toast(activity, res);
    }

    private static void toast(Context c, int res) {
        android.widget.Toast.makeText(c, res, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Tiny indirection so the download listener can find the active WebView's bridge. */
    static final class WebViewHolder {
        static WebBridge bridge(Activity activity) {
            return ((MainActivity) activity).bridgeForCurrentTab();
        }
    }
}
