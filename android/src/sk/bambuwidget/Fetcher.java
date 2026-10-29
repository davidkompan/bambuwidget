package sk.bambuwidget;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One refresh: printer list over HTTPS, one status snapshot per printer over Bambu Cloud MQTT. */
final class Fetcher {
    private static final long WAIT_MS = 20000;
    private static final int CANCELLED = 50348044; // 0x0300400C, "task canceled": not a fault

    static final String BLUE = "blue", GREEN = "green", AMBER = "amber", RED = "red", GREY = "grey";

    /** Fetches every signed-in account; a printer shared with several accounts is listed once. */
    static synchronized void refresh(Context ctx) {
        List<Cloud.Session> accounts = Store.accounts(ctx);
        if (!Store.signedIn(ctx)) { Store.saveStatus(ctx, null, "Open Bambu Widget and sign in."); return; }
        boolean multi = accounts.size() > 1, fetched = false;
        JSONArray previous = Store.printers(ctx);
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<String>();
        StringBuilder msg = new StringBuilder();
        for (Cloud.Session s : accounts) {
            String prefix = multi ? s.label + ": " : "";
            if (s.expired) {
                line(msg, prefix + "Sign-in expired. Open Bambu Widget and sign in again.");
                keep(previous, s.id, out, seen);
                continue;
            }
            try {
                List<Cloud.Printer> printers = Cloud.listPrinters(s);
                Map<String, JSONObject> fields = new HashMap<String, JSONObject>();
                int online = 0;
                for (Cloud.Printer p : printers) if (p.online) online++;
                if (online > 0) collect(s, printers, fields, online);
                for (Cloud.Printer p : printers) {
                    if (seen.add(p.serial)) out.put(derive(p, fields.get(p.serial)).put("account", s.label).put("accountId", s.id));
                }
                fetched = true;
            } catch (Cloud.AuthException | MiniMqtt.AuthException e) {
                Store.expire(ctx, s.id);
                line(msg, prefix + "Sign-in expired. Open Bambu Widget and sign in again.");
                keep(previous, s.id, out, seen);
            } catch (Exception e) {
                line(msg, prefix + "Update failed: " + e.getMessage());
                keep(previous, s.id, out, seen);
            }
        }
        // Nothing new at all: keep the last statuses and their time.
        Store.saveStatus(ctx, fetched ? out : null, msg.toString());
    }

    /** Keeps an account's last known printers when it could not be updated. */
    private static void keep(JSONArray previous, String accountId, JSONArray out, Set<String> seen) {
        for (int i = 0; i < previous.length(); i++) {
            JSONObject p = previous.optJSONObject(i);
            if (p != null && accountId.equals(p.optString("accountId")) && seen.add(p.optString("serial"))) out.put(p);
        }
    }

    private static void line(StringBuilder b, String s) {
        if (b.length() > 0) b.append('\n');
        b.append(s);
    }

