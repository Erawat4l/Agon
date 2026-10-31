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
            if (page != null) requestHistory(page);
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
        String js = "(function(){try{"
                + "var out=[];var seen={};"
                + "var as=document.querySelectorAll('a[href]');"
                + "for(var i=0;i<as.length;i++){var a=as[i];"
                + "var h=a.href||'';if(h.indexOf('/c/')<0&&h.indexOf('/chat/')<0)"
                + "continue;"
                + "var t=(a.innerText||a.getAttribute('aria-label')||'').trim();"
                + "if(!t||t.length>120)continue;"
                + "if(seen[h])continue;seen[h]=1;"
                + "out.push({u:h,t:t});"
                + "if(out.length>=40)break;}"
                + "AgonHistory.receive(JSON.stringify(out));"
                + "}catch(e){AgonHistory.receive('[]');}})();";
        page.evaluateJavascript(js, null);
    }

    /** Receives the list from the page. Runs on a WebView thread, so it hops to the UI. */
    private final class Bridge {
        @JavascriptInterface
        public void receive(final String json) {
            activity.runOnUiThread(() -> render(json));
        }
    }

    private void render(String json) {
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
                rows.add(new String[]{url, title});
            }
        } catch (Exception ignored) {
            // a malformed answer is the same as an empty one
        }

        if (rows.isEmpty()) {
            status.setText(activity.getString(R.string.history_empty));
            status.setVisibility(View.VISIBLE);
            return;
        }
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
