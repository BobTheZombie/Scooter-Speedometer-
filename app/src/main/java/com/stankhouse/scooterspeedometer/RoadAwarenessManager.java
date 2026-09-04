package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Key-free OSM speed-limit lookup plus private, on-device hazard markers. */
public class RoadAwarenessManager implements TextToSpeech.OnInitListener {
    public interface Callback { void onRoadDataChanged(); void onHazard(String message); }
    public static final String[] HAZARD_TYPES = {"Pothole", "Loose gravel", "Flooding", "Police", "Construction", "Dangerous intersection"};
    private static final long LIMIT_REFRESH_MS = 30000L;
    private final Context context;
    private final SharedPreferences prefs;
    private final Callback callback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextToSpeech speech;
    private boolean speechReady, fetching;
    private long lastFetch, lastSpeedSpeech;
    private Location lastLookup;
    private double limitMph = -1;
    private String roadName = "";

    public RoadAwarenessManager(Context context, Callback callback) {
        this.context = context.getApplicationContext(); this.callback = callback;
        prefs = this.context.getSharedPreferences("road_awareness", Context.MODE_PRIVATE);
        limitMph = prefs.getFloat("limit_mph", -1); roadName = prefs.getString("road_name", "");
        speech = new TextToSpeech(this.context, this);
    }
    @Override public void onInit(int status) { speechReady = status == TextToSpeech.SUCCESS; if (speechReady) speech.setLanguage(Locale.US); }
    public double limitMph() { return limitMph; }
    public String roadName() { return roadName; }
    public int thresholdMph() { return prefs.getInt("threshold", 5); }
    public void setThresholdMph(int value) { prefs.edit().putInt("threshold", value).apply(); callback.onRoadDataChanged(); }
    public boolean voiceEnabled() { return prefs.getBoolean("voice", true); }
    public void setVoiceEnabled(boolean value) { prefs.edit().putBoolean("voice", value).apply(); if (!value) speech.stop(); }
    public boolean hazardsEnabled() { return prefs.getBoolean("hazards_enabled", true); }
    public void setHazardsEnabled(boolean value) { prefs.edit().putBoolean("hazards_enabled", value).apply(); }
    public int hazardCount() { return hazards().length(); }
    public void clearHazards() { prefs.edit().remove("hazards").apply(); callback.onRoadDataChanged(); }

    public void update(Location location, float speedMps) {
        if (location == null) return;
        if (hazardsEnabled()) checkHazards(location);
        checkSpeed(speedMps * 2.2369363f);
        long now = System.currentTimeMillis();
        if (!fetching && now - lastFetch >= LIMIT_REFRESH_MS &&
                (lastLookup == null || lastLookup.distanceTo(location) >= 45f)) fetchLimit(location);
    }

    public void addHazard(String type, Location location) {
        if (location == null) return;
        try {
            JSONArray list = hazards();
            JSONObject hazard = new JSONObject();
            hazard.put("id", System.currentTimeMillis()); hazard.put("type", type);
            hazard.put("lat", location.getLatitude()); hazard.put("lon", location.getLongitude());
            hazard.put("created", System.currentTimeMillis()); list.put(hazard);
            prefs.edit().putString("hazards", list.toString()).apply();
            callback.onHazard(type + " marked at this location"); callback.onRoadDataChanged();
        } catch (Exception ignored) { }
    }

    private JSONArray hazards() { try { return new JSONArray(prefs.getString("hazards", "[]")); } catch (Exception e) { return new JSONArray(); } }

    private void checkHazards(Location location) {
        JSONArray list = hazards(); long now = System.currentTimeMillis();
        for (int i = 0; i < list.length(); i++) {
            JSONObject hazard = list.optJSONObject(i); if (hazard == null) continue;
            float[] result = new float[1];
            Location.distanceBetween(location.getLatitude(), location.getLongitude(), hazard.optDouble("lat"), hazard.optDouble("lon"), result);
            String id = String.valueOf(hazard.optLong("id"));
            if (result[0] <= 90f && now - prefs.getLong("warn_" + id, 0) > 10 * 60 * 1000L) {
                String message = "Hazard ahead: " + hazard.optString("type", "road hazard");
                prefs.edit().putLong("warn_" + id, now).apply(); callback.onHazard(message); speak(message, "hazard_" + id); break;
            }
        }
    }

    private void checkSpeed(float mph) {
        if (limitMph <= 0 || mph < limitMph + thresholdMph() || !voiceEnabled()) return;
        long now = System.currentTimeMillis();
        if (now - lastSpeedSpeech > 45000L) {
            lastSpeedSpeech = now; speak("Speed warning. Posted limit " + Math.round(limitMph) + " miles per hour.", "speed_warning");
        }
    }

    private void fetchLimit(Location location) {
        fetching = true; lastFetch = System.currentTimeMillis(); lastLookup = new Location(location);
        double lat = location.getLatitude(), lon = location.getLongitude();
        executor.execute(() -> {
            HttpURLConnection connection = null; double foundLimit = -1; String foundRoad = "";
            try {
                String query = String.format(Locale.US, "[out:json][timeout:8];way(around:45,%.6f,%.6f)[highway][maxspeed];out tags center;", lat, lon);
                String endpoint = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8");
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(9000);
                connection.setRequestProperty("User-Agent", "Scooter-Speedometer/2.2 (github.com/BobTheZombie/Scooter-Speedometer-)");
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder body = new StringBuilder(); String line; while ((line = reader.readLine()) != null) body.append(line); reader.close();
                JSONArray elements = new JSONObject(body.toString()).optJSONArray("elements");
                double nearest = Double.MAX_VALUE;
                if (elements != null) for (int i = 0; i < elements.length(); i++) {
                    JSONObject element = elements.optJSONObject(i), tags = element == null ? null : element.optJSONObject("tags");
                    JSONObject center = element == null ? null : element.optJSONObject("center");
                    if (tags == null || center == null) continue;
                    double distance = distanceMeters(lat, lon, center.optDouble("lat"), center.optDouble("lon"));
                    double parsed = parseMaxspeed(tags.optString("maxspeed", ""));
                    if (parsed > 0 && distance < nearest) { nearest = distance; foundLimit = parsed; foundRoad = tags.optString("name", "Road"); }
                }
            } catch (Exception ignored) { }
            finally { if (connection != null) connection.disconnect(); }
            final double deliveredLimit = foundLimit; final String deliveredRoad = foundRoad;
            main.post(() -> {
                fetching = false;
                if (deliveredLimit > 0) {
                    limitMph = deliveredLimit; roadName = deliveredRoad;
                    prefs.edit().putFloat("limit_mph", (float) limitMph).putString("road_name", roadName).apply();
                } else { limitMph = -1; roadName = ""; }
                callback.onRoadDataChanged();
            });
        });
    }

    private double parseMaxspeed(String raw) {
        if (raw == null) return -1; String lower = raw.toLowerCase(Locale.US).trim();
        try {
            String number = lower.replaceAll("[^0-9.]", " ").trim().split("\\s+")[0];
            double value = Double.parseDouble(number);
            return lower.contains("mph") ? value : value * 0.621371;
        } catch (Exception ignored) { return -1; }
    }
    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        float[] result = new float[1]; Location.distanceBetween(lat1, lon1, lat2, lon2, result); return result[0];
    }
    private void speak(String message, String id) { if (speechReady && voiceEnabled()) speech.speak(message, TextToSpeech.QUEUE_ADD, null, id); }
    public void shutdown() { executor.shutdownNow(); speech.stop(); speech.shutdown(); }
}
