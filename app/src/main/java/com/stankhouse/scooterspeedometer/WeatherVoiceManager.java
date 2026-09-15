package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Cooldown-aware spoken forecast and official-alert announcer. */
public class WeatherVoiceManager implements TextToSpeech.OnInitListener {
    public interface VoicesCallback { void onVoices(List<Voice> voices); }
    private static final long FORECAST_COOLDOWN = 20 * 60 * 1000L;
    private final SharedPreferences prefs;
    private final TextToSpeech speech;
    private final AudioManager audioManager;
    private final CopilotAlertQueue alertQueue;
    private AudioFocusRequest focusRequest;
    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> { };
    private boolean ready;

    public WeatherVoiceManager(Context context) {
        prefs = context.getSharedPreferences("weather_voice", Context.MODE_PRIVATE);
        audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        alertQueue = CopilotAlertQueue.get(context);
        speech = new TextToSpeech(context.getApplicationContext(), this);
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onDone(String utteranceId) { releaseAudioFocus(); }
            @Override public void onError(String utteranceId) { releaseAudioFocus(); }
        });
    }

    @Override public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (ready) {
            speech.setLanguage(Locale.US);
            applySettings();
        }
    }

    private void applySettings() {
        speech.setSpeechRate(prefs.getFloat("rate", .92f));
        speech.setPitch(prefs.getFloat("pitch", 1f));
        String wanted=prefs.getString("voice_name","");
        if(!wanted.isEmpty()&&speech.getVoices()!=null)for(Voice voice:speech.getVoices())if(wanted.equals(voice.getName())){speech.setVoice(voice);break;}
    }

    public float rate(){return prefs.getFloat("rate",.92f);}
    public float pitch(){return prefs.getFloat("pitch",1f);}
    public String voiceName(){Voice v=ready?speech.getVoice():null;return v==null?"System default":v.getName();}
    public void setRate(float value){prefs.edit().putFloat("rate",value).apply();if(ready)speech.setSpeechRate(value);}
    public void setPitch(float value){prefs.edit().putFloat("pitch",value).apply();if(ready)speech.setPitch(value);}
    public void voices(VoicesCallback callback){if(!ready||speech.getVoices()==null){callback.onVoices(Collections.emptyList());return;}List<Voice> list=new ArrayList<>(speech.getVoices());Collections.sort(list,Comparator.comparing(v->v.getLocale().getDisplayName()+v.getName()));callback.onVoices(list);}
    public void setVoice(Voice voice){if(voice==null)return;prefs.edit().putString("voice_name",voice.getName()).apply();if(ready)speech.setVoice(voice);}
    public void preview(){if(ready){applySettings();speech.speak("Rider Link voice check. Rain is expected in fifteen minutes.",TextToSpeech.QUEUE_FLUSH,null,"voice_preview");}}

    public boolean enabled() { return prefs.getBoolean("enabled", true); }
    public boolean imminentEnabled(){return prefs.getBoolean("imminent_enabled",true);}
    public void setImminentEnabled(boolean value){prefs.edit().putBoolean("imminent_enabled",value).apply();}
    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean("enabled", enabled).apply();
        if (!enabled) speech.stop();
    }

    public void announceForecast(WeatherRepository.WeatherData weather) {
        if (!ready || !enabled() || !imminentEnabled() || weather == null) return;
        String key = "";
        String message = "";
        if (weather.precipitationMinutes >= 0 && weather.precipitationMinutes <= 60) {
            int code = weather.precipitationCode;
            String kind = code >= 95 ? "Thunderstorms" :
                    ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) ? "Snow" : "Rain";
            int stage=weather.precipitationMinutes<=20?15:weather.precipitationMinutes<=40?30:60;
            long event=weather.precipitationStartAt>0?weather.precipitationStartAt/(15*60*1000L):0;
            key = kind + ":" + event + ":" + stage;
            message = weather.precipitationMinutes<=2?kind+" is expected to begin very soon. ":kind + " expected in approximately " + (stage==15?15:stage==30?30:weather.precipitationMinutes) + " minutes. ";
            message += weather.precipitationChance + " percent chance.";
        } else if (weather.upcomingWindGust >= 35) {
            key = "wind:" + Math.round(weather.upcomingWindGust / 5) * 5;
            message = "Weather warning. Wind gusts near " + Math.round(weather.upcomingWindGust) +
                    " miles per hour are possible within the next two hours.";
        }
        if (message.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (key.equals(prefs.getString("last_forecast_key", "")) &&
                now - prefs.getLong("last_forecast_spoken", 0) < FORECAST_COOLDOWN) return;
        alertQueue.enqueue(CopilotAlertQueue.WEATHER, key, message, FORECAST_COOLDOWN);
        prefs.edit().putString("last_forecast_key", key).putLong("last_forecast_spoken", now).apply();
    }

    public void announceAlert(WeatherRepository.AlertData alert) {
        if (!ready || !enabled() || alert == null || !alert.active() || alert.severityRank() < 2) return;
        if (alert.id.equals(prefs.getString("last_alert_id", ""))) return;
        alertQueue.enqueue(alert.severityRank() >= 3 ? CopilotAlertQueue.CRITICAL : CopilotAlertQueue.HAZARD,
                "weather-alert:" + alert.id, "Weather alert. " + alert.event + ". " + alert.headline,
                24 * 60 * 60 * 1000L);
        prefs.edit().putString("last_alert_id", alert.id).apply();
    }

    private void speak(String value, String utteranceId) {
        requestDuckingAudioFocus();
        speech.speak(value, TextToSpeech.QUEUE_ADD, null, utteranceId);
    }

    private void requestDuckingAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener)
                    .setWillPauseWhenDucked(false).build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
    }

    private void releaseAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        else audioManager.abandonAudioFocus(focusListener);
    }

    public void shutdown() { speech.stop(); releaseAudioFocus(); speech.shutdown(); }
}
