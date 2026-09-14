package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.location.Location;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads compact OSM road/address vectors, not bulk standard-map tiles. */
final class OfflineRegionManager {
    interface Callback{void done(boolean ok,String message);}
    private final Context context;private final ExecutorService worker=Executors.newSingleThreadExecutor();
    OfflineRegionManager(Context c){context=c.getApplicationContext();}
    void download(Location center,int radiusMiles,Callback callback){worker.execute(()->{try{double dy=radiusMiles/69d,dx=dy/Math.max(.25,Math.cos(Math.toRadians(center.getLatitude())));double s=center.getLatitude()-dy,w=center.getLongitude()-dx,n=center.getLatitude()+dy,e=center.getLongitude()+dx;String q=String.format(Locale.US,"[out:json][timeout:45];(way[highway](%.6f,%.6f,%.6f,%.6f);node[addr:housenumber](%.6f,%.6f,%.6f,%.6f););out tags geom;",s,w,n,e,s,w,n,e);HttpURLConnection c=(HttpURLConnection)new URL("https://overpass-api.de/api/interpreter?data="+Uri.encode(q)).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(60000);c.setRequestProperty("User-Agent","OpenNAV+/7.9 (github.com/BobTheZombie/Scooter-Speedometer-)");if(c.getResponseCode()!=200)throw new Exception("map server returned "+c.getResponseCode());StringBuilder body=new StringBuilder();BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()));String line;while((line=r.readLine())!=null)body.append(line);r.close();c.disconnect();JSONObject source=new JSONObject(body.toString());JSONArray roads=new JSONArray(),places=new JSONArray(),elements=source.getJSONArray("elements");for(int i=0;i<elements.length();i++){JSONObject x=elements.getJSONObject(i),tags=x.optJSONObject("tags");if(tags==null)continue;if("way".equals(x.optString("type"))){JSONArray g=x.optJSONArray("geometry");if(g==null)continue;JSONObject road=new JSONObject();road.put("name",tags.optString("name",tags.optString("ref","road")));road.put("highway",tags.optString("highway","road"));road.put("geometry",g);roads.put(road);}else if(tags.has("addr:housenumber")){JSONObject p=new JSONObject();p.put("lat",x.optDouble("lat"));p.put("lon",x.optDouble("lon"));p.put("label",address(tags));places.put(p);}}JSONObject pack=new JSONObject();pack.put("centerLat",center.getLatitude());pack.put("centerLon",center.getLongitude());pack.put("radiusMiles",radiusMiles);pack.put("downloaded",System.currentTimeMillis());pack.put("roads",roads);pack.put("places",places);File dir=new File(context.getFilesDir(),"opennav_offline");dir.mkdirs();File file=new File(dir,"region.json");try(FileOutputStream out=new FileOutputStream(file)){out.write(pack.toString().getBytes("UTF-8"));}callback.done(true,String.format(Locale.US,"Offline region ready • %,d roads • %,d addresses",roads.length(),places.length()));}catch(Exception ex){callback.done(false,"Offline download failed: "+ex.getMessage());}});}
    String data(){try{File f=new File(new File(context.getFilesDir(),"opennav_offline"),"region.json");if(!f.isFile())return "";BufferedReader r=new BufferedReader(new java.io.FileReader(f));StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null)b.append(line);r.close();return b.toString();}catch(Exception e){return "";}}
    String summary(){try{JSONObject p=new JSONObject(data());return p.optInt("radiusMiles")+" mi region • "+p.getJSONArray("roads").length()+" roads • "+p.getJSONArray("places").length()+" addresses";}catch(Exception e){return "No offline region downloaded";}}
    static java.util.List<GeocodingService.Result> search(Context context,String query,int limit){java.util.List<GeocodingService.Result> out=new java.util.ArrayList<>();try{String needle=query.toLowerCase(Locale.US).replace(",","").trim();JSONObject pack=new JSONObject(new OfflineRegionManager(context).data());JSONArray places=pack.getJSONArray("places");for(int i=0;i<places.length()&&out.size()<limit;i++){JSONObject p=places.getJSONObject(i);String label=p.optString("label");String normalized=label.toLowerCase(Locale.US).replace(",","");if(normalized.contains(needle)||tokens(normalized,needle)){out.add(new GeocodingService.Result(label,p.getDouble("lat"),p.getDouble("lon"),"Offline OSM"));}}}catch(Exception ignored){}return out;}
    private static boolean tokens(String value,String query){String[] words=query.split("\\s+");if(words.length<2)return false;for(String word:words)if(word.length()>1&&!value.contains(word))return false;return true;}
    private static String address(JSONObject t){StringBuilder b=new StringBuilder();add(b,t.optString("addr:housenumber"));add(b,t.optString("addr:street"));add(b,t.optString("addr:city"));add(b,t.optString("addr:state"));add(b,t.optString("addr:postcode"));return b.toString();}
    private static void add(StringBuilder b,String s){if(s==null||s.trim().isEmpty())return;if(b.length()>0)b.append(b.indexOf(",")<0?" ":", ");b.append(s.trim());}
    void shutdown(){worker.shutdownNow();}
}
