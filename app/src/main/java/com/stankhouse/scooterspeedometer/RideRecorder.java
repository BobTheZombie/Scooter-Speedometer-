package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Local ride recorder with compact GPS traces and exportable history. */
public final class RideRecorder {
    private final SharedPreferences prefs; private JSONObject active; private Location last; private long lastPoint;
    public RideRecorder(Context c){prefs=c.getSharedPreferences("ride_recorder",Context.MODE_PRIVATE);restore();}
    public boolean active(){return active!=null;}
    public void start(){try{active=new JSONObject().put("started",System.currentTimeMillis()).put("distance_m",0).put("moving_ms",0).put("max_mps",0).put("points",new JSONArray());prefs.edit().putString("active",active.toString()).apply();last=null;}catch(Exception ignored){}}
    public JSONObject stop(){if(active==null)return null;try{active.put("ended",System.currentTimeMillis());JSONArray history=history();history.put(active);while(history.length()>40)history.remove(0);prefs.edit().remove("active").putString("history",history.toString()).apply();JSONObject done=active;active=null;last=null;return done;}catch(Exception e){return null;}}
    public void update(Location fix){if(active==null||fix==null||fix.getAccuracy()>45)return;try{long now=fix.getTime()>0?fix.getTime():System.currentTimeMillis();if(last!=null){float d=last.distanceTo(fix);long dt=Math.max(0,now-last.getTime());if(dt<30000&&d<350){active.put("distance_m",active.optDouble("distance_m")+d);if(fix.getSpeed()>.7f)active.put("moving_ms",active.optLong("moving_ms")+dt);active.put("max_mps",Math.max(active.optDouble("max_mps"),fix.getSpeed()));}}if(now-lastPoint>5000){active.getJSONArray("points").put(new JSONArray().put(round(fix.getLatitude())).put(round(fix.getLongitude())).put(now));lastPoint=now;}last=new Location(fix);prefs.edit().putString("active",active.toString()).apply();}catch(Exception ignored){}}
    public JSONArray history(){try{return new JSONArray(prefs.getString("history","[]"));}catch(Exception e){return new JSONArray();}}
    public String summary(JSONObject r){if(r==null)return "No ride";double miles=r.optDouble("distance_m")/1609.344;long mins=Math.max(1,(r.optLong("ended",System.currentTimeMillis())-r.optLong("started"))/60000);return String.format(Locale.US,"%.1f mi • %d min • max %.0f mph",miles,mins,r.optDouble("max_mps")*2.23694);}
    public String exportCsv(){StringBuilder out=new StringBuilder("date,distance_miles,duration_minutes,moving_minutes,max_mph,points\n");JSONArray h=history();SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);for(int i=0;i<h.length();i++){JSONObject r=h.optJSONObject(i);if(r==null)continue;out.append(f.format(new Date(r.optLong("started")))).append(',').append(String.format(Locale.US,"%.2f",r.optDouble("distance_m")/1609.344)).append(',').append((r.optLong("ended")-r.optLong("started"))/60000).append(',').append(r.optLong("moving_ms")/60000).append(',').append(String.format(Locale.US,"%.1f",r.optDouble("max_mps")*2.23694)).append(',').append(r.optJSONArray("points")==null?0:r.optJSONArray("points").length()).append('\n');}return out.toString();}
    public double lifetimeMiles(){double m=0;JSONArray h=history();for(int i=0;i<h.length();i++){JSONObject r=h.optJSONObject(i);if(r!=null)m+=r.optDouble("distance_m");}if(active!=null)m+=active.optDouble("distance_m");return m/1609.344;}
    private void restore(){try{String s=prefs.getString("active","");if(!s.isEmpty())active=new JSONObject(s);}catch(Exception ignored){active=null;}}
    private double round(double v){return Math.round(v*1000000d)/1000000d;}
}
