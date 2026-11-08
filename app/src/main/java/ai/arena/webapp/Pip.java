package ai.arena.webapp;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Rational;
import android.webkit.WebView;

/**
 * Picture-in-picture: a playing video keeps playing in a small floating window when the app
 * is sent to the background.
 *
 * Why it is built this way:
 *
 *  - <b>The video fills the window.</b> Android's PiP shrinks the whole activity, which for
 *    a browser means a shrunk web page with a tiny video in the middle. So the tab's WebView
 *    is briefly made to cover the app and the page is asked to make the video it is playing
 *    fill the viewport; the page behind it is hidden rather than laid out - nothing moves,
 *    nothing reflows, and on the way back the page puts the video back exactly where it was.
 *  - <b>Nothing happens without a playing video.</b> The app asks the page whether a
 *    &lt;video&gt; is actually playing before it enters. No video, no floating window - pressing
 *    Home behaves normally, which is what a browser should do.
 *  - <b>The user can turn it off.</b> Settings -> "Video keeps playing when you leave".
 *  - <b>Old phones are told the truth.</b> Below Android 8 there is no PiP at all, and
 *    before Android 12 the window cannot be asked for on Home; the switch is only offered
 *    where the feature exists.
 */
public final class Pip {

    /** Android 8: picture-in-picture exists, and it can be used when leaving the app. */
    public static final int MIN_FOR_PIP = Build.VERSION_CODES.O;
    /** Android 12: the in-app PiP button and a real Home-button transition. */
    public static final int MIN_FOR_SMOOTH = Build.VERSION_CODES.S;

    /** Jump to 10 s back, 10 s forward, and toggle play/pause - the three the page exposes. */
    private static final String ACTION_REWIND = "ai.arena.webapp.pip.REWIND";
    private static final String ACTION_PLAY_PAUSE = "ai.arena.webapp.pip.PLAY_PAUSE";
    private static final String ACTION_FORWARD = "ai.arena.webapp.pip.FORWARD";

    private final Activity activity;
    private BroadcastReceiver receiver;
    /** True while the page has been shaped for the floating window. */
    private boolean shaped;

    public Pip(Activity activity) {
        this.activity = activity;
    }

    /** True when this phone can show a picture-in-picture window at all. */
    public static boolean supported(Context c) {
        return Build.VERSION.SDK_INT >= MIN_FOR_PIP
                && c.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /** The switch is only offered where it would do something. */
    public static boolean available(Context c) {
        return supported(c);
    }

    /** True while the app is in a floating window. */
    public boolean inPip() {
        if (Build.VERSION.SDK_INT < MIN_FOR_PIP) return false;
        return activity.isInPictureInPictureMode();
    }

    /**
     * Enters the floating window if - and only if - a video is playing in `page`.
     *
     * `page` is the tab on screen. Its WebView is the only one touched: the other tabs are
     * not on screen and are not a video the user is watching.
     */
    public void enterIfPlaying(final WebView page) {
        if (!supported(activity) || !Settings.pipVideo(activity)) return;
        if (page == null || inPip()) return;
        final String probe = videoPlayingJs();
        try {
            page.evaluateJavascript(probe, answer -> {
                if (!"true".equals(answer)) return;
                // The page can only be shaped from inside it, and only while it is the
                // visible one: this is the same view the floating window will show.
                shape(page);
                enter();
            });
        } catch (RuntimeException ignored) {
            // A dead renderer has no video to keep playing.
        }
    }

    /** Asks Android for the floating window, with the video's own shape. */
    private void enter() {
        if (Build.VERSION.SDK_INT < MIN_FOR_PIP) return;
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
            // 16:9 is the shape of nearly every video and it is a legal PiP aspect ratio on
            // every version; a page without a video never gets this far.
            builder.setAspectRatio(new Rational(16, 9));
            if (Build.VERSION.SDK_INT >= MIN_FOR_SMOOTH) {
                // The reason-less form: Android 12 decides by itself whether a swipe-Home is
                // a good moment, and it can animate into the window instead of cutting.
                activity.setPictureInPictureParams(builder.build());
                activity.enterPictureInPictureMode(builder.build());
            } else {
                activity.enterPictureInPictureMode(builder.build());
            }
            installActions();
        } catch (RuntimeException ignored) {
            // A phone that refuses PiP (a policy, a low-RAM cut-down ROM) must not take the
            // app down on the way to the Home screen: the video simply stops.
        }
    }

