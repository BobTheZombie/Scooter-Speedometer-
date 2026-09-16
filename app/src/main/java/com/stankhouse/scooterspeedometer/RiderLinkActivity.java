package com.stankhouse.scooterspeedometer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
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
import android.speech.tts.TextToSpeech;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

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
    private FlockCameraProvider flockCameras;
    private long lastFlockRefresh;
    private boolean openProfileRequested;
    private PoliceSightingProvider policeProvider;
    private long lastPoliceRefresh,lastPoliceWarning;
    private final List<PolicePoint> policePoints=new ArrayList<>();
    private TextToSpeech speech;
    private boolean speechReady;
    private final Map<Long,Long> policeWarningTimes=new HashMap<>();
    private final Runnable publish = new Runnable() {
        @Override public void run() { if (fix != null) refreshNearby(); handler.postDelayed(this, 10000L); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); client = new RiderLinkClient(this); flockCameras=new FlockCameraProvider(this);policeProvider=new PoliceSightingProvider();speech=new TextToSpeech(this,s->{speechReady=s==TextToSpeech.SUCCESS;if(speechReady){speech.setLanguage(Locale.US);VoiceSettings.apply(this,speech);}}); locations = (LocationManager) getSystemService(LOCATION_SERVICE);openProfileRequested=getIntent().getBooleanExtra("open_profile",false);
        Fullscreen.apply(this);
        if (!handleOAuthIntent(getIntent())) { if (client.signedIn()) showRiderLink(); else showAuthentication(); }
    }

    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleOAuthIntent(intent);}
    private boolean handleOAuthIntent(Intent intent){
        Uri data=intent==null?null:intent.getData();if(data==null||!"riderlink".equals(data.getScheme())||!"auth-callback".equals(data.getHost()))return false;
        client.completeOAuth(data,(ok,message,body)->{toast(message);if(ok&&client.signedIn()){String mail=client.email();createDefaultProfile(mail.contains("@")?mail:"Rider"+System.currentTimeMillis()%10000);showRiderLink();}else showAuthentication();});return true;
    }

    private TextView title(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(10), dp(12), dp(10)); return view;
    }
    private EditText input(String hint, boolean password) {
        EditText field = new EditText(this); field.setHint(hint); field.setTextColor(Color.WHITE); field.setHintTextColor(Color.rgb(125, 150, 160));
        UiKit.field(field); if (password) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); return field;
    }
    private Button button(String text, int color) { Button b = new Button(this); b.setText(text); UiKit.button(b,color); return b; }

    private void showAuthentication() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(24), dp(28), dp(24)); root.setBackground(UiKit.screenBackground(this));
        root.addView(UiKit.header(this,"◉","RiderLink","SOCIAL • GROUP RIDES • SOS"),new LinearLayout.LayoutParams(-1,dp(82)));
        EditText email = input("Email", false), password = input("Password (6+ characters)", true);
        LinearLayout.LayoutParams fieldLayout=new LinearLayout.LayoutParams(-1,dp(58));fieldLayout.setMargins(0,0,0,dp(8));root.addView(email,fieldLayout);LinearLayout.LayoutParams passwordLayout=new LinearLayout.LayoutParams(-1,dp(58));passwordLayout.setMargins(0,0,0,dp(8));root.addView(password,passwordLayout);
        Button signIn = button("SIGN IN", Color.rgb(0, 105, 125)); Button create = button("CREATE RIDER ACCOUNT", Color.rgb(24, 65, 76));
        LinearLayout.LayoutParams signInLayout=new LinearLayout.LayoutParams(-1,dp(54));signInLayout.setMargins(0,0,0,dp(8));root.addView(signIn,signInLayout);root.addView(create,new LinearLayout.LayoutParams(-1,dp(54)));
        TextView divider=title("OR CONTINUE WITH",11,Color.rgb(125,150,160));root.addView(divider,new LinearLayout.LayoutParams(-1,dp(38)));
        LinearLayout providers=new LinearLayout(this);providers.setGravity(Gravity.CENTER);Button google=button("GOOGLE",Color.rgb(33,66,78)),facebook=button("FACEBOOK",Color.rgb(38,78,145));google.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_google_g,0,0,0);facebook.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_facebook_f,0,0,0);google.setCompoundDrawablePadding(dp(9));facebook.setCompoundDrawablePadding(dp(9));LinearLayout.LayoutParams providerButton=new LinearLayout.LayoutParams(0,dp(54),1);providers.addView(google,providerButton);android.widget.Space providerGap=new android.widget.Space(this);providers.addView(providerGap,new LinearLayout.LayoutParams(dp(8),1));providers.addView(facebook,new LinearLayout.LayoutParams(0,dp(54),1));root.addView(providers,new LinearLayout.LayoutParams(-1,dp(62)));
        root.addView(title("Live location is always off until you enable it.", 12, Color.rgb(115, 145, 155)));
        View.OnClickListener oauth=v->{String provider=v==google?"google":"facebook",url=client.oauthUrl(provider);if(url.isEmpty()){toast("Could not start secure sign-in");return;}try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){toast("No browser is available for sign-in");}};google.setOnClickListener(oauth);facebook.setOnClickListener(oauth);
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
        int at=email==null?-1:email.indexOf('@');String seed=at>0?email.substring(0,at):(email==null?"":email);
        String name = seed.replaceAll("[^A-Za-z0-9_]", "");
        if (name.length() < 3) name = "Rider" + System.currentTimeMillis() % 10000;
        client.saveProfile(name, "Scooter", "invisible", (ok, message, body) -> { });
    }

    private void showRiderLink() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackground(UiKit.screenBackground(this));
        root.setPadding(0, 0, 0, 0);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(10), dp(4), dp(10), dp(4));
        header.addView(UiKit.header(this,"◉",client.plusActive()?"RiderLink+":"RiderLink",client.membershipAdmin()?"DEVELOPER • UNLIMITED":"RIDER NETWORK"),new LinearLayout.LayoutParams(0,dp(68),1));
        Button socialButton = UiKit.iconButton(this,"♟","Social",UiKit.SURFACE_ACTIVE);socialButton.setTextSize(11);socialButton.setPadding(dp(5),0,dp(5),0);header.addView(socialButton,new LinearLayout.LayoutParams(dp(80),dp(44)));
        Button profileButton = UiKit.iconButton(this,"●","Me",UiKit.SURFACE_HIGH);profileButton.setTextSize(11);profileButton.setPadding(dp(4),0,dp(4),0);LinearLayout.LayoutParams profileLayout=new LinearLayout.LayoutParams(dp(62),dp(44));profileLayout.setMargins(dp(6),0,0,0);header.addView(profileButton,profileLayout);root.addView(header);
        status = title("Location sharing OFF", 13, Color.rgb(255, 176, 40)); root.addView(status, new LinearLayout.LayoutParams(-1, dp(42)));
        map = new WebView(this); map.setLayerType(View.LAYER_TYPE_HARDWARE, null); WebSettings settings = map.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
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
        TextView liveLabel=title("LIVE",12,Color.WHITE);liveLabel.setSingleLine(true);liveLabel.setPadding(0,0,0,0);controls.addView(liveLabel,new LinearLayout.LayoutParams(dp(42),dp(58)));
        sharing = new Switch(this); sharing.setShowText(false); sharing.setGravity(Gravity.CENTER); sharing.setChecked(getSharedPreferences("riderlink_session",MODE_PRIVATE).getBoolean("live",false));
        controls.addView(sharing,new LinearLayout.LayoutParams(dp(46),dp(58)));
        privacyButton=compactButton("◉ BEACON",UiKit.SURFACE_HIGH,9);LinearLayout.LayoutParams actionLayout=new LinearLayout.LayoutParams(0,dp(54),1f);actionLayout.setMargins(dp(4),0,0,0);controls.addView(privacyButton,actionLayout);
        Button refresh=compactButton("↻ REFRESH",Color.rgb(0,95,115),9);LinearLayout.LayoutParams refreshLayout=new LinearLayout.LayoutParams(0,dp(54),1.08f);refreshLayout.setMargins(dp(4),0,0,0);controls.addView(refresh,refreshLayout);
        Button sos=compactButton("! SOS",UiKit.RED,10);LinearLayout.LayoutParams sosLayout=new LinearLayout.LayoutParams(0,dp(54),.72f);sosLayout.setMargins(dp(4),0,0,0);controls.addView(sos,sosLayout);root.addView(controls);
        root.addView(title("RiderLink SOS does not contact 911. Call emergency services when needed.", 10, Color.rgb(150, 160, 164)), new LinearLayout.LayoutParams(-1, dp(34)));
        setContentView(root);
        client.membership((ok,m,b)->{if(ok&&client.plusActive())status.setText(client.membershipAdmin()?"RIDERLINK+ DEVELOPER ACCESS":"RIDERLINK+ ACTIVE");});
        sharing.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("riderlink_session",MODE_PRIVATE).edit().putBoolean("live",checked).apply();
            if(checked){uploadPresence();Intent service=new Intent(this,RiderLinkPresenceService.class);if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);}
            else { stopService(new Intent(this,RiderLinkPresenceService.class)); client.goInvisible((ok,m,b)->{}); status.setText("Location sharing OFF"); }
        });
        refresh.setOnClickListener(v -> refreshNearby()); sos.setOnClickListener(v -> beginSosCountdown()); profileButton.setOnClickListener(v -> showProfileDialog());
        socialButton.setOnClickListener(v->startActivity(new Intent(this,RiderLinkSocialActivity.class)));
        privacyButton.setOnClickListener(v->showBeaconDialog());
        startLocation(); handler.removeCallbacks(publish); handler.post(publish);
        if(openProfileRequested){openProfileRequested=false;root.postDelayed(this::showProfileDialog,180L);}
    }

    private void showBeaconDialog(){
        if(fix==null){toast("Wait for GPS lock before posting a Ride Beacon");return;}
        String[] labels={"🛵 Cruising now","⚡ Down to ride","⌖ Meet up","🔧 Need mechanical help","Turn my beacon off"};
        String[] kinds={"cruising","ride","meetup","mechanical","off"};
        new AlertDialog.Builder(this).setTitle("Ride Beacon").setMessage("Visible to nearby signed-in riders for up to 2 hours. Your exact home address is never shown as a label.")
                .setItems(labels,(d,w)->{if(w==4){client.clearRideBeacon((ok,m,b)->{toast(ok?"Ride Beacon turned off":m);refreshNearby();});return;}EditText note=input("Optional note (meeting spot, route, or problem)",false);note.setSingleLine(false);new AlertDialog.Builder(this).setTitle(labels[w]).setView(note).setPositiveButton("GO LIVE",(x,y)->client.setRideBeacon(kinds[w],note.getText().toString().trim(),fix.getLatitude(),fix.getLongitude(),(ok,m,b)->{toast(ok?"Ride Beacon is live":m);refreshNearby();})).setNegativeButton("Cancel",null).show();}).setNegativeButton("Cancel",null).show();
    }

    private void showProfileDialog() {
        ScrollView scroll=new ScrollView(this);LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(8),dp(18),dp(16));panel.setBackgroundColor(Color.rgb(8,14,18));scroll.addView(panel);
        RiderAvatarView avatar=new RiderAvatarView(this);RiderAvatarView.AvatarSpec spec=new RiderAvatarView.AvatarSpec();avatar.setSpec(spec);panel.addView(avatar,new LinearLayout.LayoutParams(-1,dp(265)));
        Button membership=button(client.plusActive()?"★ RIDERLINK+ MEMBERSHIP":"UNLOCK RIDERLINK+",Color.rgb(92,48,150));panel.addView(membership,new LinearLayout.LayoutParams(-1,dp(54)));membership.setOnClickListener(v->showMembershipDialog());
        Button create3d=button("CREATE / EDIT REAL 3D AVATAR",Color.rgb(0,112,135));panel.addView(create3d,new LinearLayout.LayoutParams(-1,dp(56)));create3d.setOnClickListener(v->startActivity(new Intent(this,AvaturnActivity.class)));
        panel.addView(title("LEGACY 2D FALLBACK",11,Color.rgb(125,145,155)));
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

    private void showMembershipDialog(){client.membership((ok,message,body)->{if(!ok){toast(message+" • Run the v8.6 membership SQL migration");return;}String tier="free",expiry="",summary="RiderLink Free";boolean plus=false,price=false,admin=false;try{JSONArray a=new JSONArray(body);if(a.length()>0){JSONObject m=a.getJSONObject(0);tier=m.optString("tier","free");plus=m.optBoolean("plus_access");price=m.optBoolean("founder_price_eligible");admin=m.optBoolean("is_admin");expiry=m.optString("expires_at","");}}catch(Exception ignored){}if(admin)summary="Developer access • Unlimited";else if(plus)summary=tier.replace('_',' ').toUpperCase(Locale.US)+(expiry.isEmpty()||"null".equals(expiry)?" • Lifetime":" • Active");else if(price)summary="Founder-price eligibility secured";AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle("RiderLink+ Membership").setMessage(summary+"\n\nMemberships are authenticated by RiderLink servers and locked to your signed-in account.").setPositiveButton("REDEEM CODE",(d,w)->showRedeemCode());if(admin)b.setNeutralButton("FOUNDER CODE MANAGER",(d,w)->showFounderCodeManager());b.setNegativeButton("Done",null).show();});}
    private void showRedeemCode(){EditText code=input("RLP-XXXXXX-XXXXXX",false);code.setAllCaps(true);new AlertDialog.Builder(this).setTitle("Redeem RiderLink+ code").setView(code).setPositiveButton("UNLOCK",(d,w)->client.redeemMembershipCode(code.getText().toString().trim(),(ok,m,body)->{toast(ok?"RiderLink+ membership unlocked":m);if(ok)showRiderLink();})).setNegativeButton("Cancel",null).show();}
    private void showFounderCodeManager(){EditText name=input("Recipient name",false);String[] labels={"Free lifetime RiderLink+","Founder-price eligibility","Complimentary 30 days","Complimentary 90 days"};String[] rewards={"founder_lifetime","founder_price","complimentary","complimentary"};new AlertDialog.Builder(this).setTitle("Founder Code Manager").setView(name).setItems(labels,(d,w)->{String recipient=name.getText().toString().trim();if(recipient.isEmpty()){toast("Enter the recipient name first");return;}int days=w==3?90:30;client.createMembershipCode(recipient,rewards[w],days,(ok,m,body)->{if(!ok){toast(m);return;}String code=body.trim();if(code.startsWith("\"")&&code.endsWith("\""))code=code.substring(1,code.length()-1);final String generated=code;((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("RiderLink+ code",generated));new AlertDialog.Builder(this).setTitle("Founder code created").setMessage(recipient+"\n\n"+generated+"\n\nCopied to clipboard. This code expires in 30 days and can be redeemed once.").setPositiveButton("DONE",null).show();});}).setNegativeButton("Cancel",null).show();}

    private interface Pick{void set(int value);}
    private void bind(Spinner spinner,Pick pick,RiderAvatarView avatar){spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int i,long id){pick.set(i);avatar.invalidate();}public void onNothingSelected(android.widget.AdapterView<?> p){}});}
    private Spinner profileSpinner(String label,String[] values,LinearLayout panel){TextView heading=title(label.toUpperCase(),11,Color.rgb(145,175,185));heading.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);panel.addView(heading,new LinearLayout.LayoutParams(-1,dp(32)));Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);spinner.setAdapter(adapter);spinner.setPadding(dp(12),0,dp(12),0);spinner.setBackground(UiKit.rounded(this,Color.rgb(20,39,46),14,Color.rgb(40,72,82)));panel.addView(spinner,new LinearLayout.LayoutParams(-1,dp(52)));return spinner;}
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
        client.nearbyRideBeacons(fix.getLatitude(),fix.getLongitude(),(ok,message,body)->{if(!ok)return;String safe=body.replace("\\","\\\\").replace("'","\\'").replace("\n","");map.evaluateJavascript("setRideBeacons('"+safe+"')",null);});
        client.nearbyCommunityHazards(fix.getLatitude(),fix.getLongitude(),(ok,message,body)->{if(!ok)return;String safe=body.replace("\\","\\\\").replace("'","\\'").replace("\n","");map.evaluateJavascript("setCommunityHazards('"+safe+"')",null);});
        if(System.currentTimeMillis()-lastFlockRefresh>15L*60L*1000L){lastFlockRefresh=System.currentTimeMillis();flockCameras.nearby(fix.getLatitude(),fix.getLongitude(),(ok,body,message)->{if(!ok){lastFlockRefresh=0;status.setText("ALPR feed unavailable • tap REFRESH to retry");if(mapReady)map.evaluateJavascript("document.getElementById('flockToggle').textContent='ALPR RETRY'",null);return;}String safe=body.replace("\\","\\\\").replace("'","\\'").replace("\n","");if(mapReady)map.evaluateJavascript("setFlockHopperCameras('"+safe+"')",null);});}
        if(System.currentTimeMillis()-lastPoliceRefresh>30L*1000L){lastPoliceRefresh=System.currentTimeMillis();policeProvider.nearby(fix.getLatitude(),fix.getLongitude(),(ok,body,message)->{if(!ok){lastPoliceRefresh=0;return;}policePoints.clear();try{JSONArray list=new JSONArray(body);for(int i=0;i<list.length();i++){JSONObject p=list.getJSONObject(i);policePoints.add(new PolicePoint(p.optLong("id"),p.getDouble("latitude"),p.getDouble("longitude")));}}catch(Exception ignored){}String safe=body.replace("\\","\\\\").replace("'","\\'").replace("\n","");if(mapReady)map.evaluateJavascript("setPoliceSightings('"+safe+"')",null);checkPoliceAhead();});}
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

    @Override public void onLocationChanged(Location location) { fix = location;checkPoliceAhead(); if (mapReady) { map.evaluateJavascript("setMe(" + location.getLatitude() + "," + location.getLongitude() + ")", null); refreshNearby(); } }
    private void checkPoliceAhead(){long now=System.currentTimeMillis();if(!speechReady||fix==null||fix.getSpeed()<1.5f||now-lastPoliceWarning<30000L)return;float heading=fix.hasBearing()?fix.getBearing():0;for(PolicePoint p:policePoints){float[] d=new float[2];Location.distanceBetween(fix.getLatitude(),fix.getLongitude(),p.lat,p.lon,d);float delta=Math.abs(((d[1]-heading+540f)%360f)-180f);Long warned=policeWarningTimes.get(p.id);if(d[0]<=800f&&delta<=60f&&(warned==null||now-warned>30L*60L*1000L)){lastPoliceWarning=now;policeWarningTimes.put(p.id,now);speech.speak("Police vehicle ahead",TextToSpeech.QUEUE_ADD,null,"riderlink_police_"+p.id);return;}}}
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) { super.onRequestPermissionsResult(requestCode, permissions, results); if (requestCode == 77 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startLocation(); }
    @Override protected void onPause() { super.onPause(); if (sharing != null && sharing.isChecked()) status.setText("Sharing pauses when RiderLink closes"); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) Fullscreen.apply(this); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); try { locations.removeUpdates(this); } catch (SecurityException ignored) { } if (client != null) client.shutdown(); if(flockCameras!=null)flockCameras.shutdown();if(policeProvider!=null)policeProvider.shutdown();if(speech!=null){speech.stop();speech.shutdown();} if (map != null) map.destroy(); super.onDestroy(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int systemBarHeight(String name){int id=getResources().getIdentifier(name,"dimen","android");return id>0?getResources().getDimensionPixelSize(id):0;}
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private Button compactButton(String label,int color,float size){Button b=button(label,color);b.setSingleLine(true);b.setTextSize(size);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(2),0,dp(2),0);return b;}
    private static class PolicePoint{final long id;final double lat,lon;PolicePoint(long i,double a,double o){id=i;lat=a;lon=o;}}
}
