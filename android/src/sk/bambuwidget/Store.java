package sk.bambuwidget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Accounts, last fetched statuses and settings, in the app's private preferences. */
final class Store {
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bambu", Context.MODE_PRIVATE);
    }

    // ---------- accounts ----------
    static String accountId(Cloud.Session s) { return s.region + ":" + s.username; }

    static List<Cloud.Session> accounts(Context c) {
        SharedPreferences p = prefs(c);
        List<Cloud.Session> out = new ArrayList<Cloud.Session>();
        String json = p.getString("accounts", null);
        if (json == null) {
            // Version 1.3 kept a single session in separate keys.
            String token = p.getString("token", "");
            if (token.length() == 0) return out;
            Cloud.Session s = new Cloud.Session();
            s.region = p.getString("region", "global");
            s.token = token;
            s.username = p.getString("username", "");
            s.expiresAt = p.getLong("expiresAt", 0);
            s.id = accountId(s);
            s.label = s.username;
            out.add(s);
            saveAccounts(c, out);
            p.edit().remove("token").remove("username").remove("region").remove("expiresAt").apply();
            return out;
        }
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Cloud.Session s = new Cloud.Session();
                s.region = o.optString("region", "global");
                s.token = o.optString("token", "");
                s.username = o.optString("username", "");
                s.expiresAt = o.optLong("expiresAt", 0);
                s.id = o.optString("id", accountId(s));
                s.label = o.optString("label", s.username);
                s.expired = o.optBoolean("expired", false) || s.token.length() == 0;
                out.add(s);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static synchronized void saveAccounts(Context c, List<Cloud.Session> list) {
        JSONArray a = new JSONArray();
        try {
            for (Cloud.Session s : list) {
                a.put(new JSONObject().put("id", s.id).put("label", s.label).put("region", s.region)
                        .put("token", s.expired ? "" : s.token).put("username", s.username)
                        .put("expiresAt", s.expiresAt).put("expired", s.expired));
            }
        } catch (Exception ignored) { }
        prefs(c).edit().putString("accounts", a.toString()).apply();
    }

    /** Adds the account, or replaces it when the same Bambu account signs in again. */
    static synchronized void addAccount(Context c, Cloud.Session s, String label) {
        s.id = accountId(s);
        s.expired = false;
        List<Cloud.Session> list = accounts(c);
        String old = null;
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(s.id)) { old = list.get(i).label; list.remove(i); }
        s.label = label != null && label.trim().length() > 0 ? label.trim() : old != null ? old : s.username;
        list.add(s);
        saveAccounts(c, list);
    }

    static synchronized void removeAccount(Context c, String id) {
        List<Cloud.Session> list = accounts(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        saveAccounts(c, list);
        // Drop that account's printers from the stored statuses right away.
        JSONArray kept = new JSONArray();
        JSONArray ps = printersRaw(c);
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p != null && !id.equals(p.optString("accountId"))) kept.put(p);
        }
        prefs(c).edit().putString("printers", kept.toString()).apply();
    }

    static synchronized void expire(Context c, String id) {
        List<Cloud.Session> list = accounts(c);
        for (Cloud.Session s : list) if (s.id.equals(id)) { s.expired = true; s.token = ""; }
        saveAccounts(c, list);
    }

    /** At least one account that can fetch. */
    static boolean signedIn(Context c) {
        for (Cloud.Session s : accounts(c)) if (!s.expired) return true;
        return false;
    }

    // ---------- statuses ----------
    static void saveStatus(Context c, JSONArray printers, String message) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (printers != null) {
            e.putString("printers", printers.toString());
            e.putLong("updatedAt", System.currentTimeMillis());
        }
        e.putString("message", message == null ? "" : message);
        e.apply();
    }

    private static JSONArray printersRaw(Context c) {
        try { return new JSONArray(prefs(c).getString("printers", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    /** All fetched printers, in the user's order (printers not ordered yet come last). */
    static JSONArray printers(Context c) {
        JSONArray ps = printersRaw(c);
        List<String> order = strings(c, "order");
        List<JSONObject> list = new ArrayList<JSONObject>();
        for (int i = 0; i < ps.length(); i++) if (ps.optJSONObject(i) != null) list.add(ps.optJSONObject(i));
        JSONArray out = new JSONArray();
        for (String serial : order) {
            for (JSONObject p : list) if (serial.equals(p.optString("serial"))) { out.put(p); break; }
        }
        for (JSONObject p : list) if (!order.contains(p.optString("serial"))) out.put(p);
        return out;
    }

    /** The printers shown in the widget and the notification. */
    static JSONArray shownPrinters(Context c) {
        Set<String> hidden = hidden(c);
        JSONArray all = printers(c), out = new JSONArray();
        for (int i = 0; i < all.length(); i++) if (!hidden.contains(all.optJSONObject(i).optString("serial"))) out.put(all.optJSONObject(i));
        return out;
    }

    static long updatedAt(Context c) { return prefs(c).getLong("updatedAt", 0); }

    static String message(Context c) { return prefs(c).getString("message", ""); }

    static void setRefreshing(Context c, boolean on) { prefs(c).edit().putBoolean("refreshing", on).apply(); }

    static boolean refreshing(Context c) { return prefs(c).getBoolean("refreshing", false); }

    // ---------- printer order and visibility ----------
    private static List<String> strings(Context c, String key) {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    private static void putStrings(Context c, String key, Iterable<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        prefs(c).edit().putString(key, a.toString()).apply();
    }

    static void setOrder(Context c, List<String> serials) { putStrings(c, "order", serials); }

    static Set<String> hidden(Context c) { return new HashSet<String>(strings(c, "hidden")); }

    static void setHidden(Context c, String serial, boolean hide) {
        Set<String> h = hidden(c);
        if (hide) h.add(serial); else h.remove(serial);
        putStrings(c, "hidden", h);
    }

    // ---------- settings ----------
    /** Update interval in minutes; 0 = only when tapped. */
    static int interval(Context c) { return prefs(c).getInt("interval", 15); }

    static void setInterval(Context c, int min) { prefs(c).edit().putInt("interval", min).apply(); }

    static boolean notification(Context c) { return prefs(c).getBoolean("notification", false); }

    static void setNotification(Context c, boolean on) { prefs(c).edit().putBoolean("notification", on).apply(); }

    /** "system", "dark" or "light" (app and widget; the notification always follows the system). */
    static String theme(Context c) { return prefs(c).getString("theme", "system"); }

    static void setTheme(Context c, String t) { prefs(c).edit().putString("theme", t).apply(); }

    /** Sign out of all accounts: forget them and the statuses, keep the user's settings. */
    static void signOut(Context c) {
        prefs(c).edit().remove("accounts").putString("accounts", "[]")
                .remove("printers").remove("updatedAt").remove("message").remove("refreshing").apply();
    }
}
