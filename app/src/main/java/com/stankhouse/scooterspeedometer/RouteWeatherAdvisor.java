package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Samples forecast conditions ahead along the active route and announces meaningful changes. */
public final class RouteWeatherAdvisor {
    public interface Callback { void onRouteWeather(String summary); }
    private final SharedPreferences prefs;
    private final CopilotAlertQueue alerts;
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean fetching;

    public RouteWeatherAdvisor(Context context){prefs=context.getSharedPreferences("speedometer",Context.MODE_PRIVATE);alerts=CopilotAlertQueue.get(context);}
    public boolean enabled(){return prefs.getBoolean("copilot_route_weather",true);}
    public void check(List<Location> route,double durationSeconds,Callback callback){
        if(!enabled()||fetching||route==null||route.size()<4)return;
        fetching=true;List<Location> samples=new ArrayList<>();
        for(double fraction:new double[]{.25,.5,.75})samples.add(route.get(Math.min(route.size()-1,(int)Math.round((route.size()-1)*fraction))));
        network.execute(()->{String best="";int bestPriority=0;try{
            for(int i=0;i<samples.size();i++){double fraction=(i+1)*.25,minutes=Math.max(1,durationSeconds*fraction/60d);Forecast f=forecast(samples.get(i),minutes);String area=i==0?"on the first part of your route":i==1?"near the middle of your route":"later on your route";
                String message="";int priority=CopilotAlertQueue.WEATHER;
                if(f.code>=95&&f.chance>=40)message="Thunderstorms may affect you in about "+Math.round(minutes)+" minutes, "+area+".";
                else if(isSnow(f.code)&&f.chance>=45)message="Snow may affect you in about "+Math.round(minutes)+" minutes, "+area+".";
                else if(isRain(f.code)&&f.chance>=50)message="Rain may affect you in about "+Math.round(minutes)+" minutes, "+area+".";
                else if(f.gust>=35){message="Wind gusts near "+Math.round(f.gust)+" miles per hour are forecast "+area+".";priority=CopilotAlertQueue.HAZARD;}
                if(!message.isEmpty()&&priority>=bestPriority){best=message;bestPriority=priority;}
            }
        }catch(Exception ignored){}String result=best;int priority=bestPriority;fetching=false;main.post(()->{if(!result.isEmpty()){alerts.enqueue(priority,"route-weather:"+(System.currentTimeMillis()/1800000L),result,30*60*1000L,20*60*1000L);prefs.edit().putString("copilot_route_weather_last",result).putLong("copilot_route_weather_time",System.currentTimeMillis()).apply();}if(callback!=null)callback.onRouteWeather(result);});});
    }
    private Forecast forecast(Location p,double arrivalMinutes)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(String.format(Locale.US,"https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f&hourly=precipitation_probability,weather_code,wind_gusts_10m&wind_speed_unit=mph&forecast_hours=8&timezone=auto",p.getLatitude(),p.getLongitude())).openConnection();try{c.setConnectTimeout(7000);c.setReadTimeout(7000);c.setRequestProperty("User-Agent","Scooter-Speedometer/8.2");BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()));StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null)b.append(line);r.close();JSONObject h=new JSONObject(b.toString()).getJSONObject("hourly");int index=Math.max(0,Math.min(h.getJSONArray("weather_code").length()-1,(int)Math.floor(arrivalMinutes/60d)));return new Forecast(h.getJSONArray("precipitation_probability").optInt(index),h.getJSONArray("weather_code").optInt(index),h.getJSONArray("wind_gusts_10m").optDouble(index));}finally{c.disconnect();}}
    private boolean isRain(int c){return c>=51&&c<=67||c>=80&&c<=82;}
    private boolean isSnow(int c){return c>=71&&c<=77||c>=85&&c<=86;}
    public void shutdown(){network.shutdownNow();}
    private static final class Forecast{final int chance,code;final double gust;Forecast(int p,int c,double g){chance=p;code=c;gust=g;}}
}
