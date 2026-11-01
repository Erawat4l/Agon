package ai.arena.webapp;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The chat history, drawn natively.
 *
 * Two taps on Chat used to open arena's own sidebar. That sidebar is now hidden (it was a
 * duplicate of the app's navigation), so the gesture has nothing to open - this sheet
 * replaces it: the conversation list is read out of the live page once, rendered with the
 * app's own styling, and tapping an entry navigates the page to it.
 *
 * The list is read from whatever anchors the page renders for past conversations, so it
 * follows arena's own markup instead of guessing an API.
 */
public final class ChatHistorySheet {

    /** What the sheet needs from the activity. */
    public interface Host {
        /** Navigates the chat tab to a conversation. */
        void openConversation(String url);

        /** Starts a blank chat. */
        void newChat();

        void onDismissed();
    }

    private final Activity activity;
    private final ViewGroup parent;
    private final Host host;
    private FrameLayout overlay;
    private LinearLayout listBox;
    /** The empty-state button is added once per show, not on every refresh. */
    private boolean offeredArenaHistory;
    private TextView status;

    public ChatHistorySheet(Activity activity, ViewGroup parent, Host host) {
        this.activity = activity;
        this.parent = parent;
        this.host = host;
    }

    public boolean isShowing() {
        return overlay != null && overlay.getParent() != null;
    }

