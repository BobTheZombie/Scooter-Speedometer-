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
    private boolean active;private long startedAt;private int lastBatteryBand=100;
    public RiderCopilot(Context context,StatusListener listener){Context app=context.getApplicationContext();this.listener=listener;prefs=app.getSharedPreferences("speedometer",Context.MODE_PRIVATE);battery=(BatteryManager)app.getSystemService(Context.BATTERY_SERVICE);alerts=CopilotAlertQueue.get(app);}
    public boolean enabled(){return prefs.getBoolean("rider_copilot_enabled",true);}
    public void setEnabled(boolean enabled){prefs.edit().putBoolean("rider_copilot_enabled",enabled).apply();if(!enabled)stop();}
    public void start(){if(!enabled())return;active=true;startedAt=SystemClock.elapsedRealtime();status("COPILOT READY");}
    public void stop(){active=false;}
    public void shutdown(){stop();}
    public void update(Location fix,float speedMps,double tripMeters,int satellites,WeatherRepository.WeatherData weather){if(!active||!enabled())return;long now=SystemClock.elapsedRealtime();boolean weak=fix==null||fix.getAccuracy()>45f||satellites<3;if(prefs.getBoolean("copilot_gps_alerts",true)&&weak&&now-startedAt>20000L)enqueue(CopilotAlertQueue.HAZARD,"gps-low","GPS accuracy is low. Keep a clear view of the sky.",5*60*1000L,"GPS SIGNAL LOW");int percent=battery==null?-1:battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);int band=percent<=10?10:percent<=20?20:100;if(prefs.getBoolean("copilot_battery_alerts",true)&&band<lastBatteryBand){lastBatteryBand=band;enqueue(band<=10?CopilotAlertQueue.CRITICAL:CopilotAlertQueue.HAZARD,"battery-"+band,"Phone battery is at "+percent+" percent.",20*60*1000L,"BATTERY "+percent+"%");}else if(band==100)lastBatteryBand=100;if(prefs.getBoolean("copilot_wind_alerts",true)&&weather!=null&&weather.upcomingWindGust>=35)enqueue(CopilotAlertQueue.HAZARD,"wind:"+(System.currentTimeMillis()/3600000L),"Strong wind gusts are possible. Use extra caution.",60*60*1000L,"STRONG WIND");}
    public void announceBriefing(WeatherRepository.WeatherData weather,Location fix,float speedMps,double tripMeters,int satellites){if(!active||!enabled())return;StringBuilder m=new StringBuilder("Rider Copilot briefing. ");if(fix==null)m.append("Waiting for GPS. ");else if(fix.getAccuracy()<=15)m.append("GPS accuracy is excellent. ");else if(fix.getAccuracy()<=35)m.append("GPS is locked. ");else m.append("GPS accuracy is limited. ");if(weather!=null){m.append("It is ").append(Math.round(weather.temperature)).append(" degrees and ").append(weather.condition().toLowerCase(Locale.US)).append(". ");if(weather.precipitationMinutes>=0&&weather.precipitationMinutes<=60)m.append(weather.precipitationCode>=95?"Thunderstorms":weather.precipitationCode>=71&&weather.precipitationCode<=86?"Snow":"Rain").append(" expected in ").append(Math.max(1,weather.precipitationMinutes)).append(" minutes. ");}if(tripMeters>=160.934)m.append("Trip distance ").append(String.format(Locale.US,"%.1f",tripMeters/1609.344)).append(" miles. ");int percent=battery==null?-1:battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);if(percent>=0)m.append("Phone battery ").append(percent).append(" percent.");enqueue(CopilotAlertQueue.INFO,"briefing:"+System.currentTimeMillis(),m.toString(),0,"RIDE BRIEFING");}
    public void repeatLast(){alerts.repeatLast();status("REPEATING ALERT");}
    public void speak(String message,String status){enqueue(CopilotAlertQueue.INFO,"copilot:"+message.hashCode(),message,0,status);}
    private void enqueue(int priority,String key,String message,long cooldown,String status){alerts.enqueue(priority,key,message,cooldown);status(status);}
    private void status(String value){if(listener!=null)listener.onCopilotStatus(value);}
}
