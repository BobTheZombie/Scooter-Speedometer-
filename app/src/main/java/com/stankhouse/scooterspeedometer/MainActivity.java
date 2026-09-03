package com.stankhouse.scooterspeedometer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements LocationListener {
    private static final int LOCATION_REQUEST = 42;
    private LocationManager locationManager;
    private MediaSessionManager mediaSessionManager;
    private AudioManager audioManager;
    private SpeedView speedView;
    private SharedPreferences prefs;
    private Location lastGoodLocation;
    private long lastFixElapsed;
    private double tripMeters;
    private float maxMps;
    private float smoothedMps;
    private int satellites;
    private final SpeedFilter speedFilter = new SpeedFilter();
    private GnssStatus.Callback gnssCallback;
    private MediaController mediaController;
    private MediaMetadata mediaMetadata;
    private PlaybackState playbackState;
    private boolean navigationPermissionPending;
    private WeatherRepository weatherRepository;
    private WeatherRepository.WeatherData weatherData;
    private WeatherRepository.AlertData weatherAlert;
    private long lastWeatherRequest;

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { mediaMetadata = metadata; speedView.invalidate(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { playbackState = state; speedView.invalidate(); }
        @Override public void onSessionDestroyed() { refreshMedia(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> selectMediaController(controllers);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("speedometer", MODE_PRIVATE);
        tripMeters = prefs.getFloat("trip", 0f);
        maxMps = prefs.getFloat("max", 0f);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        weatherRepository = new WeatherRepository(this);
        weatherData = weatherRepository.cached();
        weatherAlert = weatherRepository.cachedAlert();
        speedView = new SpeedView(this);
        setContentView(speedView);
        enterImmersive();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS}, LOCATION_REQUEST);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST);
        } else {
            startGps();
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
        }
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) enterImmersive(); }
    @Override protected void onResume() {
        super.onResume();
        enterImmersive();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGps();
        refreshMedia();
        if (navigationPermissionPending && Settings.canDrawOverlays(this)) {
            navigationPermissionPending = false;
            speedView.post(this::showDestinationDialog);
        }
    }
    @Override protected void onPause() { super.onPause(); stopGps(); stopMediaListener(); saveStats(); }

    private void startGps() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, this);
            if (gnssCallback != null) return;
            gnssCallback = new GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(GnssStatus status) {
                    int used = 0;
                    for (int i = 0; i < status.getSatelliteCount(); i++) if (status.usedInFix(i)) used++;
                    satellites = used; speedView.invalidate();
                }
            };
            locationManager.registerGnssStatusCallback(gnssCallback);
        } catch (SecurityException ignored) { }
    }
    private void stopGps() {
        try {
            locationManager.removeUpdates(this);
            if (gnssCallback != null) locationManager.unregisterGnssStatusCallback(gnssCallback);
        } catch (SecurityException ignored) { }
        gnssCallback = null;
    }

    private boolean hasMediaAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }
    private ComponentName mediaListenerComponent() { return new ComponentName(this, MediaAccessService.class); }
    private void refreshMedia() {
        stopMediaListener();
        if (!hasMediaAccess()) { clearMedia(); return; }
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, mediaListenerComponent());
            selectMediaController(mediaSessionManager.getActiveSessions(mediaListenerComponent()));
        } catch (SecurityException ignored) { clearMedia(); }
    }
    private void stopMediaListener() {
        if (mediaSessionManager != null) mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        if (mediaController != null) mediaController.unregisterCallback(mediaCallback);
    }
    private void selectMediaController(List<MediaController> controllers) {
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
            speedView.invalidate();
        }
    }
    private void clearMedia() {
        mediaController = null; mediaMetadata = null; playbackState = null;
        if (speedView != null) speedView.invalidate();
    }
    private void openMediaAccessSettings() { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
    private boolean isPlaying() {
        if (playbackState == null) return false;
        return playbackState.getState() == PlaybackState.STATE_PLAYING || playbackState.getState() == PlaybackState.STATE_BUFFERING;
    }
    private void mediaPrevious() { if (mediaController != null) mediaController.getTransportControls().skipToPrevious(); }
    private void mediaPlayPause() {
        if (mediaController == null) return;
        if (isPlaying()) mediaController.getTransportControls().pause(); else mediaController.getTransportControls().play();
    }
    private void mediaNext() { if (mediaController != null) mediaController.getTransportControls().skipToNext(); }
    private void adjustMusicVolume(int direction) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        speedView.invalidate();
    }
    private int musicVolumePercent() {
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0 : Math.round(100f * audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
    }

    private void showWeatherAlert() {
        if (weatherAlert == null || !weatherAlert.active()) return;
        String details = weatherAlert.headline;
        if (!TextUtils.isEmpty(weatherAlert.instruction)) details += "\n\n" + weatherAlert.instruction;
        new AlertDialog.Builder(this).setTitle(weatherAlert.event)
                .setMessage(details).setPositiveButton("Got it", null).show();
    }

    private void beginNavigation() {
        if (!Settings.canDrawOverlays(this)) {
            navigationPermissionPending = true;
            new AlertDialog.Builder(this)
                    .setTitle("Keep the dash over navigation")
                    .setMessage("Allow Scooter Speedometer to display over other apps. This keeps speed and music controls visible over Google Maps, Waze, and other navigation apps.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Allow", (dialog, which) -> startActivity(new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))))
                    .show();
            return;
        }
        showDestinationDialog();
    }

    private void showDestinationDialog() {
        final EditText destination = new EditText(this);
        destination.setHint("Address, business, or destination");
        destination.setSingleLine(true);
        destination.setPadding(48, 12, 48, 12);
        new AlertDialog.Builder(this)
                .setTitle("Where are we going?")
                .setView(destination)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose navigation app", (dialog, which) -> {
                    String query = destination.getText().toString().trim();
                    if (!query.isEmpty()) chooseNavigationApp(query);
                }).show();
    }

    private void chooseNavigationApp(String destination) {
        String[] apps = {"Google Maps", "Waze", "Another navigation app"};
        new AlertDialog.Builder(this)
                .setTitle("Navigate with")
                .setItems(apps, (dialog, which) -> launchNavigation(destination, which))
                .show();
    }

    private void launchNavigation(String destination, int choice) {
        Intent overlay = new Intent(this, NavigationOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(overlay); else startService(overlay);

        Intent navigation;
        if (choice == 0) {
            navigation = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination)));
            navigation.setPackage("com.google.android.apps.maps");
        } else if (choice == 1) {
            navigation = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://waze.com/ul?q=" + Uri.encode(destination) + "&navigate=yes"));
            navigation.setPackage("com.waze");
        } else {
            navigation = Intent.createChooser(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=" + Uri.encode(destination))), "Navigate with");
        }
        try { startActivity(navigation); }
        catch (ActivityNotFoundException missing) {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=" + Uri.encode(destination))), "Navigate with"));
        }
    }

    @Override public void onLocationChanged(Location location) {
        float filtered = speedFilter.update(location);
        if (Float.isNaN(filtered)) { speedView.invalidate(); return; }
        lastFixElapsed = SystemClock.elapsedRealtime();
        float raw = Math.max(0f, location.getSpeed());
        smoothedMps = filtered;
        maxMps = Math.max(maxMps, smoothedMps);
        if (lastGoodLocation != null && location.getTime() > lastGoodLocation.getTime()) {
            float distance = lastGoodLocation.distanceTo(location);
            long dt = location.getTime() - lastGoodLocation.getTime();
            if (distance < 120f && dt < 15000L && (raw > 0.7f || distance > 4f)) tripMeters += distance;
        }
        lastGoodLocation = location;
        if (SystemClock.elapsedRealtime() - lastWeatherRequest > 60000L) {
            lastWeatherRequest = SystemClock.elapsedRealtime();
            weatherRepository.update(location.getLatitude(), location.getLongitude(), data -> {
                weatherData = data;
                speedView.invalidate();
            });
            weatherRepository.updateAlerts(location.getLatitude(), location.getLongitude(), alert -> {
                weatherAlert = alert;
                speedView.invalidate();
            });
        }
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
        private final RectF weatherPanelRect = new RectF();
        private boolean resizingWeather;
        private float weatherWidthFraction = prefs.getFloat("weather_width", .355f);
        private float weatherHeightFraction = prefs.getFloat("weather_height", .085f);
        private DashboardLayout dashboardLayout;
        private boolean editingDashboard;
        private int editingSection;
        private boolean resizingSection;
        private final RectF mediaSlot = new RectF(), gaugeSlot = new RectF(), navigationSlot = new RectF();
        private float editStartX, editStartY;
        private final RectF editStartRect = new RectF();
        private boolean customizePressed;
        SpeedView(Context context) {
            super(context);
            setBackgroundColor(Color.rgb(4, 8, 12));
            boolean landscape = getResources().getConfiguration().orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            dashboardLayout = DashboardLayout.decode(prefs.getString("dashboard_layout", ""),
                    DashboardLayout.preset(landscape, false));
        }

        private RectF slot(RectF normalized, float w, float h, RectF out) {
            out.set(normalized.left * w, normalized.top * h, normalized.right * w, normalized.bottom * h);
            return out;
        }

        private int beginTransform(Canvas c, RectF target, RectF base) {
            int save = c.save();
            c.clipRect(target);
            c.translate(target.left, target.top);
            c.scale(target.width() / base.width(), target.height() / base.height());
            c.translate(-base.left, -base.top);
            return save;
        }

        private int accentColor() {
            int[] colors = {Color.rgb(0,229,255), Color.rgb(105,255,120),
                    Color.rgb(255,174,0), Color.rgb(190,90,255), Color.rgb(255,65,90)};
            return colors[Math.max(0, Math.min(colors.length - 1, dashboardLayout.gaugeColor))];
        }

        private void saveDashboard() {
            prefs.edit().putString("dashboard_layout", dashboardLayout.encode()).apply();
        }

        private void applyPreset(boolean landscape, boolean compact) {
            DashboardLayout preset = DashboardLayout.preset(landscape, compact);
            preset.albumAlpha = dashboardLayout.albumAlpha;
            preset.gaugeColor = dashboardLayout.gaugeColor;
            preset.gaugeStyle = dashboardLayout.gaugeStyle;
            dashboardLayout = preset;
            saveDashboard(); invalidate();
        }

        private void showProfilePicker(boolean save) {
            String[] profiles = {"Layout 1", "Layout 2", "Layout 3"};
            new AlertDialog.Builder(MainActivity.this).setTitle(save ? "Save layout" : "Load layout")
                    .setItems(profiles, (dialog, which) -> {
                        String key = "dashboard_profile_" + which;
                        if (save) prefs.edit().putString(key, dashboardLayout.encode()).apply();
                        else {
                            String stored = prefs.getString(key, "");
                            if (!stored.isEmpty()) {
                                dashboardLayout = DashboardLayout.decode(stored, dashboardLayout);
                                saveDashboard(); invalidate();
                            }
                        }
                    }).show();
        }

        private void showDashboardCustomizer() {
            String[] choices = {editingDashboard ? "Finish editing" : "Drag & resize sections",
                    "Full portrait preset", "Compact portrait preset", "Full landscape preset",
                    "Compact landscape preset", "Album-art transparency", "Gauge color",
                    "Gauge style", "Save layout", "Load layout"};
            new AlertDialog.Builder(MainActivity.this).setTitle("Customize dashboard")
                    .setItems(choices, (dialog, which) -> {
                        if (which == 0) { editingDashboard = !editingDashboard; editingSection = 0; invalidate(); }
                        else if (which >= 1 && which <= 4) applyPreset(which >= 3, which == 2 || which == 4);
                        else if (which == 5) {
                            String[] levels = {"Subtle · 25%", "Balanced · 45%", "Bold · 65%", "Maximum · 85%"};
                            new AlertDialog.Builder(MainActivity.this).setTitle("Album-art background")
                                    .setSingleChoiceItems(levels, Math.max(0, Math.min(3, (dashboardLayout.albumAlpha - 50) / 50)),
                                            (d, item) -> { dashboardLayout.albumAlpha = new int[]{64,115,166,217}[item]; saveDashboard(); invalidate(); d.dismiss(); }).show();
                        } else if (which == 6) {
                            String[] colors = {"Cyan", "Lime", "Amber", "Purple", "Red"};
                            new AlertDialog.Builder(MainActivity.this).setTitle("Gauge color")
                                    .setSingleChoiceItems(colors, dashboardLayout.gaugeColor,
                                            (d, item) -> { dashboardLayout.gaugeColor = item; saveDashboard(); invalidate(); d.dismiss(); }).show();
                        } else if (which == 7) {
                            String[] styles = {"Classic arc", "Dual arc", "Minimal"};
                            new AlertDialog.Builder(MainActivity.this).setTitle("Gauge style")
                                    .setSingleChoiceItems(styles, dashboardLayout.gaugeStyle,
                                            (d, item) -> { dashboardLayout.gaugeStyle = item; saveDashboard(); invalidate(); d.dismiss(); }).show();
                        } else if (which == 8) showProfilePicker(true);
                        else if (which == 9) showProfilePicker(false);
                    }).show();
        }

        private void text(Canvas c, String value, float x, float y, float size, int color, Paint.Align align, boolean bold) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setTextSize(size); paint.setTextAlign(align);
            paint.setTypeface(android.graphics.Typeface.create(bold ? "sans-serif-condensed" : "sans-serif",
                    bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            c.drawText(value, x, y, paint);
        }
        private String ellipsize(String value, int max) {
            if (TextUtils.isEmpty(value)) return "";
            return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
        }
        private Bitmap albumArt() {
            if (mediaMetadata == null) return null;
            Bitmap art = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            return art;
        }
        private String metadataText(String preferred, String fallback) {
            if (mediaMetadata == null) return "";
            String value = mediaMetadata.getString(preferred);
            return TextUtils.isEmpty(value) ? mediaMetadata.getString(fallback) : value;
        }
        private void drawCover(Canvas c, Bitmap bitmap, RectF destination, int alpha) {
            if (bitmap == null || bitmap.isRecycled()) return;
            float srcRatio = bitmap.getWidth() / (float) bitmap.getHeight();
            float dstRatio = destination.width() / destination.height();
            Rect src;
            if (srcRatio > dstRatio) {
                int width = Math.round(bitmap.getHeight() * dstRatio);
                int left = (bitmap.getWidth() - width) / 2;
                src = new Rect(left, 0, left + width, bitmap.getHeight());
            } else {
                int height = Math.round(bitmap.getWidth() / dstRatio);
                int top = (bitmap.getHeight() - height) / 2;
                src = new Rect(0, top, bitmap.getWidth(), top + height);
            }
            paint.setAlpha(alpha); c.drawBitmap(bitmap, src, destination, paint); paint.setAlpha(255);
        }
        private void drawMediaPanel(Canvas c, float w, float h, float scale) {
            float top = h * .025f, bottom = h * .245f;
            RectF panel = new RectF(w * .035f, top, w * .965f, bottom);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(205, 4, 10, 14));
            c.drawRoundRect(panel, 24f * scale, 24f * scale, paint);
            if (!hasMediaAccess()) {
                text(c, "♫  ENABLE MUSIC CONTROLS", w * .5f, h * .115f, 21f * scale,
                        Color.rgb(0, 229, 255), Paint.Align.CENTER, true);
                text(c, "Tap here, then allow Scooter Speedometer", w * .5f, h * .165f,
                        13f * scale, Color.rgb(190, 205, 212), Paint.Align.CENTER, false);
                return;
            }
            if (mediaController == null || mediaMetadata == null) {
                text(c, "♫  NO MUSIC PLAYING", w * .5f, h * .125f, 20f * scale,
                        Color.rgb(154, 176, 187), Paint.Align.CENTER, true);
                text(c, "Start Spotify, YouTube Music, Pandora, or another player", w * .5f,
                        h * .17f, 12f * scale, Color.rgb(105, 128, 139), Paint.Align.CENTER, false);
                return;
            }
            Bitmap art = albumArt();
            float artSize = Math.min(w * .21f, (bottom - top) * .78f);
            RectF artRect = new RectF(w * .055f, top + (bottom - top - artSize) / 2f,
                    w * .055f + artSize, top + (bottom - top + artSize) / 2f);
            if (art != null) drawCover(c, art, artRect, 255);
            else {
                paint.setColor(Color.rgb(20, 39, 49)); c.drawRoundRect(artRect, 12f * scale, 12f * scale, paint);
                text(c, "♫", artRect.centerX(), artRect.centerY() + 15f * scale, 42f * scale,
                        Color.rgb(0, 229, 255), Paint.Align.CENTER, true);
            }
            String title = metadataText(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String artist = metadataText(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            float infoX = artRect.right + 14f * scale;
            text(c, ellipsize(title, 25), infoX, top + 39f * scale, 20f * scale, Color.WHITE, Paint.Align.LEFT, true);
            text(c, ellipsize(artist, 28), infoX, top + 65f * scale, 14f * scale,
                    Color.rgb(186, 204, 213), Paint.Align.LEFT, false);
            float buttonY = bottom - 34f * scale;
            text(c, "|◀", w * .53f, buttonY, 23f * scale, Color.WHITE, Paint.Align.CENTER, true);
            text(c, isPlaying() ? "Ⅱ" : "▶", w * .64f, buttonY, 29f * scale,
                    Color.rgb(0, 229, 255), Paint.Align.CENTER, true);
            text(c, "▶|", w * .75f, buttonY, 23f * scale, Color.WHITE, Paint.Align.CENTER, true);
            text(c, "−", w * .86f, buttonY, 28f * scale, Color.WHITE, Paint.Align.CENTER, true);
            text(c, "+", w * .95f, buttonY, 27f * scale, Color.WHITE, Paint.Align.CENTER, true);
            text(c, "VOL " + musicVolumePercent() + "%", w * .905f, top + 67f * scale,
                    10f * scale, Color.rgb(130, 220, 235), Paint.Align.CENTER, true);
        }

        private void drawWeatherPanel(Canvas c, float w, float h, float scale) {
            weatherWidthFraction = Math.max(.27f, Math.min(.535f, weatherWidthFraction));
            weatherHeightFraction = Math.max(.07f, Math.min(.17f, weatherHeightFraction));
            weatherPanelRect.set(w * .045f, h * .265f,
                    w * (.045f + weatherWidthFraction), h * (.265f + weatherHeightFraction));
            RectF panel = weatherPanelRect;
            float panelScale = Math.max(.82f, Math.min(1.55f, panel.height() / (h * .085f)));
            float left = panel.left, top = panel.top, pw = panel.width(), ph = panel.height();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(215, 4, 14, 20));
            c.drawRoundRect(panel, 18f * scale, 18f * scale, paint);
            if (weatherData == null) {
                text(c, "◌  WEATHER", left + pw * .085f, top + ph * .44f, 15f * scale * panelScale,
                        Color.rgb(0, 229, 255), Paint.Align.LEFT, true);
                text(c, "Loading…", left + pw * .085f, top + ph * .76f, 12f * scale * panelScale,
                        Color.rgb(170, 191, 201), Paint.Align.LEFT, false);
            } else {
                text(c, weatherData.icon(), left + pw * .09f, top + ph * .67f, 34f * scale * panelScale,
                        Color.rgb(255, 193, 7), Paint.Align.LEFT, true);
                text(c, String.format(Locale.US, "%.0f°", weatherData.temperature), left + pw * .30f,
                        top + ph * .55f, 27f * scale * panelScale, Color.WHITE, Paint.Align.LEFT, true);
                text(c, ellipsize(weatherData.condition(), Math.max(9, Math.round(17 * weatherWidthFraction / .355f))),
                        left + pw * .30f, top + ph * .84f, 11f * scale * panelScale,
                        Color.rgb(184, 204, 214), Paint.Align.LEFT, false);
                text(c, "☂ " + weatherData.rainChance + "%", left + pw * .68f, top + ph * .43f,
                        12f * scale * panelScale, Color.rgb(110, 210, 255), Paint.Align.LEFT, true);
                text(c, String.format(Locale.US, "%s %.0f mph", weatherData.windCompass(), weatherData.windSpeed),
                        left + pw * .68f, top + ph * .78f, 11f * scale * panelScale,
                        Color.rgb(205, 216, 222), Paint.Align.LEFT, false);
            }
            paint.setColor(Color.rgb(0, 229, 255));
            paint.setStrokeWidth(Math.max(2f, 2f * scale));
            paint.setStyle(Paint.Style.STROKE);
            float grip = Math.max(12f * scale, Math.min(panel.width(), panel.height()) * .18f);
            c.drawLine(panel.right - grip, panel.bottom - 3f * scale, panel.right - 3f * scale,
                    panel.bottom - grip, paint);
            c.drawLine(panel.right - grip * .55f, panel.bottom - 3f * scale, panel.right - 3f * scale,
                    panel.bottom - grip * .55f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawWeatherAtmosphere(Canvas c, float w, float h) {
            if (weatherData == null) return;
            int code = weatherData.weatherCode;
            long now = SystemClock.uptimeMillis();
            boolean thunder = code >= 95;
            boolean snow = (code >= 71 && code <= 77) || (code >= 85 && code <= 86);
            boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
            boolean fog = code == 45 || code == 48;
            int top = thunder ? Color.rgb(18, 22, 38) : rain ? Color.rgb(34, 55, 68) :
                    snow ? Color.rgb(89, 112, 129) : fog ? Color.rgb(78, 91, 99) :
                            code == 0 ? Color.rgb(14, 91, 153) : Color.rgb(48, 72, 88);
            int bottom = thunder ? Color.rgb(3, 6, 16) : rain ? Color.rgb(8, 20, 29) :
                    snow ? Color.rgb(25, 43, 58) : fog ? Color.rgb(27, 38, 44) :
                            code == 0 ? Color.rgb(3, 34, 69) : Color.rgb(9, 24, 34);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            if (code == 0) {
                float pulse = 1f + .05f * (float) Math.sin(now / 900.0);
                for (int i = 5; i >= 1; i--) {
                    paint.setColor(Color.argb(12 + i * 5, 255, 205, 70));
                    c.drawCircle(w * .84f, h * .31f, w * (.04f + i * .025f) * pulse, paint);
                }
                paint.setColor(Color.argb(220, 255, 224, 105));
                c.drawCircle(w * .84f, h * .31f, w * .045f, paint);
            }

            if (code >= 1 && code <= 3 || rain || thunder) {
                paint.setColor(Color.argb(55, 225, 238, 245));
                for (int i = 0; i < 7; i++) {
                    float x = (float) ((i * w * .24 + now * (.006 + i * .0007)) % (w + w * .35)) - w * .18f;
                    float y = h * (.16f + (i % 3) * .12f);
                    float r = w * (.09f + (i % 2) * .025f);
                    c.drawCircle(x, y, r, paint); c.drawCircle(x + r * .7f, y + r * .05f, r * .8f, paint);
                }
            }

            if (rain || thunder) {
                paint.setColor(Color.argb(thunder ? 145 : 105, 140, 210, 255));
                paint.setStrokeWidth(Math.max(2f, w / 360f));
                for (int i = 0; i < 55; i++) {
                    float x = (float) ((i * 83L + now / 7L) % (long) (w + 40)) - 20;
                    float y = (float) ((i * 137L + now / 3L) % (long) (h + 90)) - 90;
                    c.drawLine(x, y, x - w * .018f, y + h * .045f, paint);
                }
            } else if (snow) {
                paint.setColor(Color.argb(180, 245, 250, 255));
                for (int i = 0; i < 42; i++) {
                    float x = (float) ((i * 97L + now / (18L + i % 7)) % (long) (w + 30));
                    float y = (float) ((i * 149L + now / (9L + i % 5)) % (long) (h + 30));
                    c.drawCircle(x, y, 2f + i % 5, paint);
                }
            } else if (fog) {
                for (int i = 0; i < 8; i++) {
                    paint.setColor(Color.argb(22 + i * 3, 225, 235, 238));
                    float y = h * (.12f + i * .105f);
                    float offset = (float) ((now / (20 + i * 3)) % (long) (w * .2f));
                    c.drawRoundRect(new RectF(-w * .2f + offset, y, w * .85f + offset, y + h * .035f), 30, 30, paint);
                }
            }

            if (thunder && now % 6500L < 170L) {
                paint.setColor(Color.argb(115, 220, 235, 255)); c.drawRect(0, 0, w, h, paint);
                paint.setColor(Color.rgb(255, 240, 130)); paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(6f, w / 85f));
                Path bolt = new Path(); bolt.moveTo(w * .72f, h * .12f); bolt.lineTo(w * .60f, h * .37f);
                bolt.lineTo(w * .70f, h * .37f); bolt.lineTo(w * .54f, h * .66f); c.drawPath(bolt, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            postInvalidateDelayed(80L);
        }

        private void drawAlertBanner(Canvas c, float w, float h, float scale) {
            if (weatherAlert == null || !weatherAlert.active()) return;
            int color = weatherAlert.severityRank() >= 3 ? Color.rgb(190, 32, 38) : Color.rgb(213, 109, 20);
            float bannerLeft = Math.max(w * .42f, weatherPanelRect.right + w * .02f);
            RectF banner = new RectF(bannerLeft, h * .265f, w * .955f, h * .35f);
            paint.setColor(Color.argb(235, Color.red(color), Color.green(color), Color.blue(color)));
            paint.setStyle(Paint.Style.FILL); c.drawRoundRect(banner, 18f * scale, 18f * scale, paint);
            text(c, "⚠  " + ellipsize(weatherAlert.event.toUpperCase(Locale.US), 28), banner.left + w * .025f,
                    h * .302f, 15f * scale, Color.WHITE, Paint.Align.LEFT, true);
            text(c, ellipsize(weatherAlert.headline, 62), banner.left + w * .025f, h * .332f,
                    10f * scale, Color.rgb(255, 232, 225), Paint.Align.LEFT, false);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight(), cx = w / 2f;
            float scale = Math.min(w, h) / 500f;
            drawWeatherAtmosphere(c, w, h);
            Bitmap art = albumArt();
            if (art != null && mediaController != null) {
                drawCover(c, art, new RectF(0, 0, w, h), dashboardLayout.albumAlpha);
                paint.setColor(Color.argb(150, 0, 5, 8)); c.drawRect(0, 0, w, h, paint);
            }
            slot(dashboardLayout.media, w, h, mediaSlot);
            slot(dashboardLayout.gauge, w, h, gaugeSlot);
            slot(dashboardLayout.navigation, w, h, navigationSlot);
            RectF baseMedia = new RectF(w * .035f, h * .025f, w * .965f, h * .245f);
            int mediaSave = beginTransform(c, mediaSlot, baseMedia);
            drawMediaPanel(c, w, h, scale);
            c.restoreToCount(mediaSave);
            drawWeatherPanel(c, w, h, scale);
            drawAlertBanner(c, w, h, scale);
            boolean fresh = lastFixElapsed > 0 && SystemClock.elapsedRealtime() - lastFixElapsed < 3500;
            boolean permission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean gpsOn = false;
            try { gpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
            float shown = smoothedMps * (metric ? 3.6f : 2.2369363f);
            float maxShown = maxMps * (metric ? 3.6f : 2.2369363f);
            double tripShown = tripMeters / (metric ? 1000.0 : 1609.344);
            RectF baseGauge = new RectF(w * .10f, h * .38f, w * .90f, h * .72f);
            int gaugeSave = beginTransform(c, gaugeSlot, baseGauge);
            float radius = Math.min(w * .38f, h * .245f);
            arc.set(cx - radius, h * .49f - radius, cx + radius, h * .49f + radius);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dashboardLayout.gaugeStyle == 2 ? 5f * scale : 15f * scale);
            paint.setColor(Color.rgb(27, 42, 51));
            if (dashboardLayout.gaugeStyle != 2) c.drawArc(arc, 145, 250, false, paint);
            float limit = metric ? 130f : 80f;
            paint.setColor(shown > limit * .82f ? Color.rgb(255, 179, 0) : accentColor());
            c.drawArc(arc, 145, Math.min(shown / limit, 1f) * 250f, false, paint);
            if (dashboardLayout.gaugeStyle == 1) {
                paint.setStrokeWidth(4f * scale); paint.setColor(Color.argb(130, Color.red(accentColor()), Color.green(accentColor()), Color.blue(accentColor())));
                RectF inner = new RectF(arc.left + 22f * scale, arc.top + 22f * scale,
                        arc.right - 22f * scale, arc.bottom - 22f * scale);
                c.drawArc(inner, 145, Math.min(shown / limit, 1f) * 250f, false, paint);
            }
            text(c, fresh ? String.format(Locale.US, "%.0f", shown) : "--", cx, h * .535f,
                    Math.min(150f * scale, h * .18f), Color.WHITE, Paint.Align.CENTER, true);
            text(c, metric ? "KM/H" : "MPH", cx, h * .615f, 27f * scale,
                    accentColor(), Paint.Align.CENTER, true);
            String status; int statusColor;
            if (!permission) { status = "TAP GAUGE TO ALLOW GPS"; statusColor = Color.rgb(255,179,0); }
            else if (!gpsOn) { status = "TURN ON GPS"; statusColor = Color.rgb(255,179,0); }
            else if (!fresh) { status = "SEARCHING FOR GPS  •  " + satellites + " SAT"; statusColor = Color.rgb(255,179,0); }
            else { status = "GPS LOCK  •  " + satellites + " SAT  •  ±" + Math.round(accuracy) + "m"; statusColor = Color.rgb(74,222,128); }
            text(c, status, cx, h * .71f, 17f * scale, statusColor, Paint.Align.CENTER, true);
            c.restoreToCount(gaugeSave);

            RectF baseNavigation = new RectF(w * .36f, h * .725f, w * .64f, h * .77f);
            int navSave = beginTransform(c, navigationSlot, baseNavigation);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(220, Color.red(accentColor()) / 2, Color.green(accentColor()) / 2, Color.blue(accentColor()) / 2));
            RectF navButton = new RectF(w * .36f, h * .725f, w * .64f, h * .77f);
            c.drawRoundRect(navButton, 18f * scale, 18f * scale, paint);
            text(c, "➤  NAVIGATE", cx, h * .756f, 15f * scale, Color.WHITE, Paint.Align.CENTER, true);
            c.restoreToCount(navSave);
            paint.setColor(Color.rgb(52, 69, 78)); paint.setStrokeWidth(2f * scale);
            c.drawLine(w * .10f, h * .785f, w * .90f, h * .785f, paint);
            text(c, "MAX", w * .27f, h * .825f, 16f * scale, Color.rgb(160,180,190), Paint.Align.CENTER, true);
            text(c, String.format(Locale.US, "%.0f %s", maxShown, metric ? "km/h" : "mph"),
                    w * .27f, h * .885f, 26f * scale, Color.WHITE, Paint.Align.CENTER, true);
            text(c, "TRIP", w * .73f, h * .825f, 16f * scale, Color.rgb(160,180,190), Paint.Align.CENTER, true);
            text(c, String.format(Locale.US, "%.2f %s", tripShown, metric ? "km" : "mi"),
                    w * .73f, h * .885f, 26f * scale, Color.WHITE, Paint.Align.CENTER, true);
            text(c, "Tap gauge: MPH/KM/H  •  Hold gauge: reset", cx, h * .965f,
                    13f * scale, Color.rgb(120,145,157), Paint.Align.CENTER, false);
            paint.setColor(Color.argb(225, 8, 28, 36));
            RectF customize = new RectF(w * .405f, h * .91f, w * .595f, h * .952f);
            c.drawRoundRect(customize, 14f * scale, 14f * scale, paint);
            text(c, editingDashboard ? "✓ DONE" : "⚙ CUSTOMIZE", cx, h * .939f, 12f * scale,
                    accentColor(), Paint.Align.CENTER, true);
            if (editingDashboard) drawEditorOverlay(c, scale);
        }

        private void drawEditorOverlay(Canvas c, float scale) {
            RectF[] sections = {mediaSlot, gaugeSlot, navigationSlot};
            String[] names = {"MEDIA", "SPEEDOMETER", "NAVIGATION"};
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3f * scale);
            for (int i = 0; i < sections.length; i++) {
                RectF section = sections[i];
                paint.setColor(i + 1 == editingSection ? Color.WHITE : accentColor());
                c.drawRoundRect(section, 12f * scale, 12f * scale, paint);
                paint.setStyle(Paint.Style.FILL);
                text(c, names[i], section.left + 8f * scale, section.top + 17f * scale,
                        11f * scale, Color.WHITE, Paint.Align.LEFT, true);
                c.drawCircle(section.right - 9f * scale, section.bottom - 9f * scale, 8f * scale, paint);
                paint.setStyle(Paint.Style.STROKE);
            }
            paint.setStyle(Paint.Style.FILL);
            text(c, "Drag a section • drag its corner dot to resize", getWidth() * .5f,
                    getHeight() * .985f, 11f * scale, Color.WHITE, Paint.Align.CENTER, true);
        }

        private RectF editableRect(int section) {
            return section == 1 ? dashboardLayout.media : section == 2 ? dashboardLayout.gauge : dashboardLayout.navigation;
        }

        private void clampEditedRect(RectF rect, float minWidth, float minHeight) {
            if (rect.width() < minWidth) rect.right = rect.left + minWidth;
            if (rect.height() < minHeight) rect.bottom = rect.top + minHeight;
            if (rect.left < .01f) rect.offset(.01f - rect.left, 0);
            if (rect.top < .01f) rect.offset(0, .01f - rect.top);
            if (rect.right > .99f) rect.offset(.99f - rect.right, 0);
            if (rect.bottom > .90f) rect.offset(0, .90f - rect.bottom);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                downAt = SystemClock.elapsedRealtime(); downX = e.getX(); downY = e.getY();
                float w = getWidth(), h = getHeight();
                customizePressed = e.getX() >= w * .405f && e.getX() <= w * .595f &&
                        e.getY() >= h * .90f && e.getY() <= h * .96f;
                if (editingDashboard && !customizePressed) {
                    RectF[] sections = {mediaSlot, gaugeSlot, navigationSlot};
                    editingSection = 0;
                    for (int i = sections.length - 1; i >= 0; i--) if (sections[i].contains(e.getX(), e.getY())) {
                        editingSection = i + 1;
                        float grip = Math.max(34f * getResources().getDisplayMetrics().density,
                                Math.min(sections[i].width(), sections[i].height()) * .22f);
                        resizingSection = e.getX() > sections[i].right - grip && e.getY() > sections[i].bottom - grip;
                        editStartX = e.getX(); editStartY = e.getY(); editStartRect.set(editableRect(editingSection));
                        break;
                    }
                    invalidate(); return true;
                }
                float grip = Math.max(32f * getResources().getDisplayMetrics().density,
                        Math.min(weatherPanelRect.width(), weatherPanelRect.height()) * .32f);
                resizingWeather = e.getX() >= weatherPanelRect.right - grip &&
                        e.getX() <= weatherPanelRect.right + grip * .25f &&
                        e.getY() >= weatherPanelRect.bottom - grip &&
                        e.getY() <= weatherPanelRect.bottom + grip * .25f;
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE && editingDashboard && editingSection != 0) {
                RectF rect = editableRect(editingSection);
                float dx = (e.getX() - editStartX) / getWidth();
                float dy = (e.getY() - editStartY) / getHeight();
                rect.set(editStartRect);
                if (resizingSection) { rect.right += dx; rect.bottom += dy; }
                else rect.offset(dx, dy);
                clampEditedRect(rect, editingSection == 3 ? .16f : .24f, editingSection == 3 ? .04f : .12f);
                invalidate(); return true;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE && resizingWeather) {
                float w = getWidth(), h = getHeight();
                weatherWidthFraction = Math.max(.27f, Math.min(.535f, e.getX() / w - .045f));
                weatherHeightFraction = Math.max(.07f, Math.min(.17f, e.getY() / h - .265f));
                invalidate();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float w = getWidth(), h = getHeight();
                if (customizePressed && Math.hypot(e.getX() - downX, e.getY() - downY) < 40f) {
                    customizePressed = false;
                    if (editingDashboard) { editingDashboard = false; editingSection = 0; saveDashboard(); invalidate(); }
                    else showDashboardCustomizer();
                } else if (editingDashboard && editingSection != 0) {
                    saveDashboard(); editingSection = 0; resizingSection = false; invalidate();
                } else if (resizingWeather) {
                    prefs.edit().putFloat("weather_width", weatherWidthFraction)
                            .putFloat("weather_height", weatherHeightFraction).apply();
                    resizingWeather = false;
                } else if (mediaSlot.contains(e.getX(), e.getY())) {
                    float sourceX = w * (.035f + (e.getX() - mediaSlot.left) / mediaSlot.width() * .93f);
                    float sourceY = h * (.025f + (e.getY() - mediaSlot.top) / mediaSlot.height() * .22f);
                    if (!hasMediaAccess()) openMediaAccessSettings();
                    else if (sourceX >= w * .46f && sourceY >= h * .12f) {
                        if (sourceX < w * .585f) mediaPrevious();
                        else if (sourceX < w * .695f) mediaPlayPause();
                        else if (sourceX < w * .805f) mediaNext();
                        else if (sourceX < w * .91f) adjustMusicVolume(AudioManager.ADJUST_LOWER);
                        else adjustMusicVolume(AudioManager.ADJUST_RAISE);
                    }
                } else if (navigationSlot.contains(e.getX(), e.getY())) {
                    beginNavigation();
                } else if (e.getY() >= h * .25f && e.getY() <= h * .37f &&
                        e.getX() >= w * .40f && weatherAlert != null && weatherAlert.active()) {
                    showWeatherAlert();
                } else if (gaugeSlot.contains(e.getX(), e.getY())) {
                    long held = SystemClock.elapsedRealtime() - downAt;
                    if (held >= 1100 && Math.hypot(e.getX() - downX, e.getY() - downY) < 80) resetStats();
                    else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST);
                    else { metric = !metric; prefs.edit().putBoolean("metric", metric).apply(); invalidate(); }
                }
                performClick(); return true;
            }
            if (e.getAction() == MotionEvent.ACTION_CANCEL) {
                resizingWeather = false; resizingSection = false; editingSection = 0; customizePressed = false;
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