    /** Reads the page's conversation links, then shows them. */
    public void show(final WebView page) {
        if (isShowing()) return;

        overlay = AppUi.sheetScrim(activity, v -> dismiss());
        overlay.setOnClickListener(v -> dismiss());

        final LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface), 26f, activity));
        sheet.setPadding(AppUi.dp(activity, 20), AppUi.dp(activity, 10),
                AppUi.dp(activity, 20), AppUi.dp(activity, 18));
        sheet.setOnClickListener(v -> {
        });

        View handle = new View(activity);
        handle.setBackground(AppUi.rounded(AppUi.color(activity, R.color.handle), 3f, activity));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                AppUi.dp(activity, 40), AppUi.dp(activity, 5));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = AppUi.dp(activity, 12);
        sheet.addView(handle, handleLp);

        sheet.addView(AppUi.heading(activity, activity.getString(R.string.history_title),
                22f, R.color.on_bg));

        status = AppUi.text(activity, activity.getString(R.string.history_empty), 12.5f,
                R.color.on_bg_muted, false);
        status.setPadding(0, AppUi.dp(activity, 4), 0, AppUi.dp(activity, 8));
        sheet.addView(status);

        // Quick actions, so the sheet is useful even before the page answers.
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(chip(R.string.history_new_chat, () -> {
            dismiss();
            host.newChat();
        }));
        actions.addView(chip(R.string.history_search, () -> {
            dismiss();
            host.openConversation("https://arena.ai/history/search");
        }));
        actions.addView(chip(R.string.history_refresh, () -> {
            if (page != null) refreshHistory(page);
        }));
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.bottomMargin = AppUi.dp(activity, 8);
        sheet.addView(actions, actionsLp);

        ScrollView scroller = new ScrollView(activity);
        scroller.setFillViewport(true);
        listBox = new LinearLayout(activity);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(listBox);
        sheet.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AppUi.dp(activity, 320)));

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        overlay.addView(sheet, sheetLp);
        parent.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        AppUi.slideUp(sheet);

        if (page != null) requestHistory(page);
    }

    /**
     * Asks the live page for its conversation links.
     *
     * The page hands back a JSON array through {@link Bridge}; the sheet then renders it.
     * Only same-site /c/ style links are kept, which is what a conversation looks like.
     */
    public void requestHistory(WebView page) {
        if (page == null) return;
        page.addJavascriptInterface(new Bridge(), "AgonHistory");
        page.evaluateJavascript(JS_HISTORY, null);
    }

    /** Re-reads the page now, for the sheet's own Refresh chip. */
    public void refreshHistory(WebView page) {
        if (page == null) return;
        page.evaluateJavascript(
                "(function(){if(window.__agonHistory)window.__agonHistory();})();", null);
    }

    /**
     * Asks the page how it keeps its conversations, instead of assuming.
     *
     * Two earlier versions guessed and both were wrong on a real device: the first read
     * the light DOM, the second walked shadow roots. Arena's list is simply not in the
     * document while the sheet is open - it is fetched or rendered lazily - so no amount of
     * searching at one instant can find it. This version does not search at open time at
     * all; it gives the page a short window (about two seconds) and reports what appears:
     *
     *  1. any link that looks like a conversation, from the light DOM, every open shadow
     *     root, and every frame reachable from this one;
     *  2. failing that, whatever storage already holds: localStorage and sessionStorage
     *     keys that name a conversation or a history, which is what an app stores when it
     *     renders the list somewhere the DOM walk cannot see.
     *
     * The answer is sent twice: once immediately (cheap, usually enough) and once when the
     * document has stopped changing. The sheet draws whichever arrives with rows.
     */
    private static final String JS_HISTORY =
            "(function(){try{"
            // ---- one collector, used for every root
            + "var out=[],seen={};"
            + "function conv(u){if(!u)return false;u=String(u);"
            + "return u.indexOf('/c/')>=0||u.indexOf('/chat/')>=0||u.indexOf('/conversation')>=0"
            + "||u.indexOf('/thread')>=0;}"
            + "function add(u,t){if(!conv(u))return;"
            + "u=String(u);if(u.charAt(0)==='/')u='https://arena.ai'+u;"
            + "if(!/^https?:\\/\\/([a-z0-9-]+\\.)*arena\\.ai([\\/?#]|$)/i.test(u)&&"
            + "!/^https?:\\/\\/([a-z0-9-]+\\.)*lmarena\\.ai([\\/?#]|$)/i.test(u))return;"
            + "u=u.split('#')[0];if(seen[u])return;"
            + "t=(t||'').replace(/\\s+/g,' ').trim();if(!t||t.length>120)return;"
            + "seen[u]=1;out.push({u:u,t:t});}"
            + "function scan(root,depth){if(!root||depth>6||out.length>=40)return;"
            + "var as;try{as=root.querySelectorAll('a[href]');}catch(e){as=[];}"
            + "for(var i=0;i<as.length;i++){var a=as[i];"
            + "add(a.href,a.getAttribute('aria-label')||a.innerText||a.textContent);}"
            // elements that carry the conversation id in an attribute but are not anchors
            + "var ds;try{ds=root.querySelectorAll('[data-id],[data-conversation-id],[data-chat-id]');}"
            + "catch(e){ds=[];}"
            + "for(var k=0;k<ds.length;k++){var d=ds[k];"
            + "var id=d.getAttribute('data-conversation-id')||d.getAttribute('data-chat-id')"
            + "||d.getAttribute('data-id');"
            + "if(!id||!/^[\\w-]{6,}$/.test(id))continue;"
            + "add('/c/'+id,d.innerText||d.textContent);}"
            // buttons whose label reads like a conversation (arena renders rows as buttons)
            + "var bs;try{bs=root.querySelectorAll('button,[role=\\\"link\\\"],[role=\\\"button\\\"]');}"
            + "catch(e){bs=[];}"
            + "for(var b=0;b<bs.length&&b<400;b++){var el=bs[b];"
            + "var t=(el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim();"
            + "if(!t||t.length>120)continue;"
            + "var id2=el.getAttribute('data-id')||el.getAttribute('value')||'';"
            + "var href=el.getAttribute('data-href')||el.getAttribute('href')||'';"
            + "if(href)add(href,t);"
            + "else if(id2&&/^[\\w-]{6,}$/.test(id2))add('/c/'+id2,t);}"
            // descend, and cross into frames
            + "var kids;try{kids=root.querySelectorAll('*');}catch(e){kids=[];}"
            + "for(var j=0;j<kids.length;j++){var e2=kids[j];"
            + "if(e2.shadowRoot)scan(e2.shadowRoot,depth+1);"
            + "if(e2.tagName==='IFRAME'||e2.tagName==='FRAME'){"
            + "try{if(e2.contentDocument)scan(e2.contentDocument,depth+1);}catch(e){}}}"
            + "}"
            // ---- storage: what an app keeps when the DOM holds no list
            + "function fromStore(store){"
            + "if(!store)return;"
            + "for(var i=0;i<store.length;i++){var k=store.key(i);"
            + "if(!/chat|conversation|history|thread|session/i.test(k))continue;"
            + "var v=null;try{v=store.getItem(k);}catch(e){continue;}"
            + "if(!v||v.length>200000)continue;"
            + "var m=v.match(/[\\w-]{8,}/g);if(!m)continue;"
            + "for(var j=0;j<m.length&&out.length<60;j++){"
            + "if(/(chat|conv|thread)/i.test(k)&&/^[0-9a-f-]{8,}$/i.test(m[j])){"
            + "add('/c/'+m[j],k.replace(/[:._-]+/g,' ').trim()+' '+m[j].slice(0,8));}}"
            + "}"
            + "}"
            + "function collect(){out=[];seen={};"
            + "scan(document,0);"
            + "try{fromStore(window.localStorage);}catch(e){}"
            + "try{fromStore(window.sessionStorage);}catch(e){}"
            + "return JSON.stringify(out.slice(0,40));"
            + "}"
            // ---- first answer straight away
            + "AgonHistory.receive(collect());"
            // ---- and again once the page has actually rendered its list
            + "var pending=null,quiet=0,passes=0;"
            + "function again(){if(++passes>24)return;"
            + "if(pending)clearTimeout(pending);"
            + "pending=setTimeout(function(){"
            + "var now=collect();"
            + "if(now!=='[]')AgonHistory.receive(now);"
            + "},quiet>2?120:260);"
            + "quiet++;}"
            + "var obs=null;"
            + "try{obs=new MutationObserver(again);"
            + "obs.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}"
            // A sheet that is open while the user taps around still gets a fresh answer.
            + "setTimeout(function(){if(obs)obs.disconnect();"
            + "if(AgonHistory.done)AgonHistory.done(collect());"
            + "else AgonHistory.receive(collect());},2400);"
            // ---- and a manual nudge for the sheet's Refresh chip
            + "window.__agonHistory=function(){AgonHistory.receive(collect());};"
            + "}catch(e){try{AgonHistory.receive('[]');}catch(x){}}}"
            + ")();";

    /** Receives the list from the page. Runs on a WebView thread, so it hops to the UI. */
    private final class Bridge {
        /** An answer that arrived while the page may still be rendering its list. */
        @JavascriptInterface
        public void receive(final String json) {
            activity.runOnUiThread(() -> render(json, false));
        }

        /** The last answer for this request: from here, empty means empty. */
        @JavascriptInterface
        public void done(final String json) {
            activity.runOnUiThread(() -> render(json, true));
        }
    }

    private void render(String json, boolean finished) {
        if (listBox == null) return;
        listBox.removeAllViews();
        List<String[]> rows = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry == null) continue;
                String url = entry.optString("u", "");
                String title = entry.optString("t", "");
                if (url.isEmpty() || title.isEmpty()) continue;
                // The page supplies this list, so it is treated as untrusted input: a row
                // that is not an https link to arena's own site is dropped instead of
                // being offered as something to tap (which would navigate the tab away).
                if (!MainActivity.isArenaUrl(url)) continue;
                rows.add(new String[]{url, title});
            }
        } catch (Exception ignored) {
            // a malformed answer is the same as an empty one
        }

        if (rows.isEmpty()) {
            // An interim answer is not "you have no chats" - the page is still building
            // the list, and telling the user it is empty made a sheet that filled in a
            // second later look like it had failed.
            status.setText(activity.getString(
                    finished ? R.string.history_empty : R.string.history_looking));
            status.setVisibility(View.VISIBLE);
            if (!finished) return;
            // Even with a working scrape, arena may not have the list in the DOM (signed
            // out, or a page that renders it only on its own history route). A sheet that
            // says "nothing here" and nothing else is a dead end, so offer the route that
            // certainly exists and let arena render its own history.
            if (!offeredArenaHistory) {
                offeredArenaHistory = true;
                listBox.addView(chip(R.string.history_open_arena, () -> {
                    dismiss();
                    host.openConversation("https://arena.ai/history");
                }));
            }
            return;
        }
        offeredArenaHistory = false;
        status.setVisibility(View.GONE);
        for (String[] row : rows) {
            listBox.addView(conversationRow(row[1], row[0]));
        }
    }

    private View conversationRow(String title, final String url) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 12),
                AppUi.dp(activity, 12), AppUi.dp(activity, 12));
        row.setBackground(AppUi.pressable(activity, Color.TRANSPARENT, 12f));

        TextView label = AppUi.text(activity, title, 14.5f, R.color.on_bg, false);
        label.setMaxLines(2);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = AppUi.text(activity, "\u203a", 18f, R.color.on_bg_muted, false);
        row.addView(chevron);

        row.setOnClickListener(v -> {
            AppUi.press(v);
            dismiss();
            host.openConversation(url);
        });
        return row;
    }

    private View chip(int labelRes, Runnable onClick) {
        TextView chip = AppUi.text(activity, activity.getString(labelRes), 12f,
                R.color.on_bg, true);
        chip.setPadding(AppUi.dp(activity, 12), AppUi.dp(activity, 7),
                AppUi.dp(activity, 12), AppUi.dp(activity, 7));
        chip.setBackground(AppUi.pressable(activity,
                AppUi.color(activity, R.color.surface2), 14f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = AppUi.dp(activity, 8);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            AppUi.press(v);
            onClick.run();
        });
        return chip;
    }

    public void dismiss() {
        if (overlay == null) return;
        final FrameLayout going = overlay;
        overlay = null;
        listBox = null;
        AppUi.slideDownAndRemove(going, parent);
    }
}
