package ai.arena.webapp;

/**
 * Drives arena's own sign-in dialog from inside a WebView.
 *
 * Why this exists: there is no "https://arena.ai/?signin=1" endpoint. Arena's sign-in is
 * a dialog behind a control in its navigation, and the provider choice (Google / GitHub)
 * is a second control inside that dialog. Opening the home page alone therefore only ever
 * showed the marketing page - which is exactly what pressing GitHub used to do.
 *
 * The on-device report (1.4.1 diagnostics) showed the real problem: on arena's home page
 * there are 25 controls and *none* of them mentions logging in, Google or GitHub. The
 * home page genuinely has no Log in control; arena's leaderboard page does, and the chat
 * surface carries one behind its account/menu button. So the search here is wider than
 * "a button called Log in":
 *
 *   1. open shadow roots are walked too (arena renders parts of its shell inside them,
 *      and {@code document.querySelectorAll} cannot see into a shadow root);
 *   2. a login-labelled control that exists but is not visible at this viewport is still
 *      clicked (its handler runs); a hidden control is what a collapsed nav looks like;
 *   3. a menu / account / avatar button is pressed first when no login control is
 *      visible, because that is where the Log in entry lives on the chat page;
 *   4. "Get started" on the landing page is followed, because it opens the chat surface,
 *      whose own header does have the account entry.
 *
 * Everything happens on arena's real DOM, so whatever it does afterwards (OAuth redirect,
 * cookie, session) is unchanged - the app only supplies the taps a person would make.
 * The scripts are single string constants with a "{PROVIDER}" token, so they can be
 * syntax-checked by tools/check-resources.py exactly as they will be injected.
 */
final class SignInDriver {

    /** Runs JS in the provider WebView. */
    interface Runner {
        void run(String js, android.webkit.WebView valueCallbackHost,
                 android.webkit.ValueCallback<String> callback);
    }

    interface Result {
        void onStep(String answer);
    }

    /** Answers the caller can get back from a step. */
    static final String CLICKED = "clicked";
    static final String ALREADY = "open";
    static final String NOTHING = "none";
    /** Something that opens the sign-in path was pressed: look again in a moment. */
    static final String RETRY = "retry";

    private SignInDriver() {
    }

    /**
     * Shared helpers, prepended to every script below.
     *
     * {@code all()} collects matching elements from the document *and* from every open
     * shadow root inside it. Without that, a control rendered in a web component is
     * simply invisible to the driver - which is one of the ways "Continue with Google"
     * could do nothing at all.
     */
    static final String JS_PRELUDE =
            "var __A=(function(){"
                    + "function all(sel){var out=[],budget=4000;"
                    + "function walk(root){if(!root||budget<=0)return;var list;"
                    + "try{list=root.querySelectorAll(sel);}catch(e){list=[];}"
                    + "for(var i=0;i<list.length;i++){if(out.indexOf(list[i])<0)out.push(list[i]);}"
                    + "var kids;try{kids=root.querySelectorAll('*');}catch(e){kids=[];}"
                    + "for(var j=0;j<kids.length;j++){budget--;var el=kids[j];"
                    + "if(el.shadowRoot){walk(el.shadowRoot);}}}"
                    + "try{walk(document);}catch(e){}"
                    + "return out;}"
                    + "function label(el){if(!el)return '';"
                    + "var t=(el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim();"
                    + "if(!t){t=(el.getAttribute&&el.getAttribute('aria-label'))||'';}"
                    + "if(!t){t=(el.getAttribute&&el.getAttribute('title'))||'';}"
                    + "return t;}"
                    + "function seen(el){if(!el||el.disabled||el.hidden)return false;"
                    + "try{if(el.closest&&el.closest("
                    + "'[hidden],[aria-hidden=true],[style*=\"display: none\"],"
                    + "[style*=\"display:none\"],[style*=\"visibility: hidden\"]'))return false;}"
                    + "catch(e){}"
                    + "var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}"
                    + "function click(el){try{el.click();return true;}catch(e){return false;}}"
                    + "return {all:all,label:label,seen:seen,click:click};"
                    + "})();";

