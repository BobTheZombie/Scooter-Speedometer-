package com.stankhouse.scooterspeedometer;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Recent, human-confirmed public police vehicle sightings from SparrowMap. */
public final class PoliceSightingProvider {
    public interface Callback { void complete(boolean ok,String json,String message); }
    private static final long MAX_AGE_SECONDS=60L*60L;
    private static final double RADIUS_METERS=50000d;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());

    public void nearby(double latitude,double longitude,Callback callback){worker.execute(()->{
        HttpURLConnection connection=null;
        try{
            long now=System.currentTimeMillis()/1000L;
            String endpoint="https://map.sparrowmap.com/api/sightings?since="+(now-MAX_AGE_SECONDS)+"&vclass=public&limit=1000";
            connection=(HttpURLConnection)new URL(endpoint).openConnection();connection.setConnectTimeout(9000);connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","Scooter-Speedometer/7.3 (github.com/BobTheZombie/Scooter-Speedometer-)");
            if(connection.getResponseCode()!=200)throw new Exception("SparrowMap HTTP "+connection.getResponseCode());
            BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream()));StringBuilder body=new StringBuilder();String line;while((line=reader.readLine())!=null)body.append(line);reader.close();
            JSONArray source=new JSONArray(body.toString()),result=new JSONArray();
            for(int i=0;i<source.length();i++){JSONObject item=source.getJSONObject(i);if(!"police".equalsIgnoreCase(item.optString("vclass"))||!"confirmed".equalsIgnoreCase(item.optString("reviewed")))continue;double lat=item.optDouble("lat",999),lon=item.optDouble("lon",999);long ts=(long)item.optDouble("ts",0);double distance=distanceMeters(latitude,longitude,lat,lon);if(now-ts>MAX_AGE_SECONDS||distance>RADIUS_METERS)continue;result.put(new JSONObject().put("id",item.optLong("id")).put("latitude",lat).put("longitude",lon).put("timestamp",ts).put("age_minutes",Math.max(0,(now-ts)/60)).put("distance_m",Math.round(distance)));}
            String json=result.toString();main.post(()->callback.complete(true,json,""));
        }catch(Exception error){String message=error.getMessage()==null?"Police sightings unavailable":error.getMessage();main.post(()->callback.complete(false,"[]",message));}
        finally{if(connection!=null)connection.disconnect();}
    });}
    public void shutdown(){worker.shutdownNow();}
    private static double distanceMeters(double lat1,double lon1,double lat2,double lon2){double p1=Math.toRadians(lat1),p2=Math.toRadians(lat2),dp=Math.toRadians(lat2-lat1),dl=Math.toRadians(lon2-lon1);double a=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);return 6371000d*2d*Math.atan2(Math.sqrt(a),Math.sqrt(1d-a));}
}
