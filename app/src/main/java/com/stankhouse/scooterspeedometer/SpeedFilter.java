package com.stankhouse.scooterspeedometer;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;

import java.util.Arrays;

/** Accuracy-aware GPS speed filter tuned for low-speed scooter use. */
public class SpeedFilter {
    private final float[] samples = new float[5];
    private int sampleCount;
    private int sampleIndex;
    private float filtered;
    private float previousRaw;
    private long previousNanos;
    private boolean initialized;

    /** Returns metres/second, or NaN when a fix should be ignored. */
    public float update(Location location) {
        if (location == null || !location.hasSpeed() || !location.hasAccuracy() || location.getAccuracy() > 35f)
            return Float.NaN;

        long nowNanos = SystemClock.elapsedRealtimeNanos();
        long fixNanos = location.getElapsedRealtimeNanos();
        if (fixNanos > 0 && nowNanos - fixNanos > 3_000_000_000L) return Float.NaN;

        float raw = Math.max(0f, location.getSpeed());
        if (raw > 70f) return Float.NaN;
        float speedAccuracy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()
                ? location.getSpeedAccuracyMetersPerSecond() : Math.min(3f, .8f + location.getAccuracy() / 15f);
        if (speedAccuracy > 7f) return Float.NaN;

        float dt = previousNanos == 0 ? 1f : Math.max(.1f, Math.min(3f,
                (fixNanos - previousNanos) / 1_000_000_000f));
        if (initialized && Math.abs(raw - previousRaw) > 14f * dt + 7f && speedAccuracy > 1.5f)
            return Float.NaN;
        previousNanos = fixNanos;
        previousRaw = raw;

        samples[sampleIndex] = raw;
        sampleIndex = (sampleIndex + 1) % samples.length;
        if (sampleCount < samples.length) sampleCount++;
        float[] ordered = Arrays.copyOf(samples, sampleCount);
        Arrays.sort(ordered);
        float median = ordered[sampleCount / 2];

        boolean probablyStopped = raw < .75f && raw - speedAccuracy < .25f;
        if (probablyStopped) median = 0f;
        if (!initialized) {
            filtered = median;
            initialized = true;
            return filtered;
        }

        float confidence = Math.max(0f, Math.min(1f, 1f - speedAccuracy / 4f));
        float alpha = (float) (1f - Math.exp(-dt / 1.05f));
        alpha *= .58f + .42f * confidence;
        alpha = Math.max(.12f, Math.min(.68f, alpha));
        float change = Math.abs(median - filtered);
        if (change > 2.2f && speedAccuracy < 2.5f) alpha = Math.max(alpha, .55f);
        if (median == 0f) alpha = Math.max(alpha, .68f);
        filtered += alpha * (median - filtered);
        if (filtered < .32f) filtered = 0f;
        return filtered;
    }
}
