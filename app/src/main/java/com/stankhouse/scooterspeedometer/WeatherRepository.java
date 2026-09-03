package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

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

    public static class WeatherData {
        public final double temperature;
        public final double feelsLike;
        public final int rainChance;
        public final double windSpeed;
        public final int windDirection;
        public final int weatherCode;
        public final long updatedAt;

        WeatherData(double temperature, double feelsLike, int rainChance, double windSpeed,
                    int windDirection, int weatherCode, long updatedAt) {
            this.temperature = temperature;
            this.feelsLike = feelsLike;
            this.rainChance = rainChance;
            this.windSpeed = windSpeed;
            this.windDirection = windDirection;
            this.weatherCode = weatherCode;
            this.updatedAt = updatedAt;
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

    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static boolean fetching;
    private final SharedPreferences cache;
    private final Handler main = new Handler(Looper.getMainLooper());

    public WeatherRepository(Context context) {
        cache = context.getApplicationContext().getSharedPreferences("weather_cache", Context.MODE_PRIVATE);
    }

    public WeatherData cached() {
        if (!cache.contains("updated")) return null;
        return new WeatherData(cache.getFloat("temperature", 0), cache.getFloat("feels", 0),
                cache.getInt("rain", 0), cache.getFloat("wind", 0), cache.getInt("direction", 0),
                cache.getInt("code", 0), cache.getLong("updated", 0));
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
                                "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=1",
                        latitude, longitude);
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("User-Agent", "Scooter-Speedometer/1.3");
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
                reader.close();
                JSONObject current = new JSONObject(json.toString()).getJSONObject("current");
                result = new WeatherData(current.getDouble("temperature_2m"),
                        current.getDouble("apparent_temperature"),
                        current.optInt("precipitation_probability", 0),
                        current.getDouble("wind_speed_10m"),
                        current.optInt("wind_direction_10m", 0),
                        current.getInt("weather_code"), System.currentTimeMillis());
                cache.edit().putFloat("temperature", (float) result.temperature)
                        .putFloat("feels", (float) result.feelsLike).putInt("rain", result.rainChance)
                        .putFloat("wind", (float) result.windSpeed).putInt("direction", result.windDirection)
                        .putInt("code", result.weatherCode).putLong("updated", result.updatedAt).apply();
            } catch (Exception ignored) { }
            finally {
                if (connection != null) connection.disconnect();
                synchronized (WeatherRepository.class) { fetching = false; }
            }
            WeatherData delivered = result;
            if (delivered != null) main.post(() -> callback.onWeather(delivered));
        });
    }
}
