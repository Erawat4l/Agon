package ai.arena.webapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/** One notification channel, one distinctive sound, no silent installs. */
public final class UpdateNotifications {

    public static final String CHANNEL_UPDATES = "arena_updates";
    public static final String CHANNEL_PROGRESS = "arena_updates_progress";
    public static final int ID_AVAILABLE = 7101;
    public static final int ID_PROGRESS = 7102;

    private UpdateNotifications() {
    }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // Update available: high importance so it pops up and plays our chime.
        NotificationChannel updates = new NotificationChannel(CHANNEL_UPDATES,
                context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_HIGH);
        updates.setDescription(context.getString(R.string.channel_updates_desc));
        updates.enableVibration(true);
        updates.setShowBadge(true);
        Uri sound = Uri.parse("android.resource://" + context.getPackageName() + "/"
                + R.raw.update_chime);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        updates.setSound(sound, attrs);
        updates.enableLights(true);
        updates.setLightColor(0xFF9E8BFF);
        nm.createNotificationChannel(updates);

        // Download progress: quiet, no sound, no vibration.
        NotificationChannel progress = new NotificationChannel(CHANNEL_PROGRESS,
                context.getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW);
        progress.setDescription(context.getString(R.string.channel_progress_desc));
        progress.setSound(null, null);
        progress.enableVibration(false);
        progress.setShowBadge(false);
        nm.createNotificationChannel(progress);
    }

    /** "An Arena update is ready" with the chime. Tapping it opens the update sheet. */
    public static void notifyUpdateFound(Context context, String newVersion, String current) {
        // Settings -> "Update notifications". The update still appears as the dot on
        // More; this only controls the chime and the notification.
        if (!Settings.notifyUpdates(context.getApplicationContext())) return;
        ensureChannels(context);
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent open = MainActivity.intentForUpdate(context, newVersion);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, 1, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_UPDATES)
                : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(context.getString(R.string.update_notification_text,
                        newVersion, current))
                .setStyle(new Notification.BigTextStyle().bigText(
                        context.getString(R.string.update_notification_big, newVersion, current)))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH);
            b.setDefaults(Notification.DEFAULT_VIBRATE);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION).build();
            b.setSound(Uri.parse("android.resource://" + context.getPackageName() + "/"
                    + R.raw.update_chime), attrs);
        }
        nm.notify(ID_AVAILABLE, b.build());
    }

    public static void notifyProgress(Context context, String text, int percent) {
        ensureChannels(context);
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_PROGRESS)
                : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.update_downloading))
                .setContentText(text)
                .setOngoing(true)
                .setProgress(100, Math.max(0, percent), percent <= 0)
                .setOnlyAlertOnce(true);
        nm.notify(ID_PROGRESS, b.build());
    }

    public static void cancelProgress(Context context) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID_PROGRESS);
    }

    public static void cancelAll(Context context) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.cancel(ID_AVAILABLE);
        nm.cancel(ID_PROGRESS);
    }

    /** Fallback sound for very old devices; keeps the API surface honest. */
    static Uri defaultAlarm() {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }
}
