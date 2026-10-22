package sk.bambuwidget;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Bambu Lab account API (same endpoints as Bambu Studio / OrcaSlicer). */
final class Cloud {
    static final class Session {
        String region = "global", token = "", username = "";
        long expiresAt;
    }

    static final class Printer {
        String serial, name, model;
        boolean online;
    }

    static final class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    static final class Response {
        int status;
        JSONObject json;
    }

    private static final long TOKEN_LIFETIME_S = 90L * 24 * 3600;
    private static final CookieManager COOKIES = new CookieManager();
    static { CookieHandler.setDefault(COOKIES); }

    static String api(String region) { return "china".equals(region) ? "https://api.bambulab.cn" : "https://api.bambulab.com"; }
    static String web(String region) { return "china".equals(region) ? "https://bambulab.cn" : "https://bambulab.com"; }
    static String mqttHost(String region) { return "china".equals(region) ? "cn.mqtt.bambulab.com" : "us.mqtt.bambulab.com"; }

    static Response request(String method, String url, JSONObject body, String bearer, String csrf) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-BBL-Client-Name", "OrcaSlicer");
        c.setRequestProperty("X-BBL-Client-Type", "slicer");
        c.setRequestProperty("X-BBL-Client-Version", "01.09.05.51");
        c.setRequestProperty("X-BBL-Language", "en-US");
        c.setRequestProperty("X-BBL-OS-Type", "android");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (csrf != null) c.setRequestProperty("x-bbl-csrf-token", csrf);
        if (body != null) {
            c.setDoOutput(true);
            OutputStream o = c.getOutputStream();
            o.write(body.toString().getBytes(MiniMqtt.UTF8));
            o.close();
        }
        Response r = new Response();
        try {
            r.status = c.getResponseCode();
        } catch (IOException e) {
            throw new IOException("Cannot reach Bambu Lab (" + e.getMessage() + ")");
        }
        InputStream is = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (is != null) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
            is.close();
            text = new String(b.toByteArray(), MiniMqtt.UTF8);
        }
        c.disconnect();
        if ((r.status == 403 || r.status == 429) && text.toLowerCase().contains("cloudflare")) {
            throw new IOException("Bambu Lab blocked the request for now. Try again in a few minutes, or sign in with a token.");
        }
        try { r.json = new JSONObject(text); } catch (Exception e) { r.json = new JSONObject(); }
        return r;
    }

    private static IOException error(Response r, String what) {
        String d = r.json.optString("message", r.json.optString("error", ""));
        return new IOException(what + (d.length() > 0 && !"success".equals(d) ? ": " + d : " (HTTP " + r.status + ")"));
    }

    private static String cookie(String url, String name) {
        try {
            for (HttpCookie c : COOKIES.getCookieStore().get(new URI(url))) if (name.equals(c.getName())) return c.getValue();
        } catch (Exception ignored) { }
        return null;
    }

    /** Sign-in in up to two steps; holds the email / 2FA key between them. */
    static final class Login {
        String email = "", region = "global", tfaKey;

        /** Returns a session, or null when a code is required ("code" or "tfa" in {@link #need}). */
        String need;

        Session start(String email, String password, String region) throws Exception {
            this.email = email.trim();
            this.region = region;
            tfaKey = null;
            need = null;
            COOKIES.getCookieStore().removeAll();
            JSONObject b = new JSONObject().put("account", this.email).put("password", password).put("apiError", "");
            Response r = request("POST", api(region) + "/v1/user-service/user/login", b, null, null);
            if (r.status >= 400) throw error(r, "Sign-in failed");
            String token = r.json.optString("accessToken", "");
            if (token.length() > 0) return fromToken(region, token, r.json.optLong("expiresIn", 0));
            String type = r.json.optString("loginType", "");
            if ("verifyCode".equals(type)) { sendCode(); need = "code"; return null; }
            if ("tfa".equals(type) && r.json.optString("tfaKey", "").length() > 0) { tfaKey = r.json.getString("tfaKey"); need = "tfa"; return null; }
            throw new IOException("Unexpected sign-in response from Bambu Lab.");
        }

        void sendCode() throws Exception {
            Response r = request("POST", api(region) + "/v1/user-service/user/sendemail/code",
                    new JSONObject().put("email", email).put("type", "codeLogin"), null, null);
            if (r.status >= 400) throw error(r, "Could not send the code");
        }

        Session verifyCode(String code) throws Exception {
            Response r = request("POST", api(region) + "/v1/user-service/user/login",
                    new JSONObject().put("account", email).put("code", code.trim()), null, null);
            if (r.status == 400 && r.json.optInt("code") == 1) { sendCode(); throw new IOException("The code has expired. A new one has been emailed to you."); }
            if (r.status == 400 && r.json.optInt("code") == 2) throw new IOException("Wrong code.");
            String token = r.json.optString("accessToken", "");
            if (r.status >= 400 || token.length() == 0) throw error(r, "Code verification failed");
            return fromToken(region, token, r.json.optLong("expiresIn", 0));
        }

        Session verifyTfa(String code) throws Exception {
            if (tfaKey == null) throw new IOException("Start signing in again.");
            request("GET", web(region) + "/api/csrf", null, null, null);
            String csrf = cookie(web(region), "bbl_csrf_token");
            if (csrf == null) throw new IOException("Could not start two-factor sign-in.");
            Response r = request("POST", web(region) + "/api/sign-in/tfa",
                    new JSONObject().put("tfaKey", tfaKey).put("tfaCode", code.trim()), null, csrf);
            String token = cookie(web(region), "token");
            if (r.status >= 400 || token == null) throw new IOException("Wrong authenticator code.");
            return fromToken(region, token, 0);
        }
    }

    static Session fromToken(String region, String token, long expiresIn) throws Exception {
        token = token.trim();
        if (token.length() == 0) throw new IOException("The token is empty.");
        String username = usernameFromJwt(token);
        if (username == null) {
            Response r = request("GET", api(region) + "/v1/design-user-service/my/preference", null, token, null);
            if (r.status == 401 || r.status == 403) throw new IOException("Bambu Lab did not accept the token.");
            if (r.json.has("uid")) username = "u_" + r.json.get("uid");
        }
        if (username == null) throw new IOException("Could not read your Bambu user ID.");
        Session s = new Session();
        s.region = region;
        s.token = token;
        s.username = username;
        s.expiresAt = System.currentTimeMillis() + (expiresIn > 0 ? expiresIn : TOKEN_LIFETIME_S) * 1000;
        return s;
    }

    private static String usernameFromJwt(String token) {
        String[] p = token.split("\\.");
        if (p.length != 3) return null;
        try {
            JSONObject j = new JSONObject(new String(Base64.decode(p[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), MiniMqtt.UTF8));
            String u = j.optString("username", "");
            return u.matches("u_\\d+") ? u : null;
        } catch (Exception e) {
            return null;
        }
    }

    static List<Printer> listPrinters(Session s) throws Exception {
        Response r = request("GET", api(s.region) + "/v1/iot-service/api/user/bind", null, s.token, null);
        if (r.status == 401 || r.status == 403) throw new AuthException("Your sign-in has expired. Open the app and sign in again.");
        JSONArray devs = r.json.optJSONArray("devices");
        if (r.status >= 400 || devs == null) throw error(r, "Could not load the printer list");
        List<Printer> out = new ArrayList<Printer>();
        for (int i = 0; i < devs.length(); i++) {
            JSONObject d = devs.getJSONObject(i);
            String id = d.optString("dev_id", "");
            if (id.length() == 0) continue;
            Printer p = new Printer();
            p.serial = id;
            p.name = d.optString("name", id);
            p.model = d.optString("dev_product_name", d.optString("dev_model_name", ""));
            p.online = d.optBoolean("online", false);
            out.add(p);
        }
        return out;
    }
}
