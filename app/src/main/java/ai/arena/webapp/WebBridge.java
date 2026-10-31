package ai.arena.webapp;

import android.app.Activity;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

/**
 * JS bridge for downloads that the page generates in memory (blob: / data: URIs).
 * Kept tiny — everything runs on the UI thread.
 */
public final class WebBridge {

    private final Activity activity;
    private final WebView web;

    public WebBridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    @JavascriptInterface
    public void saveBase64(final String name, final String mime, final String base64) {
        activity.runOnUiThread(() -> Downloads.saveToDownloads(activity,
                name == null || name.isEmpty() ? "arena-download" : name,
                mime == null || mime.isEmpty() ? "application/octet-stream" : mime,
                base64));
    }

    @JavascriptInterface
    public void failed(final String what) {
        activity.runOnUiThread(() -> android.widget.Toast
                .makeText(activity, R.string.download_failed, android.widget.Toast.LENGTH_SHORT)
                .show());
    }

    /** Pulls a blob: URL through fetch() and hands the bytes to the app. */
    void downloadBlob(String blobUrl, String contentDisposition) {
        String name = android.webkit.URLUtil.guessFileName(blobUrl, contentDisposition, null);
        String js = "(function(){"
                + "var url=" + JSONObject.quote(blobUrl) + ";"
                + "var name=" + JSONObject.quote(name) + ";"
                + "var x=new XMLHttpRequest();x.open('GET',url,true);x.responseType='blob';"
                + "x.onload=function(){var b=x.response;var fr=new FileReader();"
                + "fr.onloadend=function(){var s=fr.result;var i=s.indexOf(',');"
                + "if(i>0){ArenaNative.saveBase64(name,b.type||'application/octet-stream',"
                + "s.substring(i+1));}};fr.readAsDataURL(b);};"
                + "x.onerror=function(){ArenaNative.failed('download');};x.send();})();";
        web.evaluateJavascript(js, null);
    }
}
