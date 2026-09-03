package com.stankhouse.scooterspeedometer;

import android.graphics.RectF;

import java.util.Locale;

/** Persistable normalized geometry and appearance for the dashboard editor. */
public class DashboardLayout {
    public final RectF media = new RectF();
    public final RectF gauge = new RectF();
    public final RectF navigation = new RectF();
    public int albumAlpha = 105;
    public int gaugeColor = 0;
    public int gaugeStyle = 0;

    public static DashboardLayout preset(boolean landscape, boolean compact) {
        DashboardLayout result = new DashboardLayout();
        if (landscape) {
            result.media.set(.02f, .03f, compact ? .48f : .52f, compact ? .25f : .31f);
            result.gauge.set(compact ? .50f : .54f, .13f, .98f, compact ? .72f : .78f);
            result.navigation.set(.62f, compact ? .76f : .80f, .87f, compact ? .86f : .91f);
        } else {
            result.media.set(.035f, .025f, .965f, compact ? .205f : .245f);
            result.gauge.set(.10f, compact ? .36f : .38f, .90f, compact ? .68f : .72f);
            result.navigation.set(.36f, compact ? .69f : .725f, .64f, compact ? .74f : .77f);
        }
        return result;
    }

    public String encode() {
        return String.format(Locale.US,
                "%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%f,%d,%d,%d",
                media.left, media.top, media.right, media.bottom,
                gauge.left, gauge.top, gauge.right, gauge.bottom,
                navigation.left, navigation.top, navigation.right, navigation.bottom,
                albumAlpha, gaugeColor, gaugeStyle);
    }

    public static DashboardLayout decode(String value, DashboardLayout fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            String[] p = value.split(",");
            if (p.length != 15) return fallback;
            DashboardLayout result = new DashboardLayout();
            result.media.set(f(p[0]), f(p[1]), f(p[2]), f(p[3]));
            result.gauge.set(f(p[4]), f(p[5]), f(p[6]), f(p[7]));
            result.navigation.set(f(p[8]), f(p[9]), f(p[10]), f(p[11]));
            result.albumAlpha = Integer.parseInt(p[12]);
            result.gaugeColor = Integer.parseInt(p[13]);
            result.gaugeStyle = Integer.parseInt(p[14]);
            return result;
        } catch (RuntimeException ignored) { return fallback; }
    }

    private static float f(String value) { return Float.parseFloat(value); }
}
