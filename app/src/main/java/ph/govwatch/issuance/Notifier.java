package ph.govwatch.issuance;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.List;

/** Shows the "new issuances found" notification. */
final class Notifier {

    private static final String CHANNEL_ID = "giw-updates";

    private Notifier() {
    }

    static boolean alertsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    static void notifyNew(Context context, int totalNew, List<String> lines) {
        if (!alertsAllowed(context)) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "New issuances", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification.InboxStyle style = new Notification.InboxStyle();
        for (int i = 0; i < lines.size() && i < 6; i++) {
            style.addLine(lines.get(i));
        }
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(totalNew + (totalNew == 1 ? " new issuance found" : " new issuances found"))
                .setContentText(lines.isEmpty() ? "Open the app to see them" : lines.get(0))
                .setStyle(style)
                .setContentIntent(pi)
                .setAutoCancel(true);
        nm.notify(1001, b.build());
    }
}
