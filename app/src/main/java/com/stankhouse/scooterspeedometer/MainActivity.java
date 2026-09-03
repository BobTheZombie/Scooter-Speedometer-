package com.stankhouse.scooterspeedometer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.util.Locale;

public class MainActivity extends Activity implements LocationListener {
    private static final int LOCATION_REQUEST = 42;
    private LocationManager locationManager;
    private SpeedView speedView;
    private SharedPreferences prefs;
    private Location lastGoodLocation;
    private long lastFixElapsed;
    private double tripMeters;
    private float maxMps;
    private float smoothedMps;
    private int satellites;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("speedometer", MODE_PRIVATE);
        tripMeters = prefs.getFloat("trip", 0f);
        maxMps = prefs.getFloat("max", 0f);
        speedView = new SpeedView(this);
        setContentView(speedView);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        enterImmersive();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST);
        } else startGps();
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) enterImmersive(); }
    @Override protected void onResume() { super.onResume(); enterImmersive(); if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGps(); }
    @Override protected void onPause() { super.onPause(); stopGps(); saveStats(); }

    private void startGps() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, this);
            locationManager.registerGnssStatusCallback(new GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(GnssStatus status) {
                    int used = 0; for (int i = 0; i < status.getSatelliteCount(); i++) if (status.usedInFix(i)) used++;
                    satellites = used; speedView.invalidate();
                }
            });
        } catch (SecurityException ignored) { }
    }

    private void stopGps() {
        try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
    }

    @Override public void onLocationChanged(Location location) {
        lastFixElapsed = SystemClock.elapsedRealtime();
        boolean accurate = location.hasAccuracy() && location.getAccuracy() <= 35f;
        if (!accurate) { speedView.invalidate(); return; }
        float raw = location.hasSpeed() ? Math.max(0f, location.getSpeed()) : 0f;
        if (raw < 0.45f) raw = 0f;
        smoothedMps = smoothedMps == 0f ? raw : (smoothedMps * 0.64f + raw * 0.36f);
        if (smoothedMps < 0.35f) smoothedMps = 0f;
        maxMps = Math.max(maxMps, smoothedMps);
        if (lastGoodLocation != null && location.getTime() > lastGoodLocation.getTime()) {
            float distance = lastGoodLocation.distanceTo(location);
            long dt = location.getTime() - lastGoodLocation.getTime();
            if (distance < 120f && dt < 15000L && (raw > 0.7f || distance > 4f)) tripMeters += distance;
        }
        lastGoodLocation = location;
        speedView.accuracy = location.getAccuracy();
        speedView.invalidate();
    }

    @Override public void onProviderEnabled(String provider) { speedView.invalidate(); }
    @Override public void onProviderDisabled(String provider) { speedView.invalidate(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startGps();
        speedView.invalidate();
    }

    private void resetStats() { tripMeters = 0; maxMps = 0; saveStats(); speedView.invalidate(); }
    private void saveStats() { prefs.edit().putFloat("trip", (float) tripMeters).putFloat("max", maxMps).apply(); }

    private class SpeedView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private boolean metric = prefs.getBoolean("metric", false);
        private float accuracy = 999f;
        private long downAt;
        private float downX, downY;

        SpeedView(Context context) { super(context); setBackgroundColor(Color.rgb(4, 8, 12)); }
        private void text(Canvas c, String value, float x, float y, float size, int color, Paint.Align align, boolean bold) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setTextSize(size); paint.setTextAlign(align);
            paint.setTypeface(bold ? android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD) : android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            c.drawText(value, x, y, paint);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight(), cx = w / 2f;
            float scale = Math.min(w, h) / 500f;
            boolean fresh = lastFixElapsed > 0 && SystemClock.elapsedRealtime() - lastFixElapsed < 3500;
            boolean permission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean gpsOn = false; try { gpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
            float shown = smoothedMps * (metric ? 3.6f : 2.2369363f);
            float maxShown = maxMps * (metric ? 3.6f : 2.2369363f);
            double tripShown = tripMeters / (metric ? 1000.0 : 1609.344);

            float radius = Math.min(w * .40f, h * .34f);
            arc.set(cx - radius, h * .43f - radius, cx + radius, h * .43f + radius);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(15f * scale); paint.setColor(Color.rgb(27, 42, 51)); c.drawArc(arc, 145, 250, false, paint);
            float limit = metric ? 130f : 80f;
            float sweep = Math.min(shown / limit, 1f) * 250f;
            paint.setColor(shown > limit * .82f ? Color.rgb(255, 179, 0) : Color.rgb(0, 229, 255)); c.drawArc(arc, 145, sweep, false, paint);

            text(c, fresh ? String.format(Locale.US, "%.0f", shown) : "--", cx, h * .50f, Math.min(190f * scale, h * .25f), Color.WHITE, Paint.Align.CENTER, true);
            text(c, metric ? "KM/H" : "MPH", cx, h * .59f, 28f * scale, Color.rgb(0, 229, 255), Paint.Align.CENTER, true);

            String status;
            int statusColor;
            if (!permission) { status = "TAP TO ALLOW GPS"; statusColor = Color.rgb(255,179,0); }
            else if (!gpsOn) { status = "TURN ON GPS"; statusColor = Color.rgb(255,179,0); }
            else if (!fresh) { status = "SEARCHING FOR GPS  •  " + satellites + " SAT"; statusColor = Color.rgb(255,179,0); }
            else { status = "GPS LOCK  •  " + satellites + " SAT  •  ±" + Math.round(accuracy) + "m"; statusColor = Color.rgb(74,222,128); }
            text(c, status, cx, h * .70f, 18f * scale, statusColor, Paint.Align.CENTER, true);

            paint.setColor(Color.rgb(20,31,38)); paint.setStrokeWidth(2f * scale); c.drawLine(w*.10f,h*.76f,w*.90f,h*.76f,paint);
            text(c, "MAX", w*.27f, h*.82f, 16f*scale, Color.rgb(132,154,166), Paint.Align.CENTER, true);
            text(c, String.format(Locale.US,"%.0f %s",maxShown,metric?"km/h":"mph"), w*.27f,h*.88f,26f*scale,Color.WHITE,Paint.Align.CENTER,true);
            text(c, "TRIP", w*.73f, h*.82f, 16f*scale, Color.rgb(132,154,166), Paint.Align.CENTER, true);
            text(c, String.format(Locale.US,"%.2f %s",tripShown,metric?"km":"mi"), w*.73f,h*.88f,26f*scale,Color.WHITE,Paint.Align.CENTER,true);
            text(c, "Tap MPH/KM/H to switch  •  Hold screen to reset", cx,h*.96f,13f*scale,Color.rgb(82,105,117),Paint.Align.CENTER,false);

            if (isInEditMode()) invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { downAt = SystemClock.elapsedRealtime(); downX=e.getX(); downY=e.getY(); return true; }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                long held = SystemClock.elapsedRealtime()-downAt;
                if (held >= 1100 && Math.hypot(e.getX()-downX,e.getY()-downY)<80) resetStats();
                else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST);
                else { metric = !metric; prefs.edit().putBoolean("metric", metric).apply(); invalidate(); }
                performClick(); return true;
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