    /**
     * Opens Arena's sign-in dialog.
     *
     * Answer: "clicked" (a Log in control was pressed), "open" (the dialog with the
     * provider buttons is already showing), "retry" (a menu or "Get started" was pressed,
     * so the sign-in entry may exist now) or "none" (nothing matched at all).
     */
    static final String OPEN_DIALOG_JS = JS_PRELUDE
            + "(function(){try{"
            // Controls, and the plain text nodes that arena also uses as buttons: its
            // server-rendered leaderboard shows the words as a bare span, which no
            // button/a selector would ever find.
            + "var C=__A.all('button,a,[role=\"button\"],[role=\"menuitem\"],[aria-label],"
            + "[data-testid],div[tabindex],span[tabindex]');"
            + "var T=__A.all('span,div,li,p');"
            + "function L(el){return __A.label(el).toLowerCase();}"
            // The provider buttons are on screen already: the dialog is open.
            + "for(var i=0;i<C.length;i++){var t=L(C[i]);"
            + "if(__A.seen(C[i])&&(t.indexOf('google')>=0||t.indexOf('github')>=0))return 'open';}"
            + "var exact=/^(log ?in|login|sign ?in|log ?in or sign ?up)$/i;"
            + "var loose=/(log ?in|sign ?in|login)/i;"
            + "function want(list,test,needsVisible,answer){"
            + "for(var i=0;i<list.length;i++){var el=list[i],t=L(el);"
            + "if(!t||t.length>40||!test.test(t))continue;"
            + "if(needsVisible&&!__A.seen(el))continue;"
            + "if(__A.click(el))return answer;}"
            + "return null;}"
            + "var hit;"
            // A Log in control, most certain first: a visible button/anchor, then the same
            // words rendered as text, then anything mentioning a login.
            + "hit=want(C,exact,true,'clicked')||want(T,exact,true,'clicked')"
            + "||want(C,loose,true,'clicked')||want(T,loose,true,'clicked');"
            + "if(hit)return hit;"
            // Nothing visible: open the menu / account button the entry hides behind.
            + "var menu=/(menu|hamburger|navigation|more options|account|profile|avatar)/i;"
            + "for(var i=0;i<C.length;i++){var el=C[i],t=L(el);"
            + "if(!t||t.length>40)continue;"
            + "if(el.tagName!=='BUTTON'&&el.getAttribute('role')!=='button')continue;"
            + "if(el.getAttribute('aria-expanded')==='true')continue;"
            + "if(!menu.test(t))continue;"
            + "if(!__A.seen(el))continue;"
            + "if(__A.click(el))return 'retry';}"
            // Landing page CTA: it leads to the chat surface, which has the account entry.
            + "hit=want(C,/^(get started|start chatting|start a chat)$/i,true,'retry')"
            + "||want(T,/^(get started|start chatting|start a chat)$/i,true,'retry');"
            + "if(hit)return hit;"
            // A signup entry leads to the same dialog, so it is a usable way in - but
            // only after every control that mentions logging in has been ruled out.
            + "hit=want(C,/sign ?up/i,true,'clicked')||want(T,/sign ?up/i,true,'clicked');"
            + "if(hit)return hit;"
            // Last resort: an exact Log-in label that exists but is not painted (a
            // collapsed nav renders one, and its click handler still works).
            + "hit=want(C,exact,false,'clicked')||want(T,exact,false,'clicked');"
            + "if(hit)return hit;"
            + "return 'none';"
            + "}catch(e){return 'error:'+e.message;}})();";

    /**
     * Presses the provider button inside the open dialog. "{PROVIDER}" is replaced by
     * "google" or "github".
     *
     * Answer: "clicked", "open" (already on the provider's own page) or "none".
     */
    static final String CHOOSE_PROVIDER_JS = JS_PRELUDE
            + "(function(){try{"
            + "var name='{PROVIDER}';"
            + "var h=(location.hostname||'').toLowerCase();"
            + "if(h.indexOf(name)>=0)return 'open';"
            + "var c=__A.all('button,a,[role=\"button\"],[role=\"menuitem\"],[aria-label],"
            + "div[tabindex],span[tabindex]');"
            + "function L(el){return __A.label(el).toLowerCase();}"
            + "for(var p=0;p<2;p++){"
            + "for(var i=0;i<c.length;i++){var el=c[i],t=L(el);"
            + "if(t.indexOf(name)<0)continue;"
            + "if(p===0&&!__A.seen(el))continue;"
            // Skip footer links that name the provider without being its button.
            + "try{if(el.closest&&el.closest('footer,[class*=footer]'))continue;}catch(e){}"
            + "if(__A.click(el))return 'clicked';}}"
            // Some builds render the provider as a bare link.
            + "var links=__A.all('a[href*=\"'+name+'\"]');"
            + "for(var i=0;i<links.length;i++){var a=links[i];"
            + "try{if(a.closest&&a.closest('footer,[class*=footer]'))continue;}catch(e){}"
            + "if(__A.click(a))return 'clicked';}"
            + "return 'none';"
            + "}catch(e){return 'error:'+e.message;}})();";

    static String chooseProviderScript(String provider) {
        String p = provider == null ? "google" : provider.toLowerCase();
        return CHOOSE_PROVIDER_JS.replace("{PROVIDER}", p);
    }

    /**
     * Runs a step until it answers something other than "none".
     *
     * A page that has not painted its dialog yet answers "none", and a retry a second
     * later usually succeeds; that is what makes this reliable against a real, slow page.
     * "retry" ends the step too: the caller decides how long to wait for whatever it
     * clicked to come up, instead of pressing the same menu button again (which would
     * close the menu it just opened).
     */
    static void step(final Runner runner, String script, int attempts, long delayMs,
                     final android.os.Handler handler, final Result result) {
        final int[] left = {Math.max(1, attempts)};
        final Runnable[] again = new Runnable[1];
        again[0] = () -> runner.run(script, null, value -> {
            String answer = clean(value);
            boolean done = CLICKED.equals(answer) || ALREADY.equals(answer)
                    || RETRY.equals(answer) || answer.startsWith("error");
            if (done || left[0] <= 1) {
                result.onStep(answer);
                return;
            }
            left[0]--;
            handler.postDelayed(again[0], delayMs);
        });
        handler.post(again[0]);
    }

    /** Turns the JSON string a WebView returns into a bare token. */
    static String clean(String value) {
        if (value == null) return NOTHING;
        String out = value.replace("\"", "").replace("'", "").trim();
        if (out.isEmpty() || out.startsWith("null")) return NOTHING;
        return out;
    }
}
