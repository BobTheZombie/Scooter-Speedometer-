package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.BatteryManager;
import android.os.SystemClock;
import java.util.Locale;

/** Foreground-only context producer for the shared Copilot alert queue. */
public final class RiderCopilot {
    public interface StatusListener { void onCopilotStatus(String status); }
    private final SharedPreferences prefs;private final BatteryManager battery;private final CopilotAlertQueue alerts;private final StatusListener listener;
    private boolean active;private long startedAt,lastMovingAt,lastBreakAlert,lastWeatherAdvice;private int lastBatteryBand=100,badGpsSamples;private boolean gpsWasWeak;private float peakSpeed;
    public RiderCopilot(Context context,StatusListener listener){Context app=context.getApplicationContext();this.listener=listener;prefs=app.getSharedPreferences("speedometer",Context.MODE_PRIVATE);battery=(BatteryManager)app.getSystemService(Context.BATTERY_SERVICE);alerts=CopilotAlertQueue.get(app);}
    public boolean enabled(){return prefs.getBoolean("rider_copilot_enabled",true);}
    public void setEnabled(boolean enabled){prefs.edit().putBoolean("rider_copilot_enabled",enabled).apply();if(!enabled)stop();}
    public void start(){if(!enabled())return;active=true;startedAt=SystemClock.elapsedRealtime();lastMovingAt=startedAt;badGpsSamples=0;gpsWasWeak=false;peakSpeed=0;status("COPILOT ADAPTIVE • READY");}
    public void stop(){active=false;}
    public void shutdown(){stop();}
    public void update(Location fix,float speedMps,double tripMeters,int satellites,WeatherRepository.WeatherData weather){
        if(!active||!enabled())return;long now=SystemClock.elapsedRealtime();boolean adaptive=prefs.getBoolean("copilot_adaptive",true),moving=speedMps>1.4f;peakSpeed=Math.max(peakSpeed,speedMps);if(moving)lastMovingAt=now;
        boolean weak=fix==null||fix.getAccuracy()>45f||satellites<3;badGpsSamples=weak?Math.min(20,badGpsSamples+1):0;
        if(prefs.getBoolean("copilot_gps_alerts",true)&&badGpsSamples>=6&&now-startedAt>20000L){gpsWasWeak=true;enqueue(CopilotAlertQueue.HAZARD,"gps-low","GPS accuracy has stayed low. Slow down if directions stop matching the road, and keep a clear view of the sky.",5*60*1000L,"GPS SIGNAL LOW");}
        else if(gpsWasWeak&&!weak&&fix!=null&&fix.getAccuracy()<=25f){gpsWasWeak=false;enqueue(CopilotAlertQueue.INFO,"gps-recovered:"+(now/60000L),"GPS accuracy recovered.",60000L,"GPS RECOVERED");}
        int percent=battery==null?-1:battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),band=percent<=10?10:percent<=20?20:100;
        if(prefs.getBoolean("copilot_battery_alerts",true)&&percent>=0&&band<lastBatteryBand){lastBatteryBand=band;double miles=tripMeters/1609.344;String context=adaptive&&miles>=1?String.format(Locale.US," You are %.1f miles into this ride.",miles):"";enqueue(band<=10?CopilotAlertQueue.CRITICAL:CopilotAlertQueue.HAZARD,"battery-"+band,"Phone battery is at "+percent+" percent."+context+(band<=10?" Consider ending navigation or connecting power.":""),20*60*1000L,"BATTERY "+percent+"%");}else if(band==100)lastBatteryBand=100;
        if(prefs.getBoolean("copilot_wind_alerts",true)&&weather!=null&&weather.upcomingWindGust>=35){String advice=moving&&speedMps>8?" Reduce speed and leave extra room for crosswinds.":" Use extra caution before continuing.";enqueue(CopilotAlertQueue.HAZARD,"wind:"+(System.currentTimeMillis()/3600000L),"Strong wind gusts are possible."+advice,60*60*1000L,"STRONG WIND");}
        if(adaptive&&prefs.getBoolean("copilot_ride_coaching",true)&&moving&&now-startedAt>=75*60*1000L&&now-lastBreakAlert>=45*60*1000L){lastBreakAlert=now;enqueue(CopilotAlertQueue.INFO,"break:"+(now/(45*60*1000L)),"You have been riding for over 75 minutes. Plan a safe stop to stretch, hydrate, and check the scooter.",45*60*1000L,"BREAK RECOMMENDED");}
        if(adaptive&&weather!=null&&weather.precipitationMinutes>=0&&weather.precipitationMinutes<=20&&moving&&now-lastWeatherAdvice>15*60*1000L){lastWeatherAdvice=now;String type=weather.precipitationCode>=95?"Thunderstorms":weather.precipitationCode>=71&&weather.precipitationCode<=86?"Snow":"Rain";String action=weather.precipitationCode>=95?" Consider shelter before it arrives.":speedMps>7?" Reduce speed before roads become slick.":" Roads may become slick.";enqueue(weather.precipitationCode>=95?CopilotAlertQueue.HAZARD:CopilotAlertQueue.WEATHER,"adaptive-weather:"+(System.currentTimeMillis()/900000L),type+" expected in about "+Math.max(1,weather.precipitationMinutes)+" minutes."+action,15*60*1000L,"WEATHER AHEAD");}
    }
    public void announceBriefing(WeatherRepository.WeatherData weather,Location fix,float speedMps,double tripMeters,int satellites){if(!active||!enabled())return;StringBuilder m=new StringBuilder("Rider Copilot briefing. ");long minutes=Math.max(0,(SystemClock.elapsedRealtime()-startedAt)/60000L);if(minutes>1)m.append("Ride time ").append(minutes).append(" minutes. ");if(fix==null)m.append("Waiting for GPS. ");else if(fix.getAccuracy()<=15)m.append("GPS accuracy is excellent. ");else if(fix.getAccuracy()<=35)m.append("GPS is locked. ");else m.append("GPS accuracy is limited. ");if(weather!=null){m.append("It is ").append(Math.round(weather.temperature)).append(" degrees and ").append(weather.condition().toLowerCase(Locale.US)).append(". ");if(weather.precipitationMinutes>=0&&weather.precipitationMinutes<=60)m.append(weather.precipitationCode>=95?"Thunderstorms":weather.precipitationCode>=71&&weather.precipitationCode<=86?"Snow":"Rain").append(" expected in ").append(Math.max(1,weather.precipitationMinutes)).append(" minutes. ");}if(tripMeters>=160.934)m.append("Trip distance ").append(String.format(Locale.US,"%.1f",tripMeters/1609.344)).append(" miles. ");if(peakSpeed>1)m.append("Peak speed ").append(Math.round(peakSpeed*2.23694f)).append(" miles per hour. ");int percent=battery==null?-1:battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);if(percent>=0)m.append("Phone battery ").append(percent).append(" percent.");enqueue(CopilotAlertQueue.INFO,"briefing:"+System.currentTimeMillis(),m.toString(),0,"RIDE BRIEFING");}
    public void repeatLast(){alerts.repeatLast();status("REPEATING ALERT");}
    public void speak(String message,String status){enqueue(CopilotAlertQueue.INFO,"copilot:"+message.hashCode(),message,0,status);}
    private void enqueue(int priority,String key,String message,long cooldown,String status){alerts.enqueue(priority,key,message,cooldown);status(status);}
    private void status(String value){if(listener!=null)listener.onCopilotStatus(value);}
}
