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
    /** The page on screen is already the provider's own sign-in (accounts.google.com). */
    static final String ON_PROVIDER = "provider";
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
     * The body of {@link #OPEN_DIALOG_JS}, without the wrapper, so the watching script
     * below runs exactly this logic instead of a second copy that drifts from it.
     *
     * Answer: "clicked" (a Log in control was pressed), "open" (the dialog with the
     * provider buttons is already showing), "provider" (this *is* the provider's page, so
     * there is nothing of Arena's to open), "retry" (a menu or "Get started" was pressed,
     * so the sign-in entry may exist now) or "none" (nothing matched at all).
     */
    private static final String OPEN_DIALOG_BODY =
            // The page the sheet is showing may already be the provider's own sign-in.
            // Arena's dialog does not exist there, and the old code spent all ten of its
            // search rounds on accounts.google.com before giving up - which is exactly the
            // screen the phone was left on, with the header claiming Google was opening
            // its sign-in page.
            "var __h=(location.hostname||'').toLowerCase();"
            + "if(__h&&__h.indexOf('arena.ai')<0&&__h.indexOf('lmarena.ai')<0"
            + "&&__h.indexOf('google')<0)return 'none';"
            // Controls, and the plain text nodes that arena also uses as buttons: its
            // server-rendered leaderboard shows the words as a bare span, which no
            // button/a selector would ever find.
            + "var C=__A.all('button,a,[role=\"button\"],[role=\"menuitem\"],[aria-label],"
            + "[data-testid],div[tabindex],span[tabindex]');"
            + "var T=__A.all('span,div,li,p');"
            + "function L(el){return __A.label(el).toLowerCase();}"
            // Google's own sign-in is on screen: an email field asking to continue to
            // Arena, or the account chooser's title. Nothing here is an Arena Log in
            // control, so the driver says so instead of searching for one.
            + "if(__h.indexOf('google')>=0){"
            + "var hasMail=__A.all('input[type=email],input[name=identifier]').length>0;"
            + "var asksArena=/continue to arena|choose an account|sign in with google/i"
            + ".test(((document.body&&document.body.innerText)||'').slice(0,4000));"
            + "if(hasMail||asksArena)return 'provider';}"
            // A provider button is on screen: press it here and now.
            // This used to answer 'open' and rely on the caller to press it a round later,
            // which is a race - if the follow-up look landed before the page had navigated
            // it answered 'open' again, nothing was ever clicked, and pressing Continue
            // with Google by hand did nothing at all. Clicking in the same pass means the
            // press happens exactly once, on the frame the button exists.
            + "for(var i=0;i<C.length;i++){var t=L(C[i]);"
            + "if(__A.seen(C[i])&&(t.indexOf('google')>=0||t.indexOf('github')>=0)){"
            + "if(__A.click(C[i]))return 'clicked';return 'open';}}"
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
            + "return 'none';";

    /** Opens Arena's sign-in dialog. */
    static final String OPEN_DIALOG_JS = JS_PRELUDE
            + "(function(){try{"
            + OPEN_DIALOG_BODY
            + "}catch(e){return 'error:'+e.message;}})();";

    /**
     * Opens Arena's sign-in dialog as soon as the control exists.
     *
     * The old loop asked the page every 900 ms and gave up after ten looks - so a page that
     * painted its Log in control at 1.4 s cost a round trip, and a slow one cost ten. This
     * script tries once immediately and, if there is nothing yet, installs a
     * MutationObserver that clicks the moment the control appears. Later calls return what
     * the observer did.
     *
     * Answer: the same answers as {@link #OPEN_DIALOG_JS} ("clicked", "open", "provider",
     * "retry", "none") plus "watch" (armed, still nothing on the page).
     */
    static final String WATCH_DIALOG_JS = JS_PRELUDE
            + "(function(){try{"
            + "var w=window;"
            // The observer already clicked, or the page has since turned into the provider.
            + "if(w.__agonHit)return w.__agonHit;"
            + "var __h=(location.hostname||'').toLowerCase();"
            + "if(__h&&__h.indexOf('arena.ai')<0&&__h.indexOf('lmarena.ai')<0"
            + "&&__h.indexOf('google')<0)return 'none';"
            + "if(__h.indexOf('google')>=0){"
            + "var hasMail=__A.all('input[type=email],input[name=identifier]').length>0;"
            + "var asksArena=/continue to arena|choose an account|sign in with google/i"
            + ".test(((document.body&&document.body.innerText)||'').slice(0,4000));"
            + "if(hasMail||asksArena)return 'provider';}"
            // One pass of the real search, so a page that is already ready clicks now.
            + "var found=(function(){" + OPEN_DIALOG_BODY + "})();"
            + "if(found&&found!=='none'&&found!=='watch'){w.__agonHit=found;return found;}"
            + "if(w.__agonArmed)return 'watch';"
            + "w.__agonArmed=true;"
            + "function look(){var hit=(function(){" + OPEN_DIALOG_BODY + "})();"
            + "if(hit&&hit!=='none'&&hit!=='watch'){w.__agonHit=hit;"
            + "try{w.__agonWatch.disconnect();}catch(e){}return;}}"
            + "try{"
            + "w.__agonWatch=new MutationObserver(function(){try{look();}catch(e){}});"
            + "w.__agonWatch.observe(document.documentElement||document,"
            + "{childList:true,subtree:true,attributes:true});"
            + "}catch(e){w.__agonArmed=false;}"
            // A single-element React app can finish rendering its dialog without a DOM
            // mutation this observer sees only once: one extra look on the next frame.
            + "try{setTimeout(look,600);}catch(e){}"
            + "return 'watch';"
            + "}catch(e){return 'error:'+e.message;}})();";

    /**
     * "Is this Arena page signed in?" - answered by the page, not by a cookie.
     *
     * CookieManager cannot read HttpOnly cookies, so the session cookie is usually
     * invisible to the app even when it is there. The page is not: every Arena surface
     * offers a sign-in control while signed out, so a rendered page with none of them, and
     * an account/avatar control instead, is a signed-in page.
     *
     * Answer: "in", "out" or "unknown" (nothing to read).
     */
    static final String SIGNED_OUT_JS = JS_PRELUDE
            + "(function(){try{"
            + "var els=__A.all('button,a,[role=\"button\"],[aria-label],[title]');"
            + "var signedOut=false,account=false;"
            + "for(var i=0;i<els.length;i++){var el=els[i];"
            + "var t=__A.label(el).toLowerCase();"
            + "if(t.length<60&&/(log ?in|sign ?in|login)/.test(t)){"
            + "if(__A.seen(el)){signedOut=true;break;}}}"
            + "for(var j=0;j<els.length;j++){var a=els[j];"
            + "var u=((a.getAttribute&&a.getAttribute('aria-label'))||'')+' '"
            + "+(a.getAttribute&&a.getAttribute('title')||'');"
            + "var img=a.querySelector&&a.querySelector('img[alt]');"
            + "if(img&&img.alt)u+=' '+img.alt;"
            + "if(/account|profile|avatar|signed in/i.test(u)&&__A.seen(a)){"
            + "account=true;break;}}"
            + "if(signedOut&&!account)return 'out';"
            + "if(account)return 'in';"
            + "var body=(document.body&&document.body.innerText)||'';"
            + "if(!document.body||body.trim().length<20)return 'unknown';"
            + "return signedOut?'out':'in';"
            + "}catch(e){return 'unknown';}})();";

    /**
     * Presses the provider button inside the open dialog. "{PROVIDER}" is replaced by
     * "google" or "github".
     *
     * Answer: "clicked", "open" (already on the provider's own page) or "none".
     */
    static final String CHOOSE_PROVIDER_JS = JS_PRELUDE
            + "(function(){try{"
            + "var name='{PROVIDER}';"
            // The page may have turned into the provider's own sign-in between the two
            // steps: say so instead of hunting for a button that is no longer anywhere.
            + "var g=/google/.test(name)&&(location.hostname||'').toLowerCase().indexOf('google')>=0;"
            + "if(g&&__A.all('input[type=email],input[name=identifier]').length>0)return 'open';"
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

    /**
     * Fills Arena's own email field and presses its "Continue with email" button.
     *
     * The form is Arena's: the "Your email" input sits inside its sign-in dialog and its
     * button posts it to Arena's own endpoint. This script only types the address the user
     * entered in the app and presses that button - the password (or the emailed link) is
     * still Arena's to ask for, which is the point: Agon never handles it.
     *
     * Why it is needed at all: the first version pressed "Continue with email" with the
     * field empty, and Arena answered "Email is required" - a dead end with the address
     * sitting in the app's own form. React inputs ignore a plain value assignment, so the
     * value goes through the native setter and a real input event.
     *
     * Answer: "filled" (address typed and button pressed), "nofield" (no email input on the
     * page), "nobutton", or "error:...".
     */
    static final String FILL_EMAIL_JS = JS_PRELUDE
            + "(function(){try{"
            + "var addr='{EMAIL}';"
            + "function isEmail(el){try{if(el.tagName!=='INPUT')return false;"
            + "var t=(el.getAttribute('type')||'').toLowerCase();"
            + "if(t==='email')return true;"
            + "var hay=((el.name||'')+' '+(el.id||'')+' '+(el.placeholder||'')+' '"
            + "+(el.getAttribute('aria-label')||'')).toLowerCase();"
            + "return /email|e-mail/.test(hay)&&t!=='password';}catch(e){return false;}}"
            + "var fields=__A.all('input');var target=null;"
            + "for(var i=0;i<fields.length;i++){if(isEmail(fields[i])){target=fields[i];break;}}"
            + "if(!target)return 'nofield';"
            + "function setValue(el,v){"
            + "try{var proto=el.tagName==='TEXTAREA'?window.HTMLTextAreaElement:window.HTMLInputElement;"
            + "var desc=Object.getOwnPropertyDescriptor(proto.prototype,'value');"
            + "if(desc&&desc.set)desc.set.call(el,v);else el.value=v;}catch(e){el.value=v;}"
            + "try{el.dispatchEvent(new Event('input',{bubbles:true}));}catch(e){}"
            + "try{el.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}"
            + "try{el.dispatchEvent(new FocusEvent('blur',{bubbles:true}));}catch(e){}}"
            // Focus the field the way a tap would, so a framework that commits on focus
            // treats this as a real edit rather than a scripted one.
            + "try{target.focus();}catch(e){}"
            + "setValue(target,addr);"
            + "var c=__A.all('button,a,[role=\"button\"],div[tabindex],span[tabindex]');"
            + "function L(el){return __A.label(el).toLowerCase();}"
            + "for(var j=0;j<c.length;j++){var el=c[j],t=L(el);"
            + "if(t.indexOf('email')<0)continue;"
            + "if(t.indexOf('google')>=0)continue;"
            + "if(!__A.seen(el))continue;"
            + "if(__A.click(el))return 'filled';}"
            + "return 'nobutton';"
            + "}catch(e){return 'error:'+e.message;}})();";

    static String fillEmailScript(String email) {
        String safe = email == null ? "" : email;
        // The address is data, not code: it is escaped for a single-quoted JS literal and
        // then for a Java string. Anything else would be an injection into Arena's page.
        String js = safe.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", " ").replace("\r", " ");
        return FILL_EMAIL_JS.replace("{EMAIL}", js);
    }

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
