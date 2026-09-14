package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * Foreground-only, voice-first ride assistant.
 *
 * Rider Copilot never starts a service and cannot speak once stop() is called by the
 * visible activity. Alerts are deliberately rate-limited to avoid distracting a rider.
 */
public final class RiderCopilot implements TextToSpeech.OnInitListener {
    public interface StatusListener { void onCopilotStatus(String status); }

    private static final long GPS_ALERT_COOLDOWN_MS = 5 * 60_000L;
    private static final long BATTERY_ALERT_COOLDOWN_MS = 20 * 60_000L;

    private final Context context;
    private final SharedPreferences prefs;
    private final AudioManager audio;
    private final BatteryManager battery;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StatusListener listener;

    private TextToSpeech tts;
    private boolean ready;
    private boolean active;
    private long startedAt;
    private long lastGpsAlert;
    private long lastBatteryAlert;
    private int lastBatteryBand = 100;
    private AudioFocusRequest focusRequest;

    public RiderCopilot(Context context, StatusListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.prefs = this.context.getSharedPreferences("speedometer", Context.MODE_PRIVATE);
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.battery = (BatteryManager) this.context.getSystemService(Context.BATTERY_SERVICE);
    }

    public boolean enabled() { return prefs.getBoolean("rider_copilot_enabled", true); }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean("rider_copilot_enabled", enabled).apply();
        if (!enabled) stop();
    }

    public void start() {
        if (!enabled() || active) return;
        active = true;
        startedAt = SystemClock.elapsedRealtime();
        if (tts == null) tts = new TextToSpeech(context, this);
        status("COPILOT READY");
    }

    public void stop() {
        active = false;
        main.removeCallbacksAndMessages(null);
        abandonFocus();
        if (tts != null) tts.stop();
    }

    public void shutdown() {
        stop();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        ready = false;
    }

    @Override public void onInit(int result) {
        ready = result == TextToSpeech.SUCCESS;
        if (!ready || tts == null) return;
        tts.setLanguage(Locale.getDefault());
        tts.setSpeechRate(prefs.getFloat("voice_rate", 1f));
        tts.setPitch(prefs.getFloat("voice_pitch", 1f));
    }

    public void update(Location fix, float speedMps, double tripMeters, int satellites,
                       WeatherRepository.WeatherData weather) {
        if (!active || !enabled()) return;
        long now = SystemClock.elapsedRealtime();

        boolean weakFix = fix == null || fix.getAccuracy() > 45f || satellites < 3;
        if (weakFix && now - startedAt > 20_000L && now - lastGpsAlert > GPS_ALERT_COOLDOWN_MS) {
            lastGpsAlert = now;
            speak("GPS accuracy is low. Keep a clear view of the sky.", "GPS SIGNAL LOW");
        }

        int percent = battery == null ? -1 :
                battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        int band = percent <= 10 ? 10 : percent <= 20 ? 20 : 100;
        if (band < lastBatteryBand && now - lastBatteryAlert > BATTERY_ALERT_COOLDOWN_MS) {
            lastBatteryBand = band;
            lastBatteryAlert = now;
            speak("Phone battery is at " + percent + " percent.", "BATTERY " + percent + "%");
        } else if (band == 100) {
            lastBatteryBand = 100;
        }

        if (weather != null && weather.upcomingWindGust >= 35 &&
                !prefs.getBoolean("copilot_wind_ack_" + (System.currentTimeMillis() / 3_600_000L), false)) {
            prefs.edit().putBoolean("copilot_wind_ack_" + (System.currentTimeMillis() / 3_600_000L), true).apply();
            speak("Strong wind gusts are possible. Use extra caution.", "STRONG WIND");
        }
    }

    public void announceBriefing(WeatherRepository.WeatherData weather, Location fix,
                                  float speedMps, double tripMeters, int satellites) {
        if (!active || !enabled()) return;
        StringBuilder message = new StringBuilder("Rider Copilot briefing. ");
        if (fix == null) message.append("Waiting for GPS. ");
        else if (fix.getAccuracy() <= 15f) message.append("GPS accuracy is excellent. ");
        else if (fix.getAccuracy() <= 35f) message.append("GPS is locked. ");
        else message.append("GPS accuracy is limited. ");

        if (weather != null) {
            message.append("It is ").append(Math.round(weather.temperature))
                    .append(" degrees and ").append(weather.condition().toLowerCase(Locale.US)).append(". ");
            if (weather.precipitationMinutes >= 0 && weather.precipitationMinutes <= 60) {
                message.append(weather.precipitationCode >= 95 ? "Thunderstorms" :
                        weather.precipitationCode >= 71 && weather.precipitationCode <= 86 ? "Snow" : "Rain");
                message.append(" expected in ").append(Math.max(1, weather.precipitationMinutes))
                        .append(" minutes. ");
            }
        }
        if (tripMeters >= 160.934) {
            message.append("Trip distance ").append(String.format(Locale.US, "%.1f", tripMeters / 1609.344))
                    .append(" miles. ");
        }
        int percent = battery == null ? -1 :
                battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (percent >= 0) message.append("Phone battery ").append(percent).append(" percent.");
        speak(message.toString(), "RIDE BRIEFING");
    }

    public void speak(String message, String status) {
        if (!active || !enabled() || !ready || message == null || message.trim().isEmpty()) return;
        requestDuckingFocus();
        status(status);
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "rider-copilot");
        main.removeCallbacksAndMessages(null);
        main.postDelayed(this::abandonFocus, Math.max(2500L, message.length() * 65L));
    }

    private void status(String value) {
        if (listener != null) main.post(() -> listener.onCopilotStatus(value));
    }

    private void requestDuckingFocus() {
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(change -> { }).build();
            audio.requestAudioFocus(focusRequest);
        } else {
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
    }

    private void abandonFocus() {
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audio.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        } else {
            audio.abandonAudioFocus(null);
        }
    }
}
