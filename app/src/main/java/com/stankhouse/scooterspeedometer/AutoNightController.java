package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.Calendar;

/** Combines astronomical sunset with the ambient-light sensor for automatic night mode. */
public class AutoNightController implements SensorEventListener {
    public interface Callback { void onNightChanged(); }
    private final SharedPreferences prefs;
    private final SensorManager sensors;
    private final Sensor light;
    private final Callback callback;
    private double latitude = Double.NaN, longitude = Double.NaN;
    private boolean darkBySensor, darkBySun, night;
    private long darkSince;

    public AutoNightController(Context context, Callback callback) {
        prefs = context.getSharedPreferences("night_mode", Context.MODE_PRIVATE);
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        this.callback = callback;
    }

    public void start() {
        if (light != null) sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        recalculate();
    }
    public void stop() { sensors.unregisterListener(this); }
    public boolean enabled() { return prefs.getBoolean("automatic", true); }
    public boolean oled() { return prefs.getBoolean("oled", true); }
    public boolean isNight() { return enabled() && night; }
    public void setEnabled(boolean value) { prefs.edit().putBoolean("automatic", value).apply(); recalculate(); }
    public void setOled(boolean value) { prefs.edit().putBoolean("oled", value).apply(); callback.onNightChanged(); }

    public void updateLocation(double lat, double lon) {
        latitude = lat; longitude = lon; recalculate();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        float lux = event.values[0];
        long now = android.os.SystemClock.elapsedRealtime();
        if (lux <= 8f) {
            if (darkSince == 0) darkSince = now;
            if (!darkBySensor && now - darkSince >= 8000L) { darkBySensor = true; recalculate(); }
        } else if (lux >= 25f) {
            darkSince = 0;
            if (darkBySensor) { darkBySensor = false; recalculate(); }
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void recalculate() {
        boolean old = night;
        darkBySun = !Double.isNaN(latitude) && sunIsBelowHorizon(latitude, longitude, System.currentTimeMillis());
        night = darkBySensor || darkBySun;
        if (old != night || !enabled()) callback.onNightChanged();
    }

    /** NOAA-style solar-position approximation; true after sunset/before sunrise at the current fix. */
    private boolean sunIsBelowHorizon(double lat, double lon, long millis) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(millis);
        int day = c.get(Calendar.DAY_OF_YEAR);
        double hourUtc = (millis / 3600000.0) % 24.0;
        double gamma = 2.0 * Math.PI / 365.0 * (day - 1 + (hourUtc - 12.0) / 24.0);
        double equation = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
        double declination = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma) + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma) + 0.00148 * Math.sin(3 * gamma);
        double minutesUtc = (millis / 60000.0) % 1440.0;
        double trueSolarMinutes = (minutesUtc + equation + 4.0 * lon + 1440.0) % 1440.0;
        double hourAngle = Math.toRadians(trueSolarMinutes / 4.0 - 180.0);
        double latRad = Math.toRadians(lat);
        double elevation = Math.asin(Math.sin(latRad) * Math.sin(declination) +
                Math.cos(latRad) * Math.cos(declination) * Math.cos(hourAngle));
        return Math.toDegrees(elevation) < -0.833;
    }
}
