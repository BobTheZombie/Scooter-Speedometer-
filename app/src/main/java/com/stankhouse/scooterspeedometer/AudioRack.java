package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;

/** Persistent, crash-safe system-output EQ and gain stage. */
public final class AudioRack {
    public static final int[] FREQUENCIES = {60, 150, 400, 1000, 2400, 6000, 14000};
    private final SharedPreferences prefs;
    private final Context context;
    private Equalizer equalizer;
    private LoudnessEnhancer amplifier;
    private String status = "READY";

    public AudioRack(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences("audio_rack", Context.MODE_PRIVATE);
        open();
    }

    private void open() {
        release();
        notifyEffectSession(true);
        try {
            equalizer = new Equalizer(0, 0);
            applyEqualizer();
        } catch (Throwable error) { equalizer = null; status = "EQ NOT SUPPORTED BY THIS PHONE"; }
        try {
            amplifier = new LoudnessEnhancer(0);
            applyAmplifier();
        } catch (Throwable error) { amplifier = null; status = "AMP NOT SUPPORTED BY THIS PHONE"; }
    }

    public boolean eqEnabled() { return prefs.getBoolean("eq_enabled", false); }
    public boolean ampEnabled() { return prefs.getBoolean("amp_enabled", false); }
    public int gain(int index) { return prefs.getInt("band_" + index, 0); }
    public int amplifierGain() { return prefs.getInt("amp_gain", 300); }
    public String status() { return status; }
    public String oemName() {
        String maker=Build.MANUFACTURER==null?"Android":Build.MANUFACTURER.trim();
        if(maker.equalsIgnoreCase("samsung"))return "Samsung SoundAlive / system DSP";
        if(maker.equalsIgnoreCase("oneplus")||maker.equalsIgnoreCase("oppo")||maker.equalsIgnoreCase("realme"))return "OnePlus/OPlus audio DSP";
        if(maker.equalsIgnoreCase("lge")||maker.equalsIgnoreCase("lg"))return "LG audio / Quad DAC DSP";
        if(maker.equalsIgnoreCase("motorola"))return "Motorola Audio Effects / Dolby";
        return maker+" Android audio effects";
    }
    public boolean eqAvailable() { return equalizer != null; }
    public boolean ampAvailable() { return amplifier != null; }

    public void setEqEnabled(boolean value) { prefs.edit().putBoolean("eq_enabled", value).apply(); applyEqualizer(); }
    public void setAmpEnabled(boolean value) { prefs.edit().putBoolean("amp_enabled", value).apply(); applyAmplifier(); }
    public void setGain(int index, int db) {
        prefs.edit().putInt("band_" + index, Math.max(-12, Math.min(12, db))).apply();
        applyEqualizer();
    }
    public void setAmplifierGain(int millibels) {
        prefs.edit().putInt("amp_gain", Math.max(0, Math.min(1200, millibels))).apply();
        applyAmplifier();
    }
    public void flat() {
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < FREQUENCIES.length; i++) editor.putInt("band_" + i, 0);
        editor.apply(); applyEqualizer();
    }

    private void applyEqualizer() {
        if (equalizer == null) return;
        try {
            short[] range = equalizer.getBandLevelRange();
            short bands = equalizer.getNumberOfBands();
            for (short band = 0; band < bands; band++) {
                float hz = equalizer.getCenterFreq(band) / 1000f;
                float db = interpolatedGain(hz);
                int mb = Math.round(db * 100f);
                equalizer.setBandLevel(band, (short) Math.max(range[0], Math.min(range[1], mb)));
            }
            equalizer.setEnabled(eqEnabled());
            status = "READY";
        } catch (Throwable error) { status = "EQ UNAVAILABLE ON CURRENT OUTPUT"; }
    }

    private float interpolatedGain(float hz) {
        if (hz <= FREQUENCIES[0]) return gain(0);
        for (int i = 1; i < FREQUENCIES.length; i++) if (hz <= FREQUENCIES[i]) {
            float lower = FREQUENCIES[i - 1], upper = FREQUENCIES[i];
            float position = (float) (Math.log(hz / lower) / Math.log(upper / lower));
            return gain(i - 1) + position * (gain(i) - gain(i - 1));
        }
        return gain(FREQUENCIES.length - 1);
    }

    private void applyAmplifier() {
        if (amplifier == null) return;
        try {
            amplifier.setTargetGain(amplifierGain());
            amplifier.setEnabled(ampEnabled());
            status = "READY";
        } catch (Throwable error) { status = "AMP UNAVAILABLE ON CURRENT OUTPUT"; }
    }

    public void refresh() { if (equalizer == null || amplifier == null) open(); else { applyEqualizer(); applyAmplifier(); } }

    public boolean openOemPanel(Context activityContext) {
        String maker=Build.MANUFACTURER==null?"":Build.MANUFACTURER.toLowerCase();
        String[] packages;
        if(maker.contains("samsung")) packages=new String[]{"com.sec.android.app.soundalive","com.samsung.android.soundassistant"};
        else if(maker.contains("oneplus")||maker.contains("oppo")||maker.contains("realme")) packages=new String[]{"com.oneplus.sound.tuner","com.oplus.audio.effectcenter","com.oplus.audio"};
        else if(maker.contains("lge")||maker.equals("lg")) packages=new String[]{"com.lge.audioeffect","com.lge.music"};
        else if(maker.contains("motorola")) packages=new String[]{"com.motorola.audiofx","com.motorola.dolby.dolbyui"};
        else packages=new String[0];
        for(String packageName:packages) try {
            Intent launch=context.getPackageManager().getLaunchIntentForPackage(packageName);
            if(launch!=null){launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);activityContext.startActivity(launch);return true;}
        } catch(Throwable ignored) { }
        try {
            Intent standard=new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION,0)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME,context.getPackageName())
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE,AudioEffect.CONTENT_TYPE_MUSIC);
            if(standard.resolveActivity(context.getPackageManager())!=null){activityContext.startActivity(standard);return true;}
        } catch(Throwable ignored) { }
        return false;
    }

    private void notifyEffectSession(boolean open) {
        try {
            Intent intent=new Intent(open?AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION:AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION,0).putExtra(AudioEffect.EXTRA_PACKAGE_NAME,context.getPackageName())
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE,AudioEffect.CONTENT_TYPE_MUSIC);
            context.sendBroadcast(intent);
        } catch(Throwable ignored) { }
    }

    public void release() {
        try { if (equalizer != null) equalizer.release(); } catch (Throwable ignored) { }
        try { if (amplifier != null) amplifier.release(); } catch (Throwable ignored) { }
        equalizer = null; amplifier = null;
        notifyEffectSession(false);
    }
}
