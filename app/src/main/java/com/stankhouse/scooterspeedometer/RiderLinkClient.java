package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RiderLinkClient {
    public interface Callback { void complete(boolean success, String message, String body); }
    public static final String URL = "https://jwjiujxdcybsjmxqjojl.supabase.co";
    public static final String KEY = "sb_publishable_G4ZDOqnyhCHO8CSg6swLTQ_P0HfD5gG";
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public RiderLinkClient(Context context) { prefs = context.getSharedPreferences("riderlink_session", Context.MODE_PRIVATE); }
    public boolean signedIn() { return !prefs.getString("access", "").isEmpty(); }
    public String userId() { return prefs.getString("user_id", ""); }
    public String email() { return prefs.getString("email", ""); }

    public void authenticate(String email, String password, boolean create, Callback callback) {
        try {
            JSONObject body = new JSONObject().put("email", email).put("password", password);
            request("POST", "/auth/v1/" + (create ? "signup" : "token?grant_type=password"), body.toString(), false, (ok, msg, data) -> {
                if (ok) {
                    try {
                        JSONObject root = new JSONObject(data); String access = root.optString("access_token", "");
                        JSONObject user = root.optJSONObject("user");
                        if (access.isEmpty()) { callback.complete(true, "Check your email to confirm the account, then sign in.", data); return; }
                        prefs.edit().putString("access", access).putString("refresh", root.optString("refresh_token", ""))
                                .putString("user_id", user == null ? "" : user.optString("id", ""))
                                .putString("email", email).apply();
                    } catch (Exception e) { callback.complete(false, "Invalid sign-in response", data); return; }
                }
                callback.complete(ok, msg, data);
            });
        } catch (Exception e) { callback.complete(false, e.getMessage(), ""); }
    }

    public void saveProfile(String username, String scooter, String privacy, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("id", userId()).put("username", username).put("scooter", scooter).put("privacy", privacy); }
        catch (Exception ignored) { }
        rest("POST", "/rest/v1/profiles?on_conflict=id", body.toString(), "resolution=merge-duplicates,return=minimal", callback);
    }
    public void updatePresence(double lat, double lon, float accuracy, String privacy, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("user_id", userId()).put("latitude", lat).put("longitude", lon).put("accuracy", accuracy)
                .put("privacy", privacy).put("updated_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(new java.util.Date())); }
        catch (Exception ignored) { }
        rest("POST", "/rest/v1/rider_presence?on_conflict=user_id", body.toString(), "resolution=merge-duplicates,return=minimal", callback);
    }
    public void goInvisible(Callback callback) { rest("DELETE", "/rest/v1/rider_presence?user_id=eq." + userId(), null, "return=minimal", callback); }
    public void nearby(double lat, double lon, Callback callback) {
        try { rest("POST", "/rest/v1/rpc/nearby_riders", new JSONObject().put("p_lat", lat).put("p_lon", lon).put("p_radius_km", 25).toString(), "return=representation", callback); }
        catch (Exception e) { callback.complete(false, e.getMessage(), "[]"); }
    }
    public void nearbySos(double lat, double lon, Callback callback) {
        try { rest("POST", "/rest/v1/rpc/nearby_sos", new JSONObject().put("p_lat", lat).put("p_lon", lon).put("p_radius_km", 40).toString(), "return=representation", callback); }
        catch (Exception e) { callback.complete(false, e.getMessage(), "[]"); }
    }
    public void sendSos(double lat, double lon, String note, Callback callback) {
        try { rest("POST", "/rest/v1/sos_alerts", new JSONObject().put("user_id", userId()).put("latitude", lat)
                .put("longitude", lon).put("message", note).put("active", true).toString(), "return=representation", callback); }
        catch (Exception e) { callback.complete(false, e.getMessage(), ""); }
    }
    public void cancelSos(Callback callback) { rest("PATCH", "/rest/v1/sos_alerts?user_id=eq." + userId() + "&active=eq.true", "{\"active\":false}", "return=minimal", callback); }
    public void signOut() { prefs.edit().clear().apply(); }
    public void shutdown() { worker.shutdownNow(); }

    private void rest(String method, String path, String body, String prefer, Callback callback) {
        request(method, path, body, true, (ok, msg, data) -> callback.complete(ok, msg, data), prefer);
    }
    private void request(String method, String path, String body, boolean auth, Callback callback) { request(method, path, body, auth, callback, null); }
    private void request(String method, String path, String body, boolean auth, Callback callback, String prefer) {
        worker.execute(() -> {
            HttpURLConnection connection = null; boolean ok = false; String response = ""; String message;
            try {
                connection = (HttpURLConnection) new URL(URL + path).openConnection(); connection.setRequestMethod(method);
                connection.setConnectTimeout(9000); connection.setReadTimeout(10000); connection.setRequestProperty("apikey", KEY);
                connection.setRequestProperty("Content-Type", "application/json");
                if (auth) connection.setRequestProperty("Authorization", "Bearer " + prefs.getString("access", ""));
                if (prefer != null) connection.setRequestProperty("Prefer", prefer);
                if (body != null) { connection.setDoOutput(true); byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    try (OutputStream out = connection.getOutputStream()) { out.write(bytes); } }
                int code = connection.getResponseCode(); ok = code >= 200 && code < 300;
                InputStream stream = ok ? connection.getInputStream() : connection.getErrorStream();
                if (stream != null) { BufferedReader reader = new BufferedReader(new InputStreamReader(stream)); StringBuilder text = new StringBuilder();
                    String line; while ((line = reader.readLine()) != null) text.append(line); response = text.toString(); reader.close(); }
                message = ok ? "Done" : parseError(response, "RiderLink request failed (" + code + ")");
            } catch (Exception e) { message = e.getMessage() == null ? "Network error" : e.getMessage(); }
            finally { if (connection != null) connection.disconnect(); }
            final boolean deliveredOk = ok; final String deliveredMessage = message, deliveredBody = response;
            main.post(() -> callback.complete(deliveredOk, deliveredMessage, deliveredBody));
        });
    }
    private String parseError(String body, String fallback) {
        try { JSONObject error = new JSONObject(body); return error.optString("msg", error.optString("message", fallback)); }
        catch (Exception ignored) { return fallback; }
    }
}
