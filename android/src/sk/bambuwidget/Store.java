package sk.bambuwidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Session and last fetched statuses, in the app's private preferences. */
final class Store {
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bambu", Context.MODE_PRIVATE);
    }

    static void saveSession(Context c, Cloud.Session s) {
        prefs(c).edit()
                .putString("region", s.region).putString("token", s.token)
                .putString("username", s.username).putLong("expiresAt", s.expiresAt)
                .apply();
    }

    static Cloud.Session session(Context c) {
        SharedPreferences p = prefs(c);
        String token = p.getString("token", "");
        if (token.length() == 0) return null;
        Cloud.Session s = new Cloud.Session();
        s.region = p.getString("region", "global");
        s.token = token;
        s.username = p.getString("username", "");
        s.expiresAt = p.getLong("expiresAt", 0);
        return s;
    }

    static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }

    static void saveStatus(Context c, JSONArray printers, String message) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (printers != null) {
            e.putString("printers", printers.toString());
            e.putLong("updatedAt", System.currentTimeMillis());
        }
        e.putString("message", message == null ? "" : message);
        e.apply();
    }

    static JSONArray printers(Context c) {
        try { return new JSONArray(prefs(c).getString("printers", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static long updatedAt(Context c) { return prefs(c).getLong("updatedAt", 0); }

    static String message(Context c) { return prefs(c).getString("message", ""); }

    static void setRefreshing(Context c, boolean on) { prefs(c).edit().putBoolean("refreshing", on).apply(); }

    static boolean refreshing(Context c) { return prefs(c).getBoolean("refreshing", false); }

    /** Update interval in minutes; 0 = only when tapped. */
    static int interval(Context c) { return prefs(c).getInt("interval", 15); }

    static void setInterval(Context c, int min) { prefs(c).edit().putInt("interval", min).apply(); }

    static boolean notification(Context c) { return prefs(c).getBoolean("notification", false); }

    static void setNotification(Context c, boolean on) { prefs(c).edit().putBoolean("notification", on).apply(); }

    /** "system", "dark" or "light" (app and widget; the notification always follows the system). */
    static String theme(Context c) { return prefs(c).getString("theme", "system"); }

    static void setTheme(Context c, String t) { prefs(c).edit().putString("theme", t).apply(); }

    /** Sign out: forget the session and statuses, keep the user's settings. */
    static void signOut(Context c) {
        prefs(c).edit().remove("token").remove("username").remove("region").remove("expiresAt")
                .remove("printers").remove("updatedAt").remove("message").remove("refreshing").apply();
    }
}
