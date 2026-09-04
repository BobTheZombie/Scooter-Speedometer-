package com.stankhouse.scooterspeedometer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight, key-free current-weather client backed by Open-Meteo. */
public class WeatherRepository {
    public interface Callback { void onWeather(WeatherData data); }
    public interface AlertCallback { void onAlert(AlertData alert); }

    public static class AlertData {
        public final String id;
        public final String event;
        public final String headline;
        public final String severity;
        public final String instruction;
        public final long updatedAt;

        AlertData(String id, String event, String headline, String severity, String instruction, long updatedAt) {
            this.id = id;
            this.event = event;
            this.headline = headline;
            this.severity = severity;
            this.instruction = instruction;
            this.updatedAt = updatedAt;
        }

        public boolean active() { return id != null && !id.isEmpty(); }
        public int severityRank() {
            if ("Extreme".equalsIgnoreCase(severity)) return 4;
            if ("Severe".equalsIgnoreCase(severity)) return 3;
            if ("Moderate".equalsIgnoreCase(severity)) return 2;
            if ("Minor".equalsIgnoreCase(severity)) return 1;
            return 0;
        }
    }

    public static class WeatherData {
        public final double temperature;
        public final double feelsLike;
        public final int rainChance;
        public final double windSpeed;
        public final int windDirection;
        public final int weatherCode;
        public final int precipitationMinutes;
        public final int precipitationChance;
        public final int precipitationCode;
        public final double upcomingWindGust;
        public final long updatedAt;

        WeatherData(double temperature, double feelsLike, int rainChance, double windSpeed,
                    int windDirection, int weatherCode, long updatedAt, int precipitationMinutes,
                    int precipitationChance, int precipitationCode, double upcomingWindGust) {
            this.temperature = temperature;
            this.feelsLike = feelsLike;
            this.rainChance = rainChance;
            this.windSpeed = windSpeed;
            this.windDirection = windDirection;
            this.weatherCode = weatherCode;
            this.updatedAt = updatedAt;
            this.precipitationMinutes = precipitationMinutes;
            this.precipitationChance = precipitationChance;
            this.precipitationCode = precipitationCode;
            this.upcomingWindGust = upcomingWindGust;
        }

        public String icon() {
            if (weatherCode == 0) return "☀";
            if (weatherCode <= 3) return "☁";
            if (weatherCode == 45 || weatherCode == 48) return "≋";
            if (weatherCode >= 51 && weatherCode <= 67) return "☂";
            if (weatherCode >= 71 && weatherCode <= 77) return "❄";
            if (weatherCode >= 80 && weatherCode <= 82) return "☂";
            if (weatherCode >= 85 && weatherCode <= 86) return "❄";
            if (weatherCode >= 95) return "ϟ";
            return "☁";
        }

        public String condition() {
            if (weatherCode == 0) return "Clear";
            if (weatherCode == 1) return "Mostly clear";
            if (weatherCode == 2) return "Partly cloudy";
            if (weatherCode == 3) return "Cloudy";
            if (weatherCode == 45 || weatherCode == 48) return "Fog";
            if (weatherCode >= 51 && weatherCode <= 57) return "Drizzle";
            if (weatherCode >= 61 && weatherCode <= 67) return "Rain";
            if (weatherCode >= 71 && weatherCode <= 77) return "Snow";
            if (weatherCode >= 80 && weatherCode <= 82) return "Showers";
            if (weatherCode >= 85 && weatherCode <= 86) return "Snow showers";
            if (weatherCode >= 95) return "Thunderstorm";
            return "Weather";
        }

        public String windCompass() {
            String[] points = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            return points[((windDirection + 22) / 45) % 8];
        }
    }

