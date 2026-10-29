package sk.bambuwidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

public class WidgetProvider extends AppWidgetProvider {
    static final String ACTION_REFRESH = "sk.bambuwidget.REFRESH";


    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        render(c, m, ids);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (ACTION_REFRESH.equals(i.getAction())) {
            final PendingResult pending = goAsync();
            Updater.runInBackground(c.getApplicationContext(), new Runnable() {
                @Override
                public void run() { pending.finish(); }
            });
        } else {
            super.onReceive(c, i);
        }
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        render(c, m, m.getAppWidgetIds(new ComponentName(c, WidgetProvider.class)));
    }

    static String minutes(int min) { return Views.minutes(min); }

    private static void render(Context c, AppWidgetManager m, int[] ids) {
        if (ids == null || ids.length == 0) return;
        RemoteViews v = new RemoteViews(c.getPackageName(), Views.widgetLayout(c));
        v.removeAllViews(R.id.rows);
        int rowLayout = Views.rowLayout(c);

        JSONArray ps = Store.shownPrinters(c);
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null) v.addView(R.id.rows, Views.row(c, rowLayout, p, true));
        }

        long at = Store.updatedAt(c);
        String updated = Store.refreshing(c) ? "Updating\u2026" : (at > 0 ? DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at)) : "");
        v.setTextViewText(R.id.updated, updated);

        String msg = Store.message(c);
        if (!Store.signedIn(c)) msg = "Tap to open Bambu Widget and sign in.";
        else if (ps.length() == 0 && msg.length() == 0 && Store.printers(c).length() > 0) msg = "All printers are hidden. Open Bambu Widget to choose which to show.";
        else if (ps.length() == 0 && msg.length() == 0) msg = Store.refreshing(c) ? "Loading printers\u2026" : "No printers yet. Tap to update.";
        v.setTextViewText(R.id.message, msg);
        v.setViewVisibility(R.id.message, msg.length() > 0 ? View.VISIBLE : View.GONE);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), flags);
        Intent r = new Intent(c, WidgetProvider.class).setAction(ACTION_REFRESH);
        PendingIntent refresh = PendingIntent.getBroadcast(c, 1, r, flags);
        v.setOnClickPendingIntent(R.id.header, open);
        v.setOnClickPendingIntent(R.id.root, Store.signedIn(c) ? refresh : open);

        m.updateAppWidget(ids, v);
    }
}
