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
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Key-free in-app OSM map and turn guidance with no commercial map SDK. */
public class BuiltInNavigationActivity extends Activity implements LocationListener, TextToSpeech.OnInitListener {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<RouteStep> steps = new ArrayList<>();
    private WebView map;
    private boolean mapReady;
    private LocationManager locationManager;
    private Location currentLocation;
    private double destinationLatitude, destinationLongitude;
    private boolean hasDestination;
    private String destinationQuery;
    private TextView instruction, tripInfo;
    private Button goButton;
    private boolean guidanceActive;
    private Location filteredLocation;
    private long lastMapUpdate;
    private String routeSummary="";
    private TextToSpeech speech;
    private boolean speechReady;
    private int stepIndex, lastSpokenStep = -1;
    private int spokenStage;
    private long offRouteSince;
    private long lastRouteRequest;
    private FlockCameraProvider flockCameras;
    private long lastFlockRefresh;
    private final List<CameraPoint> cameraPoints = new ArrayList<>();
    private final List<RoutePoint> routePoints = new ArrayList<>();
    private final Map<String,Long> cameraWarningTimes = new HashMap<>();
    private long lastCameraWarning;
    private PoliceSightingProvider policeProvider;
    private long lastPoliceRefresh,lastPoliceWarning;
    private final List<PolicePoint> policePoints=new ArrayList<>();
    private final Map<Long,Long> policeWarningTimes=new HashMap<>();
    private MapTileCache tileCache;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Fullscreen.apply(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        destinationQuery = getIntent().getStringExtra("destination");
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        flockCameras = new FlockCameraProvider(this);
        policeProvider = new PoliceSightingProvider();
        tileCache = new MapTileCache(this);
        speech = new TextToSpeech(this, this);
        FrameLayout root = new FrameLayout(this);
        map = new WebView(this);
        map.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        map.setOverScrollMode(View.OVER_SCROLL_NEVER);map.setVerticalScrollBarEnabled(false);map.setHorizontalScrollBarEnabled(false);map.setBackgroundColor(Color.rgb(7,16,23));
        WebSettings settings = map.getSettings(); settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true); settings.setAllowFileAccess(true);settings.setCacheMode(WebSettings.LOAD_DEFAULT);settings.setLoadsImagesAutomatically(true);
        map.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){WebResourceResponse cached=tileCache.intercept(request);return cached!=null?cached:super.shouldInterceptRequest(view,request);}
            @Override public void onPageFinished(WebView view, String url) {
                mapReady = true;
                if (currentLocation != null) updateMapLocation(currentLocation);
            }
        });
        map.loadUrl("file:///android_asset/map.html");
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));
        instruction = label(20, Color.WHITE, 0xE5101820, Gravity.CENTER_VERTICAL, 22);
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-1, dp(88));
        top.gravity = Gravity.TOP; top.setMargins(dp(8), dp(8), dp(8), 0); root.addView(instruction, top);
        tripInfo = label(15, Color.WHITE, 0xE5101820, Gravity.CENTER, 12);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, dp(50));
        bottom.gravity = Gravity.BOTTOM; bottom.setMargins(dp(45), 0, dp(45), dp(15)); root.addView(tripInfo, bottom);
        goButton = new Button(this);goButton.setText("GO");goButton.setTextSize(20);UiKit.button(goButton,Color.rgb(0,126,148));goButton.setVisibility(View.GONE);
        goButton.setOnClickListener(v->startGuidance());FrameLayout.LayoutParams goParams=new FrameLayout.LayoutParams(dp(112),dp(58));goParams.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;goParams.setMargins(0,0,0,dp(70));root.addView(goButton,goParams);
        Button close = new Button(this); close.setText("×"); close.setTextSize(22);UiKit.button(close,Color.rgb(40,55,62)); close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(44), dp(44));
        closeParams.gravity = Gravity.BOTTOM | Gravity.RIGHT; closeParams.setMargins(0,0,dp(4),dp(18)); root.addView(close, closeParams);
        setContentView(root);
        requestLocation();
    }

    private TextView label(int size, int color, int background, int gravity, int padding) {
        TextView result = new TextView(this); result.setTextSize(size); result.setTextColor(color);
        result.setBackgroundColor(background); result.setGravity(gravity); result.setPadding(dp(padding), 0, dp(padding), 0);
        result.setText("Finding your location…"); return result;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { finish(); return; }
        try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, this);if(locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,1000L,0f,this); }
        catch (SecurityException ignored) { }
    }
    @Override public void onLocationChanged(Location location) {
        Location smooth=filterLocation(location);if(smooth==null)return;currentLocation = smooth; updateMapLocation(smooth);
        if(guidanceActive){checkCameraWarnings(smooth);checkPoliceWarnings(smooth);}
        if (!hasDestination && destinationQuery != null) geocodeAndRoute();
        else if (hasDestination&&guidanceActive) updateGuidance(smooth);
    }
    private Location filterLocation(Location next){if(next==null||!next.hasAccuracy()||next.getAccuracy()>85f)return null;if(filteredLocation==null){filteredLocation=new Location(next);return new Location(filteredLocation);}long dt=Math.max(1,next.getTime()-filteredLocation.getTime());float jump=filteredLocation.distanceTo(next);float allowed=Math.max(45f,(filteredLocation.getSpeed()+18f)*dt/1000f+next.getAccuracy()*1.5f);if(dt<5000&&jump>allowed)return null;float alpha=Math.max(.22f,Math.min(.82f,(next.getSpeed()/12f)+(.55f-next.getAccuracy()/150f)));Location out=new Location(next);out.setLatitude(filteredLocation.getLatitude()+(next.getLatitude()-filteredLocation.getLatitude())*alpha);out.setLongitude(filteredLocation.getLongitude()+(next.getLongitude()-filteredLocation.getLongitude())*alpha);if(next.hasBearing()&&filteredLocation.hasBearing()){float delta=((next.getBearing()-filteredLocation.getBearing()+540f)%360f)-180f;out.setBearing((filteredLocation.getBearing()+delta*Math.max(.25f,alpha)+360f)%360f);}filteredLocation=out;return new Location(out);}
    private void updateMapLocation(Location location) {
        if (!mapReady) return;
        SnappedPoint snap=guidanceActive?snapToRoute(location):null;double mapLat=snap==null?location.getLatitude():snap.lat,mapLon=snap==null?location.getLongitude():snap.lon;float bearing=snap==null?(location.hasBearing()?location.getBearing():0):snap.bearing;
        map.evaluateJavascript(String.format(Locale.US, "updateLocation(%.7f,%.7f,%.1f,%.1f,%s)",
                mapLat,mapLon,bearing,
                location.hasAccuracy() ? location.getAccuracy() : 20,guidanceActive?"true":"false"), null);
        if(System.currentTimeMillis()-lastFlockRefresh>15L*60L*1000L){lastFlockRefresh=System.currentTimeMillis();flockCameras.nearby(location.getLatitude(),location.getLongitude(),(ok,body,message)->{if(!ok){lastFlockRefresh=0;return;}applyCameraData(body);String safe=body.replace("\\","\\\\").replace("'","\\'").replace("\n","");if(mapReady)map.evaluateJavascript("setFlockHopperCameras('"+safe+"')",null);});}
        if(System.currentTimeMillis()-lastPoliceRefresh>30L*1000L){lastPoliceRefresh=System.currentTimeMillis();policeProvider.nearby(location.getLatitude(),location.getLongitude(),(ok,body,message)->{if(!ok){lastPoliceRefresh=0;return;}applyPoliceData(body);String safe=body.replace("\\","\\\\").replace("'","\\'").replace("\n","");if(mapReady)map.evaluateJavascript("setPoliceSightings('"+safe+"')",null);});}
    }
    private void geocodeAndRoute() {
        String query = destinationQuery; destinationQuery = null;
        instruction.setText("Finding “" + query + "”…");
        network.execute(() -> {
            try {
                GeocodingService.Result result=GeocodingService.geocode(query,currentLocation);
                main.post(() -> { destinationLatitude = result.latitude; destinationLongitude = result.longitude; hasDestination = true; instruction.setText("Found via "+result.source+" • Calculating route…");requestRoute(); });
            } catch (Exception error) { main.post(() -> showError(error.getMessage())); }
        });
    }
    private void requestRoute() {
        if (currentLocation == null || !hasDestination || System.currentTimeMillis() - lastRouteRequest < 8000) return;
        lastRouteRequest = System.currentTimeMillis(); double lon = currentLocation.getLongitude(), lat = currentLocation.getLatitude();
        instruction.setText("OpenNAV+ • Calculating scooter route…");
        network.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String endpoint = String.format(Locale.US, "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true&alternatives=true", lon, lat, destinationLongitude, destinationLatitude);
                connection = open(new URL(endpoint)); JSONArray candidates=new JSONObject(read(connection)).getJSONArray("routes");JSONObject route = candidates.getJSONObject(0);
                String geometry = route.getJSONObject("geometry").toString(); double distance = route.getDouble("distance"), duration = route.getDouble("duration");
                List<RouteStep> parsed = parseSteps(route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps"));
                List<RoutePoint> routeShape=parseRouteShape(route.getJSONObject("geometry"));
                markTrafficSignalTurns(parsed,routeShape);
                main.post(() -> applyRoute(geometry, parsed, routeShape, distance, duration));
            } catch (Exception error) { main.post(() -> showError("Route unavailable: " + error.getMessage())); }
            finally { if (connection != null) connection.disconnect(); }
        });
    }
    private HttpURLConnection open(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", "Scooter-Speedometer/1.8 (github.com/BobTheZombie/Scooter-Speedometer-)");
        return connection;
    }
    private List<RouteStep> parseSteps(JSONArray json) throws Exception {
        List<RouteStep> result = new ArrayList<>();
        for (int i = 0; i < json.length(); i++) {
            JSONObject item = json.getJSONObject(i), maneuver = item.getJSONObject("maneuver"); JSONArray point = maneuver.getJSONArray("location");
            String type = maneuver.optString("type", "continue"), modifier = maneuver.optString("modifier", ""), road = item.optString("name", "").trim();
            if(road.isEmpty())road=item.optString("ref","").trim();
            if(road.isEmpty())road=item.optString("destinations","").trim();
            String wording = maneuverWording(type,modifier,road,maneuver.optInt("exit",0));
            result.add(new RouteStep(point.getDouble(1), point.getDouble(0), wording,type,modifier));
        }
        return result;
    }
    private String maneuverWording(String type,String modifier,String road,int exit){String onto=road.isEmpty()?"":" onto "+road;String direction=modifier.isEmpty()?"straight":modifier.replace("slight ","slightly ");
        if("depart".equals(type))return "Head "+direction+(road.isEmpty()?"":" on "+road);
        if("arrive".equals(type))return "Your destination is "+(modifier.contains("left")?"on the left":modifier.contains("right")?"on the right":"ahead");
        if(type.contains("roundabout")||"rotary".equals(type))return "At the roundabout, take "+(exit>0?(exit<=10?ordinal(exit)+" exit":"exit number "+exit):"the exit")+onto;
        if("merge".equals(type))return "Merge "+direction+onto;
        if("fork".equals(type))return "Keep "+direction+onto;
        if("on ramp".equals(type))return "Take the ramp "+direction+onto;
        if("off ramp".equals(type))return "Take the exit "+direction+onto;
        if("new name".equals(type)||"continue".equals(type))return "Continue "+direction+onto;
        if("end of road".equals(type))return "At the end of the road, turn "+direction+onto;
        return "Turn "+direction+onto;
    }
    private String ordinal(int number){String[] words={"","the first","the second","the third","the fourth","the fifth","the sixth","the seventh","the eighth","the ninth","the tenth"};return number>0&&number<words.length?words[number]:"exit number "+number;}
    private List<RoutePoint> parseRouteShape(JSONObject geometry)throws Exception{List<RoutePoint> result=new ArrayList<>();JSONArray coordinates=geometry.getJSONArray("coordinates");for(int i=0;i<coordinates.length();i++){JSONArray p=coordinates.getJSONArray(i);result.add(new RoutePoint(p.getDouble(1),p.getDouble(0)));}return result;}
    private void applyRoute(String geometry, List<RouteStep> parsed, List<RoutePoint> shape, double distance, double duration) {
        steps.clear(); steps.addAll(parsed); stepIndex = Math.min(1, Math.max(0, steps.size() - 1)); lastSpokenStep = -1;spokenStage=0;offRouteSince=0;
        routePoints.clear();routePoints.addAll(shape);
        if (mapReady) map.evaluateJavascript("setRoute(" + geometry + ")", null);
        routeSummary=String.format(Locale.US, "%.1f mi  •  %d min  •  ETA %s  •  OpenNAV+", distance / 1609.344, Math.round(duration / 60),new java.text.SimpleDateFormat("h:mm a",Locale.US).format(new java.util.Date(System.currentTimeMillis()+(long)(duration*1000))));tripInfo.setText(routeSummary);
        if(guidanceActive){goButton.setVisibility(View.GONE);if(!steps.isEmpty())instruction.setText(steps.get(stepIndex).instruction);if(mapReady)map.evaluateJavascript("startGuidance()",null);}else{goButton.setVisibility(View.VISIBLE);instruction.setText("Route ready • Review the route, then tap GO");}
    }
    private void startGuidance(){if(steps.isEmpty())return;guidanceActive=true;goButton.setVisibility(View.GONE);stepIndex=Math.min(1,Math.max(0,steps.size()-1));lastSpokenStep=-1;spokenStage=0;instruction.setText(steps.get(stepIndex).instruction);if(mapReady)map.evaluateJavascript("startGuidance()",null);if(speechReady)speech.speak("Navigation started",TextToSpeech.QUEUE_ADD,null,"navigation_started");if(currentLocation!=null)updateGuidance(currentLocation);}
    private SnappedPoint snapToRoute(Location location){if(routePoints.size()<2)return null;double best=Double.MAX_VALUE,bestLat=location.getLatitude(),bestLon=location.getLongitude();float bestBearing=location.hasBearing()?location.getBearing():0;for(int i=1;i<routePoints.size();i++){RoutePoint a=routePoints.get(i-1),b=routePoints.get(i);double scale=Math.cos(Math.toRadians(location.getLatitude()));double ax=(a.lon-location.getLongitude())*111320d*scale,ay=(a.lat-location.getLatitude())*111320d,bx=(b.lon-location.getLongitude())*111320d*scale,by=(b.lat-location.getLatitude())*111320d;double vx=bx-ax,vy=by-ay,den=vx*vx+vy*vy,t=den==0?0:Math.max(0,Math.min(1,-(ax*vx+ay*vy)/den));double x=ax+t*vx,y=ay+t*vy,d=Math.hypot(x,y);if(d<best){best=d;bestLat=location.getLatitude()+y/111320d;bestLon=location.getLongitude()+x/(111320d*scale);float[] br=new float[2];Location.distanceBetween(a.lat,a.lon,b.lat,b.lon,br);bestBearing=br[1];}}return best<=Math.max(30d,location.getAccuracy()*1.25d)?new SnappedPoint(bestLat,bestLon,bestBearing):null;}
    private void applyCameraData(String body){cameraPoints.clear();try{JSONArray list=new JSONArray(body);for(int i=0;i<list.length();i++){JSONObject p=list.getJSONObject(i);cameraPoints.add(new CameraPoint(p.getDouble("latitude"),p.getDouble("longitude")));}if(currentLocation!=null)checkCameraWarnings(currentLocation);}catch(Exception ignored){}}
    private void checkCameraWarnings(Location location){
        if(!speechReady||cameraPoints.isEmpty()||location.getSpeed()<1.5f)return;long now=System.currentTimeMillis();if(now-lastCameraWarning<12000L)return;
        CameraPoint best=null;float bestDistance=Float.MAX_VALUE;float heading=location.hasBearing()?location.getBearing():routeHeading(location);
        for(CameraPoint camera:cameraPoints){float[] d=new float[2];Location.distanceBetween(location.getLatitude(),location.getLongitude(),camera.lat,camera.lon,d);if(d[0]>152.4f||d[0]>=bestDistance)continue;
            float delta=Math.abs(((d[1]-heading+540f)%360f)-180f);if(delta>75f)continue;if(!routePoints.isEmpty()&&distanceToRouteMeters(camera)>65d)continue;
            String key=camera.key();Long warned=cameraWarningTimes.get(key);if(warned!=null&&now-warned<20L*60L*1000L)continue;best=camera;bestDistance=d[0];}
        if(best==null)return;String key=best.key();cameraWarningTimes.put(key,now);lastCameraWarning=now;int feet=Math.max(100,Math.round((bestDistance*3.28084f)/50f)*50);String warning="Reported plate reader ahead in "+feet+" feet";
        speech.speak(warning,TextToSpeech.QUEUE_ADD,null,"alpr_"+key);if(mapReady)map.evaluateJavascript(String.format(Locale.US,"highlightFlock(%.7f,%.7f)",best.lat,best.lon),null);
    }
    private void applyPoliceData(String body){policePoints.clear();try{JSONArray list=new JSONArray(body);for(int i=0;i<list.length();i++){JSONObject p=list.getJSONObject(i);policePoints.add(new PolicePoint(p.optLong("id"),p.getDouble("latitude"),p.getDouble("longitude")));}if(currentLocation!=null)checkPoliceWarnings(currentLocation);}catch(Exception ignored){}}
    private void checkPoliceWarnings(Location location){if(!speechReady||policePoints.isEmpty()||location.getSpeed()<1.5f)return;long now=System.currentTimeMillis();if(now-lastPoliceWarning<30000L)return;float heading=location.hasBearing()?location.getBearing():routeHeading(location);PolicePoint best=null;float bestDistance=Float.MAX_VALUE;for(PolicePoint point:policePoints){float[] d=new float[2];Location.distanceBetween(location.getLatitude(),location.getLongitude(),point.lat,point.lon,d);if(d[0]>1200f||d[0]>=bestDistance)continue;float delta=Math.abs(((d[1]-heading+540f)%360f)-180f);if(delta>65f)continue;if(!routePoints.isEmpty()&&distanceToRouteMeters(new CameraPoint(point.lat,point.lon))>100d)continue;Long warned=policeWarningTimes.get(point.id);if(warned!=null&&now-warned<30L*60L*1000L)continue;best=point;bestDistance=d[0];}if(best==null)return;policeWarningTimes.put(best.id,now);lastPoliceWarning=now;speech.speak("Police vehicle ahead",TextToSpeech.QUEUE_ADD,null,"police_"+best.id);if(mapReady)map.evaluateJavascript(String.format(Locale.US,"highlightPolice(%.7f,%.7f)",best.lat,best.lon),null);}
    private float routeHeading(Location location){if(stepIndex<steps.size()){RouteStep s=steps.get(stepIndex);float[] d=new float[2];Location.distanceBetween(location.getLatitude(),location.getLongitude(),s.latitude,s.longitude,d);return d[1];}return 0f;}
    private double distanceToRouteMeters(CameraPoint c){double best=Double.MAX_VALUE;for(int i=1;i<routePoints.size();i++){best=Math.min(best,segmentDistanceMeters(c,routePoints.get(i-1),routePoints.get(i)));if(best<=20d)return best;}return best;}
    private double segmentDistanceMeters(CameraPoint p,RoutePoint a,RoutePoint b){double scale=Math.cos(Math.toRadians(p.lat));double ax=(a.lon-p.lon)*111320d*scale,ay=(a.lat-p.lat)*111320d,bx=(b.lon-p.lon)*111320d*scale,by=(b.lat-p.lat)*111320d;double vx=bx-ax,vy=by-ay,den=vx*vx+vy*vy,t=den==0?0:Math.max(0,Math.min(1,-(ax*vx+ay*vy)/den));return Math.hypot(ax+t*vx,ay+t*vy);}
    private void updateGuidance(Location location) {
        if (steps.isEmpty()) return; RouteStep step = steps.get(Math.min(stepIndex, steps.size() - 1)); float[] result = new float[1];
        Location.distanceBetween(location.getLatitude(), location.getLongitude(), step.latitude, step.longitude, result); float metres = result[0];
        if (metres < 28 && stepIndex < steps.size() - 1) { stepIndex++; lastSpokenStep = -1;spokenStage=0; updateGuidance(location); return; }
        String wording=step.guidance();float feet=metres*3.28084f;
        instruction.setText(formatVisualDistance(feet)+"  •  "+step.icon()+"  "+wording);
        int stage=metres<=45?5:metres<=155?4:metres<=410?3:metres<=805?2:metres<=1610?1:0;
        if(speechReady&&stage>spokenStage){speech.speak(spokenLead(stage)+wording,TextToSpeech.QUEUE_ADD,null,"turn_"+stepIndex+"_"+stage);spokenStage=stage;lastSpokenStep=stepIndex;}
        double routeDistance=distanceToRouteMeters(new RoutePoint(location.getLatitude(),location.getLongitude()));long now=System.currentTimeMillis();
        if(routeDistance>85){if(offRouteSince==0)offRouteSince=now;else if(now-offRouteSince>7000&&now-lastRouteRequest>15000){instruction.setText("Rerouting…");speech.speak("You are off route. Recalculating.",TextToSpeech.QUEUE_ADD,null,"reroute");requestRoute();offRouteSince=0;}}else offRouteSince=0;
    }
    private String formatVisualDistance(float feet){if(feet>=1000f){float miles=feet/5280f;return miles>=10f?String.format(Locale.US,"%.0f mi",miles):String.format(Locale.US,"%.1f mi",miles);}return Math.max(50,Math.round(feet/50f)*50)+" ft";}
    private String spokenLead(int stage){if(stage>=5)return "Now, ";if(stage==4)return "In 500 feet, ";if(stage==3)return "In a quarter mile, ";if(stage==2)return "In half a mile, ";return "In one mile, ";}
    private double distanceToRouteMeters(RoutePoint p){double best=Double.MAX_VALUE;CameraPoint probe=new CameraPoint(p.lat,p.lon);for(int i=1;i<routePoints.size();i++){best=Math.min(best,segmentDistanceMeters(probe,routePoints.get(i-1),routePoints.get(i)));if(best<12)return best;}return best;}

    private void markTrafficSignalTurns(List<RouteStep> parsed,List<RoutePoint> shape){if(shape.isEmpty()||parsed.isEmpty())return;double south=90,north=-90,west=180,east=-180;for(RoutePoint p:shape){south=Math.min(south,p.lat);north=Math.max(north,p.lat);west=Math.min(west,p.lon);east=Math.max(east,p.lon);}float[] span=new float[1];Location.distanceBetween(south,west,north,east,span);if(span[0]>65000)return;HttpURLConnection c=null;try{String query=String.format(Locale.US,"[out:json][timeout:12];node[highway=traffic_signals](%.6f,%.6f,%.6f,%.6f);out body;",south-.002,west-.002,north+.002,east+.002);c=open(new URL("https://overpass-api.de/api/interpreter?data="+android.net.Uri.encode(query)));JSONArray nodes=new JSONObject(read(c)).getJSONArray("elements");for(RouteStep step:parsed){if("depart".equals(step.type)||"arrive".equals(step.type)||"continue".equals(step.type))continue;for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);float[] d=new float[1];Location.distanceBetween(step.latitude,step.longitude,n.getDouble("lat"),n.getDouble("lon"),d);if(d[0]<=38){step.atTrafficSignal=true;break;}}}}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}}
    private String read(HttpURLConnection connection) throws Exception { BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream())); StringBuilder out = new StringBuilder(); String line; while ((line = reader.readLine()) != null) out.append(line); reader.close(); return out.toString(); }
    private void showError(String message) { instruction.setText(message); new AlertDialog.Builder(this).setTitle("Navigation").setMessage(message).setPositiveButton("Close", (d,w) -> finish()).show(); }
    @Override public void onInit(int status) { speechReady = status == TextToSpeech.SUCCESS; if (speechReady) { speech.setLanguage(Locale.US); VoiceSettings.apply(this,speech); } }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) Fullscreen.apply(this); }
    @Override protected void onDestroy() { try { locationManager.removeUpdates(this); } catch (Exception ignored) {} network.shutdownNow();if(flockCameras!=null)flockCameras.shutdown();if(policeProvider!=null)policeProvider.shutdown(); speech.stop(); speech.shutdown(); map.destroy(); super.onDestroy(); }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    private static class RouteStep { final double latitude, longitude; final String instruction,type,modifier;boolean atTrafficSignal;RouteStep(double latitude,double longitude,String instruction,String type,String modifier){this.latitude=latitude;this.longitude=longitude;this.instruction=instruction;this.type=type;this.modifier=modifier;}String guidance(){if(!atTrafficSignal)return instruction;String phrase=instruction.isEmpty()?instruction:instruction.substring(0,1).toLowerCase(Locale.US)+instruction.substring(1);if(type.equals("turn")||type.equals("fork")||type.equals("merge")||type.equals("new name"))return "At the next light, "+phrase;return instruction;}String icon(){if(type.contains("roundabout")||type.equals("rotary"))return "↻";if(type.equals("arrive"))return "◆";if(modifier.contains("left"))return "↰";if(modifier.contains("right"))return "↱";if(type.contains("ramp"))return "⇗";return "↑";}}
    private static class RoutePoint{final double lat,lon;RoutePoint(double a,double o){lat=a;lon=o;}}
    private static class CameraPoint{final double lat,lon;CameraPoint(double a,double o){lat=a;lon=o;}String key(){return String.format(Locale.US,"%.5f,%.5f",lat,lon);}}
    private static class PolicePoint{final long id;final double lat,lon;PolicePoint(long i,double a,double o){id=i;lat=a;lon=o;}}
    private static class SnappedPoint{final double lat,lon;final float bearing;SnappedPoint(double a,double o,float b){lat=a;lon=o;bearing=b;}}
}
