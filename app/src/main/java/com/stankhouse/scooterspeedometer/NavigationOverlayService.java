package com.stankhouse.scooterspeedometer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

public class NavigationOverlayService extends Service implements LocationListener {
    private static final String CHANNEL_ID = "navigation_overlay";
    private static final int NOTIFICATION_ID = 27;
    private static final String ACTION_STOP = "com.stankhouse.scooterspeedometer.STOP_OVERLAY";

    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private OverlayView overlayView;
    private LocationManager locationManager;
    private MediaSessionManager mediaSessionManager;
    private MediaController mediaController;
    private MediaMetadata mediaMetadata;
    private PlaybackState playbackState;
    private float smoothedMps;
    private SharedPreferences prefs;
    private WeatherRepository weatherRepository;
    private WeatherRepository.WeatherData weatherData;
    private WeatherRepository.AlertData weatherAlert;
    private long lastWeatherRequest;

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { mediaMetadata = metadata; redraw(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { playbackState = state; redraw(); }
        @Override public void onSessionDestroyed() { refreshMedia(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> selectController(controllers);

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("speedometer", MODE_PRIVATE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        weatherRepository = new WeatherRepository(this);
        weatherData = weatherRepository.cached();
        weatherAlert = weatherRepository.cachedAlert();
        createNotificationChannel();
        startAsForeground();
        if (Settings.canDrawOverlays(this)) addOverlay();
        startLocation();
        refreshMedia();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Navigation dashboard",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the scooter speed and media dashboard visible during navigation");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void startAsForeground() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, NavigationOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(com.stankhouse.scooterspeedometer.R.drawable.ic_launcher)
                .setContentTitle("Scooter dashboard is active")
                .setContentText("Speed and music controls are visible over navigation")
                .setContentIntent(openIntent)
                .addAction(new Notification.Action.Builder(null, "Close dashboard", stopIntent).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void addOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView = new OverlayView(this);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        windowParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, dp(126), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = 0;
        windowParams.y = dp(110);
        windowManager.addView(overlayView, windowParams);
    }

    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, this); }
        catch (SecurityException ignored) { }
    }

    private boolean hasMediaAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void refreshMedia() {
        stopMedia();
        if (!hasMediaAccess()) { clearMedia(); return; }
        ComponentName listener = new ComponentName(this, MediaAccessService.class);
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, listener);
            selectController(mediaSessionManager.getActiveSessions(listener));
        } catch (SecurityException ignored) { clearMedia(); }
    }

    private void selectController(List<MediaController> controllers) {
        MediaController selected = null;
        if (controllers != null) for (MediaController controller : controllers) {
            PlaybackState state = controller.getPlaybackState();
            if (selected == null) selected = controller;
            if (state != null && (state.getState() == PlaybackState.STATE_PLAYING ||
                    state.getState() == PlaybackState.STATE_BUFFERING)) { selected = controller; break; }
        }
        if (mediaController != null) mediaController.unregisterCallback(mediaCallback);
        mediaController = selected;
        if (selected == null) clearMedia();
        else {
            selected.registerCallback(mediaCallback);
            mediaMetadata = selected.getMetadata();
            playbackState = selected.getPlaybackState();
            redraw();
        }
    }

    private void stopMedia() {
        if (mediaSessionManager != null) mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        if (mediaController != null) mediaController.unregisterCallback(mediaCallback);
    }
    private void clearMedia() { mediaController = null; mediaMetadata = null; playbackState = null; redraw(); }
    private void redraw() { if (overlayView != null) overlayView.postInvalidate(); }
    private boolean isPlaying() {
        if (playbackState == null) return false;
        return playbackState.getState() == PlaybackState.STATE_PLAYING || playbackState.getState() == PlaybackState.STATE_BUFFERING;
    }

    @Override public void onLocationChanged(Location location) {
        if (location.hasAccuracy() && location.getAccuracy() <= 40f) {
            float raw = location.hasSpeed() ? Math.max(0, location.getSpeed()) : 0;
            if (raw < .45f) raw = 0;
            smoothedMps = smoothedMps == 0 ? raw : smoothedMps * .62f + raw * .38f;
            if (smoothedMps < .35f) smoothedMps = 0;
            if (android.os.SystemClock.elapsedRealtime() - lastWeatherRequest > 60000L) {
                lastWeatherRequest = android.os.SystemClock.elapsedRealtime();
                weatherRepository.update(location.getLatitude(), location.getLongitude(), data -> {
                    weatherData = data;
                    redraw();
                });
                weatherRepository.updateAlerts(location.getLatitude(), location.getLongitude(), alert -> {
                    weatherAlert = alert;
                    redraw();
                });
            }
            redraw();
        }
    }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override public void onDestroy() {
        try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
        stopMedia();
        if (windowManager != null && overlayView != null) windowManager.removeView(overlayView);
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }

    private class OverlayView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downRawY;
        private int startWindowY;
        private boolean dragging;

        OverlayView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        private void text(Canvas c, String value, float x, float y, float size, int color, Paint.Align align, boolean bold) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setTextSize(size); paint.setTextAlign(align);
            paint.setTypeface(android.graphics.Typeface.create(bold ? "sans-serif-condensed" : "sans-serif",
                    bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            c.drawText(value, x, y, paint);
        }
        private String shortText(String value, int max) {
            if (TextUtils.isEmpty(value)) return "";
            return value.length() <= max ? value : value.substring(0, max - 1) + "…";
        }
        private Bitmap art() {
            if (mediaMetadata == null) return null;
            Bitmap result = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (result == null) result = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (result == null) result = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            return result;
        }
        private void drawCover(Canvas c, Bitmap bitmap, RectF dst) {
            if (bitmap == null || bitmap.isRecycled()) return;
            float sr = bitmap.getWidth() / (float) bitmap.getHeight(), dr = dst.width() / dst.height();
            Rect src;
            if (sr > dr) { int sw = Math.round(bitmap.getHeight() * dr), left = (bitmap.getWidth() - sw) / 2; src = new Rect(left, 0, left + sw, bitmap.getHeight()); }
            else { int sh = Math.round(bitmap.getWidth() / dr), top = (bitmap.getHeight() - sh) / 2; src = new Rect(0, top, bitmap.getWidth(), top + sh); }
            c.drawBitmap(bitmap, src, dst, paint);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), scale = getResources().getDisplayMetrics().density;
            RectF card = new RectF(dp(6), dp(4), w - dp(6), h - dp(4));
            paint.setShadowLayer(dp(8), 0, dp(2), Color.argb(150, 0, 0, 0));
            int weatherTint = Color.rgb(3, 10, 14);
            if (weatherData != null) {
                int code = weatherData.weatherCode;
                if (code == 0) weatherTint = Color.rgb(4, 48, 82);
                else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) weatherTint = Color.rgb(20, 42, 56);
                else if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) weatherTint = Color.rgb(48, 68, 82);
                else if (code >= 95) weatherTint = Color.rgb(30, 18, 47);
            }
            paint.setColor(Color.argb(236, Color.red(weatherTint), Color.green(weatherTint), Color.blue(weatherTint)));
            c.drawRoundRect(card, dp(22), dp(22), paint);
            paint.clearShadowLayer();

            Bitmap album = art();
            RectF mediaBackground = new RectF(w * .29f, dp(4), w - dp(6), h - dp(4));
            if (album != null) {
                paint.setAlpha(115); drawCover(c, album, mediaBackground); paint.setAlpha(255);
                paint.setColor(Color.argb(145, 0, 5, 8)); c.drawRoundRect(mediaBackground, dp(22), dp(22), paint);
            }

            boolean metric = prefs.getBoolean("metric", false);
            float speed = smoothedMps * (metric ? 3.6f : 2.2369363f);
            text(c, String.format(java.util.Locale.US, "%.0f", speed), w * .14f, h * .59f, dp(58), Color.WHITE, Paint.Align.CENTER, true);
            text(c, metric ? "KM/H" : "MPH", w * .14f, h * .82f, dp(14), Color.rgb(0,229,255), Paint.Align.CENTER, true);
            text(c, "⋮⋮", w * .275f, h * .58f, dp(24), Color.rgb(95,120,132), Paint.Align.CENTER, true);

            if (weatherAlert != null && weatherAlert.active()) {
                text(c, "⚠ " + shortText(weatherAlert.event.toUpperCase(java.util.Locale.US), 31),
                        w * .34f, h * .18f, dp(12), Color.rgb(255, 150, 90), Paint.Align.LEFT, true);
            } else if (weatherData != null) {
                text(c, weatherData.icon() + " " + String.format(java.util.Locale.US, "%.0f°", weatherData.temperature),
                        w * .34f, h * .18f, dp(13), Color.rgb(255, 220, 110), Paint.Align.LEFT, true);
                text(c, "☂" + weatherData.rainChance + "%  " + weatherData.windCompass() + " " +
                                String.format(java.util.Locale.US, "%.0f", weatherData.windSpeed) + "mph",
                        w * .48f, h * .18f, dp(11), Color.rgb(205, 220, 228), Paint.Align.LEFT, false);
            }

            String title = mediaMetadata == null ? "No music playing" : mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = mediaMetadata == null ? "Tap your music app to start" : mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            text(c, shortText(title, 27), w * .34f, h * .39f, dp(17), Color.WHITE, Paint.Align.LEFT, true);
            text(c, shortText(artist, 30), w * .34f, h * .59f, dp(12), Color.rgb(205,218,224), Paint.Align.LEFT, false);
            text(c, "|◀", w * .63f, h * .83f, dp(22), Color.WHITE, Paint.Align.CENTER, true);
            text(c, isPlaying() ? "Ⅱ" : "▶", w * .76f, h * .83f, dp(27), Color.rgb(0,229,255), Paint.Align.CENTER, true);
            text(c, "▶|", w * .88f, h * .83f, dp(22), Color.WHITE, Paint.Align.CENTER, true);
            text(c, "×", w * .965f, h * .31f, dp(25), Color.rgb(220,230,235), Paint.Align.CENTER, false);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float w = getWidth();
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downRawY = event.getRawY(); startWindowY = windowParams.y; dragging = event.getX() < w * .31f; return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE && dragging) {
                windowParams.y = Math.max(0, startWindowY + Math.round(event.getRawY() - downRawY));
                windowManager.updateViewLayout(this, windowParams); return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (Math.abs(event.getRawY() - downRawY) < dp(8)) {
                    float x = event.getX();
                    if (x < w * .29f) {
                        boolean metric = prefs.getBoolean("metric", false);
                        prefs.edit().putBoolean("metric", !metric).apply(); invalidate();
                    } else if (x > w * .93f) stopSelf();
                    else if (x > w * .56f && x < w * .69f && mediaController != null) mediaController.getTransportControls().skipToPrevious();
                    else if (x < w * .82f && x > w * .69f && mediaController != null) {
                        if (isPlaying()) mediaController.getTransportControls().pause(); else mediaController.getTransportControls().play();
                    } else if (x > w * .82f && x < w * .93f && mediaController != null) mediaController.getTransportControls().skipToNext();
                }
                dragging = false; performClick(); return true;
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
