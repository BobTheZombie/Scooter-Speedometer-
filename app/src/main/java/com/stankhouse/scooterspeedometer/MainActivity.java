package com.stankhouse.scooterspeedometer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements LocationListener {
    private interface MenuHandler { void select(int which); }
    private static final int LOCATION_REQUEST = 42;
    private static final int CAMERA_REQUEST = 44;
    private static final int MICROPHONE_REQUEST = 45;
    private LocationManager locationManager;
    private MediaSessionManager mediaSessionManager;
    private AudioManager audioManager;
    private AudioRack audioRack;
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
    private WeatherVoiceManager weatherVoice;
    private long lastWeatherRequest;
    private AutoNightController nightMode;
    private RoadAwarenessManager roadAwareness;
    private DeliveryCockpit deliveryCockpit;
    private RideRecorder rideRecorder;
    private CrashDetector crashDetector;
    private UpdateManager updateManager;
    private RiderCopilot riderCopilot;
    private String roadAlert = "";
    private long roadAlertUntil;
    private boolean weatherConsentPrompted,notificationConsentPrompted;
    private SpeechRecognizer speechRecognizer;
    private String hudVoiceStatus="";
    private long hudVoiceStatusUntil;
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private final ExecutorService roadNameWorker=Executors.newSingleThreadExecutor();
    private String currentStreet="LOCATING ROAD…";
    private float smoothedBearing=Float.NaN;
    private long lastRoadNameLookup;
    private Location lastRoadNameLocation;
    private boolean roadNameLookupRunning;
    private final BroadcastReceiver hudReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(speedView!=null)speedView.invalidate();}};

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
        currentStreet=prefs.getString("current_street","LOCATING ROAD…");
        tripMeters = prefs.getFloat("trip", 0f);
        maxMps = prefs.getFloat("max", 0f);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioRack = new AudioRack(this);
        weatherRepository = new WeatherRepository(this);
        weatherVoice = new WeatherVoiceManager(this);
        nightMode = new AutoNightController(this, () -> { if (speedView != null) speedView.invalidate(); });
        roadAwareness = new RoadAwarenessManager(this, new RoadAwarenessManager.Callback() {
            @Override public void onRoadDataChanged() { if (speedView != null) speedView.invalidate(); }
            @Override public void onHazard(String message) {
                roadAlert = message; roadAlertUntil = SystemClock.elapsedRealtime() + 7000L;
                if (speedView != null) speedView.invalidate();
            }
        });
        deliveryCockpit = new DeliveryCockpit(this);
        rideRecorder = new RideRecorder(this);
        crashDetector = new CrashDetector(this,force -> runOnUiThread(this::showCrashCheckIn));
        updateManager = new UpdateManager(this);
        riderCopilot = new RiderCopilot(this, this::setHudVoiceStatus);
        crashDetector.setEnabled(prefs.getBoolean("crash_detection",false));
        weatherData = weatherRepository.cached();
        weatherAlert = weatherRepository.cachedAlert();
        speedView = new SpeedView(this);
        speedView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentView(speedView);
        IntentFilter hudFilter=new IntentFilter(MediaAccessService.ACTION_HUD_UPDATE);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(hudReceiver,hudFilter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(hudReceiver,hudFilter);
        enterImmersive();
        updateManager.check(false);
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

    private void startVoiceCommand(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MICROPHONE_REQUEST);return;}
        if(!SpeechRecognizer.isRecognitionAvailable(this)){android.widget.Toast.makeText(this,"Speech recognition is not installed",android.widget.Toast.LENGTH_LONG).show();return;}
        if(speechRecognizer==null){speechRecognizer=SpeechRecognizer.createSpeechRecognizer(this);speechRecognizer.setRecognitionListener(new RecognitionListener(){public void onReadyForSpeech(Bundle b){setHudVoiceStatus("LISTENING…");}public void onBeginningOfSpeech(){setHudVoiceStatus("HEARING YOU…");}public void onRmsChanged(float r){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){setHudVoiceStatus("WORKING…");}public void onError(int e){setHudVoiceStatus("DIDN'T CATCH THAT — TAP MIC");}public void onResults(Bundle b){ArrayList<String> values=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(values==null||values.isEmpty())setHudVoiceStatus("DIDN'T CATCH THAT");else executeVoiceCommand(values);}public void onPartialResults(Bundle b){}public void onEvent(int t,Bundle b){}});}
        Intent listen=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);listen.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);listen.putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault());listen.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);listen.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);listen.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,1100L);listen.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,700L);speechRecognizer.startListening(listen);
    }
    private void setHudVoiceStatus(String value){hudVoiceStatus=value;hudVoiceStatusUntil=SystemClock.elapsedRealtime()+4500L;if(speedView!=null)speedView.invalidate();}
    private void executeVoiceCommand(List<String> candidates){String heard=candidates.get(0).toLowerCase(Locale.US);String done="COMMAND NOT RECOGNIZED";for(String raw:candidates){String command=raw.toLowerCase(Locale.US);if(command.contains("copilot")&&(command.contains("brief")||command.contains("status")||command.contains("report"))){riderCopilot.announceBriefing(weatherData,lastGoodLocation,smoothedMps,tripMeters,satellites);done="RIDE BRIEFING";break;}if(command.contains("navigate")||command.contains("directions")){beginNavigation();done="OPENING NAVIGATION";break;}if(command.contains("backup")||command.contains("rear camera")){openBackupCamera();done="OPENING BACKUP CAMERA";break;}if(command.contains("rider")||command.contains("social")){startActivity(new Intent(this,RiderLinkActivity.class));done="OPENING RIDERLINK";break;}if(command.contains("next")&&command.contains("song")){mediaNext();done="NEXT TRACK";break;}if(command.contains("previous")||command.contains("last song")){mediaPrevious();done="PREVIOUS TRACK";break;}if(command.contains("pause")||command.contains("play")){mediaPlayPause();done="MEDIA TOGGLED";break;}if(command.contains("volume up")||command.contains("louder")){adjustMusicVolume(AudioManager.ADJUST_RAISE);done="VOLUME UP";break;}if(command.contains("volume down")||command.contains("quieter")){adjustMusicVolume(AudioManager.ADJUST_LOWER);done="VOLUME DOWN";break;}if(command.contains("weather")||command.contains("forecast")){requestWeather(lastGoodLocation==null?0:lastGoodLocation.getLatitude(),lastGoodLocation==null?0:lastGoodLocation.getLongitude(),true);done="REFRESHING WEATHER";break;}if(command.contains("hazard")){showHazardPicker();done="CHOOSE HAZARD";break;}if(command.contains("customize")||command.contains("settings")){speedView.showDashboardCustomizer();done="OPENING CUSTOMIZE";break;}}
        setHudVoiceStatus(done+("COMMAND NOT RECOGNIZED".equals(done)?" • "+heard.toUpperCase(Locale.US):""));
    }

    private void enterImmersive() {
        Fullscreen.apply(this);
    }

    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) enterImmersive(); }
    @Override protected void onResume() {
        super.onResume();
        enterImmersive();
        nightMode.start();
        if (riderCopilot != null) riderCopilot.start();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGps();
        refreshMedia();
        if (audioRack != null) audioRack.refresh();
        if(updateManager!=null)updateManager.resumePendingInstall();
        if(!notificationConsentPrompted&&!prefs.contains("notification_monitoring")){notificationConsentPrompted=true;speedView.post(this::showNotificationConsent);}
        if (!weatherConsentPrompted && !prefs.contains("weather_provider_consent")) {
            weatherConsentPrompted = true;
            speedView.post(this::showWeatherProviderConsent);
        }
        if (prefs.getBoolean("weather_provider_consent", false) && weatherRepository.customLocation())
            speedView.post(() -> requestWeather(0, 0, false));
        if (navigationPermissionPending && Settings.canDrawOverlays(this)) {
            navigationPermissionPending = false;
            speedView.post(this::showDestinationDialog);
        }
    }

    private void showNotificationConsent(){new AlertDialog.Builder(this).setTitle("Enable ride notifications & media?").setMessage("This powers music controls, message and call indicators, spoken alerts, and delivery offers. You can control each category or shut monitoring off instantly under Rider → Notification monitoring.").setPositiveButton("Enable",(d,w)->{prefs.edit().putBoolean("notification_monitoring",true).putBoolean("monitor_media",true).putBoolean("monitor_messages",true).putBoolean("monitor_calls",true).putBoolean("monitor_delivery",true).apply();sendBroadcast(new Intent(MediaAccessService.ACTION_MONITORING_CHANGED).setPackage(getPackageName()));if(!hasMediaAccess())openMediaAccessSettings();else if(Build.VERSION.SDK_INT>=24)android.service.notification.NotificationListenerService.requestRebind(mediaListenerComponent());refreshMedia();}).setNegativeButton("Keep off",(d,w)->prefs.edit().putBoolean("notification_monitoring",false).apply()).show();}

    private void showWeatherProviderConsent() {
        new AlertDialog.Builder(this).setTitle("Enable live weather?")
                .setMessage("To show local conditions and warnings, Scooter Speedometer sends your current GPS coordinates to Open-Meteo for weather and weather.gov for official U.S. alerts. Weather data stays out of RiderLink and Supabase.")
                .setPositiveButton("Enable live weather", (dialog, which) -> {
                    prefs.edit().putBoolean("weather_provider_consent", true).apply();
                    lastWeatherRequest = 0L;
                    if (weatherRepository.customLocation()) requestWeather(0, 0, true);
                })
                .setNegativeButton("Not now", (dialog, which) ->
                        prefs.edit().putBoolean("weather_provider_consent", false).apply()).show();
    }

    private void requestWeather(double gpsLatitude,double gpsLongitude,boolean force) {
        if(!prefs.getBoolean("weather_provider_consent",false))return;
        if(force){weatherRepository.clearCache();weatherData=null;weatherAlert=null;}
        lastWeatherRequest=SystemClock.elapsedRealtime();
        weatherRepository.update(gpsLatitude,gpsLongitude,data->{weatherData=data;weatherVoice.announceForecast(data);speedView.invalidate();});
        weatherRepository.updateAlerts(gpsLatitude,gpsLongitude,alert->{weatherAlert=alert;weatherVoice.announceAlert(alert);speedView.invalidate();});
    }

    private void showWeatherSettings() {
        String enabled=prefs.getBoolean("weather_provider_consent",false)?"Enabled":"Disabled";
        String keyState=prefs.getString("accuweather_api_key","").isEmpty()?"Not configured":"Configured";
        String[] items={"Live weather  •  "+enabled,"Imminent voice alerts  •  "+(weatherVoice.imminentEnabled()?"ON":"OFF"),"Provider  •  "+weatherRepository.providerName(),"Location  •  "+weatherRepository.locationName(),"Refresh  •  "+(weatherRepository.refreshMs()/60000L)+" minute(s)","AccuWeather API key  •  "+keyState,"Refresh now"};
        new AlertDialog.Builder(this).setTitle("Live weather & Sense background").setItems(items,(d,which)->{
            if(which==0){boolean next=!prefs.getBoolean("weather_provider_consent",false);prefs.edit().putBoolean("weather_provider_consent",next).apply();if(next)requestWeather(lastGoodLocation==null?0:lastGoodLocation.getLatitude(),lastGoodLocation==null?0:lastGoodLocation.getLongitude(),true);else speedView.invalidate();}
            else if(which==1){weatherVoice.setImminentEnabled(!weatherVoice.imminentEnabled());showWeatherSettings();}
            else if(which==2)showWeatherProviderPicker();
            else if(which==3)showWeatherLocationPicker();
            else if(which==4)showWeatherRefreshPicker();
            else if(which==5)showAccuWeatherKeyDialog();
            else requestWeather(lastGoodLocation==null?0:lastGoodLocation.getLatitude(),lastGoodLocation==null?0:lastGoodLocation.getLongitude(),true);
        }).setNegativeButton("Done",null).show();
    }

    private void showWeatherProviderPicker(){String[] choices={"Automatic fallback","Open-Meteo","MET Norway","AccuWeather (API key required)"};new AlertDialog.Builder(this).setTitle("Weather provider").setSingleChoiceItems(choices,weatherRepository.provider(),(d,which)->{d.dismiss();if(which==3&&prefs.getString("accuweather_api_key","").isEmpty()){showAccuWeatherKeyDialog();return;}prefs.edit().putInt("weather_provider",which).apply();requestWeather(lastGoodLocation==null?0:lastGoodLocation.getLatitude(),lastGoodLocation==null?0:lastGoodLocation.getLongitude(),true);}).setNegativeButton("Back",(d,w)->showWeatherSettings()).show();}
    private void showAccuWeatherKeyDialog(){EditText input=weatherInput("AccuWeather API key");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setText(prefs.getString("accuweather_api_key",""));new AlertDialog.Builder(this).setTitle("AccuWeather access").setMessage("Enter your own AccuWeather developer key. AccuWeather requires attribution and an appropriate license for vehicle use. AccuWeather refresh is limited to 10 minutes to protect your quota.").setView(input).setPositiveButton("SAVE & USE",(d,w)->{String key=input.getText().toString().trim();if(key.isEmpty()){android.widget.Toast.makeText(this,"API key not saved",android.widget.Toast.LENGTH_LONG).show();return;}prefs.edit().putString("accuweather_api_key",key).putInt("weather_provider",3).apply();requestWeather(lastGoodLocation==null?0:lastGoodLocation.getLatitude(),lastGoodLocation==null?0:lastGoodLocation.getLongitude(),true);}).setNeutralButton("REMOVE KEY",(d,w)->{prefs.edit().remove("accuweather_api_key").putInt("weather_provider",0).apply();requestWeather(lastGoodLocation==null?0:lastGoodLocation.getLatitude(),lastGoodLocation==null?0:lastGoodLocation.getLongitude(),true);}).setNegativeButton("Cancel",null).show();}

    private Switch copilotSwitch(String title,String summary,boolean checked){Switch item=new Switch(this);item.setText(title+"\n"+summary);item.setTextColor(UiKit.TEXT);item.setTextSize(15);item.setChecked(checked);item.setPadding(UiKit.dp(this,16),UiKit.dp(this,9),UiKit.dp(this,12),UiKit.dp(this,9));item.setBackground(UiKit.rounded(this,UiKit.SURFACE_HIGH,18,UiKit.BORDER));item.setButtonTintList(android.content.res.ColorStateList.valueOf(UiKit.CYAN));return item;}
    private TextView copilotAction(String title,String summary){TextView item=UiKit.text(this,title+"\n"+summary,15,UiKit.TEXT,true);item.setPadding(UiKit.dp(this,16),UiKit.dp(this,10),UiKit.dp(this,16),UiKit.dp(this,10));item.setBackground(UiKit.rounded(this,UiKit.SURFACE_HIGH,18,UiKit.BORDER));item.setClickable(true);return item;}
    private void showCopilotSettings(){LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(UiKit.dp(this,16),UiKit.dp(this,10),UiKit.dp(this,16),UiKit.dp(this,12));panel.setBackground(UiKit.screenBackground(this));panel.addView(UiKit.header(this,"◈","Rider Copilot","Adaptive, prioritized ride intelligence"));Switch master=copilotSwitch("Rider Copilot",riderCopilot.enabled()?"Active while the app is visible":"All Copilot context alerts are paused",riderCopilot.enabled());Switch adaptive=copilotSwitch("Adaptive intelligence","Combine speed, trip time, GPS, battery and weather context",prefs.getBoolean("copilot_adaptive",true));Switch coaching=copilotSwitch("Ride coaching","Smart break, hydration and scooter-check reminders",prefs.getBoolean("copilot_ride_coaching",true));Switch priority=copilotSwitch("Smart priority queue","Safety and navigation can interrupt lower-priority speech",prefs.getBoolean("copilot_prioritized_queue",true));Switch route=copilotSwitch("Route-aware weather","Check forecast conditions ahead along OpenNAV+ routes",prefs.getBoolean("copilot_route_weather",true));Switch gps=copilotSwitch("GPS quality warnings","Warn after a sustained weak or inaccurate fix",prefs.getBoolean("copilot_gps_alerts",true));Switch battery=copilotSwitch("Battery warnings","Announce 20 and 10 percent battery thresholds",prefs.getBoolean("copilot_battery_alerts",true));Switch wind=copilotSwitch("Strong-wind warnings","Prioritize gust warnings while riding",prefs.getBoolean("copilot_wind_alerts",true));Switch[] switches={master,adaptive,coaching,priority,route,gps,battery,wind};for(Switch item:switches){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,UiKit.dp(this,66));lp.setMargins(0,0,0,UiKit.dp(this,8));panel.addView(item,lp);}String[] levels={"All alerts","Safety + navigation","Urgent hazards only"};TextView level=copilotAction("Alert detail  •  "+levels[Math.max(0,Math.min(2,prefs.getInt("copilot_alert_level",0)))],"Choose how much Copilot is allowed to say");TextView repeat=copilotAction("Repeat last alert","Replay the most recent completed Copilot message");TextView voice=copilotAction("Voice Studio","Voice, speaking speed, pitch and message reading");for(TextView item:new TextView[]{level,repeat,voice}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,UiKit.dp(this,62));lp.setMargins(0,0,0,UiKit.dp(this,8));panel.addView(item,lp);}View.OnClickListener save=v->{prefs.edit().putBoolean("copilot_adaptive",adaptive.isChecked()).putBoolean("copilot_ride_coaching",coaching.isChecked()).putBoolean("copilot_prioritized_queue",priority.isChecked()).putBoolean("copilot_route_weather",route.isChecked()).putBoolean("copilot_gps_alerts",gps.isChecked()).putBoolean("copilot_battery_alerts",battery.isChecked()).putBoolean("copilot_wind_alerts",wind.isChecked()).apply();riderCopilot.setEnabled(master.isChecked());if(master.isChecked())riderCopilot.start();};for(Switch item:switches)item.setOnClickListener(save);level.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Copilot alert detail").setSingleChoiceItems(levels,prefs.getInt("copilot_alert_level",0),(d,w)->{prefs.edit().putInt("copilot_alert_level",w).apply();d.dismiss();showCopilotSettings();}).show());repeat.setOnClickListener(v->riderCopilot.repeatLast());voice.setOnClickListener(v->showVoiceStudio());ScrollView scroll=new ScrollView(this);scroll.addView(panel);new AlertDialog.Builder(this).setView(scroll).setPositiveButton("Done",null).show();}
    private void showVoiceStudio(){boolean messages=prefs.getBoolean("hud_speak_messages",true),calls=prefs.getBoolean("hud_speak_calls",true),copilot=riderCopilot!=null&&riderCopilot.enabled();String[] items={"Voice  •  "+weatherVoice.voiceName(),"Speech speed  •  "+Math.round(weatherVoice.rate()*100)+"%","Pitch  •  "+Math.round(weatherVoice.pitch()*100)+"%","Rider Copilot  •  "+(copilot?"ON":"OFF"),"Speak ride briefing","Read incoming messages  •  "+(messages?"ON":"OFF"),"Announce incoming calls  •  "+(calls?"ON":"OFF"),"Preview voice","Open Android voice downloads"};new AlertDialog.Builder(this).setTitle("Voice Studio").setItems(items,(d,which)->{if(which==0)showVoicePicker();else if(which==1)showVoiceSlider(true);else if(which==2)showVoiceSlider(false);else if(which==3){riderCopilot.setEnabled(!copilot);if(!copilot)riderCopilot.start();showVoiceStudio();}else if(which==4)riderCopilot.announceBriefing(weatherData,lastGoodLocation,smoothedMps,tripMeters,satellites);else if(which==5){prefs.edit().putBoolean("hud_speak_messages",!messages).apply();showVoiceStudio();}else if(which==6){prefs.edit().putBoolean("hud_speak_calls",!calls).apply();showVoiceStudio();}else if(which==7)weatherVoice.preview();else{try{startActivity(new Intent("com.android.settings.TTS_SETTINGS"));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}}}).setNegativeButton("Done",null).show();}
    private void showVoicePicker(){weatherVoice.voices(voices->{if(voices.isEmpty()){android.widget.Toast.makeText(this,"No voices found. Install voices in Android Text-to-speech settings.",android.widget.Toast.LENGTH_LONG).show();return;}String[] labels=new String[voices.size()];for(int i=0;i<voices.size();i++){android.speech.tts.Voice v=voices.get(i);labels[i]=v.getLocale().getDisplayName()+"  •  "+v.getName()+(v.isNetworkConnectionRequired()?"  [online]":"  [offline]");}new AlertDialog.Builder(this).setTitle("Installed voices").setItems(labels,(d,which)->{weatherVoice.setVoice(voices.get(which));weatherVoice.preview();}).setNegativeButton("Back",(d,w)->showVoiceStudio()).show();});}
    private void showVoiceSlider(boolean rate){android.widget.SeekBar bar=new android.widget.SeekBar(this);bar.setMax(100);float current=rate?weatherVoice.rate():weatherVoice.pitch();bar.setProgress(Math.round((current-.5f)*100));bar.setPadding(30,20,30,20);new AlertDialog.Builder(this).setTitle(rate?"Speech speed":"Voice pitch").setMessage("50% to 150% — 90–100% usually sounds most natural.").setView(bar).setPositiveButton("SAVE",(d,w)->{float value=.5f+bar.getProgress()/100f;if(rate)weatherVoice.setRate(value);else weatherVoice.setPitch(value);weatherVoice.preview();}).setNegativeButton("Cancel",null).show();}
    private void showWeatherRefreshPicker(){String[] choices={"Every minute (live)","Every 2 minutes","Every 5 minutes","Every 10 minutes"};new AlertDialog.Builder(this).setTitle("Weather refresh rate").setSingleChoiceItems(choices,Math.max(0,Math.min(3,prefs.getInt("weather_refresh",0))),(d,which)->{prefs.edit().putInt("weather_refresh",which).apply();d.dismiss();requestWeather(lastGoodLocation==null?0:lastGoodLocation.getLatitude(),lastGoodLocation==null?0:lastGoodLocation.getLongitude(),true);}).setNegativeButton("Back",(d,w)->showWeatherSettings()).show();}
    private void showWeatherLocationPicker(){String[] choices={"Use live GPS location","Search city or ZIP code","Enter latitude / longitude"};new AlertDialog.Builder(this).setTitle("Weather location").setItems(choices,(d,which)->{if(which==0){prefs.edit().putBoolean("weather_custom_location",false).apply();requestWeather(lastGoodLocation==null?0:lastGoodLocation.getLatitude(),lastGoodLocation==null?0:lastGoodLocation.getLongitude(),true);}else if(which==1)showWeatherLocationSearch();else showWeatherCoordinates();}).setNegativeButton("Back",(d,w)->showWeatherSettings()).show();}
    private EditText weatherInput(String hint){EditText input=new EditText(this);input.setHint(hint);input.setSingleLine(true);input.setTextColor(Color.WHITE);input.setHintTextColor(Color.GRAY);input.setPadding(24,8,24,8);return input;}
    private void saveWeatherLocation(String name,double lat,double lon){prefs.edit().putBoolean("weather_custom_location",true).putString("weather_custom_name",name).putLong("weather_custom_lat",Double.doubleToRawLongBits(lat)).putLong("weather_custom_lon",Double.doubleToRawLongBits(lon)).apply();requestWeather(0,0,true);}
    private void showWeatherLocationSearch(){EditText input=weatherInput("City, state or ZIP code");new AlertDialog.Builder(this).setTitle("Search weather location").setView(input).setPositiveButton("SEARCH",(d,w)->{String q=input.getText().toString().trim();if(q.isEmpty())return;android.widget.Toast.makeText(this,"Finding "+q+"…",android.widget.Toast.LENGTH_SHORT).show();weatherRepository.geocode(q,(ok,label,lat,lon)->{if(ok){saveWeatherLocation(label,lat,lon);android.widget.Toast.makeText(this,"Weather location: "+label,android.widget.Toast.LENGTH_LONG).show();}else android.widget.Toast.makeText(this,"Location not found",android.widget.Toast.LENGTH_LONG).show();});}).setNegativeButton("Cancel",null).show();}
    private void showWeatherCoordinates(){LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(24,8,24,8);EditText lat=weatherInput("Latitude, e.g. 42.2917"),lon=weatherInput("Longitude, e.g. -85.5872");lat.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);lon.setInputType(lat.getInputType());panel.addView(lat);panel.addView(lon);new AlertDialog.Builder(this).setTitle("Custom coordinates").setView(panel).setPositiveButton("USE LOCATION",(d,w)->{try{double a=Double.parseDouble(lat.getText().toString()),o=Double.parseDouble(lon.getText().toString());if(a < -90||a>90||o < -180||o>180)throw new Exception();saveWeatherLocation(String.format(Locale.US,"%.3f, %.3f",a,o),a,o);}catch(Exception e){android.widget.Toast.makeText(this,"Enter valid latitude and longitude",android.widget.Toast.LENGTH_LONG).show();}}).setNegativeButton("Cancel",null).show();}
    @Override protected void onPause() { super.onPause(); nightMode.stop(); if(riderCopilot!=null)riderCopilot.stop(); stopGps(); stopMediaListener(); saveStats(); if(deliveryCockpit!=null)deliveryCockpit.flushMileage(); }

    private void startGps() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this);
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
        if (!prefs.getBoolean("notification_monitoring",false)||!prefs.getBoolean("monitor_media",true)||!hasMediaAccess()) { clearMedia(); return; }
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
    private void openActiveMediaApp() {
        if(mediaController==null){android.widget.Toast.makeText(this,"Start music first, then tap the media card",android.widget.Toast.LENGTH_SHORT).show();return;}
        String packageName=mediaController.getPackageName();
        try{
            Intent launch=getPackageManager().getLaunchIntentForPackage(packageName);
            if(launch==null)launch=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
        }catch(Exception error){android.widget.Toast.makeText(this,"Could not open the active music app",android.widget.Toast.LENGTH_LONG).show();}
    }
    private void adjustMusicVolume(int direction) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        speedView.invalidate();
    }
    private int musicVolumePercent() {
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0 : Math.round(100f * audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
    }

    private void showMediaVolumeSlider() {
        int maximum=audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(30,18,30,12);
        TextView value=new TextView(this);value.setTextColor(Color.WHITE);value.setTextSize(22);value.setGravity(android.view.Gravity.CENTER);panel.addView(value);
        SeekBar slider=new SeekBar(this);slider.setMax(Math.max(1,maximum));slider.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));slider.setPadding(4,18,4,16);panel.addView(slider);
        value.setText("MEDIA VOLUME  •  "+musicVolumePercent()+"%");
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int progress,boolean user){if(user)audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,progress,0);value.setText("MEDIA VOLUME  •  "+musicVolumePercent()+"%");if(speedView!=null)speedView.invalidate();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        new AlertDialog.Builder(this).setTitle("VOLUME").setView(panel).setPositiveButton("DONE",null).show();
    }

    private void showEqualizer() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(28, 12, 28, 12);
        Switch enabled = settingsSwitch("7-band EQ", audioRack.eqEnabled()); panel.addView(enabled);
        enabled.setOnCheckedChangeListener((button, value) -> { audioRack.setEqEnabled(value); if (speedView != null) speedView.invalidate(); });
        for (int i = 0; i < AudioRack.FREQUENCIES.length; i++) {
            final int band = i;
            TextView label = new TextView(this); label.setTextColor(Color.WHITE); label.setTextSize(14); label.setPadding(6, 9, 6, 0);
            int hz = AudioRack.FREQUENCIES[i]; label.setText((hz >= 1000 ? (hz / 1000f) + " kHz" : hz + " Hz") + "   " + signedDb(audioRack.gain(i)));
            SeekBar slider = new SeekBar(this); slider.setMax(24); slider.setProgress(audioRack.gain(i) + 12);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { int db=progress-12; label.setText((AudioRack.FREQUENCIES[band]>=1000?(AudioRack.FREQUENCIES[band]/1000f)+" kHz":AudioRack.FREQUENCIES[band]+" Hz")+"   "+signedDb(db)); audioRack.setGain(band,db); }
                public void onStartTrackingTouch(SeekBar bar) { } public void onStopTrackingTouch(SeekBar bar) { }
            });
            panel.addView(label); panel.addView(slider);
        }
        TextView note = new TextView(this); note.setText("Changes apply live to the phone's media output. Available range: −12 to +12 dB."); note.setTextColor(Color.rgb(175,195,205)); note.setPadding(6,14,6,8); panel.addView(note);
        scroll.addView(panel);
        new AlertDialog.Builder(this).setTitle("7-BAND PARAMETRIC EQ").setMessage((audioRack.eqAvailable()?"Shape the output curve":"This phone currently blocks the system EQ effect")+"\n"+audioRack.oemName())
                .setView(scroll).setNeutralButton("FLAT",(d,w)->{audioRack.flat();showEqualizer();})
                .setNegativeButton("OEM AUDIO",(d,w)->openOemAudio()).setPositiveButton("DONE",null).show();
    }

    private String signedDb(int db) { return (db > 0 ? "+" : "") + db + " dB"; }

    private void showAmplifier() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(28,12,28,12);
        Switch enabled = settingsSwitch("Amplifier", audioRack.ampEnabled()); panel.addView(enabled);
        TextView value = new TextView(this); value.setTextColor(Color.WHITE); value.setTextSize(18); value.setPadding(8,18,8,4); value.setText("Gain  +"+(audioRack.amplifierGain()/100f)+" dB"); panel.addView(value);
        SeekBar slider = new SeekBar(this); slider.setMax(12); slider.setProgress(audioRack.amplifierGain()/100); panel.addView(slider);
        TextView warning = new TextView(this); warning.setText("Use only enough gain to overcome quiet recordings. Heavy boost can cause clipping, distortion, or speaker damage."); warning.setTextColor(Color.rgb(255,185,110)); warning.setPadding(8,18,8,8); panel.addView(warning);
        enabled.setOnCheckedChangeListener((button,on)->{audioRack.setAmpEnabled(on);if(speedView!=null)speedView.invalidate();});
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){audioRack.setAmplifierGain(p*100);value.setText("Gain  +"+p+".0 dB");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        new AlertDialog.Builder(this).setTitle("MEDIA AMPLIFIER").setMessage((audioRack.ampAvailable()?"Digital gain stage":"This phone currently blocks the amplifier effect")+"\n"+audioRack.oemName())
                .setView(panel).setNegativeButton("OEM AUDIO",(d,w)->openOemAudio()).setPositiveButton("DONE",null).show();
    }

    private void openOemAudio(){if(!audioRack.openOemPanel(this))android.widget.Toast.makeText(this,"No separate "+audioRack.oemName()+" control panel is installed. The built-in audio rack remains available.",android.widget.Toast.LENGTH_LONG).show();}

    private void openBackupCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            return;
        }
        startActivity(new Intent(this, BackupCameraActivity.class));
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
        new AddressAutocompleteDialog(this, lastGoodLocation, this::chooseNavigationApp).show();
    }

    private void chooseNavigationApp(String destination) {
        String[] apps = {"OpenNAV+", "Google Maps", "Waze", "Another navigation app"};
        new AlertDialog.Builder(this)
                .setTitle("Navigate with")
                .setItems(apps, (dialog, which) -> launchNavigation(destination, which))
                .show();
    }

    private void launchNavigation(String destination, int choice) {
        Intent overlay = new Intent(this, NavigationOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(overlay); else startService(overlay);

        if (choice == 0) {
            startActivity(new Intent(this, BuiltInNavigationActivity.class).putExtra("destination", destination));
            return;
        }
        Intent navigation;
        if (choice == 1) {
            navigation = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination)));
            navigation.setPackage("com.google.android.apps.maps");
        } else if (choice == 2) {
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
        updateRoadCompass(location);
        nightMode.updateLocation(location.getLatitude(), location.getLongitude());
        roadAwareness.update(location, smoothedMps);
        deliveryCockpit.updateMileage(location);rideRecorder.update(location);crashDetector.update(location);
        if(riderCopilot!=null)riderCopilot.update(location,smoothedMps,tripMeters,satellites,weatherData);
        if (prefs.getBoolean("weather_provider_consent", false) &&
                SystemClock.elapsedRealtime() - lastWeatherRequest > weatherRepository.refreshMs())
            requestWeather(location.getLatitude(),location.getLongitude(),false);
        speedView.accuracy = location.getAccuracy();
        speedView.invalidate();
    }
    private void updateRoadCompass(Location location){
        if(location.hasBearing()&&location.getSpeed()>=.8f){float next=(location.getBearing()+360f)%360f;if(Float.isNaN(smoothedBearing))smoothedBearing=next;else{float delta=((next-smoothedBearing+540f)%360f)-180f;smoothedBearing=(smoothedBearing+delta*.22f+360f)%360f;}}
        long now=SystemClock.elapsedRealtime();
        if(roadNameLookupRunning||now-lastRoadNameLookup<30000L)return;
        if(lastRoadNameLocation!=null&&lastRoadNameLocation.distanceTo(location)<80f&&now-lastRoadNameLookup<120000L)return;
        final Location sample=new Location(location);roadNameLookupRunning=true;lastRoadNameLookup=now;lastRoadNameLocation=sample;
        roadNameWorker.execute(()->{String street=null;try{street=GeocodingService.reverseStreet(sample.getLatitude(),sample.getLongitude());}catch(Exception ignored){}final String result=street;mainHandler.post(()->{roadNameLookupRunning=false;if(result!=null&&!result.isEmpty()){currentStreet=result;prefs.edit().putString("current_street",result).apply();if(speedView!=null)speedView.invalidate();}});});
    }
    private String compassDirection(){if(Float.isNaN(smoothedBearing))return "—";String[] points={"N","NE","E","SE","S","SW","W","NW"};return points[Math.round(smoothedBearing/45f)%8];}
    private String roadCompassLabel(){String street=currentStreet==null||currentStreet.trim().isEmpty()?"ROAD UNKNOWN":currentStreet.trim();if(street.length()>32)street=street.substring(0,31)+"…";return compassDirection()+"  •  "+street;}
    @Override public void onProviderEnabled(String provider) { speedView.invalidate(); }
    @Override public void onProviderDisabled(String provider) { speedView.invalidate(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startGps();
        if (requestCode == CAMERA_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            openBackupCamera();
        if (requestCode == MICROPHONE_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            startVoiceCommand();
        speedView.invalidate();
    }
    private void resetStats() { tripMeters = 0; maxMps = 0; saveStats(); speedView.invalidate(); }
    private void saveStats() { prefs.edit().putFloat("trip", (float) tripMeters).putFloat("max", maxMps).apply(); }

    private void showHazardPicker() {
        if (lastGoodLocation == null || SystemClock.elapsedRealtime() - lastFixElapsed > 5000L) {
            new AlertDialog.Builder(this).setTitle("GPS fix required")
                    .setMessage("Wait for GPS LOCK before marking a road hazard.").setPositiveButton("OK", null).show();
            return;
        }
        String[] hazardTypes = java.util.Arrays.copyOf(RoadAwarenessManager.HAZARD_TYPES, RoadAwarenessManager.HAZARD_TYPES.length + 1);
        hazardTypes[hazardTypes.length - 1] = "Flock / license-plate camera";
        new AlertDialog.Builder(this).setTitle("Report a hazard here")
                .setItems(hazardTypes, (dialog, which) -> {
                    String type=hazardTypes[which];roadAwareness.addHazard(type,lastGoodLocation);
                    if(which==hazardTypes.length-1){RiderLinkClient link=new RiderLinkClient(this);if(link.signedIn())link.reportCommunityHazard("flock_camera",lastGoodLocation.getLatitude(),lastGoodLocation.getLongitude(),(ok,msg,body)->{android.widget.Toast.makeText(this,ok?"Flock camera shared on RiderLink":"Saved locally • RiderLink share failed: "+msg,android.widget.Toast.LENGTH_LONG).show();link.shutdown();});else{android.widget.Toast.makeText(this,"Saved locally • sign into RiderLink to share it on the live map",android.widget.Toast.LENGTH_LONG).show();link.shutdown();}}
                })
                .setNeutralButton("Manage", (dialog, which) -> showRoadSettings()).setNegativeButton("Cancel", null).show();
    }

    private void showRoadSettings() {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density), rowPad = Math.round(12 * density);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(pad, Math.round(8 * density), pad, Math.round(4 * density));
        panel.setBackgroundColor(Color.rgb(9, 12, 15));

        TextView nightHeading = settingsHeading("NIGHT DISPLAY"); panel.addView(nightHeading);
        Switch automatic = settingsSwitch("Automatic sunset + light sensor", nightMode.enabled());
        panel.addView(automatic); automatic.setOnCheckedChangeListener((button, checked) -> { nightMode.setEnabled(checked); speedView.invalidate(); });
        Switch oled = settingsSwitch("Extra-dark OLED mode", nightMode.oled());
        panel.addView(oled); oled.setOnCheckedChangeListener((button, checked) -> { nightMode.setOled(checked); speedView.invalidate(); });

        TextView roadHeading = settingsHeading("ROAD WARNINGS"); roadHeading.setPadding(0, rowPad, 0, 0); panel.addView(roadHeading);
        Switch voice = settingsSwitch("Spoken speed warnings", roadAwareness.voiceEnabled());
        panel.addView(voice); voice.setOnCheckedChangeListener((button, checked) -> roadAwareness.setVoiceEnabled(checked));
        Switch hazards = settingsSwitch("Hazard proximity alerts", roadAwareness.hazardsEnabled());
        panel.addView(hazards); hazards.setOnCheckedChangeListener((button, checked) -> roadAwareness.setHazardsEnabled(checked));

        TextView threshold = settingsAction("Overspeed threshold", "+" + roadAwareness.thresholdMph() + " mph");
        panel.addView(threshold); threshold.setOnClickListener(v -> {
            String[] levels = {"At the limit", "+3 mph", "+5 mph", "+10 mph"}; int[] values = {0, 3, 5, 10};
            new AlertDialog.Builder(this).setTitle("Spoken warning threshold")
                    .setSingleChoiceItems(levels, thresholdIndex(), (d, item) -> {
                        roadAwareness.setThresholdMph(values[item]); threshold.setText("Overspeed threshold\n+" + values[item] + " mph"); d.dismiss();
                    }).show();
        });
        TextView clear = settingsAction("Clear saved hazards", roadAwareness.hazardCount() + " currently saved");
        panel.addView(clear); clear.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Clear saved hazards?")
                .setMessage("This removes every locally saved hazard marker.")
                .setPositiveButton("Clear", (d, w) -> { roadAwareness.clearHazards(); clear.setText("Clear saved hazards\n0 currently saved"); })
                .setNegativeButton("Cancel", null).show());

        ScrollView scroller = new ScrollView(this); scroller.setFillViewport(true); scroller.setBackgroundColor(Color.rgb(9, 12, 15));
        scroller.addView(panel);
        new AlertDialog.Builder(this).setTitle("Night & road awareness").setView(scroller)
                .setPositiveButton("Done", null).show();
    }

    private int thresholdIndex() {
        int value = roadAwareness.thresholdMph(); return value == 0 ? 0 : value == 3 ? 1 : value == 10 ? 3 : 2;
    }
    private TextView settingsHeading(String label) {
        TextView view = new TextView(this); view.setText(label); view.setTextColor(Color.rgb(0, 190, 220));
        view.setTextSize(12); view.setAllCaps(true); view.setPadding(0, 0, 0, 2); return view;
    }
    private Switch settingsSwitch(String label, boolean checked) {
        Switch toggle = new Switch(this); toggle.setText(label); toggle.setChecked(checked);
        toggle.setTextColor(Color.WHITE); toggle.setTextSize(16); toggle.setGravity(android.view.Gravity.CENTER_VERTICAL);
        toggle.setPadding(20, 7, 20, 7); toggle.setMinHeight(Math.round(56 * getResources().getDisplayMetrics().density));UiKit.card(toggle); return toggle;
    }
    private TextView settingsAction(String label, String detail) {
        TextView view = new TextView(this); view.setText(label + "\n" + detail); view.setTextColor(Color.WHITE); view.setTextSize(16);
        view.setGravity(android.view.Gravity.CENTER_VERTICAL); view.setPadding(20, 8, 20, 8);view.setLineSpacing(3,1f);UiKit.card(view);
        view.setMinHeight(Math.round(58 * getResources().getDisplayMetrics().density)); return view;
    }
    private void showCrashCheckIn(){final Handler timer=new Handler(getMainLooper());final boolean[] answered={false};AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Possible crash detected").setMessage("A hard impact and sudden stop were detected. RiderLink SOS will be sent in 30 seconds unless you confirm that you are okay. This does not contact 911.").setCancelable(false).setPositiveButton("I'M OKAY",(d,w)->answered[0]=true).setNegativeButton("SEND SOS NOW",null).create();dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{answered[0]=true;sendCrashSos();dialog.dismiss();}));dialog.show();timer.postDelayed(()->{if(!answered[0]){sendCrashSos();if(dialog.isShowing())dialog.dismiss();}},30000);}
    private void sendCrashSos(){if(lastGoodLocation==null){android.widget.Toast.makeText(this,"Crash check-in triggered, but GPS is not ready",android.widget.Toast.LENGTH_LONG).show();return;}RiderLinkClient c=new RiderLinkClient(this);if(!c.signedIn()){android.widget.Toast.makeText(this,"Crash detected. RiderLink sign-in is required to send nearby SOS alerts.",android.widget.Toast.LENGTH_LONG).show();c.shutdown();return;}c.sendSos(lastGoodLocation.getLatitude(),lastGoodLocation.getLongitude(),"Automatic crash check-in: rider may need assistance",(ok,m,b)->{android.widget.Toast.makeText(this,ok?"RiderLink crash SOS sent":m,android.widget.Toast.LENGTH_LONG).show();c.shutdown();});}
    @Override protected void onDestroy() {
        try{unregisterReceiver(hudReceiver);}catch(Exception ignored){}
        if(speechRecognizer!=null)speechRecognizer.destroy();
        if (weatherVoice != null) weatherVoice.shutdown();
        if (roadAwareness != null) roadAwareness.shutdown();
        if(crashDetector!=null)crashDetector.stop();
        if(audioRack!=null)audioRack.release();
        if(updateManager!=null)updateManager.destroy();
        if(riderCopilot!=null)riderCopilot.shutdown();
        mainHandler.removeCallbacksAndMessages(null);roadNameWorker.shutdownNow();
        super.onDestroy();
    }

    private class SpeedView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SenseWeatherScene weatherScene = new SenseWeatherScene();
        private final RectF arc = new RectF();
        private boolean metric = prefs.getBoolean("metric", false);
        private float accuracy = 999f;
        private long downAt;
        private float downX, downY;
        private final RectF weatherPanelRect = new RectF();
        private boolean resizingWeather;
        private boolean draggingWeather;
        private boolean weatherMoved;
        private int weatherResizeCorner;
        private float weatherStartX, weatherStartY;
        private final RectF weatherStartRect = new RectF();
        private float weatherLeftFraction = prefs.getFloat("weather_left", .045f);
        private float weatherTopFraction = prefs.getFloat("weather_top", .265f);
        private float weatherWidthFraction = prefs.getFloat("weather_width", .355f);
        private float weatherHeightFraction = prefs.getFloat("weather_height", .085f);
        private int weatherSkin = prefs.getInt("weather_skin", 0);
        private DashboardLayout dashboardLayout;
        private boolean editingDashboard;
        private int editingSection;
        private boolean resizingSection;
        private final RectF mediaSlot = new RectF(), gaugeSlot = new RectF(), navigationSlot = new RectF();
        private final RectF dasherCard = new RectF();
        private final RectF hudMicRect = new RectF();
        private final RectF hudUserRect = new RectF(), hudSettingsRect = new RectF();
        private float editStartX, editStartY;
        private final RectF editStartRect = new RectF();
        private boolean customizePressed;
        private int dashboardTheme = prefs.getInt("dashboard_theme", 0);
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
            if (nightMode != null && nightMode.isNight()) return Color.rgb(255, 128, 24);
            int[] themeAccents={0,Color.rgb(255,63,24),Color.rgb(0,255,210),Color.rgb(255,176,45),Color.rgb(125,210,255),Color.rgb(255,45,148),Color.rgb(220,235,240)};
            if(dashboardTheme>0&&dashboardTheme<themeAccents.length)return themeAccents[dashboardTheme];
            int[] colors = {Color.rgb(0,229,255), Color.rgb(105,255,120),
                    Color.rgb(255,174,0), Color.rgb(190,90,255), Color.rgb(255,65,90)};
            return colors[Math.max(0, Math.min(colors.length - 1, dashboardLayout.gaugeColor))];
        }

        private void saveDashboard() {
            prefs.edit().putString("dashboard_layout", dashboardLayout.encode()).apply();
        }

        private void showWeatherSkinPicker() {
            String[] skins = {"HTC Sense glass", "Neon cyan", "Minimal clear",
                    "Retro amber", "Storm radar"};
            new AlertDialog.Builder(MainActivity.this).setTitle("Weather widget skin")
                    .setSingleChoiceItems(skins, weatherSkin, (dialog, which) -> {
                        weatherSkin = which; prefs.edit().putInt("weather_skin", which).apply();
                        invalidate(); dialog.dismiss();
                    }).setNegativeButton("Cancel", null).show();
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
            String[] choices = {"Ride Center", "Dashboard layout", "Appearance & media",
                    "OpenNAV+", "Night mode & road safety", "Rider Copilot & voice",
                    "Delivery cockpit", "Dashboard profiles", "Software update"};
            String[] details={"Record rides, maintenance, crash check-in and groups",
                    "Arrange panels, resize widgets and apply presets",
                    "Themes, gauge design, album artwork and live weather",
                    "Offline maps, scooter-safe routing and saved destinations",
                    "Automatic dimming, OLED mode, speed and hazard warnings",
                    "Voice, spoken alerts, HUD reading and ride briefings",
                    "DoorDash offers, delivery shifts and mileage",
                    "Save and restore three complete cockpit arrangements",
                    "Check GitHub for a signed production release"};
            showPolishedMenu("SETTINGS","Personalize the entire riding system",choices,details,which -> {
                        if (which == 0) showRideCenter();
                        else if (which == 1) showLayoutMenu();
                        else if (which == 2) showAppearanceMenu();
                        else if (which == 3) showOpenNavSettings();
                        else if (which == 4) showRoadSettings();
                        else if (which == 5) showCopilotSettings();
                        else if (which == 6) showDasherSettings();
                        else if(which==7)showProfilesMenu();
                        else updateManager.check(true);
                    });
        }

                private void showOpenNavSettings(){OfflineRegionManager regions=new OfflineRegionManager(MainActivity.this);boolean avoid=prefs.getBoolean("opennav_avoid_highways",true);long used=MapTileCache.sizeBytes(MainActivity.this)/(1024L*1024L);int max=prefs.getInt("opennav_cache_mb",120);String[] choices={"Download offline region","Scooter-safe routing","Map cache size","Clear offline maps & cache","Recent destinations & favorites","Report missing address"};String[] details={regions.summary(),avoid?"ON • avoid motorways, toll roads and highway ramps":"OFF • fastest car-profile route",used+" MB used • "+max+" MB limit","Remove cached tiles, vectors and offline address index","Shown automatically in destination search • hold to favorite","Open an OpenStreetMap note at the current GPS position"};regions.shutdown();showPolishedMenu("OPENNAV+ OFFLINE","Navigation that survives weak signal",choices,details,which->{if(which==0)chooseOfflineRadius();else if(which==1){boolean next=!prefs.getBoolean("opennav_avoid_highways",true);prefs.edit().putBoolean("opennav_avoid_highways",next).apply();android.widget.Toast.makeText(MainActivity.this,next?"Scooter-safe routing enabled":"Fastest routing enabled",android.widget.Toast.LENGTH_SHORT).show();}else if(which==2)chooseCacheSize();else if(which==3){int n=MapTileCache.clear(MainActivity.this);android.widget.Toast.makeText(MainActivity.this,"Cleared "+n+" cached files",android.widget.Toast.LENGTH_LONG).show();}else if(which==4){new AddressAutocompleteDialog(MainActivity.this,lastGoodLocation,MainActivity.this::chooseNavigationApp).show();}else reportMissingAddress();});}
        private void chooseOfflineRadius(){if(lastGoodLocation==null){android.widget.Toast.makeText(MainActivity.this,"Wait for GPS lock before downloading a region",android.widget.Toast.LENGTH_LONG).show();return;}String[] radii={"1 mile • neighborhood","3 miles • city area","5 miles • extended area"};new AlertDialog.Builder(MainActivity.this).setTitle("Download around current location").setItems(radii,(d,w)->{int radius=new int[]{1,3,5}[w];android.widget.Toast.makeText(MainActivity.this,"Downloading OSM roads and addresses…",android.widget.Toast.LENGTH_LONG).show();OfflineRegionManager manager=new OfflineRegionManager(MainActivity.this);manager.download(lastGoodLocation,radius,(ok,message)->runOnUiThread(()->{android.widget.Toast.makeText(MainActivity.this,message,android.widget.Toast.LENGTH_LONG).show();manager.shutdown();}));}).setNegativeButton("Cancel",null).show();}
        private void chooseCacheSize(){String[] sizes={"64 MB","120 MB","256 MB","512 MB"};int current=prefs.getInt("opennav_cache_mb",120),selected=current<=64?0:current<=120?1:current<=256?2:3;new AlertDialog.Builder(MainActivity.this).setTitle("OpenNAV+ cache limit").setSingleChoiceItems(sizes,selected,(d,w)->{prefs.edit().putInt("opennav_cache_mb",new int[]{64,120,256,512}[w]).apply();d.dismiss();}).setNegativeButton("Cancel",null).show();}
        private void reportMissingAddress(){if(lastGoodLocation==null){android.widget.Toast.makeText(MainActivity.this,"GPS lock is required to place the map note",android.widget.Toast.LENGTH_LONG).show();return;}String url=String.format(Locale.US,"https://www.openstreetmap.org/note/new?lat=%.7f&lon=%.7f",lastGoodLocation.getLatitude(),lastGoodLocation.getLongitude());startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}

        private void showRideCenter(){boolean recording=rideRecorder.active(),crash=prefs.getBoolean("crash_detection",false);org.json.JSONArray history=rideRecorder.history();String last=history.length()==0?"No completed rides":rideRecorder.summary(history.optJSONObject(history.length()-1));String[] choices={recording?"Stop & save current ride":"Start ride recording","Ride history","Export ride log","Maintenance garage","Crash detection & check-in","RiderLink group rides"};String[] details={recording?"Finish the session and store its GPS trace":"Record route, time, speed and distance",history.length()+" rides • last: "+last,"Share CSV with mileage and ride statistics",String.format(Locale.US,"Service schedule • %.1f recorded miles",rideRecorder.lifetimeMiles()),crash?"ON • impact + sudden-stop detection":"OFF • tap to enable","Create or join a temporary live riding group"};showPolishedMenu("RIDE CENTER","Trips, scooter care and rider safety",choices,details,which->{if(which==0){if(rideRecorder.active()){org.json.JSONObject done=rideRecorder.stop();android.widget.Toast.makeText(MainActivity.this,"Ride saved • "+rideRecorder.summary(done),android.widget.Toast.LENGTH_LONG).show();}else{rideRecorder.start();android.widget.Toast.makeText(MainActivity.this,"Ride recording started",android.widget.Toast.LENGTH_LONG).show();}invalidate();}else if(which==1)showRideHistory();else if(which==2){Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/csv");share.putExtra(Intent.EXTRA_SUBJECT,"Scooter ride log");share.putExtra(Intent.EXTRA_TEXT,rideRecorder.exportCsv());startActivity(Intent.createChooser(share,"Export ride log"));}else if(which==3){Intent garage=new Intent(MainActivity.this,MaintenanceGarageActivity.class);garage.putExtra("miles",rideRecorder.lifetimeMiles());startActivity(garage);}else if(which==4){boolean enabled=!prefs.getBoolean("crash_detection",false);prefs.edit().putBoolean("crash_detection",enabled).apply();crashDetector.setEnabled(enabled);android.widget.Toast.makeText(MainActivity.this,enabled?"Crash check-in enabled":"Crash check-in disabled",android.widget.Toast.LENGTH_LONG).show();}else startActivity(new Intent(MainActivity.this,GroupRideActivity.class));});}
        private void showRideHistory(){org.json.JSONArray h=rideRecorder.history();String[] rows=new String[h.length()];java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("MMM d, yyyy • h:mm a",Locale.US);for(int i=0;i<h.length();i++){org.json.JSONObject r=h.optJSONObject(h.length()-1-i);rows[i]=(r==null?"Ride":f.format(new java.util.Date(r.optLong("started"))))+"\n"+rideRecorder.summary(r);}if(rows.length==0)rows=new String[]{"No completed rides yet"};new AlertDialog.Builder(MainActivity.this).setTitle("Ride history").setItems(rows,null).setPositiveButton("Done",null).show();}

        private void showPolishedMenu(String title,String subtitle,String[] labels,String[] details,MenuHandler handler){
            final int pad=UiKit.dp(MainActivity.this,16);
            LinearLayout panel=new LinearLayout(MainActivity.this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(pad,UiKit.dp(MainActivity.this,12),pad,UiKit.dp(MainActivity.this,16));
            panel.setBackground(UiKit.screenBackground(MainActivity.this));

            String headerIcon="SETTINGS".equals(title)?"⚙":"RIDER".equals(title)?"●":
                    title.contains("OPENNAV")?"➤":title.contains("VOICE")?"◈":"✦";
            LinearLayout header=UiKit.header(MainActivity.this,headerIcon,title,subtitle);
            panel.addView(header,new LinearLayout.LayoutParams(-1,UiKit.dp(MainActivity.this,76)));

            LinearLayout status=new LinearLayout(MainActivity.this);
            status.setGravity(android.view.Gravity.CENTER_VERTICAL);
            status.setPadding(UiKit.dp(MainActivity.this,12),0,UiKit.dp(MainActivity.this,12),0);
            status.setBackground(UiKit.rounded(MainActivity.this,Color.rgb(5,25,32),16,Color.rgb(24,66,78)));
            TextView statusText=UiKit.text(MainActivity.this,
                    "●  GPS "+(lastGoodLocation==null?"SEARCHING":"LOCKED")+"     ◈  COPILOT "+
                            (riderCopilot!=null&&riderCopilot.enabled()?"ON":"OFF"),
                    11,lastGoodLocation==null?UiKit.AMBER:UiKit.GREEN,true);
            statusText.setLetterSpacing(.04f);
            status.addView(statusText,new LinearLayout.LayoutParams(-1,-1));
            LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(-1,UiKit.dp(MainActivity.this,38));
            statusParams.setMargins(0,0,0,UiKit.dp(MainActivity.this,12));
            panel.addView(status,statusParams);

            for(int i=0;i<labels.length;i++){
                final int index=i;
                LinearLayout row=new LinearLayout(MainActivity.this);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(UiKit.dp(MainActivity.this,10),0,UiKit.dp(MainActivity.this,10),0);
                UiKit.card(row);

                TextView icon=new TextView(MainActivity.this);
                icon.setText(menuIcon(labels[i]));
                icon.setGravity(android.view.Gravity.CENTER);
                icon.setTextColor(UiKit.CYAN_BRIGHT);
                icon.setTextSize(22);
                icon.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
                icon.setBackground(UiKit.rounded(MainActivity.this,Color.rgb(7,45,55),16,Color.rgb(30,100,114)));
                row.addView(icon,new LinearLayout.LayoutParams(UiKit.dp(MainActivity.this,50),UiKit.dp(MainActivity.this,50)));

                LinearLayout copy=new LinearLayout(MainActivity.this);
                copy.setOrientation(LinearLayout.VERTICAL);
                copy.setGravity(android.view.Gravity.CENTER_VERTICAL);
                copy.setPadding(UiKit.dp(MainActivity.this,14),UiKit.dp(MainActivity.this,7),UiKit.dp(MainActivity.this,8),UiKit.dp(MainActivity.this,7));
                TextView name=UiKit.text(MainActivity.this,labels[i],16,UiKit.TEXT,true);
                TextView detail=UiKit.text(MainActivity.this,details==null?"":details[i],12,UiKit.MUTED,false);
                detail.setMaxLines(2);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(name,new LinearLayout.LayoutParams(-1,UiKit.dp(MainActivity.this,25)));
                copy.addView(detail,new LinearLayout.LayoutParams(-1,UiKit.dp(MainActivity.this,36)));
                row.addView(copy,new LinearLayout.LayoutParams(0,-1,1));

                TextView arrow=UiKit.text(MainActivity.this,"›",28,Color.rgb(117,158,170),false);
                arrow.setGravity(android.view.Gravity.CENTER);
                row.addView(arrow,new LinearLayout.LayoutParams(UiKit.dp(MainActivity.this,28),-1));
                row.setOnClickListener(v->{v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);if(activeMenu!=null)activeMenu.dismiss();handler.select(index);});

                LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,UiKit.dp(MainActivity.this,78));
                rowParams.setMargins(0,0,0,UiKit.dp(MainActivity.this,9));
                panel.addView(row,rowParams);
            }

            TextView footer=UiKit.text(MainActivity.this,"VERSION 8.1  •  RIDER COPILOT",10,Color.rgb(91,132,144),true);
            footer.setGravity(android.view.Gravity.CENTER);
            footer.setLetterSpacing(.13f);
            panel.addView(footer,new LinearLayout.LayoutParams(-1,UiKit.dp(MainActivity.this,32)));

            ScrollView scroll=new ScrollView(MainActivity.this);
            scroll.setFillViewport(true);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.addView(panel);
            activeMenu=new AlertDialog.Builder(MainActivity.this).setView(scroll).setNegativeButton("DONE",null).create();
            activeMenu.setOnShowListener(d->{android.view.Window window=activeMenu.getWindow();if(window!=null){window.setDimAmount(.82f);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.96f),WindowManager.LayoutParams.WRAP_CONTENT);}});
            activeMenu.show();
        }

        private String menuIcon(String label){
            String value=label.toLowerCase(Locale.US);
            if(value.contains("ride center"))return "◉";
            if(value.contains("layout"))return "▦";
            if(value.contains("appearance")||value.contains("theme"))return "✦";
            if(value.contains("opennav")||value.contains("navigation"))return "➤";
            if(value.contains("night")||value.contains("road")||value.contains("hazard"))return "◐";
            if(value.contains("copilot")||value.contains("voice")||value.contains("spoken"))return "◈";
            if(value.contains("delivery")||value.contains("dasher"))return "$";
            if(value.contains("profile"))return "▤";
            if(value.contains("update"))return "↻";
            if(value.contains("identity")||value.contains("scooter"))return "♙";
            if(value.contains("avatar"))return "◎";
            if(value.contains("riderlink")||value.contains("map"))return "⌖";
            if(value.contains("community")||value.contains("friends")||value.contains("clubs"))return "♟";
            if(value.contains("notification"))return "▣";
            if(value.contains("permission"))return "✓";
            if(value.contains("privacy"))return "◇";
            if(value.contains("weather"))return "☁";
            if(value.contains("media")||value.contains("album"))return "▶";
            if(value.contains("save"))return "↓";
            if(value.contains("load"))return "↑";
            return "•";
        }
        private AlertDialog activeMenu;

        private void showUserMenu(){
            boolean monitoring=prefs.getBoolean("notification_monitoring",false);
            RiderLinkClient account=new RiderLinkClient(MainActivity.this);
            boolean signedIn=account.signedIn();
            account.shutdown();
            String[] choices={"Rider identity & scooter","3D avatar studio","RiderLink live map",
                    "Rider community","Notification controls","App permissions","Privacy & rider safety"};
            String[] details={signedIn?"Signed in • profile, scooter and performance setup":"Sign in or create your RiderLink identity",
                    "Create or update your full Avaturn character",
                    "Nearby riders, live sharing, hazards and SOS",
                    "Friends, clubs, direct messages and group rides",
                    monitoring?"ACTIVE • select exactly what the HUD may process":"PRIVATE • notification monitoring is currently off",
                    "Manage location, microphone, camera and overlays",
                    "Foreground processing, sharing controls and SOS notice"};
            showPolishedMenu("RIDER","Profile, community and privacy",choices,details,which->{
                if(which==0)openRiderProfile();
                else if(which==1)openAvatarCreator();
                else if(which==2)startActivity(new Intent(MainActivity.this,RiderLinkActivity.class));
                else if(which==3)startActivity(new Intent(MainActivity.this,RiderLinkSocialActivity.class));
                else if(which==4)showNotificationControls();
                else if(which==5){Intent permissions=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));startActivity(permissions);}
                else new AlertDialog.Builder(MainActivity.this).setTitle("Privacy & rider safety").setMessage("Notification monitoring is off by default and stops whenever the app leaves the foreground. Message and call details remain on this phone. Live RiderLink location remains off until you enable it. RiderLink SOS does not contact 911 or emergency services.").setPositiveButton("Done",null).show();
            });
        }
        private void showNotificationControls(){
            LinearLayout panel=new LinearLayout(MainActivity.this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(UiKit.dp(MainActivity.this,18),UiKit.dp(MainActivity.this,8),UiKit.dp(MainActivity.this,18),UiKit.dp(MainActivity.this,10));panel.setBackgroundColor(UiKit.SURFACE);
            panel.addView(settingsAction("Private by default","The listener ignores and clears notification content whenever the master switch is off."));
            Switch master=settingsSwitch("Notification monitoring",prefs.getBoolean("notification_monitoring",false));Switch media=settingsSwitch("Media player controls",prefs.getBoolean("monitor_media",true));Switch messages=settingsSwitch("Message counter & details",prefs.getBoolean("monitor_messages",true));Switch calls=settingsSwitch("Call counter & caller",prefs.getBoolean("monitor_calls",true));Switch delivery=settingsSwitch("Delivery offer extraction",prefs.getBoolean("monitor_delivery",true));
            panel.addView(master);panel.addView(media);panel.addView(messages);panel.addView(calls);panel.addView(delivery);TextView access=settingsAction("Android notification access",hasMediaAccess()?"Permission granted • tap to review or revoke":"Permission not granted • tap to open Android settings");panel.addView(access);
            AlertDialog dialog=new AlertDialog.Builder(MainActivity.this).setTitle("Notification privacy").setView(panel).setPositiveButton("Done",null).setNegativeButton("Back",(d,w)->showUserMenu()).create();dialog.show();
            View.OnClickListener save=v->{boolean enabled=master.isChecked();prefs.edit().putBoolean("notification_monitoring",enabled).putBoolean("monitor_media",media.isChecked()).putBoolean("monitor_messages",messages.isChecked()).putBoolean("monitor_calls",calls.isChecked()).putBoolean("monitor_delivery",delivery.isChecked()).apply();media.setEnabled(enabled);messages.setEnabled(enabled);calls.setEnabled(enabled);delivery.setEnabled(enabled);sendBroadcast(new Intent(MediaAccessService.ACTION_MONITORING_CHANGED).setPackage(getPackageName()));if(enabled){if(!hasMediaAccess())openMediaAccessSettings();else if(Build.VERSION.SDK_INT>=24)android.service.notification.NotificationListenerService.requestRebind(mediaListenerComponent());}else{prefs.edit().remove("hud_title").remove("hud_body").remove("hud_type").putInt("hud_message_count",0).putInt("hud_call_count",0).apply();}refreshMedia();invalidate();};
            master.setOnClickListener(save);media.setOnClickListener(save);messages.setOnClickListener(save);calls.setOnClickListener(save);delivery.setOnClickListener(save);access.setOnClickListener(v->openMediaAccessSettings());media.setEnabled(master.isChecked());messages.setEnabled(master.isChecked());calls.setEnabled(master.isChecked());delivery.setEnabled(master.isChecked());
        }
        private void openRiderProfile(){RiderLinkClient account=new RiderLinkClient(MainActivity.this);boolean signed=account.signedIn();account.shutdown();Intent profile=new Intent(MainActivity.this,RiderLinkActivity.class);if(signed)profile.putExtra("open_profile",true);startActivity(profile);}
        private void openAvatarCreator(){RiderLinkClient account=new RiderLinkClient(MainActivity.this);boolean signed=account.signedIn();account.shutdown();if(signed)startActivity(new Intent(MainActivity.this,AvaturnActivity.class));else openRiderProfile();}

        private void showDasherSettings() {
            float density = getResources().getDisplayMetrics().density;
            LinearLayout panel = new LinearLayout(MainActivity.this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(Math.round(20 * density), Math.round(10 * density), Math.round(20 * density), Math.round(8 * density));
            panel.setBackgroundColor(Color.rgb(9, 12, 15));
            Switch enabled = settingsSwitch("Enable Dasher Mode", prefs.getBoolean("dasher_mode", false));
            panel.addView(enabled);
            Switch shift = settingsSwitch("Delivery shift / mileage logging", deliveryCockpit.shiftActive());
            panel.addView(shift);
            Switch voice = settingsSwitch("Speak incoming offers", prefs.getBoolean("dasher_voice", true));
            panel.addView(voice);
            TextView stats = settingsAction("Today's delivery cockpit",
                    String.format(Locale.US, "%.1f mi • %d offers • $%.2f offered", deliveryCockpit.dailyMiles(),
                            deliveryCockpit.dailyOffers(), deliveryCockpit.dailyOfferValue()));
            panel.addView(stats);
            TextView permission = settingsAction("Notification access",
                    hasMediaAccess() ? "Granted" : "Tap here to grant access");
            panel.addView(permission);
            boolean screenCapture=android.provider.Settings.Secure.getString(getContentResolver(),android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)!=null&&android.provider.Settings.Secure.getString(getContentResolver(),android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).contains(getPackageName()+"/"+DasherAccessibilityService.class.getName());
            TextView deepCapture=settingsAction("Full offer-screen capture",screenCapture?"Enabled • reads only the DoorDash Dasher screen during an active shift":"Optional • tap to enable in Android Accessibility settings");panel.addView(deepCapture);
            TextView open = settingsAction("Open DoorDash Dasher", "Accept and manage deliveries securely");
            panel.addView(open);
            TextView privacy = settingsAction("Privacy", "Order notifications stay only on this phone");
            privacy.setTextColor(Color.rgb(160, 185, 195)); panel.addView(privacy);
            new AlertDialog.Builder(MainActivity.this).setTitle("Delivery / Dasher mode")
                    .setView(panel).setPositiveButton("Done", null)
                    .setNegativeButton("Back", (dialog, which) -> showDashboardCustomizer()).show();
            enabled.setOnCheckedChangeListener((button, checked) -> {
                prefs.edit().putBoolean("dasher_mode", checked).apply();
                if (checked && !hasMediaAccess()) openMediaAccessSettings();
                invalidate();
            });
            shift.setOnCheckedChangeListener((button, checked) -> { deliveryCockpit.setShiftActive(checked); invalidate(); });
            voice.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("dasher_voice", checked).apply());
            permission.setOnClickListener(v -> openMediaAccessSettings());
            deepCapture.setOnClickListener(v->{prefs.edit().putBoolean("dasher_screen_capture",true).apply();startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));});
            open.setOnClickListener(v -> openDasherApp());
        }

        private void openDasherApp() {
            String packageName = prefs.getString("dasher_package", "com.doordash.driverapp");
            Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch != null) startActivity(launch);
            else android.widget.Toast.makeText(MainActivity.this, "Install or open the DoorDash Dasher app first", android.widget.Toast.LENGTH_LONG).show();
        }

        private void drawDasherCard(Canvas c, float w, float h, float scale) {
            if (!prefs.getBoolean("dasher_mode", false)) return;
            String title = prefs.getString("dasher_title", "");
            String body = prefs.getString("dasher_body", "");
            float offerPay = prefs.getFloat("dasher_offer_pay", 0f);
            float offerMiles = prefs.getFloat("dasher_offer_miles", 0f);
            float perMile = prefs.getFloat("dasher_offer_per_mile", 0f);
            float tip = prefs.getFloat("dasher_offer_tip", 0f);
            int items = prefs.getInt("dasher_offer_items", 0), minutes = prefs.getInt("dasher_offer_minutes", 0);
            String restaurant = prefs.getString("dasher_restaurant", title);
            String pickup = prefs.getString("dasher_pickup", ""), dropoff = prefs.getString("dasher_dropoff", "");
            String captureSource=prefs.getString("dasher_capture_source","notification"),missing=prefs.getString("dasher_missing_fields","");
            long age = System.currentTimeMillis() - prefs.getLong("dasher_time", 0L);
            boolean active = (!TextUtils.isEmpty(title) || !TextUtils.isEmpty(body)) && age < 30L * 60L * 1000L;
            dasherCard.set(w * .025f, h * .245f, w * .975f, h * .405f);
            paint.setColor(active ? Color.argb(242, 155, 12, 25) : Color.argb(225, 14, 20, 25));
            c.drawRoundRect(dasherCard, 18f * scale, 18f * scale, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f * scale);
            paint.setColor(active ? Color.rgb(255, 70, 82) : Color.rgb(85, 105, 115));
            c.drawRoundRect(dasherCard, 18f * scale, 18f * scale, paint); paint.setStyle(Paint.Style.FILL);
            text(c, "DASHER COCKPIT", w * .055f, h * .270f, 12f * scale, active ? Color.WHITE : Color.rgb(165,180,188), Paint.Align.LEFT, true);
            text(c, deliveryCockpit.shiftActive() ? "SHIFT ON" : "SHIFT OFF", w * .945f, h * .270f, 11f * scale,
                    deliveryCockpit.shiftActive() ? Color.rgb(112,255,145) : Color.rgb(190,195,198), Paint.Align.RIGHT, true);
            if (active) {
                text(c, ellipsize(restaurant, 46), w * .055f, h * .296f, 16f * scale, Color.WHITE, Paint.Align.LEFT, true);
                int scoreColor = perMile >= 2f ? Color.rgb(105,255,130) : perMile >= 1f ? Color.rgb(255,195,65) : Color.rgb(255,105,105);
                String metrics = (offerPay > 0 ? String.format(Locale.US, "$%.2f", offerPay) : "PAY --") + "  •  " +
                        (offerMiles > 0 ? String.format(Locale.US, "%.1f MI", offerMiles) : "MI --") + "  •  " +
                        (perMile > 0 ? String.format(Locale.US, "$%.2f/MI", perMile) : "RATE --") +
                        (tip > 0 ? String.format(Locale.US, "  •  TIP $%.2f", tip) : "  •  TIP --");
                text(c, metrics, w * .055f, h * .322f, 14f * scale, scoreColor, Paint.Align.LEFT, true);
                String timing = (items > 0 ? items + " ITEMS" : "ITEMS --") + "  •  " + (minutes > 0 ? minutes + " MIN" : "TIME --");
                text(c, timing, w * .945f, h * .296f, 10f * scale, Color.rgb(255,215,220), Paint.Align.RIGHT, true);
                text(c, "PICKUP  " + ellipsize(TextUtils.isEmpty(pickup) ? restaurant : pickup, 55), w * .055f, h * .347f,
                        10f * scale, Color.rgb(235,238,240), Paint.Align.LEFT, false);
                text(c, "DROP  " + ellipsize(TextUtils.isEmpty(dropoff) ? "Open Dasher for address" : dropoff, 57), w * .055f, h * .369f,
                        10f * scale, Color.rgb(235,238,240), Paint.Align.LEFT, false);
                if(!TextUtils.isEmpty(missing))text(c,"CAPTURED VIA "+captureSource.toUpperCase(Locale.US)+" • MISSING: "+ellipsize(missing,38),w*.945f,h*.383f,7f*scale,Color.rgb(255,195,205),Paint.Align.RIGHT,true);
            } else {
                text(c, hasMediaAccess() ? "Waiting for DoorDash orders…" : "Tap to grant notification access",
                        w * .055f, h * .315f, 14f * scale, Color.rgb(175,194,202), Paint.Align.LEFT, false);
            }
            text(c, String.format(Locale.US, "TODAY %.1f MI  •  SHIFT %.1f MI / %d MIN  •  %d OFFERS  •  $%.2f OFFERED",
                    deliveryCockpit.dailyMiles(), deliveryCockpit.shiftMiles(), deliveryCockpit.shiftMinutes(),
                    deliveryCockpit.dailyOffers(), deliveryCockpit.dailyOfferValue()),
                    w * .5f, h * .397f, 8.5f * scale, Color.rgb(215,225,230), Paint.Align.CENTER, true);
        }

        private void showLayoutMenu() {
            String[] choices = {editingDashboard ? "✓ Finish drag & resize" : "✥ Drag & resize sections",
                    "Full portrait", "Compact portrait", "Full landscape", "Compact landscape"};
            String[] details={editingDashboard?"Save section positions and return":"Reposition media, gauge and navigation","Maximum information for upright mounting","Larger gauge with fewer distractions","Wide-screen dashboard arrangement","Minimal wide-screen riding layout"};
            showPolishedMenu("LAYOUT & PRESETS","Choose a preset or build your own",choices,details,which -> {
                if (which == 0) { editingDashboard = !editingDashboard; editingSection = 0; invalidate(); }
                else applyPreset(which >= 3, which == 2 || which == 4);
            });
        }

        private void showAppearanceMenu() {
            String[] choices = {"Dashboard theme", "Album-art transparency", "Gauge color", "Gauge style", "Weather widget skin", "Live weather provider"};
            String[] themeNames={"Classic dashboard","Inferno tuner","Neon grid","Retro amber","Arctic glass","Synthwave","Midnight OLED"};String[] details={themeNames[Math.max(0,Math.min(themeNames.length-1,dashboardTheme))],Math.round(dashboardLayout.albumAlpha/255f*100)+"% background strength",new String[]{"Cyan","Lime","Amber","Purple","Red"}[Math.max(0,Math.min(4,dashboardLayout.gaugeColor))],new String[]{"Classic arc","Dual arc","Minimal"}[Math.max(0,Math.min(2,dashboardLayout.gaugeStyle))],new String[]{"HTC Sense glass","Neon cyan","Minimal clear","Retro amber","Storm radar"}[Math.max(0,Math.min(4,weatherSkin))],weatherRepository.providerName()+" • "+weatherRepository.locationName()};
            showPolishedMenu("APPEARANCE & MEDIA","Visual system",choices,details,which -> {
                if (which == 0) {
                    String[] themes = {"Classic dashboard", "Inferno tuner", "Neon grid", "Retro amber", "Arctic glass", "Synthwave", "Midnight OLED"};
                    new AlertDialog.Builder(MainActivity.this).setTitle("Dashboard theme")
                            .setSingleChoiceItems(themes, dashboardTheme, (d, item) -> {
                                dashboardTheme = item; prefs.edit().putInt("dashboard_theme", item).apply(); invalidate(); d.dismiss();
                            }).show();
                } else if (which == 1) {
                    String[] levels = {"Subtle · 25%", "Balanced · 45%", "Bold · 65%", "Maximum · 85%"};
                    new AlertDialog.Builder(MainActivity.this).setTitle("Album-art background")
                            .setSingleChoiceItems(levels, Math.max(0, Math.min(3, (dashboardLayout.albumAlpha - 50) / 50)),
                                    (d, item) -> { dashboardLayout.albumAlpha = new int[]{64,115,166,217}[item]; saveDashboard(); invalidate(); d.dismiss(); }).show();
                } else if (which == 2) {
                    String[] colors = {"Cyan", "Lime", "Amber", "Purple", "Red"};
                    new AlertDialog.Builder(MainActivity.this).setTitle("Gauge color")
                            .setSingleChoiceItems(colors, dashboardLayout.gaugeColor,
                                    (d, item) -> { dashboardLayout.gaugeColor = item; saveDashboard(); invalidate(); d.dismiss(); }).show();
                } else if (which == 3) {
                    String[] styles = {"Classic arc", "Dual arc", "Minimal"};
                    new AlertDialog.Builder(MainActivity.this).setTitle("Gauge style")
                            .setSingleChoiceItems(styles, dashboardLayout.gaugeStyle,
                                    (d, item) -> { dashboardLayout.gaugeStyle = item; saveDashboard(); invalidate(); d.dismiss(); }).show();
                } else if (which == 4) showWeatherSkinPicker();
                else showWeatherSettings();
            });
        }

        private void showProfilesMenu() {
            String[] choices = {"Save current layout", "Load saved layout"};
            showPolishedMenu("SAVED PROFILES","Three reusable cockpit configurations",choices,new String[]{"Store the current section arrangement","Restore a previously saved arrangement"},which->showProfilePicker(which==0));
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

        private int themePanelColor(){int[] colors={Color.argb(205,4,10,14),Color.argb(235,16,5,4),Color.argb(222,0,18,22),Color.argb(230,30,18,4),Color.argb(205,9,30,45),Color.argb(225,22,5,38),Color.argb(238,0,0,0)};return colors[Math.max(0,Math.min(colors.length-1,dashboardTheme))];}
        private void drawThemeBackground(Canvas c,float w,float h,float scale){if(dashboardTheme==1){drawInfernoBackground(c,w,h,scale);return;}int[][] gradients={{Color.rgb(4,8,12),Color.rgb(7,23,31)},{0,0},{Color.rgb(0,9,12),Color.rgb(0,42,40)},{Color.rgb(18,10,3),Color.rgb(58,31,6)},{Color.rgb(5,25,42),Color.rgb(33,71,96)},{Color.rgb(12,2,28),Color.rgb(66,5,72)},{Color.BLACK,Color.rgb(4,4,5)}};int theme=Math.max(0,Math.min(6,dashboardTheme));paint.setStyle(Paint.Style.FILL);paint.setShader(new LinearGradient(0,0,w,h,gradients[theme][0],gradients[theme][1],Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,paint);paint.setShader(null);paint.setStyle(Paint.Style.STROKE);float gap=(theme==3?34f:theme==4?46f:theme==6?70f:28f)*scale;paint.setStrokeWidth(Math.max(1f,scale));paint.setColor(Color.argb(theme==6?16:32,Color.red(accentColor()),Color.green(accentColor()),Color.blue(accentColor())));if(theme==2||theme==4){for(float x=0;x<w;x+=gap)c.drawLine(x,0,x,h,paint);for(float y=0;y<h;y+=gap)c.drawLine(0,y,w,y,paint);}else if(theme==3){for(float y=0;y<h;y+=gap)c.drawLine(0,y,w,y,paint);}else if(theme==5){float horizon=h*.66f;for(int i=-7;i<=7;i++)c.drawLine(w*.5f,horizon,w*.5f+i*w*.17f,h,paint);for(float y=horizon;y<h;y+=gap)c.drawLine(0,y,w,y,paint);}else if(theme==6){for(float r=gap;r<Math.max(w,h);r+=gap)c.drawCircle(w*.5f,h*.49f,r,paint);}paint.setStyle(Paint.Style.FILL);String[] labels={"OPENNAV+ RIDER COCKPIT","","NEON GRID // LIVE RIDE","RETRO TOURING // OPENNAV+","ARCTIC GLASS // PRECISION","SYNTHWAVE // NIGHT RIDE","MIDNIGHT OLED // LOW POWER"};if(!labels[theme].isEmpty())text(c,labels[theme],w*.5f,h*.019f,9f*scale,accentColor(),Paint.Align.CENTER,true);}

        private void drawInfernoBackground(Canvas c, float w, float h, float scale) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, 0, w, h,
                    Color.rgb(3, 3, 4), Color.rgb(35, 3, 1), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint); paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1f, scale));
            paint.setColor(Color.argb(32, 255, 80, 28));
            float gap = 28f * scale;
            for (float x = -h; x < w; x += gap) c.drawLine(x, 0, x + h, h, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, 0, w, 0,
                    Color.argb(235, 20, 20, 22), Color.argb(235, 92, 12, 2), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h * .025f, paint); paint.setShader(null);
            text(c, "STANKHOUSE  //  INFERNO PERFORMANCE", w * .5f, h * .019f,
                    10f * scale, Color.rgb(255, 145, 72), Paint.Align.CENTER, true);
        }

        private void drawInfernoGauge(Canvas c, float w, float h, float cx, float radius, float shown, float limit, float scale) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStrokeWidth(3f * scale); paint.setColor(Color.rgb(126, 128, 132));
            RectF outer = new RectF(cx - radius - 11f * scale, h * .49f - radius - 11f * scale,
                    cx + radius + 11f * scale, h * .49f + radius + 11f * scale);
            c.drawArc(outer, 140, 260, false, paint);
            paint.setStrokeWidth(8f * scale);
            paint.setShader(new LinearGradient(outer.left, outer.top, outer.right, outer.bottom,
                    Color.rgb(255, 184, 70), Color.rgb(165, 8, 0), Shader.TileMode.CLAMP));
            c.drawArc(outer, 145, Math.min(shown / limit, 1f) * 250f, false, paint); paint.setShader(null);
            paint.setStrokeWidth(2f * scale);
            for (int i = 0; i <= 20; i++) {
                double angle = Math.toRadians(145 + i * 12.5);
                float r1 = radius - (i % 5 == 0 ? 16f : 9f) * scale;
                float r2 = radius + 2f * scale;
                float centerY = h * .49f;
                paint.setColor(i >= 16 ? Color.rgb(255, 48, 25) : Color.rgb(210, 214, 216));
                c.drawLine(cx + (float) Math.cos(angle) * r1, centerY + (float) Math.sin(angle) * r1,
                        cx + (float) Math.cos(angle) * r2, centerY + (float) Math.sin(angle) * r2, paint);
            }
            double needleAngle = Math.toRadians(145 + Math.min(shown / limit, 1f) * 250f);
            float centerY = h * .49f;
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(5f * scale); paint.setColor(Color.rgb(255, 55, 24));
            paint.setShadowLayer(9f * scale, 0, 0, Color.rgb(255, 60, 18));
            c.drawLine(cx, centerY, cx + (float) Math.cos(needleAngle) * radius * .72f,
                    centerY + (float) Math.sin(needleAngle) * radius * .72f, paint);
            paint.clearShadowLayer(); paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(190, 194, 197));
            c.drawCircle(cx, centerY, 11f * scale, paint); paint.setColor(Color.rgb(35, 2, 0)); c.drawCircle(cx, centerY, 6f * scale, paint);
        }

        private void drawInfernoFrames(Canvas c, float scale) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f * scale); paint.setColor(Color.argb(190, 255, 67, 24));
            c.drawRoundRect(mediaSlot, 18f * scale, 18f * scale, paint);
            c.drawRoundRect(weatherPanelRect, 12f * scale, 12f * scale, paint);
            c.drawRoundRect(navigationSlot, 12f * scale, 12f * scale, paint);
            paint.setStyle(Paint.Style.FILL);
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
        private void drawAnimatedCover(Canvas c, Bitmap bitmap, RectF destination, int alpha, float strength) {
            if (!isPlaying()) { drawCover(c, bitmap, destination, alpha); return; }
            double time = SystemClock.uptimeMillis() / 1000.0;
            float pulse = 1f + strength * (.55f + .45f * (float) Math.sin(time * 1.35));
            float driftX = destination.width() * strength * .38f * (float) Math.sin(time * .31);
            float driftY = destination.height() * strength * .28f * (float) Math.cos(time * .27);
            int save = c.save();
            c.clipRect(destination);
            c.translate(destination.centerX() + driftX, destination.centerY() + driftY);
            c.rotate(strength * 16f * (float) Math.sin(time * .19));
            c.scale(pulse, pulse);
            c.translate(-destination.centerX(), -destination.centerY());
            drawCover(c, bitmap, destination, alpha);
            c.restoreToCount(save);
        }
        private void drawMediaPanel(Canvas c, float w, float h, float scale) {
            float top = h * .025f, bottom = h * .245f;
            RectF panel = new RectF(w * .035f, top, w * .965f, bottom);
            paint.setStyle(Paint.Style.FILL); paint.setColor(themePanelColor());
            c.drawRoundRect(panel, 24f * scale, 24f * scale, paint);
            if (dashboardTheme > 0) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f * scale); paint.setColor(Color.argb(185,Color.red(accentColor()),Color.green(accentColor()),Color.blue(accentColor())));
                c.drawRoundRect(panel, 24f * scale, 24f * scale, paint); paint.setStyle(Paint.Style.FILL);
            }
            if (!hasMediaAccess()) {
                text(c, "♫  ENABLE MUSIC CONTROLS", w * .5f, h * .115f, 21f * scale,
                        accentColor(), Paint.Align.CENTER, true);
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
            if (art != null) drawAnimatedCover(c, art, artRect, 255, .035f);
            else {
                paint.setColor(Color.rgb(20, 39, 49)); c.drawRoundRect(artRect, 12f * scale, 12f * scale, paint);
                text(c, "♫", artRect.centerX(), artRect.centerY() + 15f * scale, 42f * scale,
                        accentColor(), Paint.Align.CENTER, true);
            }
            String title = metadataText(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String artist = metadataText(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            float infoX = artRect.right + 14f * scale;
            text(c, ellipsize(title, 25), infoX, top + 39f * scale, 20f * scale, Color.WHITE, Paint.Align.LEFT, true);
            text(c, ellipsize(artist, 28), infoX, top + 65f * scale, 14f * scale,
                    Color.rgb(186, 204, 213), Paint.Align.LEFT, false);
            text(c, "TAP NOW PLAYING TO OPEN", infoX, top + 84f * scale, 8f * scale,
                    Color.rgb(105, 150, 164), Paint.Align.LEFT, true);
            float buttonY = bottom - 34f * scale;
            text(c, audioRack.eqEnabled() ? "EQ•" : "EQ", w * .34f, buttonY, 13f * scale,
                    audioRack.eqEnabled() ? accentColor() : Color.rgb(175,195,204), Paint.Align.CENTER, true);
            text(c, audioRack.ampEnabled() ? "AMP•" : "AMP", w * .43f, buttonY, 12f * scale,
                    audioRack.ampEnabled() ? accentColor() : Color.rgb(175,195,204), Paint.Align.CENTER, true);
            text(c, "|◀", w * .53f, buttonY, 23f * scale, Color.WHITE, Paint.Align.CENTER, true);
            text(c, isPlaying() ? "Ⅱ" : "▶", w * .64f, buttonY, 29f * scale,
                    accentColor(), Paint.Align.CENTER, true);
            text(c, "▶|", w * .75f, buttonY, 23f * scale, Color.WHITE, Paint.Align.CENTER, true);
            paint.setColor(Color.argb(95,0,229,255));
            c.drawRoundRect(new RectF(w*.815f,buttonY-24f*scale,w*.965f,buttonY+12f*scale),18f*scale,18f*scale,paint);
            text(c, "▰  " + musicVolumePercent() + "%", w * .89f, buttonY-1f*scale,
                    12f * scale, Color.WHITE, Paint.Align.CENTER, true);
        }

        private void drawWeatherPanel(Canvas c, float w, float h, float scale) {
            weatherWidthFraction = Math.max(.20f, Math.min(.80f, weatherWidthFraction));
            weatherHeightFraction = Math.max(.06f, Math.min(.28f, weatherHeightFraction));
            weatherLeftFraction = Math.max(.01f, Math.min(.99f - weatherWidthFraction, weatherLeftFraction));
            weatherTopFraction = Math.max(.01f, Math.min(.90f - weatherHeightFraction, weatherTopFraction));
            weatherPanelRect.set(w * weatherLeftFraction, h * weatherTopFraction,
                    w * (weatherLeftFraction + weatherWidthFraction), h * (weatherTopFraction + weatherHeightFraction));
            RectF panel = weatherPanelRect;
            float panelScale = Math.max(.82f, Math.min(1.55f, panel.height() / (h * .085f)));
            float left = panel.left, top = panel.top, pw = panel.width(), ph = panel.height();
            paint.setStyle(Paint.Style.FILL);
            int accent = weatherSkin == 3 ? Color.rgb(255, 174, 0) : weatherSkin == 4 ?
                    Color.rgb(55, 150, 255) : Color.rgb(0, 229, 255);
            int primary = weatherSkin == 3 ? Color.rgb(255, 218, 130) : Color.WHITE;
            int secondary = weatherSkin == 2 ? Color.rgb(220, 230, 235) :
                    weatherSkin == 3 ? Color.rgb(255, 190, 70) : Color.rgb(184, 204, 214);
            if (dashboardTheme == 1) {
                accent = Color.rgb(255, 83, 28); primary = Color.rgb(255, 238, 220); secondary = Color.rgb(222, 152, 122);
                paint.setShader(new LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
                        Color.argb(242, 48, 9, 3), Color.argb(230, 7, 5, 5), Shader.TileMode.CLAMP));
            } else if (weatherSkin == 0) {
                paint.setShader(new LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
                        Color.argb(232, 12, 34, 45), Color.argb(205, 1, 8, 13), Shader.TileMode.CLAMP));
            } else if (weatherSkin == 1) paint.setColor(Color.argb(225, 0, 12, 18));
            else if (weatherSkin == 2) paint.setColor(Color.argb(125, 0, 5, 8));
            else if (weatherSkin == 3) paint.setColor(Color.argb(235, 28, 17, 2));
            else {
                paint.setShader(new LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
                        Color.argb(238, 5, 35, 72), Color.argb(225, 18, 5, 42), Shader.TileMode.CLAMP));
            }
            c.drawRoundRect(panel, 18f * scale, 18f * scale, paint);
            paint.setShader(null);
            if (weatherSkin == 0) drawSenseWeatherEffects(c, panel, scale);
            if (weatherSkin == 1 || weatherSkin == 3) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth((weatherSkin == 1 ? 3f : 2f) * scale);
                paint.setColor(accent); c.drawRoundRect(panel, 18f * scale, 18f * scale, paint); paint.setStyle(Paint.Style.FILL);
            } else if (weatherSkin == 4) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f * scale);
                for (int i = 1; i <= 3; i++) { paint.setColor(Color.argb(50, 70, 190, 255));
                    c.drawCircle(panel.right - ph * .55f, panel.centerY(), ph * .13f * i, paint); }
                paint.setStyle(Paint.Style.FILL);
            }
            text(c, weatherVoice.enabled() ? "🔊" : "🔇", panel.right - pw * .055f,
                    panel.top + ph * .24f, 10f * scale * panelScale, primary, Paint.Align.RIGHT, true);
            if (weatherData == null) {
                text(c, "◌  WEATHER", left + pw * .085f, top + ph * .44f, 15f * scale * panelScale,
                        accent, Paint.Align.LEFT, true);
                text(c, "Loading…", left + pw * .085f, top + ph * .76f, 12f * scale * panelScale,
                        secondary, Paint.Align.LEFT, false);
            } else {
                float iconBob = weatherSkin == 0 ? (float) Math.sin(SystemClock.uptimeMillis() / 620.0) * ph * .025f : 0f;
                text(c, weatherData.icon(), left + pw * .09f, top + ph * .67f + iconBob, 34f * scale * panelScale,
                        weatherSkin == 3 ? accent : Color.rgb(255, 193, 7), Paint.Align.LEFT, true);
                text(c, String.format(Locale.US, "%.0f°", weatherData.temperature), left + pw * .30f,
                        top + ph * .55f, 27f * scale * panelScale, primary, Paint.Align.LEFT, true);
                text(c, ellipsize(weatherData.condition(), Math.max(9, Math.round(17 * weatherWidthFraction / .355f))),
                        left + pw * .30f, top + ph * .84f, 11f * scale * panelScale,
                        secondary, Paint.Align.LEFT, false);
                String imminent = weatherData.upcomingLabel();
                text(c, imminent.isEmpty() ? "☂ " + weatherData.rainChance + "%" : "⚠ " + imminent,
                        left + pw * .68f, top + ph * .43f,
                        12f * scale * panelScale, accent, Paint.Align.LEFT, true);
                text(c, String.format(Locale.US, "%s %.0f mph", weatherData.windCompass(), weatherData.windSpeed),
                        left + pw * .68f, top + ph * .78f, 11f * scale * panelScale,
                        secondary, Paint.Align.LEFT, false);
                long ageMinutes = Math.max(0, (System.currentTimeMillis() - weatherData.updatedAt) / 60000L);
                text(c, (ageMinutes < 2 ? "LIVE" : ageMinutes + "m ago") + " • " + weatherRepository.providerName(),
                        panel.right - pw * .04f, panel.bottom - ph * .06f, 7f * scale * panelScale,
                        secondary, Paint.Align.RIGHT, true);
            }
            paint.setColor(accent);
            paint.setStrokeWidth(Math.max(2f, 2f * scale));
            paint.setStyle(Paint.Style.STROKE);
            float grip = Math.max(12f * scale, Math.min(panel.width(), panel.height()) * .18f);
            c.drawLine(panel.left, panel.top + grip, panel.left + grip, panel.top, paint);
            c.drawLine(panel.right - grip, panel.top, panel.right, panel.top + grip, paint);
            c.drawLine(panel.left, panel.bottom - grip, panel.left + grip, panel.bottom, paint);
            c.drawLine(panel.right - grip, panel.bottom, panel.right, panel.bottom - grip, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawHud(Canvas c,float w,float h,float scale){
            float cy=h*.045f,r=Math.max(18f*scale,Math.min(w,h)*.032f);hudUserRect.set(w*.07f-r,cy-r,w*.07f+r,cy+r);hudSettingsRect.set(w*.17f-r,cy-r,w*.17f+r,cy+r);hudMicRect.set(w*.705f-r,cy-r,w*.705f+r,cy+r);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.argb(72,5,25,33));c.drawCircle(hudUserRect.centerX(),cy,r,paint);c.drawCircle(hudSettingsRect.centerX(),cy,r,paint);drawUserIcon(c,hudUserRect.centerX(),cy,scale);drawSettingsIcon(c,hudSettingsRect.centerX(),cy,scale);
            paint.setColor(hudVoiceStatusUntil>SystemClock.elapsedRealtime()?Color.argb(205,255,64,74):Color.argb(105,0,135,158));c.drawCircle(hudMicRect.centerX(),hudMicRect.centerY(),r,paint);drawMicrophone(c,hudMicRect.centerX(),hudMicRect.centerY(),scale);
            int messages=prefs.getInt("hud_message_count",0),calls=prefs.getInt("hud_call_count",0);drawHudIcon(c,"✉",w*.825f,cy,messages,scale);drawHudIcon(c,"☎",w*.935f,cy,calls,scale);
            long age=System.currentTimeMillis()-prefs.getLong("hud_time",0);if(age<12000L){String kind=prefs.getString("hud_type","message");String title=prefs.getString("hud_title","");String body=prefs.getString("hud_body","");RectF banner=new RectF(w*.08f,h*.088f,w*.92f,h*.145f);paint.setColor(Color.argb(184,3,22,29));c.drawRoundRect(banner,15f*scale,15f*scale,paint);text(c,("call".equals(kind)?"CALL  /  ":"MESSAGE  /  ")+ellipsize(title,30),banner.left+w*.025f,h*.112f,13f*scale,Color.rgb(0,229,255),Paint.Align.LEFT,true);text(c,ellipsize(body,64),banner.left+w*.025f,h*.136f,10f*scale,Color.WHITE,Paint.Align.LEFT,false);postInvalidateDelayed(500L);}
            if(hudVoiceStatusUntil>SystemClock.elapsedRealtime()){RectF statusBox=new RectF(w*.22f,h*.15f,w*.78f,h*.185f);paint.setColor(Color.argb(238,0,80,96));c.drawRoundRect(statusBox,12f*scale,12f*scale,paint);text(c,hudVoiceStatus,w*.5f,h*.174f,11f*scale,Color.WHITE,Paint.Align.CENTER,true);postInvalidateDelayed(250L);}
        }
        private void drawUserIcon(Canvas c,float x,float y,float scale){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.3f*scale);paint.setColor(Color.WHITE);c.drawCircle(x,y-7f*scale,6f*scale,paint);RectF shoulders=new RectF(x-11f*scale,y+2f*scale,x+11f*scale,y+18f*scale);c.drawArc(shoulders,190,160,false,paint);paint.setStyle(Paint.Style.FILL);}
        private void drawSettingsIcon(Canvas c,float x,float y,float scale){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.3f*scale);paint.setColor(Color.WHITE);c.drawCircle(x,y,10f*scale,paint);c.drawCircle(x,y,3f*scale,paint);for(int i=0;i<8;i++){double a=i*Math.PI/4;c.drawLine(x+(float)Math.cos(a)*10f*scale,y+(float)Math.sin(a)*10f*scale,x+(float)Math.cos(a)*14f*scale,y+(float)Math.sin(a)*14f*scale,paint);}paint.setStyle(Paint.Style.FILL);}
        private void drawMicrophone(Canvas c,float x,float y,float scale){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.4f*scale);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(Color.WHITE);RectF capsule=new RectF(x-5f*scale,y-10f*scale,x+5f*scale,y+5f*scale);c.drawRoundRect(capsule,6f*scale,6f*scale,paint);Path stem=new Path();stem.moveTo(x-10f*scale,y);stem.quadTo(x-10f*scale,y+12f*scale,x,y+12f*scale);stem.quadTo(x+10f*scale,y+12f*scale,x+10f*scale,y);c.drawPath(stem,paint);c.drawLine(x,y+12f*scale,x,y+17f*scale,paint);c.drawLine(x-6f*scale,y+17f*scale,x+6f*scale,y+17f*scale,paint);paint.setStyle(Paint.Style.FILL);}
        private void drawHudIcon(Canvas c,String icon,float x,float y,int count,float scale){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.4f*scale);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(Color.WHITE);if("✉".equals(icon)){RectF box=new RectF(x-13f*scale,y-9f*scale,x+13f*scale,y+10f*scale);c.drawRoundRect(box,3f*scale,3f*scale,paint);c.drawLine(box.left+2f*scale,box.top+2f*scale,x,y+2f*scale,paint);c.drawLine(x,y+2f*scale,box.right-2f*scale,box.top+2f*scale,paint);}else{Path phone=new Path();phone.moveTo(x-10f*scale,y-11f*scale);phone.quadTo(x-16f*scale,y-5f*scale,x-7f*scale,y+5f*scale);phone.quadTo(x+3f*scale,y+16f*scale,x+11f*scale,y+9f*scale);phone.lineTo(x+6f*scale,y+3f*scale);phone.lineTo(x,y+7f*scale);phone.quadTo(x-5f*scale,y+3f*scale,x-7f*scale,y);phone.lineTo(x-3f*scale,y-5f*scale);phone.close();c.drawPath(phone,paint);}paint.setStyle(Paint.Style.FILL);if(count>0){float bx=x+17f*scale,by=y-14f*scale,br=10f*scale;paint.setColor(Color.rgb(230,35,50));c.drawCircle(bx,by,br,paint);text(c,count>99?"99+":String.valueOf(count),bx,by+4f*scale,count>99?7f*scale:9f*scale,Color.WHITE,Paint.Align.CENTER,true);}}

        private void drawSenseWeatherEffects(Canvas c, RectF panel, float scale) {
            if (weatherData == null) return;
            long now = SystemClock.uptimeMillis();
            int code = weatherData.weatherCode;
            boolean thunder = code >= 95;
            boolean snow = (code >= 71 && code <= 77) || (code >= 85 && code <= 86);
            boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
            boolean cloud = code >= 1 && code <= 3 || rain || thunder;
            int save = c.save(); Path weatherClip = new Path();
            weatherClip.addRoundRect(panel, 18f * scale, 18f * scale, Path.Direction.CW); c.clipPath(weatherClip);
            float pw = panel.width(), ph = panel.height();
            if (code == 0) {
                float pulse = 1f + .09f * (float) Math.sin(now / 700.0);
                for (int i = 4; i >= 1; i--) {
                    paint.setColor(Color.argb(9 + i * 6, 255, 210, 70));
                    c.drawCircle(panel.left + pw * .14f, panel.centerY(), ph * (.14f + i * .09f) * pulse, paint);
                }
            }
            // Cloud blobs were intentionally removed. Their overlapping translucent geometry
            // looked like retained/torn frames on high-refresh displays.
            if (rain || thunder) {
                paint.setStrokeWidth(Math.max(1.5f, scale)); paint.setColor(Color.argb(125, 115, 210, 255));
                for (int i = 0; i < 18; i++) {
                    float x = panel.left + (float) ((i * 47L + now / 9L) % Math.max(1L, (long) pw));
                    float y = panel.top + (float) ((i * 31L + now / 5L) % Math.max(1L, (long) ph));
                    c.drawLine(x, y, x - ph * .035f, y + ph * .18f, paint);
                }
            } else if (snow) {
                paint.setColor(Color.argb(175, 250, 252, 255));
                for (int i = 0; i < 15; i++) {
                    float x = panel.left + (float) ((i * 61L + now / (20L + i % 4)) % Math.max(1L, (long) pw));
                    float y = panel.top + (float) ((i * 37L + now / (12L + i % 3)) % Math.max(1L, (long) ph));
                    c.drawCircle(x, y, Math.max(1.5f, scale * (1f + i % 3)), paint);
                }
            }
            if (thunder && now % 5200L < 130L) {
                paint.setColor(Color.argb(105, 225, 238, 255)); c.drawRect(panel, paint);
            }
            paint.setShader(new LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
                    Color.argb(42, 255, 255, 255), Color.argb(4, 255, 255, 255), Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(panel.left, panel.top, panel.right, panel.top + ph * .38f), 18f * scale, 18f * scale, paint);
            paint.setShader(null); c.restoreToCount(save);
        }

        private void drawWeatherAtmosphere(Canvas c, float w, float h) {
            if (weatherData == null) return;
            weatherScene.draw(c,w,h,weatherData.weatherCode,SystemClock.uptimeMillis(),nightMode.isNight());
        }

        private boolean weatherAnimationActive() {
            if (weatherData == null) return false;
            int code=weatherData.weatherCode;
            return code <= 3 || code == 45 || code == 48 ||
                    (code >= 51 && code <= 86) || code >= 95;
        }

        private void drawAlertBanner(Canvas c, float w, float h, float scale) {
            if (weatherAlert == null || !weatherAlert.active()) return;
            int color = weatherAlert.severityRank() >= 3 ? Color.rgb(190, 32, 38) : Color.rgb(213, 109, 20);
            boolean overlapsWeatherRow = weatherPanelRect.bottom > h * .265f && weatherPanelRect.top < h * .35f;
            float bannerLeft = overlapsWeatherRow ? Math.max(w * .42f, weatherPanelRect.right + w * .02f) : w * .42f;
            RectF banner = new RectF(bannerLeft, h * .265f, w * .955f, h * .35f);
            paint.setColor(Color.argb(235, Color.red(color), Color.green(color), Color.blue(color)));
            paint.setStyle(Paint.Style.FILL); c.drawRoundRect(banner, 18f * scale, 18f * scale, paint);
            text(c, "⚠  " + ellipsize(weatherAlert.event.toUpperCase(Locale.US), 28), banner.left + w * .025f,
                    h * .302f, 15f * scale, Color.WHITE, Paint.Align.LEFT, true);
            text(c, ellipsize(weatherAlert.headline, 62), banner.left + w * .025f, h * .332f,
                    10f * scale, Color.rgb(255, 232, 225), Paint.Align.LEFT, false);
        }

        private void drawRoadAwareness(Canvas c, float w, float h, float scale, float shownMph) {
            double limit = roadAwareness.limitMph();
            if (limit > 0) {
                float x = w * .84f, y = h * .59f, radius = Math.min(w, h) * .057f;
                paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); c.drawCircle(x, y, radius, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5f * scale); paint.setColor(Color.rgb(220, 40, 32));
                c.drawCircle(x, y, radius - 2f * scale, paint);
                text(c, String.valueOf(Math.round(limit)), x, y + 10f * scale, 28f * scale,
                        Color.rgb(20, 20, 20), Paint.Align.CENTER, true);
                text(c, ellipsize(roadAwareness.roadName(), 18), x, y + radius + 18f * scale,
                        10f * scale, nightMode.isNight() ? Color.rgb(255, 164, 74) : Color.rgb(180, 202, 211), Paint.Align.CENTER, false);
            }
            paint.setStyle(Paint.Style.FILL); paint.setColor(nightMode.isNight() ? Color.argb(190, 48, 12, 4) : Color.argb(178, 55, 22, 10));
            RectF hazard = new RectF(w * .755f, h * .91f, w * .975f, h * .952f);
            c.drawRoundRect(hazard, 14f * scale, 14f * scale, paint);
            text(c, "⚠ MARK HAZARD", w * .865f, h * .939f, 11f * scale,
                    Color.rgb(255, 174, 55), Paint.Align.CENTER, true);
            if (!roadAlert.isEmpty() && SystemClock.elapsedRealtime() < roadAlertUntil) {
                RectF banner = new RectF(w * .17f, h * .79f, w * .83f, h * .855f);
                paint.setColor(Color.argb(238, 80, 26, 3)); c.drawRoundRect(banner, 16f * scale, 16f * scale, paint);
                text(c, "⚠  " + roadAlert.toUpperCase(Locale.US), w * .5f, h * .832f,
                        14f * scale, Color.rgb(255, 192, 62), Paint.Align.CENTER, true);
                postInvalidateDelayed(250L);
            }
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight(), cx = w / 2f;
            float scale = Math.min(w, h) / 500f;
            drawThemeBackground(c, w, h, scale);
            drawWeatherAtmosphere(c, w, h);
            Bitmap art = albumArt();
            if (art != null && mediaController != null) {
                RectF background = new RectF(0, 0, w, h);
                int artAlpha = nightMode.isNight() ? Math.max(18, dashboardLayout.albumAlpha / 3) : dashboardLayout.albumAlpha;
                drawAnimatedCover(c, art, background, artAlpha, .085f);
                if (isPlaying()) {
                    double glowTime = SystemClock.uptimeMillis() / 1000.0;
                    int glowAlpha = 18 + Math.round(12f * (1f + (float) Math.sin(glowTime * 1.6)) / 2f);
                    paint.setShader(new LinearGradient(0, h, w, 0,
                            Color.argb(glowAlpha, 0, 229, 255), Color.argb(glowAlpha, 160, 60, 255), Shader.TileMode.CLAMP));
                    c.drawRect(background, paint); paint.setShader(null);
                }
                paint.setColor(Color.argb(150, 0, 5, 8)); c.drawRect(0, 0, w, h, paint);
            }
            if (dashboardTheme == 1) {
                paint.setShader(new LinearGradient(0, 0, w, h, Color.argb(30, 255, 72, 20),
                        Color.argb(115, 20, 0, 0), Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, paint); paint.setShader(null);
            }
            if (nightMode.isNight()) {
                int darkness = nightMode.oled() ? 205 : 145;
                paint.setColor(Color.argb(darkness, 0, 0, 0)); c.drawRect(0, 0, w, h, paint);
            }
            if (isPlaying() && art != null) postInvalidateDelayed(50L);
            slot(dashboardLayout.media, w, h, mediaSlot);
            slot(dashboardLayout.gauge, w, h, gaugeSlot);
            slot(dashboardLayout.navigation, w, h, navigationSlot);
            RectF baseMedia = new RectF(w * .035f, h * .025f, w * .965f, h * .245f);
            int mediaSave = beginTransform(c, mediaSlot, baseMedia);
            drawMediaPanel(c, w, h, scale);
            c.restoreToCount(mediaSave);
            drawHud(c,w,h,scale);
            drawWeatherPanel(c, w, h, scale);
            drawAlertBanner(c, w, h, scale);
            drawDasherCard(c, w, h, scale);
            boolean fresh = lastFixElapsed > 0 && SystemClock.elapsedRealtime() - lastFixElapsed < 3500;
            boolean permission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean gpsOn = false;
            try { gpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
            float shown = smoothedMps * (metric ? 3.6f : 2.2369363f);
            float shownMph = smoothedMps * 2.2369363f;
            float maxShown = maxMps * (metric ? 3.6f : 2.2369363f);
            double tripShown = tripMeters / (metric ? 1000.0 : 1609.344);
            RectF baseGauge = new RectF(w * .10f, h * .38f, w * .90f, h * .72f);
            int gaugeSave = beginTransform(c, gaugeSlot, baseGauge);
            float radius = Math.min(w * .38f, h * .245f);
            arc.set(cx - radius, h * .49f - radius, cx + radius, h * .49f + radius);
            if (dashboardTheme == 1) drawInfernoGauge(c, w, h, cx, radius, shown, metric ? 130f : 80f, scale);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dashboardLayout.gaugeStyle == 2 ? 5f * scale : 15f * scale);
            paint.setColor(Color.rgb(27, 42, 51));
            if (dashboardLayout.gaugeStyle != 2) c.drawArc(arc, 145, 250, false, paint);
            float limit = metric ? 130f : 80f;
            double roadLimit = roadAwareness.limitMph();
            int speedColor = roadLimit > 0 && shownMph >= roadLimit + roadAwareness.thresholdMph() ? Color.rgb(255, 55, 42) :
                    roadLimit > 0 && shownMph >= roadLimit ? Color.rgb(255, 179, 0) : accentColor();
            paint.setColor(speedColor);
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
            text(c, roadCompassLabel(), cx, h * .655f, 17f * scale,
                    Color.rgb(205,229,238), Paint.Align.CENTER, true);
            String status; int statusColor;
            if (!permission) { status = "TAP GAUGE TO ALLOW GPS"; statusColor = Color.rgb(255,179,0); }
            else if (!gpsOn) { status = "TURN ON GPS"; statusColor = Color.rgb(255,179,0); }
            else if (!fresh) { status = "SEARCHING FOR GPS  •  " + satellites + " SAT"; statusColor = Color.rgb(255,179,0); }
            else { status = "GPS LOCK  •  " + satellites + " SAT  •  ±" + Math.round(accuracy) + "m"; statusColor = Color.rgb(74,222,128); }
            if(rideRecorder.active()){status="● REC  •  "+status;statusColor=UiKit.RED;}
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
            paint.setColor(Color.argb(72, 4, 18, 24));RectF systemDock=new RectF(w*.012f,h*.902f,w*.988f,h*.96f);c.drawRoundRect(systemDock,19f*scale,19f*scale,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1f*scale);paint.setColor(Color.argb(90,75,140,155));c.drawRoundRect(systemDock,19f*scale,19f*scale,paint);paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(178, 8, 28, 36));
            RectF customize = new RectF(w * .405f, h * .91f, w * .595f, h * .952f);
            c.drawRoundRect(customize, 14f * scale, 14f * scale, paint);
            text(c, editingDashboard ? "✓ DONE" : "⚙ SETTINGS", cx, h * .939f, 12f * scale,
                    accentColor(), Paint.Align.CENTER, true);
            paint.setColor(Color.argb(178, 5, 44, 43));
            RectF backupCamera = new RectF(w * .025f, h * .91f, w * .245f, h * .952f);
            c.drawRoundRect(backupCamera, 14f * scale, 14f * scale, paint);
            text(c, "◀ BACKUP CAM", w * .135f, h * .939f, 12f * scale,
                    Color.rgb(91, 255, 188), Paint.Align.CENTER, true);
            paint.setColor(Color.argb(178, 3, 50, 67));
            RectF riderLink = new RectF(w * .255f, h * .91f, w * .395f, h * .952f);
            c.drawRoundRect(riderLink, 14f * scale, 14f * scale, paint);
            text(c, "● RIDERS", w * .325f, h * .939f, 10f * scale,
                    Color.rgb(0, 229, 255), Paint.Align.CENTER, true);
            drawRoadAwareness(c, w, h, scale, shownMph);
            if (dashboardTheme == 1) drawInfernoFrames(c, scale);
            if (editingDashboard) drawEditorOverlay(c, scale);
            if (weatherAnimationActive()) postInvalidateDelayed(33L);
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

        private void clampWeatherRect(RectF rect, int corner) {
            float minW = .20f, minH = .06f, maxW = .80f, maxH = .28f;
            if (rect.width() < minW) { if (corner == 1 || corner == 3) rect.left = rect.right - minW; else rect.right = rect.left + minW; }
            if (rect.height() < minH) { if (corner == 1 || corner == 2) rect.top = rect.bottom - minH; else rect.bottom = rect.top + minH; }
            if (rect.width() > maxW) { if (corner == 1 || corner == 3) rect.left = rect.right - maxW; else rect.right = rect.left + maxW; }
            if (rect.height() > maxH) { if (corner == 1 || corner == 2) rect.top = rect.bottom - maxH; else rect.bottom = rect.top + maxH; }
            if (rect.left < .01f) rect.offset(.01f - rect.left, 0);
            if (rect.right > .99f) rect.offset(.99f - rect.right, 0);
            if (rect.top < .01f) rect.offset(0, .01f - rect.top);
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
                resizingWeather = false; draggingWeather = false; weatherMoved = false; weatherResizeCorner = 0;
                if (weatherPanelRect.contains(e.getX(), e.getY())) {
                    float grip = Math.max(30f * getResources().getDisplayMetrics().density,
                            Math.min(weatherPanelRect.width(), weatherPanelRect.height()) * .28f);
                    boolean left = e.getX() <= weatherPanelRect.left + grip;
                    boolean right = e.getX() >= weatherPanelRect.right - grip;
                    boolean top = e.getY() <= weatherPanelRect.top + grip;
                    boolean bottom = e.getY() >= weatherPanelRect.bottom - grip;
                    weatherResizeCorner = left && top ? 1 : right && top ? 2 : left && bottom ? 3 : right && bottom ? 4 : 0;
                    resizingWeather = weatherResizeCorner != 0;
                    draggingWeather = !resizingWeather;
                    weatherStartX = e.getX(); weatherStartY = e.getY();
                    weatherStartRect.set(weatherLeftFraction, weatherTopFraction,
                            weatherLeftFraction + weatherWidthFraction, weatherTopFraction + weatherHeightFraction);
                }
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
            if (e.getAction() == MotionEvent.ACTION_MOVE && (resizingWeather || draggingWeather)) {
                float dx = (e.getX() - weatherStartX) / getWidth();
                float dy = (e.getY() - weatherStartY) / getHeight();
                if (Math.hypot(e.getX() - weatherStartX, e.getY() - weatherStartY) > 12f) weatherMoved = true;
                RectF changed = new RectF(weatherStartRect);
                if (draggingWeather) changed.offset(dx, dy);
                else if (weatherResizeCorner == 1) { changed.left += dx; changed.top += dy; }
                else if (weatherResizeCorner == 2) { changed.right += dx; changed.top += dy; }
                else if (weatherResizeCorner == 3) { changed.left += dx; changed.bottom += dy; }
                else { changed.right += dx; changed.bottom += dy; }
                clampWeatherRect(changed, weatherResizeCorner);
                weatherLeftFraction = changed.left; weatherTopFraction = changed.top;
                weatherWidthFraction = changed.width(); weatherHeightFraction = changed.height();
                invalidate();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float w = getWidth(), h = getHeight();
                if (!editingDashboard && prefs.getBoolean("dasher_mode", false) && dasherCard.contains(e.getX(), e.getY()) &&
                        dasherCard.contains(downX, downY)) {
                    if (!hasMediaAccess()) openMediaAccessSettings(); else openDasherApp();
                    return true;
                }
                boolean backupPressed = !editingDashboard && downX >= w * .015f && downX <= w * .255f &&
                        downY >= h * .90f && downY <= h * .96f &&
                        e.getX() >= w * .015f && e.getX() <= w * .255f && e.getY() >= h * .90f && e.getY() <= h * .96f;
                boolean hazardPressed = !editingDashboard && downX >= w * .745f && downX <= w * .985f &&
                        downY >= h * .90f && downY <= h * .96f &&
                        e.getX() >= w * .745f && e.getX() <= w * .985f && e.getY() >= h * .90f && e.getY() <= h * .96f;
                boolean riderLinkPressed = !editingDashboard && downX >= w * .245f && downX <= w * .405f &&
                        downY >= h * .90f && downY <= h * .96f && e.getX() >= w * .245f &&
                        e.getX() <= w * .405f && e.getY() >= h * .90f && e.getY() <= h * .96f;
                boolean userPressed=!editingDashboard&&hudUserRect.contains(downX,downY)&&hudUserRect.contains(e.getX(),e.getY());
                boolean settingsPressed=!editingDashboard&&hudSettingsRect.contains(downX,downY)&&hudSettingsRect.contains(e.getX(),e.getY());
                boolean micPressed=!editingDashboard&&hudMicRect.contains(downX,downY)&&hudMicRect.contains(e.getX(),e.getY());
                if(userPressed){showUserMenu();
                }else if(settingsPressed){showDashboardCustomizer();
                }else if(micPressed){startVoiceCommand();
                } else if (riderLinkPressed) {
                    startActivity(new Intent(MainActivity.this, RiderLinkActivity.class));
                } else if (hazardPressed) {
                    showHazardPicker();
                } else if (backupPressed) {
                    openBackupCamera();
                } else if (customizePressed && Math.hypot(e.getX() - downX, e.getY() - downY) < 40f) {
                    customizePressed = false;
                    if (editingDashboard) { editingDashboard = false; editingSection = 0; saveDashboard(); invalidate(); }
                    else showDashboardCustomizer();
                } else if (editingDashboard && editingSection != 0) {
                    saveDashboard(); editingSection = 0; resizingSection = false; invalidate();
                } else if (resizingWeather || draggingWeather) {
                    long held = SystemClock.elapsedRealtime() - downAt;
                    if (weatherMoved) prefs.edit().putFloat("weather_left", weatherLeftFraction)
                            .putFloat("weather_top", weatherTopFraction)
                            .putFloat("weather_width", weatherWidthFraction)
                            .putFloat("weather_height", weatherHeightFraction).apply();
                    else if (draggingWeather && held >= 700L) showWeatherSkinPicker();
                    else if (draggingWeather) weatherVoice.setEnabled(!weatherVoice.enabled());
                    resizingWeather = false; draggingWeather = false; weatherResizeCorner = 0; invalidate();
                } else if (mediaSlot.contains(e.getX(), e.getY())) {
                    float sourceX = w * (.035f + (e.getX() - mediaSlot.left) / mediaSlot.width() * .93f);
                    float sourceY = h * (.025f + (e.getY() - mediaSlot.top) / mediaSlot.height() * .22f);
                    if (!hasMediaAccess()) openMediaAccessSettings();
                    else if (sourceY >= h * .12f) {
                        if (sourceX >= w * .29f && sourceX < w * .385f) showEqualizer();
                        else if (sourceX >= w * .385f && sourceX < w * .485f) showAmplifier();
                        else if (sourceX < w * .585f) mediaPrevious();
                        else if (sourceX < w * .695f) mediaPlayPause();
                        else if (sourceX < w * .805f) mediaNext();
                        else showMediaVolumeSlider();
                    } else openActiveMediaApp();
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
                resizingWeather = false; draggingWeather = false; resizingSection = false;
                editingSection = 0; customizePressed = false;
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
