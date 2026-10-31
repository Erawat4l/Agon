package ai.arena.webapp;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Why is sign-in not working?" - an on-device report instead of another guess.
 *
 * Every previous fix for the sign-in was a hypothesis. This screen answers the questions
 * with facts from the phone: whether popups are enabled, what the WebView really reports
 * as its user agent, whether the cookie jar already holds a session, and what Arena's page
 * actually contains (does it have a "Log in" control, does it have provider buttons).
 *
 * Everything is read-only: it never clicks on the page, never signs in, never sends the
 * report anywhere. The user can copy it into a message.
 */
final class SignInDiagnostics {

    private SignInDiagnostics() {
    }

    /** Builds and shows the report as a sheet over the activity window. */
    static void show(final Activity activity, final ViewGroup parent, final WebView page) {
        final FrameLayout scrim = AppUi.sheetScrim(activity, null);
        scrim.setOnClickListener(v -> AppUi.slideDownAndRemove(scrim, parent));

        final LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(AppUi.rounded(AppUi.color(activity, R.color.surface), 24f,
                activity));
        sheet.setPadding(AppUi.dp(activity, 20), AppUi.dp(activity, 12),
                AppUi.dp(activity, 20), AppUi.dp(activity, 18));
        sheet.setOnClickListener(v -> {
        });

        TextView title = AppUi.heading(activity, activity.getString(R.string.diag_title),
                20f, R.color.on_bg);
        sheet.addView(title);

        final TextView body = AppUi.text(activity, activity.getString(R.string.diag_working),
                11.5f, R.color.on_bg_muted, false);
        body.setPadding(0, AppUi.dp(activity, 8), 0, 0);
        body.setTextIsSelectable(true);
        android.widget.ScrollView scroller = new android.widget.ScrollView(activity);
        scroller.addView(body);
        LinearLayout.LayoutParams scrollerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AppUi.dp(activity, 300));
        scrollerLp.topMargin = AppUi.dp(activity, 8);
        sheet.addView(scroller, scrollerLp);

        TextView copy = AppUi.text(activity, activity.getString(R.string.diag_copy), 14f,
                R.color.on_accent, true);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(0, AppUi.dp(activity, 13), 0, AppUi.dp(activity, 13));
        copy.setBackground(AppUi.pressable(activity, AppUi.color(activity, R.color.accent),
                14f));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.topMargin = AppUi.dp(activity, 12);
        sheet.addView(copy, copyLp);

        TextView close = AppUi.text(activity, activity.getString(R.string.diag_close),
                13.5f, R.color.on_bg_muted, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, AppUi.dp(activity, 12), 0, 0);
        close.setOnClickListener(v -> AppUi.slideDownAndRemove(scrim, parent));
        sheet.addView(close);

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        scrim.addView(sheet, sheetLp);
        parent.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        AppUi.slideUp(sheet);

        final StringBuilder facts = new StringBuilder();
        copy.setOnClickListener(v -> {
            AppUi.press(copy);
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) activity.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "Agon sign-in report", facts.toString()));
            android.widget.Toast.makeText(activity, R.string.diag_copied,
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        // Facts that need no page.
        facts.append("package: ").append(activity.getPackageName()).append('\n');
        facts.append("version: ").append(UpdateManager.currentVersionName(activity))
                .append(" (").append(UpdateManager.currentVersionCode(activity)).append(")\n");
        facts.append("android: ").append(android.os.Build.VERSION.RELEASE)
                .append(" / sdk ").append(android.os.Build.VERSION.SDK_INT).append('\n');
        facts.append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append('\n');
        if (page != null) {
            facts.append("webview: ").append(page.getSettings().getUserAgentString())
                    .append('\n');
            facts.append("multiple windows: ")
                    .append(page.getSettings().supportMultipleWindows()).append('\n');
            facts.append("js popups: ")
                    .append(page.getSettings().getJavaScriptCanOpenWindowsAutomatically())
                    .append('\n');
        }
        String cookies = CookieManager.getInstance().getCookie("https://arena.ai");
        facts.append("cookie jar: ")
                .append(cookies == null || cookies.isEmpty() ? "empty"
                        : cookies.length() + " chars")
                .append('\n');
        facts.append("session cookie: ")
                .append(SignInSheet.hasSessionCookie(cookies) ? "present" : "absent")
                .append('\n');
        facts.append("last crash: ").append(CrashLog.lastLine(activity)).append('\n');
        facts.append("accounts permission: ")
                .append(activity.checkSelfPermission("android.permission.GET_ACCOUNTS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ? "granted" : "not granted").append('\n');
        body.setText(facts.toString());

        if (page == null) return;

        // Facts from the live page: what Arena's own markup offers right now.
        //
        // The report that decided the 1.4.2 driver fix said "controls: 25, login: 0" -
        // the page the sheet loaded had no log-in control at all. So the page half now
        // says more: whether the control is there under *any* selector (bare text nodes
        // included) and whether the page runs arena's app shell (a shadow root or a
        // hydrated nav), because those decide which route the driver takes.
        String js = "(function(){try{"
                + "function label(el){var t=(el.innerText||el.textContent||'').trim();"
                + "if(!t)t=(el.getAttribute&&el.getAttribute('aria-label'))||'';return t;}"
                + "function box(el){var r=el.getBoundingClientRect();"
                + "return r.width>0&&r.height>0;}"
                + "var c=document.querySelectorAll("
                + "'button,a,[role=\"button\"],[role=\"menuitem\"],[aria-label]');"
                + "var t2=document.querySelectorAll('span,div,li,p');"
                + "var login=0,google=0,github=0,visible=0,textLogin=0,shadow=0;"
                + "for(var i=0;i<c.length;i++){var el=c[i];var t=label(el).toLowerCase();"
                + "if(box(el))visible++;"
                + "if(/log ?in|sign ?in|login/.test(t))login++;"
                + "if(t.indexOf('google')>=0)google++;"
                + "if(t.indexOf('github')>=0)github++;}"
                + "for(var i=0;i<t2.length;i++){var t=label(t2[i]).toLowerCase();"
                + "if(t.length<40&&/^(log ?in|login|sign ?in)$/.test(t)&&box(t2[i]))textLogin++;}"
                + "var hosts=document.querySelectorAll('*');"
                + "for(var i=0;i<hosts.length&&i<3000;i++){"
                + "if(hosts[i].shadowRoot)shadow++;}"
                + "return JSON.stringify({url:location.href,controls:c.length,"
                + "visible:visible,login:login,google:google,github:github,"
                + "textLogin:textLogin,shadowRoots:shadow,"
                + "title:document.title});"
                + "}catch(e){return JSON.stringify({error:e.message});}})();";
        page.evaluateJavascript(js, value -> {
            String cleaned = value == null ? "" : value;
            if (cleaned.startsWith("\"")) {
                cleaned = cleaned.substring(1);
            }
            if (cleaned.endsWith("\"")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            cleaned = cleaned.replace("\\\"", "\"");
            facts.append("\npage: ").append(cleaned).append('\n');
            body.setText(facts.toString());
        });
    }
}