    private static void collect(Cloud.Session s, List<Cloud.Printer> printers, Map<String, JSONObject> fields, int online) throws Exception {
        MiniMqtt m = new MiniMqtt();
        m.connect(Cloud.mqttHost(s.region), 8883, "bambu-widget-android-" + Long.toHexString(System.nanoTime()), s.username, s.token, 15000);
        try {
            int seq = 1;
            for (Cloud.Printer p : printers) {
                if (!p.online) continue;
                m.subscribe("device/" + p.serial + "/report");
                m.publish("device/" + p.serial + "/request",
                        "{\"pushing\":{\"sequence_id\":\"" + (seq++) + "\",\"command\":\"pushall\",\"version\":1,\"push_target\":1}}");
            }
            long deadline = System.currentTimeMillis() + WAIT_MS;
            int complete = 0;
            while (complete < online) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                m.setTimeout((int) Math.max(500, left));
                MiniMqtt.Message msg;
                try { msg = m.read(); } catch (SocketTimeoutException e) { break; }
                String[] t = msg.topic.split("/");
                if (t.length < 2) continue;
                JSONObject print;
                try { print = new JSONObject(new String(msg.payload, MiniMqtt.UTF8)).optJSONObject("print"); } catch (Exception e) { continue; }
                if (print == null) continue;
                String cmd = print.optString("command", "push_status");
                if (!"push_status".equals(cmd)) continue;
                JSONObject f = fields.get(t[1]);
                boolean wasComplete = f != null && f.has("gcode_state");
                if (f == null) { f = new JSONObject(); fields.put(t[1], f); }
                Iterator<String> keys = print.keys();
                while (keys.hasNext()) { String k = keys.next(); f.put(k, print.get(k)); }
                if (!wasComplete && f.has("gcode_state")) complete++;
            }
        } finally {
            m.close();
        }
    }

    static JSONObject derive(Cloud.Printer p, JSONObject f) throws Exception {
        JSONObject o = new JSONObject().put("serial", p.serial).put("name", p.name).put("model", p.model);
        if (f == null || !f.has("gcode_state")) {
            return o.put("label", p.online ? "No data" : "Offline").put("color", GREY).put("percent", 0).put("remaining", -1);
        }
        String state = f.optString("gcode_state", "").toUpperCase();
        boolean active = state.equals("RUNNING") || state.equals("PAUSE") || state.equals("PREPARE") || state.equals("SLICING") || state.equals("INIT");
        int percent = f.optInt("mc_percent", 0);
        int remaining = active ? f.optInt("mc_remaining_time", -1) : -1;

        // "Error" only when a fault actually stopped or paused the job; warnings during a running print are ignored.
        int pe = f.optInt("print_error", 0);
        boolean cancelled = state.equals("FAILED") && pe == CANCELLED;
        boolean error = (state.equals("FAILED") && !cancelled) || (state.equals("PAUSE") && pausedByFault(f));
        String label, color;
        if (error) { label = "Error"; color = RED; }
        else if (cancelled) { label = "Cancelled"; color = GREY; }
        else if (state.equals("RUNNING")) { label = "Printing"; color = BLUE; }
        else if (state.equals("PAUSE")) { label = "Paused"; color = AMBER; }
        else if (active) { label = "Preparing"; color = BLUE; }
        else if (state.equals("FINISH")) { label = "Finished"; color = GREEN; percent = 100; }
        else { label = "Idle"; color = GREY; percent = 0; }
        o.put("label", label).put("color", color).put("percent", Math.max(0, Math.min(100, percent))).put("remaining", remaining);
        if (remaining >= 0) o.put("endsAt", System.currentTimeMillis() + remaining * 60000L);
        boolean hasJob = active || state.equals("FINISH") || state.equals("FAILED");
        if (hasJob) {
            String file = f.optString("subtask_name", "").trim();
            if (file.length() == 0) {
                file = f.optString("gcode_file", "").trim().replaceAll("^.*[\\\\/]", "")
                        .replaceAll("(?i)\\.gcode(\\.3mf)?$", "").replaceAll("(?i)\\.3mf$", "");
            }
            o.put("file", file);
            int total = f.optInt("total_layer_num", 0);
            if (total > 0) o.put("layer", f.optInt("layer_num", 0) + "/" + total);
        }
        if (f.has("nozzle_temper")) o.put("nozzle", temp(f, "nozzle_temper", "nozzle_target_temper"));
        if (f.has("bed_temper")) o.put("bed", temp(f, "bed_temper", "bed_target_temper"));
        return o;
    }

    private static String temp(JSONObject f, String cur, String target) {
        long c = Math.round(f.optDouble(cur, 0)), t = Math.round(f.optDouble(target, 0));
        return c + (t > 0 ? "/" + t : "") + " \u00B0C";
    }

    /** Pause stages the printer enters by itself because of a problem (user / G-code pauses are not faults). */
    private static final int[] FAULT_STAGES = { 6, 17, 20, 21, 23, 26, 27, 28, 32, 33, 34, 35 };

    /** Why is a paused printer paused: a print error, a fault stage, or a fatal HMS message. */
    private static boolean pausedByFault(JSONObject f) {
        int pe = f.optInt("print_error", 0);
        if (pe != 0 && pe != CANCELLED) return true;
        int stage = f.optInt("stg_cur", -1);
        for (int st : FAULT_STAGES) if (st == stage) return true;
        JSONArray hms = f.optJSONArray("hms");
        if (hms == null) return false;
        for (int i = 0; i < hms.length(); i++) {
            JSONObject h = hms.optJSONObject(i);
            if (h != null && (h.optLong("code", 0) >>> 16) == 1) return true; // fatal
        }
        return false;
    }
}
