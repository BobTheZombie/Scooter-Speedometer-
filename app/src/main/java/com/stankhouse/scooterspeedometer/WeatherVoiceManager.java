package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Cooldown-aware spoken forecast and official-alert announcer. */
public class WeatherVoiceManager implements TextToSpeech.OnInitListener {
    private static final long FORECAST_COOLDOWN = 45 * 60 * 1000L;
    private final SharedPreferences prefs;
    private final TextToSpeech speech;
    private boolean ready;

    public WeatherVoiceManager(Context context) {
        prefs = context.getSharedPreferences("weather_voice", Context.MODE_PRIVATE);
        speech = new TextToSpeech(context.getApplicationContext(), this);
    }

    @Override public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (ready) speech.setLanguage(Locale.US);
    }

    public boolean enabled() { return prefs.getBoolean("enabled", true); }
    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean("enabled", enabled).apply();
        if (!enabled) speech.stop();
    }

    public void announceForecast(WeatherRepository.WeatherData weather) {
        if (!ready || !enabled() || weather == null) return;
        String key = "";
        String message = "";
        if (weather.precipitationMinutes > 0 && weather.precipitationMinutes <= 60) {
            int code = weather.precipitationCode;
            String kind = code >= 95 ? "Thunderstorms" :
                    ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) ? "Snow" : "Rain";
            key = kind + ":" + weather.precipitationMinutes;
            message = kind + " expected in approximately " + weather.precipitationMinutes + " minutes. " +
                    weather.precipitationChance + " percent chance.";
        } else if (weather.upcomingWindGust >= 35) {
            key = "wind:" + Math.round(weather.upcomingWindGust / 5) * 5;
            message = "Weather warning. Wind gusts near " + Math.round(weather.upcomingWindGust) +
                    " miles per hour are possible within the next two hours.";
        }
        if (message.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (key.equals(prefs.getString("last_forecast_key", "")) &&
                now - prefs.getLong("last_forecast_spoken", 0) < FORECAST_COOLDOWN) return;
        speak(message, "weather_forecast");
        prefs.edit().putString("last_forecast_key", key).putLong("last_forecast_spoken", now).apply();
    }

    public void announceAlert(WeatherRepository.AlertData alert) {
        if (!ready || !enabled() || alert == null || !alert.active() || alert.severityRank() < 2) return;
        if (alert.id.equals(prefs.getString("last_alert_id", ""))) return;
        speak("Weather alert. " + alert.event + ". " + alert.headline, "weather_alert");
        prefs.edit().putString("last_alert_id", alert.id).apply();
    }

    private void speak(String value, String utteranceId) {
        speech.speak(value, TextToSpeech.QUEUE_ADD, null, utteranceId);
    }

    public void shutdown() { speech.stop(); speech.shutdown(); }
}
