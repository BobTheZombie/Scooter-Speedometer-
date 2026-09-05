package com.stankhouse.scooterspeedometer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.Intent;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private Button privacyButton;
    private TextView status;
    private boolean mapReady;
    private String lastSosSeen = "";
    private final Runnable publish = new Runnable() {
        @Override public void run() { if (fix != null) refreshNearby(); handler.postDelayed(this, 10000L); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); client = new RiderLinkClient(this); locations = (LocationManager) getSystemService(LOCATION_SERVICE);
        Fullscreen.apply(this);
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
        root.setPadding(0, 0, 0, 0);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(10), dp(4), dp(10), dp(4));
        TextView logo = title("RIDERLINK", 22, Color.rgb(0, 229, 255)); logo.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL); header.addView(logo, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button socialButton = button("SOCIAL", Color.rgb(0, 91, 112)); header.addView(socialButton, new LinearLayout.LayoutParams(dp(92), dp(46)));
        Button profileButton = button("PROFILE", Color.rgb(20, 54, 64)); header.addView(profileButton, new LinearLayout.LayoutParams(dp(92), dp(46))); root.addView(header);
        status = title("Location sharing OFF", 13, Color.rgb(255, 176, 40)); root.addView(status, new LinearLayout.LayoutParams(-1, dp(42)));
        map = new WebView(this); map.setLayerType(View.LAYER_TYPE_SOFTWARE, null); WebSettings settings = map.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true); settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        map.setBackgroundColor(Color.rgb(5, 10, 13)); map.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        map.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { mapReady = true; refreshNearby(); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if(url!=null && url.startsWith("geo:")){try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(Exception e){toast("No navigation app found");}return true;}return false;
            }
        });
        map.loadUrl("file:///android_asset/riderlink_map.html"); root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(this); controls.setPadding(dp(7), dp(5), dp(7), dp(5)); controls.setGravity(Gravity.CENTER);
        TextView liveLabel=title("LIVE",16,Color.WHITE);controls.addView(liveLabel,new LinearLayout.LayoutParams(dp(56),dp(58)));
        sharing = new Switch(this); sharing.setShowText(false); sharing.setGravity(Gravity.CENTER); sharing.setChecked(getSharedPreferences("riderlink_session",MODE_PRIVATE).getBoolean("live",false));
        controls.addView(sharing, new LinearLayout.LayoutParams(dp(64), dp(58)));
        privacyButton=button("NEARBY",Color.rgb(18,46,55));controls.addView(privacyButton,new LinearLayout.LayoutParams(0,dp(54),.85f));
        Button refresh = button("REFRESH", Color.rgb(0, 95, 115)); controls.addView(refresh, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button sos = button("SOS", Color.rgb(180, 22, 22)); controls.addView(sos, new LinearLayout.LayoutParams(0, dp(54), .75f)); root.addView(controls);
        root.addView(title("RiderLink SOS does not contact 911. Call emergency services when needed.", 10, Color.rgb(150, 160, 164)), new LinearLayout.LayoutParams(-1, dp(34)));
        setContentView(root);
        sharing.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("riderlink_session",MODE_PRIVATE).edit().putBoolean("live",checked).apply();
            if(checked){uploadPresence();Intent service=new Intent(this,RiderLinkPresenceService.class);if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);}
            else { stopService(new Intent(this,RiderLinkPresenceService.class)); client.goInvisible((ok,m,b)->{}); status.setText("Location sharing OFF"); }
        });
        refresh.setOnClickListener(v -> refreshNearby()); sos.setOnClickListener(v -> beginSosCountdown()); profileButton.setOnClickListener(v -> showProfileDialog());
        socialButton.setOnClickListener(v->startActivity(new Intent(this,RiderLinkSocialActivity.class)));
        privacyButton.setOnClickListener(v->toast("LIVE shares an approximate public position. Turn LIVE off to become invisible."));
        startLocation(); handler.removeCallbacks(publish); handler.post(publish);
    }

    private void showProfileDialog() {
        ScrollView scroll=new ScrollView(this);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(8),dp(18),dp(16));panel.setBackgroundColor(Color.rgb(8,14,18));scroll.addView(panel);
        RiderAvatarView avatar=new RiderAvatarView(this);RiderAvatarView.AvatarSpec spec=new RiderAvatarView.AvatarSpec();avatar.setSpec(spec);panel.addView(avatar,new LinearLayout.LayoutParams(-1,dp(265)));
        EditText username=input("Rider name",false);panel.addView(username,new LinearLayout.LayoutParams(-1,dp(56)));
        panel.addView(title("BUILD YOUR RIDER",13,Color.rgb(0,229,255)));
        Spinner skin=profileSpinner("Skin tone",new String[]{"Porcelain","Light","Warm","Tan","Brown","Deep brown","Ebony","Peach"},panel);
        Spinner face=profileSpinner("Face",new String[]{"Classic","Smile","Focused","Serious","Glasses","Beard"},panel);
        Spinner hair=profileSpinner("Hair style",new String[]{"Bald","Close cut","Short","Curls","Side sweep","Spikes","Full curls","Top knot","Locs","Long"},panel);
        Spinner hairColor=profileSpinner("Hair color",new String[]{"Black","Brown","Auburn","Blonde","Red","Purple","Teal","Silver","Pink","Blue-black"},panel);
        Spinner outfit=profileSpinner("Clothing color",new String[]{"Black","Blue","Red","Green","Amber","Purple","Pink","White","Brown","Cyan"},panel);
        Spinner outfitStyle=profileSpinner("Clothing style",new String[]{"Riding jacket","Zipped jacket","Armored jacket"},panel);
        Spinner helmet=profileSpinner("Helmet",new String[]{"No helmet","Red","Blue","Green","Amber","Purple","White","Black","Orange"},panel);
        panel.addView(title("SCOOTER GARAGE",13,Color.rgb(0,229,255)));
        String[] makes={"HHH","Honda","Yamaha","TaoTao","IceBear","Vespa","Kymco","Genuine","Piaggio","SYM","Lance","Wolf","Other / custom"};
        Spinner make=profileSpinner("Scooter make",makes,panel);EditText model=input("Model / year",false);panel.addView(model,new LinearLayout.LayoutParams(-1,dp(56)));
        Spinner scooterStyle=profileSpinner("Scooter body",new String[]{"Sport scooter","Touring scooter","Classic moped","Maxi scooter","Mini bike","Custom"},panel);
        Spinner scooterColor=profileSpinner("Scooter color",new String[]{"Red","Blue","Green","Amber","Purple","White","Black","Orange","Pink","Silver"},panel);
        String[] upgradeNames={"Big-bore kit","Performance carb / EFI","Variator","Clutch / springs","Performance exhaust","CDI / ignition","Oil cooler","Suspension","Brake upgrade","Lighting","Audio system","Cargo / delivery setup"};
        CheckBox[] upgrades=new CheckBox[upgradeNames.length];for(int i=0;i<upgradeNames.length;i++){upgrades[i]=new CheckBox(this);upgrades[i].setText(upgradeNames[i]);upgrades[i].setTextColor(Color.WHITE);upgrades[i].setMinHeight(dp(44));panel.addView(upgrades[i]);}
        EditText custom=input("Other upgrades",false);panel.addView(custom,new LinearLayout.LayoutParams(-1,dp(56)));
        Pick[] picks={i->spec.skin=i,i->spec.face=i,i->spec.hairStyle=i,i->spec.hairColor=i,i->spec.outfit=i,i->spec.outfitStyle=i,i->spec.helmet=i,i->spec.scooterStyle=i,i->spec.scooterColor=i};
        Spinner[] avatarSpinners={skin,face,hair,hairColor,outfit,outfitStyle,helmet,scooterStyle,scooterColor};for(int i=0;i<avatarSpinners.length;i++)bind(avatarSpinners[i],picks[i],avatar);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Rider avatar & garage").setView(scroll).setPositiveButton("SAVE PROFILE",(d,w)->{
            String name=username.getText().toString().trim();if(name.length()<3){toast("Rider name must be at least 3 characters");return;}
            StringBuilder mods=new StringBuilder();for(int i=0;i<upgrades.length;i++)if(upgrades[i].isChecked()){if(mods.length()>0)mods.append(", ");mods.append(upgradeNames[i]);}if(!custom.getText().toString().trim().isEmpty()){if(mods.length()>0)mods.append(", ");mods.append(custom.getText().toString().trim());}
            client.saveExtendedProfile(name,sharing!=null&&sharing.isChecked()?"nearby":"invisible",makes[make.getSelectedItemPosition()],model.getText().toString().trim(),mods.toString(),spec.toJson(),(ok,m,b)->toast(ok?"Avatar and scooter saved":m+" • Run the v4 RiderLink SQL migration"));
        }).setNeutralButton("Sign out", (d,w) -> {
            if (sharing != null && sharing.isChecked()) client.goInvisible((ok,m,b) -> { client.signOut(); showAuthentication(); });
            else { client.signOut(); showAuthentication(); }
        }).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->client.getProfile((ok,m,body)->{if(!ok)return;try{org.json.JSONArray list=new org.json.JSONArray(body);if(list.length()>0){org.json.JSONObject p=list.getJSONObject(0);username.setText(p.optString("username"));model.setText(p.optString("scooter_model"));select(make,makes,p.optString("scooter_make","Other / custom"));RiderAvatarView.AvatarSpec loaded=RiderAvatarView.AvatarSpec.fromJson(p.optJSONObject("avatar_config")==null?"{}":p.optJSONObject("avatar_config").toString());spec.skin=loaded.skin;spec.face=loaded.face;spec.hairStyle=loaded.hairStyle;spec.hairColor=loaded.hairColor;spec.outfit=loaded.outfit;spec.outfitStyle=loaded.outfitStyle;spec.helmet=loaded.helmet;spec.scooterStyle=loaded.scooterStyle;spec.scooterColor=loaded.scooterColor;int[] vals={spec.skin,spec.face,spec.hairStyle,spec.hairColor,spec.outfit,spec.outfitStyle,spec.helmet,spec.scooterStyle,spec.scooterColor};for(int i=0;i<avatarSpinners.length;i++)avatarSpinners[i].setSelection(vals[i]);String saved=p.optString("scooter_upgrades","");for(int i=0;i<upgrades.length;i++)upgrades[i].setChecked(saved.toLowerCase().contains(upgradeNames[i].toLowerCase()));avatar.setSpec(spec);}}catch(Exception ignored){}}));dialog.show();
    }

    private interface Pick{void set(int value);}
    private void bind(Spinner spinner,Pick pick,RiderAvatarView avatar){spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int i,long id){pick.set(i);avatar.invalidate();}public void onNothingSelected(android.widget.AdapterView<?> p){}});}
    private Spinner profileSpinner(String label,String[] values,LinearLayout panel){TextView heading=title(label.toUpperCase(),11,Color.rgb(145,175,185));heading.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);panel.addView(heading,new LinearLayout.LayoutParams(-1,dp(32)));Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);spinner.setAdapter(adapter);spinner.setBackgroundColor(Color.rgb(20,39,46));panel.addView(spinner,new LinearLayout.LayoutParams(-1,dp(52)));return spinner;}
    private void select(Spinner spinner,String[] values,String wanted){for(int i=0;i<values.length;i++)if(values[i].equalsIgnoreCase(wanted)){spinner.setSelection(i);return;}spinner.setSelection(values.length-1);}

    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 77); return; }
        try { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 2f, this); Location saved = locations.getLastKnownLocation(LocationManager.GPS_PROVIDER); if (saved != null) onLocationChanged(saved); }
        catch (SecurityException ignored) { }
    }
    private void uploadPresence() {
        if (fix == null) { status.setText("Waiting for GPS before sharing…"); return; }
        client.updatePresence(fix.getLatitude(), fix.getLongitude(), fix.getAccuracy(), "nearby", (ok,m,b) -> status.setText(ok ? "LIVE • visible to nearby riders" : m));
    }
    private void refreshNearby() {
        if (fix == null || !mapReady) return;
        client.nearby(fix.getLatitude(), fix.getLongitude(), (ok, message, body) -> {
            if (!ok) { status.setText(message); return; }
            try { int count=new org.json.JSONArray(body).length(); status.setText((sharing!=null&&sharing.isChecked()?"LIVE • ":"")+count+" nearby rider"+(count==1?"":"s")); } catch(Exception ignored){}
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
        client.nearbyCommunityHazards(fix.getLatitude(),fix.getLongitude(),(ok,message,body)->{if(!ok)return;String safe=body.replace("\\","\\\\").replace("'","\\'").replace("\n","");map.evaluateJavascript("setCommunityHazards('"+safe+"')",null);});
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
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) Fullscreen.apply(this); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); try { locations.removeUpdates(this); } catch (SecurityException ignored) { } if (client != null) client.shutdown(); if (map != null) map.destroy(); super.onDestroy(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int systemBarHeight(String name){int id=getResources().getIdentifier(name,"dimen","android");return id>0?getResources().getDimensionPixelSize(id):0;}
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
