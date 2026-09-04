package com.stankhouse.scooterspeedometer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class RiderLinkActivity extends Activity implements LocationListener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RiderLinkClient client;
    private LocationManager locations;
    private Location fix;
    private WebView map;
    private Switch sharing;
    private Spinner privacy;
    private TextView status;
    private boolean mapReady;
    private String lastSosSeen = "";
    private final Runnable publish = new Runnable() {
        @Override public void run() { if (sharing != null && sharing.isChecked() && fix != null) uploadPresence(); handler.postDelayed(this, 10000L); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); client = new RiderLinkClient(this); locations = (LocationManager) getSystemService(LOCATION_SERVICE);
        getWindow().setStatusBarColor(Color.rgb(7, 15, 20)); getWindow().setNavigationBarColor(Color.BLACK);
        if (client.signedIn()) showRiderLink(); else showAuthentication();
    }

    private TextView title(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(10), dp(12), dp(10)); return view;
    }
    private EditText input(String hint, boolean password) {
        EditText field = new EditText(this); field.setHint(hint); field.setTextColor(Color.WHITE); field.setHintTextColor(Color.rgb(125, 150, 160));
        field.setSingleLine(true); if (password) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); return field;
    }
    private Button button(String text, int color) { Button b = new Button(this); b.setText(text); b.setTextColor(Color.WHITE); b.setBackgroundColor(color); return b; }

    private void showAuthentication() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(24), dp(28), dp(24)); root.setBackgroundColor(Color.rgb(4, 10, 14));
        root.addView(title("RIDERLINK", 34, Color.rgb(0, 229, 255)));
        root.addView(title("Find riders. Ride together. Help nearby.", 15, Color.rgb(180, 202, 212)));
        EditText email = input("Email", false), password = input("Password (6+ characters)", true);
        root.addView(email, new LinearLayout.LayoutParams(-1, dp(58))); root.addView(password, new LinearLayout.LayoutParams(-1, dp(58)));
        Button signIn = button("SIGN IN", Color.rgb(0, 105, 125)); Button create = button("CREATE RIDER ACCOUNT", Color.rgb(24, 65, 76));
        root.addView(signIn, new LinearLayout.LayoutParams(-1, dp(54))); root.addView(create, new LinearLayout.LayoutParams(-1, dp(54)));
        root.addView(title("Live location is always off until you enable it.", 12, Color.rgb(115, 145, 155)));
        View.OnClickListener action = v -> {
            String mail = email.getText().toString().trim(), pass = password.getText().toString();
            if (!mail.contains("@") || pass.length() < 6) { toast("Enter a valid email and password"); return; }
            signIn.setEnabled(false); create.setEnabled(false);
            client.authenticate(mail, pass, v == create, (ok, message, body) -> {
                signIn.setEnabled(true); create.setEnabled(true); toast(message);
                if (ok && client.signedIn()) { createDefaultProfile(mail); showRiderLink(); }
            });
        };
        signIn.setOnClickListener(action); create.setOnClickListener(action); setContentView(root);
    }

    private void createDefaultProfile(String email) {
        String name = email.substring(0, email.indexOf('@')).replaceAll("[^A-Za-z0-9_]", "");
        if (name.length() < 3) name = "Rider" + System.currentTimeMillis() % 10000;
        client.saveProfile(name, "Scooter", "invisible", (ok, message, body) -> { });
    }

    private void showRiderLink() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(4, 10, 14));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(10), dp(4), dp(10), dp(4));
        TextView logo = title("RIDERLINK", 22, Color.rgb(0, 229, 255)); header.addView(logo, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button profileButton = button("PROFILE", Color.rgb(20, 54, 64)); header.addView(profileButton, new LinearLayout.LayoutParams(dp(105), dp(46))); root.addView(header);
        status = title("Location sharing OFF", 13, Color.rgb(255, 176, 40)); root.addView(status, new LinearLayout.LayoutParams(-1, dp(42)));
        map = new WebView(this); WebSettings settings = map.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        map.setBackgroundColor(Color.rgb(5, 10, 13)); map.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        map.setWebViewClient(new android.webkit.WebViewClient() { @Override public void onPageFinished(WebView view, String url) { mapReady = true; refreshNearby(); } });
        map.loadUrl("file:///android_asset/riderlink_map.html"); root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(this); controls.setPadding(dp(7), dp(5), dp(7), dp(5)); controls.setGravity(Gravity.CENTER);
        sharing = new Switch(this); sharing.setText("LIVE"); sharing.setTextColor(Color.WHITE); sharing.setGravity(Gravity.CENTER);
        controls.addView(sharing, new LinearLayout.LayoutParams(0, dp(58), .8f));
        privacy = new Spinner(this); String[] modes = {"Nearby riders", "Invisible"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes); privacy.setAdapter(adapter);
        controls.addView(privacy, new LinearLayout.LayoutParams(0, dp(58), 1.25f));
        Button refresh = button("REFRESH", Color.rgb(0, 95, 115)); controls.addView(refresh, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button sos = button("SOS", Color.rgb(180, 22, 22)); controls.addView(sos, new LinearLayout.LayoutParams(0, dp(54), .75f)); root.addView(controls);
        root.addView(title("RiderLink SOS does not contact 911. Call emergency services when needed.", 10, Color.rgb(150, 160, 164)), new LinearLayout.LayoutParams(-1, dp(34)));
        setContentView(root);
        sharing.setOnCheckedChangeListener((button, checked) -> { if (checked) uploadPresence(); else { client.goInvisible((ok,m,b)->{}); status.setText("Location sharing OFF"); } });
        refresh.setOnClickListener(v -> refreshNearby()); sos.setOnClickListener(v -> beginSosCountdown()); profileButton.setOnClickListener(v -> showProfileDialog());
        startLocation(); handler.removeCallbacks(publish); handler.post(publish);
    }

    private void showProfileDialog() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(20), 0, dp(20), 0);
        panel.setBackgroundColor(Color.rgb(8, 14, 18));
        EditText username = input("Rider name", false), scooter = input("Scooter / bike", false); panel.addView(username); panel.addView(scooter);
        new AlertDialog.Builder(this).setTitle("Rider profile").setView(panel).setPositiveButton("Save", (d,w) -> {
            String name = username.getText().toString().trim(); if (name.length() < 3) { toast("Rider name must be at least 3 characters"); return; }
            client.saveProfile(name, scooter.getText().toString().trim(), sharing != null && sharing.isChecked() ? "nearby" : "invisible", (ok,m,b) -> toast(ok ? "Profile saved" : m));
        }).setNeutralButton("Sign out", (d,w) -> {
            if (sharing != null && sharing.isChecked()) client.goInvisible((ok,m,b) -> { client.signOut(); showAuthentication(); });
            else { client.signOut(); showAuthentication(); }
        })
                .setNegativeButton("Cancel", null).show();
    }

    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 77); return; }
        try { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 2f, this); Location saved = locations.getLastKnownLocation(LocationManager.GPS_PROVIDER); if (saved != null) onLocationChanged(saved); }
        catch (SecurityException ignored) { }
    }
    private void uploadPresence() {
        if (fix == null) { status.setText("Waiting for GPS before sharing…"); return; }
        String mode = privacy.getSelectedItemPosition() == 0 ? "nearby" : "invisible";
        if ("invisible".equals(mode)) { sharing.setChecked(false); return; }
        client.updatePresence(fix.getLatitude(), fix.getLongitude(), fix.getAccuracy(), mode, (ok,m,b) -> status.setText(ok ? "LIVE • visible to nearby riders" : m));
    }
    private void refreshNearby() {
        if (fix == null || !mapReady) return;
        client.nearby(fix.getLatitude(), fix.getLongitude(), (ok, message, body) -> {
            if (!ok) { status.setText(message); return; }
            String safe = body.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "");
            map.evaluateJavascript("setRiders('" + safe + "'," + fix.getLatitude() + "," + fix.getLongitude() + ")", null);
        });
        client.nearbySos(fix.getLatitude(), fix.getLongitude(), (ok, message, body) -> {
            if (!ok) return;
            String safe = body.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "");
            map.evaluateJavascript("setSos('" + safe + "')", null);
            try {
                org.json.JSONArray alerts = new org.json.JSONArray(body);
                if (alerts.length() > 0) {
                    org.json.JSONObject alert = alerts.getJSONObject(0); String id = alert.optString("id");
                    if (!id.equals(lastSosSeen)) { lastSosSeen = id; status.setText("SOS NEARBY • " + alert.optString("username", "Rider") + " needs assistance"); }
                }
            } catch (Exception ignored) { }
        });
    }

    private void beginSosCountdown() {
        if (fix == null) { toast("GPS fix required for SOS"); return; }
        TextView countdown = title("Sending RiderLink SOS in 5…\nThis does not contact 911.", 18, Color.rgb(180, 20, 20));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Emergency rider alert").setView(countdown).setNegativeButton("CANCEL", null).create();
        final int[] left = {5}; Runnable timer = new Runnable() { @Override public void run() {
            if (!dialog.isShowing()) return; left[0]--;
            if (left[0] <= 0) { dialog.dismiss(); sendSos(); }
            else { countdown.setText("Sending RiderLink SOS in " + left[0] + "…\nThis does not contact 911."); handler.postDelayed(this, 1000L); }
        }};
        dialog.setOnShowListener(d -> handler.postDelayed(timer, 1000L)); dialog.show();
    }
    private void sendSos() {
        sharing.setChecked(true); client.sendSos(fix.getLatitude(), fix.getLongitude(), "Rider needs assistance", (ok,m,b) -> {
            if (!ok) { toast(m); return; }
            status.setText("SOS ACTIVE • location is being shared");
            new AlertDialog.Builder(this).setTitle("RiderLink SOS active").setMessage("Nearby RiderLink users can see the alert and your location. Call 911 separately for an emergency.")
                    .setPositiveButton("CANCEL SOS", (d,w) -> client.cancelSos((done,msg,data) -> { status.setText("SOS cancelled"); toast(done ? "SOS cancelled" : msg); }))
                    .setNegativeButton("Keep active", null).show();
        });
    }

    @Override public void onLocationChanged(Location location) { fix = location; if (mapReady) { map.evaluateJavascript("setMe(" + location.getLatitude() + "," + location.getLongitude() + ")", null); refreshNearby(); } }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) { super.onRequestPermissionsResult(requestCode, permissions, results); if (requestCode == 77 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startLocation(); }
    @Override protected void onPause() { super.onPause(); if (sharing != null && sharing.isChecked()) status.setText("Sharing pauses when RiderLink closes"); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); try { locations.removeUpdates(this); } catch (SecurityException ignored) { } if (client != null) client.shutdown(); if (map != null) map.destroy(); super.onDestroy(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
