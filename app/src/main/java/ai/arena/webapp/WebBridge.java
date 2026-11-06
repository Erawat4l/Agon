package ai.arena.webapp;

import android.app.Activity;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

/**
 * JS bridge for downloads that the page generates in memory (blob: / data: URIs).
 * Kept tiny — everything runs on the UI thread.
 *
 * Security note: every method here is reachable from any script running in the tab's
 * WebView. Nothing in the bridge reads page state back to the native side — it only
 * *writes a file* — so the worst a hostile script could do is download a file with a
 * name it chooses. The name is flattened to a single path segment anyway: a name such as
 * "../../databases/x" must never escape the Downloads folder.
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
        final String safeName = safeFileName(name);
        final String safeMime = mime == null || mime.isEmpty()
                ? "application/octet-stream" : mime;
        activity.runOnUiThread(() -> Downloads.saveToDownloads(activity,
                safeName == null ? "arena-download" : safeName, safeMime, base64));
    }

    /**
     * Ctrl/Cmd+N in the page: a browser-shaped request for a new chat tab.
     *
     * The only inbound call that changes what is on screen, and it can do nothing worse
     * than what the strip's own + button does - the activity decides, not the page.
     */
    @JavascriptInterface
    public void newChat() {
        activity.runOnUiThread(() -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).onNewChatShortcut();
        });
    }

    /**
     * The page copied a block for the user: put it on the clipboard as well, and say so.
     *
     * This is the second half of "i still cant copy". The page's own copy path can be
     * refused (no user gesture, no clipboard permission granted to the site), and when it
     * is, nothing reaches Android - so the text is handed over here and written natively
     * with ClipData, which needs no permission at all. Writing it twice is harmless: the
     * clipboard holds one clip, and the last write wins with the same text.
     */
    @JavascriptInterface
    public void copied(final String text) {
        if (text == null || text.trim().isEmpty()) return;
        activity.runOnUiThread(() -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).onPageCopied(text);
        });
    }

    /**
     * The page could not copy by itself and is handing the text over instead.
     *
     * Same destination as copied(): the app writes the clip natively, so the user gets the
     * text even when both of the page's own clipboard routes were refused.
     */
    @JavascriptInterface
    public void copyRefused(final String text) {
        if (text == null || text.trim().isEmpty()) return;
        activity.runOnUiThread(() -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).onPageCopied(text);
        });
    }

    /** The page could not copy by itself: tell the user the one route that always works. */
    @JavascriptInterface
    public void copyHint() {
        activity.runOnUiThread(() -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).onCopyUnavailable();
        });
    }

    /**
     * Ctrl/Cmd+Tab in the page: move along the strip.
     *
     * One step, and it wraps - exactly what the strip's own arrows do, because the keys and
     * the arrows are the same gesture on two devices. Nothing is announced: the chip that
     * lights up is the answer.
     */
    @JavascriptInterface
    public void cycleTab(final int direction) {
        activity.runOnUiThread(() -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).onCycleTabShortcut(direction);
            }
        });
    }

    /** Ctrl/Cmd+W in the page: close the tab on screen, never the app's last page. */
    @JavascriptInterface
    public void closeTab() {
        activity.runOnUiThread(() -> {
            if (activity instanceof MainActivity) ((MainActivity) activity).onCloseTabShortcut();
        });
    }

    /**
     * Three taps in the page flipped text selection: keep the setting in step.
     *
     * `unlocked` true means selection is now ON, which is the setting "App-style text"
     * turned OFF. The gesture is a shortcut for a switch that exists, never a third state
     * of its own: Settings shows what happened and the next launch starts from it.
     */
    @JavascriptInterface
    public void selectToggled(final boolean unlocked) {
        activity.runOnUiThread(() -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).onSelectToggled(unlocked);
            }
        });
    }

    @JavascriptInterface
    public void failed(final String what) {
        activity.runOnUiThread(() -> android.widget.Toast
                .makeText(activity, R.string.download_failed, android.widget.Toast.LENGTH_SHORT)
                .show());
    }

    /**
     * One path segment, no control characters, and never empty.
     *
     * The download is written through the MediaStore (or into the public Downloads
     * directory), so a name containing slashes or ".." is the only part of this call an
     * attacker could aim somewhere else; flattening it removes that.
     */
    static String safeFileName(String name) {
        if (name == null) return null;
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '/' || ch == '\\' || ch < 0x20 || ch == 0x7f) continue;
            out.append(ch);
        }
        String flat = out.toString().trim();
        while (flat.startsWith(".")) flat = flat.substring(1);
        if (flat.length() > 120) flat = flat.substring(flat.length() - 120);
        return flat.isEmpty() ? null : flat;
    }

    /** Pulls a blob: URL through fetch() and hands the bytes to the app. */
    void downloadBlob(String blobUrl, String contentDisposition) {
        String name = safeFileName(
                android.webkit.URLUtil.guessFileName(blobUrl, contentDisposition, null));
        if (name == null) name = "arena-download";
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
