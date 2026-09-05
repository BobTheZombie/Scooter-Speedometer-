package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

/** Applies one voice profile to weather, navigation, hazards and delivery alerts. */
public final class VoiceSettings {
    private VoiceSettings() { }
    public static void apply(Context context, TextToSpeech speech) {
        SharedPreferences prefs=context.getSharedPreferences("weather_voice",Context.MODE_PRIVATE);
        speech.setSpeechRate(prefs.getFloat("rate",.92f));speech.setPitch(prefs.getFloat("pitch",1f));
        String wanted=prefs.getString("voice_name","");
        if(!wanted.isEmpty()&&speech.getVoices()!=null)for(Voice voice:speech.getVoices())if(wanted.equals(voice.getName())){speech.setVoice(voice);break;}
    }
}
