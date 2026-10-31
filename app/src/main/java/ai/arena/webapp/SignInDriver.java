package ai.arena.webapp;

/**
 * Drives arena's own sign-in dialog from inside a WebView.
 *
 * Why this exists: there is no "https://arena.ai/?signin=1" endpoint. Arena's sign-in is
 * a dialog on the home page, behind a control in its navigation ("Log in"), and the
 * provider choice (Google / GitHub) is a second control inside that dialog. Opening the
 * home page alone therefore only ever showed the marketing page - which is exactly what
 * pressing GitHub used to do.
 *
 * So the sheet loads the page, waits for it to settle, presses Arena's own "Log in"
 * control, waits again, then presses "Continue with Google" or "Continue with GitHub".
 * Everything happens on arena's real DOM, so whatever it does afterwards (OAuth redirect,
 * cookie, session) is unchanged - the app only supplies the taps a person would make.
 *
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

    private SignInDriver() {
    }

    /**
     * Opens Arena's sign-in dialog.
     *
     * Answer: "clicked" (a Log in control was pressed), "open" (the dialog with the
     * provider buttons is already showing) or "none" (nothing matched).
     */
    static final String OPEN_DIALOG_JS =
            "(function(){try{"
                    + "function items(){return document.querySelectorAll("
                    + "'button,a,[role=\"button\"],[role=\"menuitem\"],div[tabindex]');}"
                    + "function label(el){var t=(el.innerText||el.textContent||'').trim();"
                    + "if(!t)t=(el.getAttribute&&el.getAttribute('aria-label'))||'';return t;}"
                    + "function seen(el){if(!el||el.disabled||el.hidden)return false;"
                    + "if(el.closest&&el.closest('[hidden],[aria-hidden=\"true\"],"
                    + "[style*=\"display: none\"],[style*=\"display:none\"]'))return false;"
                    + "var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}"
                    + "function provider(){var c=items();"
                    + "for(var i=0;i<c.length;i++){var el=c[i];var t=label(el).toLowerCase();"
                    + "if(seen(el)&&(t.indexOf('google')>=0||t.indexOf('github')>=0))return el;}"
                    + "return null;}"
                    // Already showing the dialog? Nothing to do.
                    + "if(provider())return 'open';"
                    + "var c=items();"
                    + "var exact=/^(log ?in|sign ?in|sign ?up|log ?in ?\\/ ?sign ?up)$/i;"
                    + "for(var i=0;i<c.length;i++){var el=c[i];"
                    + "if(exact.test(label(el))&&seen(el)){el.click();return 'clicked';}}"
                    // A control whose label merely mentions logging in.
                    + "for(var i=0;i<c.length;i++){var el=c[i];"
                    + "if(/log ?in|sign ?in/i.test(label(el))&&seen(el)){el.click();"
                    + "return 'clicked';}}"
                    + "return 'none';"
                    + "}catch(e){return 'error:'+e.message;}})();";

    /**
     * Presses the provider button inside the open dialog. "{PROVIDER}" is replaced by
     * "google" or "github".
     *
     * Answer: "clicked", "open" (already on the provider's own page) or "none".
     */
    static final String CHOOSE_PROVIDER_JS =
            "(function(){try{"
                    + "var name='{PROVIDER}';"
                    + "var h=(location.hostname||'').toLowerCase();"
                    + "if(h.indexOf(name)>=0)return 'open';"
                    + "var c=document.querySelectorAll("
                    + "'button,a,[role=\"button\"],[role=\"menuitem\"],div[tabindex]');"
                    + "function label(el){var t=(el.innerText||el.textContent||'').trim();"
                    + "if(!t)t=(el.getAttribute&&el.getAttribute('aria-label'))||'';"
                    + "return t.toLowerCase();}"
                    + "function seen(el){if(!el||el.disabled||el.hidden)return false;"
                    + "if(el.closest&&el.closest('[hidden],[aria-hidden=\"true\"],"
                    + "[style*=\"display: none\"],[style*=\"display:none\"]'))return false;"
                    + "var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}"
                    + "var best=null;"
                    + "for(var i=0;i<c.length;i++){var el=c[i];"
                    + "if(!seen(el))continue;"
                    + "if(label(el).indexOf(name)<0)continue;"
                    // Skip footer links that name the provider without being its button.
                    + "if(el.closest&&el.closest('footer,[class*=\"footer\" i]'))continue;"
                    + "best=el;break;}"
                    + "if(best){best.click();return 'clicked';}"
                    // Some builds render the provider as a bare link.
                    + "var a=document.querySelector('a[href*=\"'+name+'\"]');"
                    + "if(a){a.click();return 'clicked';}"
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
     */
    static void step(final Runner runner, String script, int attempts, long delayMs,
                     final android.os.Handler handler, final Result result) {
        final int[] left = {Math.max(1, attempts)};
        final Runnable[] again = new Runnable[1];
        again[0] = () -> runner.run(script, null, value -> {
            String answer = clean(value);
            boolean done = CLICKED.equals(answer) || ALREADY.equals(answer);
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