    // Refresh frequently while riding, without hammering the providers on every GPS fix.
    private static final long CACHE_MS = 2 * 60 * 1000L;
    private static final long ALERT_CACHE_MS = 2 * 60 * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static boolean fetching;
    private static boolean fetchingAlerts;
    private final Context context;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());

    public WeatherRepository(Context context) {
        this.context = context.getApplicationContext();
        cache = this.context.getSharedPreferences("weather_cache", Context.MODE_PRIVATE);
    }

    public WeatherData cached() {
        if (!cache.contains("updated")) return null;
        return new WeatherData(cache.getFloat("temperature", 0), cache.getFloat("feels", 0),
                cache.getInt("rain", 0), cache.getFloat("wind", 0), cache.getInt("direction", 0),
                cache.getInt("code", 0), cache.getLong("updated", 0),
                cache.getInt("precip_minutes", -1), cache.getInt("precip_chance", 0),
                cache.getInt("precip_code", 0), cache.getFloat("upcoming_gust", 0));
    }

    public AlertData cachedAlert() {
        if (!cache.contains("alert_updated")) return null;
        return new AlertData(cache.getString("alert_id", ""), cache.getString("alert_event", ""),
                cache.getString("alert_headline", ""), cache.getString("alert_severity", "Unknown"),
                cache.getString("alert_instruction", ""), cache.getLong("alert_updated", 0));
    }

    public void update(double latitude, double longitude, Callback callback) {
        WeatherData saved = cached();
        if (saved != null) callback.onWeather(saved);
        if (saved != null && System.currentTimeMillis() - saved.updatedAt < CACHE_MS) return;
        synchronized (WeatherRepository.class) {
            if (fetching) return;
            fetching = true;
        }
        EXECUTOR.execute(() -> {
            WeatherData result = null;
            HttpURLConnection connection = null;
            try {
                String endpoint = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f" +
                                "&current=temperature_2m,apparent_temperature,precipitation_probability," +
                                "weather_code,wind_speed_10m,wind_direction_10m" +
                                "&minutely_15=precipitation,precipitation_probability,weather_code,wind_gusts_10m" +
                                "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=1",
                        latitude, longitude);
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("User-Agent", "Scooter-Speedometer/3.3");
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
                reader.close();
                JSONObject root = new JSONObject(json.toString());
                JSONObject current = root.getJSONObject("current");
                int precipMinutes = -1, precipChance = 0, precipCode = 0;
                double maxGust = 0;
                JSONObject quarterHour = root.optJSONObject("minutely_15");
                if (quarterHour != null) {
                    JSONArray amount = quarterHour.optJSONArray("precipitation");
                    JSONArray chance = quarterHour.optJSONArray("precipitation_probability");
                    JSONArray codes = quarterHour.optJSONArray("weather_code");
                    JSONArray gusts = quarterHour.optJSONArray("wind_gusts_10m");
                    int count = amount == null ? 0 : Math.min(amount.length(), 9);
                    for (int i = 1; i < count; i++) {
                        int probability = chance == null ? 0 : chance.optInt(i, 0);
                        double quantity = amount.optDouble(i, 0);
                        if (gusts != null) maxGust = Math.max(maxGust, gusts.optDouble(i, 0));
                        if (precipMinutes < 0 && probability >= 50 && quantity > 0.01) {
                            precipMinutes = i * 15; precipChance = probability;
                            precipCode = codes == null ? 61 : codes.optInt(i, 61);
                        }
                    }
                }
                result = new WeatherData(current.getDouble("temperature_2m"),
                        current.getDouble("apparent_temperature"),
                        current.optInt("precipitation_probability", 0),
                        current.getDouble("wind_speed_10m"),
                        current.optInt("wind_direction_10m", 0),
                        current.getInt("weather_code"), System.currentTimeMillis(), precipMinutes,
                        precipChance, precipCode, maxGust);
                cache.edit().putFloat("temperature", (float) result.temperature)
                        .putFloat("feels", (float) result.feelsLike).putInt("rain", result.rainChance)
                        .putFloat("wind", (float) result.windSpeed).putInt("direction", result.windDirection)
                        .putInt("code", result.weatherCode).putLong("updated", result.updatedAt).apply();
                cache.edit().putInt("precip_minutes", result.precipitationMinutes)
                        .putInt("precip_chance", result.precipitationChance)
                        .putInt("precip_code", result.precipitationCode)
                        .putFloat("upcoming_gust", (float) result.upcomingWindGust).apply();
            } catch (Exception ignored) { }
            finally {
                if (connection != null) connection.disconnect();
                synchronized (WeatherRepository.class) { fetching = false; }
            }
            WeatherData delivered = result;
            if (delivered != null) main.post(() -> callback.onWeather(delivered));
        });
    }

    public void updateAlerts(double latitude, double longitude, AlertCallback callback) {
        AlertData saved = cachedAlert();
        if (saved != null) callback.onAlert(saved);
        if (saved != null && System.currentTimeMillis() - saved.updatedAt < ALERT_CACHE_MS) return;
        synchronized (WeatherRepository.class) {
            if (fetchingAlerts) return;
            fetchingAlerts = true;
        }
        EXECUTOR.execute(() -> {
            AlertData result = null;
            HttpURLConnection connection = null;
            try {
                String endpoint = String.format(Locale.US,
                        "https://api.weather.gov/alerts/active?point=%.4f,%.4f", latitude, longitude);
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(9000);
                connection.setReadTimeout(9000);
                connection.setRequestProperty("User-Agent",
                        "Scooter-Speedometer/3.3 (github.com/BobTheZombie/Scooter-Speedometer-)");
                connection.setRequestProperty("Accept", "application/geo+json");
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
                reader.close();
                JSONArray features = new JSONObject(json.toString()).getJSONArray("features");
                AlertData strongest = null;
                for (int i = 0; i < features.length(); i++) {
                    JSONObject feature = features.getJSONObject(i);
                    JSONObject properties = feature.getJSONObject("properties");
                    AlertData candidate = new AlertData(feature.optString("id", "alert-" + i),
                            properties.optString("event", "Weather alert"),
                            properties.optString("headline", properties.optString("description", "")),
                            properties.optString("severity", "Unknown"),
                            properties.optString("instruction", ""), System.currentTimeMillis());
                    if (strongest == null || candidate.severityRank() > strongest.severityRank()) strongest = candidate;
                }
                result = strongest == null ? new AlertData("", "", "", "Unknown", "", System.currentTimeMillis()) : strongest;
                cache.edit().putString("alert_id", result.id).putString("alert_event", result.event)
                        .putString("alert_headline", result.headline).putString("alert_severity", result.severity)
                        .putString("alert_instruction", result.instruction).putLong("alert_updated", result.updatedAt).apply();
            } catch (Exception ignored) { }
            finally {
                if (connection != null) connection.disconnect();
                synchronized (WeatherRepository.class) { fetchingAlerts = false; }
            }
            AlertData delivered = result;
            if (delivered != null) main.post(() -> {
                callback.onAlert(delivered);
                notifyIfNew(delivered);
            });
        });
    }

    private void notifyIfNew(AlertData alert) {
        if (!alert.active() || alert.severityRank() < 2) return;
        String lastId = cache.getString("last_notified_alert", "");
        if (alert.id.equals(lastId)) return;
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = "weather_alerts";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Live weather alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Official active National Weather Service alerts near the scooter");
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(context, 44, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, channelId) : new Notification.Builder(context);
        Notification notification = builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(alert.event)
                .setContentText(alert.headline)
                .setStyle(new Notification.BigTextStyle().bigText(alert.headline))
                .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_HIGH).build();
        manager.notify(4401, notification);
        cache.edit().putString("last_notified_alert", alert.id).apply();
    }
}
