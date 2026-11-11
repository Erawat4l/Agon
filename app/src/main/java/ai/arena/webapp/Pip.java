package ai.arena.webapp;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Rational;

/**
 * Picture-in-picture for the whole app: the page you are on shrinks into a small floating
 * window when you go to the home screen, and keeps running there.
 *
 * What this is not: a video player feature. There is no video element involved anywhere -
 * any page can go into the floating window, because the floating window is the app. That
 * distinction matters: a video-shaped rule ("only while something is playing") would leave
 * a chat that is still streaming, a file download, or a page you simply want to watch, with
 * nothing to show for itself.
 *
 * How it is built:
 *
 *  - the app's own rows - the tab strip and the nav - are hidden while the window is up
 *    (MainActivity.pipChromeVisible), because they are rows of the same column as the page
 *    and a 200-pixel-tall tab strip inside a floating window is most of it;
 *  - the window's shape is the app's own shape, so it looks like a small version of the app
 *    rather than a letterboxed page;
 *  - Android 12 and up is asked to enter the window by itself when the user goes home
 *    (autoEnterEnabled), which animates instead of cutting; older versions use the
 *    leave hint, which is the same moment without the animation;
 *  - the switch is read at that moment, and a phone that cannot do picture-in-picture is
 *    never offered it.
 */
public final class Pip {

    /** Android 8: picture-in-picture exists. */
    public static final int MIN_FOR_PIP = Build.VERSION_CODES.O;
    /** Android 12: enter-on-home without a manual call, with the proper transition. */
    public static final int MIN_FOR_AUTO = Build.VERSION_CODES.S;

    /**
     * The aspect ratios Android accepts for a window, roughly 1:2.39 to 2.39:1. The app's own
     * shape is clamped into that range rather than guessed at.
     */
    private static final double MIN_RATIO = 1d / 2.39d;
    private static final double MAX_RATIO = 2.39d;

    private final Activity activity;

    public Pip(Activity activity) {
        this.activity = activity;
    }

    /** True when this phone can show a picture-in-picture window at all. */
    public static boolean supported(Context c) {
        return Build.VERSION.SDK_INT >= MIN_FOR_PIP
                && c.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /** The setting is only shown where it would do something. */
    public static boolean available(Context c) {
        return supported(c);
    }

    /** True while the app is in a floating window. */
    public boolean inPip() {
        if (Build.VERSION.SDK_INT < MIN_FOR_PIP) return false;
        return activity.isInPictureInPictureMode();
    }

    /**
     * Keeps Android's own parameters in step with the setting, and turns on enter-on-home
     * where the version has it.
     *
     * Called when the app comes to the front and when the switch changes: the parameters are
     * what Android reads when the user leaves, so they have to be current before that.
     */
    public void sync() {
        if (Build.VERSION.SDK_INT < MIN_FOR_PIP) return;
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                    .setAspectRatio(shape());
            if (Build.VERSION.SDK_INT >= MIN_FOR_AUTO) {
                builder.setAutoEnterEnabled(Settings.pipApp(activity));
            }
            activity.setPictureInPictureParams(builder.build());
        } catch (RuntimeException ignored) {
            // A ROM that refuses the parameters must not take the app down over them.
        }
    }

    /**
     * Puts the app into the floating window now.
     *
     * Only used below Android 12, where entering has to be asked for at the moment the user
     * leaves. On 12+ Android enters by itself (see sync) and calling this as well would be a
     * second entry with no animation.
     */
    public void enter() {
        if (!supported(activity) || !Settings.pipApp(activity)) return;
        if (Build.VERSION.SDK_INT >= MIN_FOR_AUTO) return;   // Android does it, smoothly
        if (inPip()) return;
        try {
            activity.enterPictureInPictureMode(
                    new PictureInPictureParams.Builder().setAspectRatio(shape()).build());
        } catch (RuntimeException ignored) {
            // A policy or a cut-down ROM: the app simply stays in the background.
        }
    }

    /** The window's shape: the app's own, within what Android allows. */
    @TargetApi(Build.VERSION_CODES.O)
    private Rational shape() {
        int w = activity.getWindow() == null ? 0
                : activity.getWindow().getDecorView().getWidth();
        int h = activity.getWindow() == null ? 0
                : activity.getWindow().getDecorView().getHeight();
        if (w <= 0 || h <= 0) return new Rational(9, 16);   // a phone, before it is measured
        double ratio = (double) w / (double) h;
        if (ratio < MIN_RATIO) ratio = MIN_RATIO;
        if (ratio > MAX_RATIO) ratio = MAX_RATIO;
        // A small denominator keeps the Rational exact enough and readable in logs.
        int rw = (int) Math.round(ratio * 1000d);
        return new Rational(rw, 1000);
    }
}
