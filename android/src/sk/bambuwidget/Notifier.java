package sk.bambuwidget;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;


/**
 * Optional permanent status bar notification with one line per printer.
 * Compiled against API 23, so the notification channel (API 26+) is created through reflection.
 */
final class Notifier {
    private static final int ID = 1;
    private static final String CHANNEL = "status";

    static void update(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (!Store.notification(c) || Store.session(c) == null) { nm.cancel(ID); return; }

        JSONArray ps = Store.printers(c);
        // Collapsed: all printers side by side. Expanded: one block per printer (details dropped above 3
        // printers, Android limits the expanded height).
        RemoteViews small = new RemoteViews(c.getPackageName(), R.layout.notif_small);
        RemoteViews big = new RemoteViews(c.getPackageName(), R.layout.notif_big);
        small.removeAllViews(R.id.cols);
        big.removeAllViews(R.id.rows);
        boolean error = false;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            if (Fetcher.RED.equals(p.optString("color"))) error = true;
            small.addView(R.id.cols, Views.col(c, p));
            big.addView(R.id.rows, Views.row(c, R.layout.row_auto, p, ps.length() <= 3));
            if (text.length() > 0) text.append(" \u00B7 ");
            text.append(p.optString("name")).append(' ').append(p.optString("label"));
        }
        if (ps.length() == 0) {
            String msg = Store.message(c).length() > 0 ? Store.message(c) : "No data yet";
            text.append(msg);
        }

        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long at = Store.updatedAt(c);
        Notification.Builder b = new Notification.Builder(c)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(error ? "Printer error" : "3D printers")
                .setContentText(text.toString()) // used by screen readers and where custom views are not shown
                .setColor(error ? 0xFFDC3C3C : 0xFF51A34F)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(at > 0 ? at : System.currentTimeMillis())
                .setShowWhen(at > 0)
                .setPriority(Notification.PRIORITY_LOW)
                .setContentIntent(open);

        Notification n;
        if (ps.length() > 0 && Build.VERSION.SDK_INT >= 24) {
            // API 24+: system header (icon, app name, time, expand arrow) around our own content.
            try {
                b.setStyle((Notification.Style) Class.forName("android.app.Notification$DecoratedCustomViewStyle").newInstance());
                Notification.Builder.class.getMethod("setCustomContentView", RemoteViews.class).invoke(b, small);
                Notification.Builder.class.getMethod("setCustomBigContentView", RemoteViews.class).invoke(b, big);
            } catch (Exception ignored) { }
            if (Build.VERSION.SDK_INT >= 26) {
                ensureChannel(nm);
                try { Notification.Builder.class.getMethod("setChannelId", String.class).invoke(b, CHANNEL); } catch (Exception ignored) { }
            }
            n = b.build();
        } else if (ps.length() > 0) {
            b.setContent(small);
            n = b.build();
            n.bigContentView = big;
        } else {
            if (Build.VERSION.SDK_INT >= 26) {
                ensureChannel(nm);
                try { Notification.Builder.class.getMethod("setChannelId", String.class).invoke(b, CHANNEL); } catch (Exception ignored) { }
            }
            n = b.build();
        }
        nm.notify(ID, n);
    }

    static void cancel(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(ID);
    }

    private static void ensureChannel(NotificationManager nm) {
        try {
            Class<?> ch = Class.forName("android.app.NotificationChannel");
            Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, "Printer status", 2 /* IMPORTANCE_LOW: no sound */);
            ch.getMethod("setShowBadge", boolean.class).invoke(channel, false);
            NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
        } catch (Exception ignored) { }
    }
}
