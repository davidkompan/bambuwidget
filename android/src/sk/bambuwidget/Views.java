package sk.bambuwidget;

import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/** Builds the printer rows / columns shared by the widget and the notification. */
final class Views {
    private static final int[] BARS = { R.id.bar_blue, R.id.bar_green, R.id.bar_amber, R.id.bar_red, R.id.bar_grey };
    private static final String[] COLORS = { Fetcher.BLUE, Fetcher.GREEN, Fetcher.AMBER, Fetcher.RED, Fetcher.GREY };
    private static final int[] TEXT = { 0xFF388BFD, 0xFF51A34F, 0xFFE6A028, 0xFFDC3C3C, 0xFF8A909B };

    /** Layout ids for the chosen theme: "auto" layouts follow the system through layout-night. */
    static int widgetLayout(Context c) {
        String t = Store.theme(c);
        return "dark".equals(t) ? R.layout.widget_dark : "light".equals(t) ? R.layout.widget_light : R.layout.widget_auto;
    }

    static int rowLayout(Context c) {
        String t = Store.theme(c);
        return "dark".equals(t) ? R.layout.row_dark : "light".equals(t) ? R.layout.row_light : R.layout.row_auto;
    }

    static String minutes(int min) {
        if (min < 0) return "";
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
    }

    private static int colorIndex(JSONObject p) {
        String color = p.optString("color", Fetcher.GREY);
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i].equals(color)) return i;
        return COLORS.length - 1;
    }

    private static boolean hasJob(JSONObject p) {
        return !Fetcher.GREY.equals(p.optString("color")) || p.optInt("percent") > 0;
    }

    private static void bar(RemoteViews r, JSONObject p, int ci) {
        for (int i = 0; i < BARS.length; i++) {
            r.setViewVisibility(BARS[i], i == ci ? View.VISIBLE : View.GONE);
            if (i == ci) r.setProgressBar(BARS[i], 100, p.optInt("percent", 0), false);
        }
    }

    /** Full row: name, model, state, file, bar with %, details. */
    static RemoteViews row(Context c, int layout, JSONObject p, boolean details) {
        RemoteViews r = new RemoteViews(c.getPackageName(), layout);
        int ci = colorIndex(p);
        r.setTextViewText(R.id.name, p.optString("name"));
        r.setTextViewText(R.id.model, p.optString("model"));
        r.setTextViewText(R.id.label, p.optString("label"));
        r.setTextColor(R.id.label, TEXT[ci]);
        r.setTextColor(R.id.dot, TEXT[ci]);
        String file = p.optString("file", "");
        r.setTextViewText(R.id.file, file);
        r.setViewVisibility(R.id.file, file.length() > 0 ? View.VISIBLE : View.GONE);
        bar(r, p, ci);
        r.setTextViewText(R.id.pct, hasJob(p) ? p.optInt("percent", 0) + " %" : "");

        StringBuilder meta = new StringBuilder();
        if (details) {
            long endsAt = p.optLong("endsAt", 0);
            if (endsAt > 0) {
                int left = (int) Math.max(0, (endsAt - System.currentTimeMillis()) / 60000L);
                append(meta, minutes(left) + " left");
                append(meta, "ends " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(endsAt)));
            }
            if (p.has("layer")) append(meta, "layer " + p.optString("layer"));
            if (p.has("nozzle")) append(meta, "nozzle " + p.optString("nozzle"));
            if (p.has("bed")) append(meta, "bed " + p.optString("bed"));
        }
        r.setTextViewText(R.id.meta, meta.toString());
        r.setViewVisibility(R.id.meta, meta.length() > 0 ? View.VISIBLE : View.GONE);
        return r;
    }

    /** Compact column: name, % (or state when there is no job / an error), thin bar. */
    static RemoteViews col(Context c, JSONObject p) {
        RemoteViews r = new RemoteViews(c.getPackageName(), R.layout.col_auto);
        int ci = colorIndex(p);
        r.setTextViewText(R.id.name, p.optString("name"));
        boolean showLabel = Fetcher.RED.equals(p.optString("color")) || !hasJob(p);
        r.setTextViewText(R.id.label, showLabel ? p.optString("label") : p.optInt("percent", 0) + " %");
        r.setTextColor(R.id.label, TEXT[ci]);
        bar(r, p, ci);
        return r;
    }

    private static void append(StringBuilder b, String s) {
        if (b.length() > 0) b.append(" · ");
        b.append(s);
    }
}