    /**
     * Called when the floating window is entered or left (from MainActivity's callback).
     *
     * Entering keeps the page shaped; leaving puts it back, because the user is coming back
     * to the tab as they left it.
     */
    public void onPipChanged(boolean inPip, WebView page) {
        if (!inPip && shaped) {
            restore(page);
        }
    }

    /**
     * Makes the page show nothing but its video.
     *
     * Only the page is touched here; the app's own strip and nav are hidden by the activity
     * (see MainActivity.pipChromeVisible), because they are rows of the same column as the
     * WebView and a floating window full of tab strip is not what the user asked for.
     */
    private void shape(WebView page) {
        shaped = true;
        try {
            page.evaluateJavascript(pipCssJs(true), null);
        } catch (RuntimeException ignored) {
        }
    }

    /** Puts the page back exactly as the CSS found it. */
    private void restore(WebView page) {
        shaped = false;
        if (page == null) return;
        try {
            page.evaluateJavascript(pipCssJs(false), null);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * The three buttons Android draws over/under a PiP window.
     *
     * They are the page's own video controls driven from outside: the page is asked to
     * nudge its &lt;video&gt; by ten seconds or to pause it, which is what the on-screen
     * controls would have done.
     */
    @TargetApi(Build.VERSION_CODES.O)
    private void installActions() {
        if (Build.VERSION.SDK_INT < MIN_FOR_PIP) return;
        if (receiver == null) {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent == null ? null : intent.getAction();
                    if (action == null) return;
                    WebView page = currentPage();
                    if (page == null) return;
                    try {
                        page.evaluateJavascript(videoActionJs(action), null);
                    } catch (RuntimeException ignored) {
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_REWIND);
            filter.addAction(ACTION_PLAY_PAUSE);
            filter.addAction(ACTION_FORWARD);
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(receiver, filter);
            }
        }
        if (Build.VERSION.SDK_INT < MIN_FOR_SMOOTH) return;
        java.util.List<RemoteAction> actions = new java.util.ArrayList<>();
        actions.add(remote(R.drawable.ic_pip_rewind, R.string.pip_rewind, ACTION_REWIND));
        actions.add(remote(R.drawable.ic_pip_play_pause, R.string.pip_play_pause, ACTION_PLAY_PAUSE));
        actions.add(remote(R.drawable.ic_pip_forward, R.string.pip_forward, ACTION_FORWARD));
        try {
            activity.setPictureInPictureParams(
                    new PictureInPictureParams.Builder()
                            .setAspectRatio(new Rational(16, 9))
                            .setActions(actions)
                            .build());
        } catch (RuntimeException ignored) {
            // A ROM with a smaller RemoteAction limit: the controls are optional.
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private RemoteAction remote(int iconRes, int labelRes, String action) {
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                activity, action.hashCode(),
                new Intent(action).setPackage(activity.getPackageName()),
                Build.VERSION.SDK_INT >= 23
                        ? android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE
                        : android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        return new RemoteAction(Icon.createWithResource(activity, iconRes),
                activity.getString(labelRes), activity.getString(labelRes), pi);
    }

    /** Unregisters the control receiver. Called from the activity's onDestroy. */
    public void release() {
        if (receiver != null) {
            try {
                activity.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
            }
            receiver = null;
        }
    }

    /** The tab on screen, through the activity. Kept as one call so the shape is obvious. */
    private WebView currentPage() {
        if (activity instanceof MainActivity) return ((MainActivity) activity).pipPage();
        return null;
    }

    // ------------------------------------------------------------------ page scripts

    /**
     * Answers "is a video playing in this page right now?".
     *
     * muted is not a reason to say no: an autoplaying muted video is exactly the case where
     * the user wants it to keep going. A video that is paused, ended, or too small to be the
     * thing they are watching is.
     */
    static String videoPlayingJs() {
        return "(function(){try{"
                + "var v=document.querySelectorAll('video'),i,e,b;"
                + "for(i=0;i<v.length;i++){e=v[i];b=e.getBoundingClientRect();"
                + "if(e.paused||e.ended||e.readyState<2)continue;"
                + "if(b.width<80||b.height<45)continue;"
                + "return true;}"
                + "return false;}catch(e){return false;}})();";
    }

    /**
     * Shapes the page for the floating window, and undoes it.
     *
     * Only the video's own inline styles are touched, and the originals are kept on the
     * element - so leaving the window restores the page to the pixel, including a video
     * that the site had styled itself. The rest of the page is hidden rather than removed:
     * `visibility` does not reflow a layout, so nothing jumps while the window is up.
     */
    static String pipCssJs(boolean on) {
        if (!on) {
            return "(function(){try{"
                    + "var r=window.__agonPip;if(!r)return;"
                    + "r.forEach(function(x){"
                    + "if(x.el&&x.css===null){x.el.removeAttribute('style');}"
                    + "else if(x.el){x.el.setAttribute('style',x.css);}});"
                    + "window.__agonPip=null;"
                    + "}catch(e){}})();";
        }
        return "(function(){try{"
                + "var v=document.querySelectorAll('video'),i,t=null;"
                + "for(i=0;i<v.length;i++){var e=v[i],b=e.getBoundingClientRect();"
                + "if(!e.paused&&!e.ended&&e.readyState>=2&&b.width>=80){t=e;break;}}"
                + "if(!t)return;"
                + "var r=[],b;"
                + "for(b=t.parentElement;b&&b!==document.documentElement;b=b.parentElement){"
                + "r.push({el:b,css:b.getAttribute('style')});}"
                + "r.push({el:t,css:t.getAttribute('style')});"
                + "r.push({el:document.documentElement,css:document.documentElement.getAttribute('style')});"
                + "window.__agonPip=r;"
                + "for(i=0;i<r.length-2;i++){"
                + "r[i].el.style.setProperty('visibility','visible','important');"
                + "r[i].el.style.setProperty('overflow','visible','important');}"
                + "document.body.style.setProperty('overflow','hidden','important');"
                + "document.body.style.setProperty('background','#000','important');"
                + "document.documentElement.style.setProperty('background','#000','important');"
                + "document.body.style.setProperty('visibility','hidden','important');"
                + "t.style.setProperty('position','fixed','important');"
                + "t.style.setProperty('left','0','important');"
                + "t.style.setProperty('top','0','important');"
                + "t.style.setProperty('width','100vw','important');"
                + "t.style.setProperty('height','100vh','important');"
                + "t.style.setProperty('object-fit','contain','important');"
                + "t.style.setProperty('z-index','2147483647','important');"
                + "t.style.setProperty('visibility','visible','important');"
                + "t.style.setProperty('background','#000','important');"
                + "try{t.controls=true;}catch(e){}"
                + "}catch(e){}})();";
    }

    /** The PiP buttons, as actions on the page's own video element. */
    static String videoActionJs(String action) {
        String body;
        if (ACTION_REWIND.equals(action)) {
            body = "v.currentTime=Math.max(0,v.currentTime-10);";
        } else if (ACTION_FORWARD.equals(action)) {
            body = "v.currentTime=Math.min(v.duration||1e9,v.currentTime+10);";
        } else {
            body = "if(v.paused)v.play().catch(function(){});else v.pause();";
        }
        return "(function(){try{"
                + "var a=document.querySelectorAll('video'),i,v=null;"
                + "for(i=0;i<a.length;i++){v=a[i];break;}"
                + "if(!v)return;" + body
                + "}catch(e){}})();";
    }
}
