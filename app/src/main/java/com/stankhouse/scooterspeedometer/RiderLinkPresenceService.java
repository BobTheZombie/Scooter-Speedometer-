package com.stankhouse.scooterspeedometer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

public class RiderLinkPresenceService extends Service implements LocationListener {
    public static final String ACTION_STOP = "com.stankhouse.scooterspeedometer.STOP_RIDERLINK";
    private LocationManager locations; private RiderLinkClient client; private long lastUpload;
    @Override public void onCreate() { super.onCreate(); client=new RiderLinkClient(this); locations=(LocationManager)getSystemService(LOCATION_SERVICE); createChannel(); }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && ACTION_STOP.equals(intent.getAction())) { stopSharing(); return START_NOT_STICKY; }
        startForeground(3107, notification());
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
            try { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,3000L,3f,this); } catch(SecurityException ignored){}
        return START_STICKY;
    }
    private Notification notification(){
        Intent open=new Intent(this,RiderLinkActivity.class); PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,RiderLinkPresenceService.class).setAction(ACTION_STOP); PendingIntent stopPi=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"riderlink_live"):new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("RiderLink LIVE")
                .setContentText("Your location is visible to nearby riders").setOngoing(true).setContentIntent(content)
                .addAction(new Notification.Action.Builder(null,"STOP SHARING",stopPi).build()).build();
    }
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel("riderlink_live","RiderLink live location",NotificationManager.IMPORTANCE_LOW);c.setDescription("Shown while RiderLink location sharing is active");getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    @Override public void onLocationChanged(Location location){long now=System.currentTimeMillis();if(now-lastUpload<8000)return;lastUpload=now;client.refreshSession((ok,m,b)->{if(ok)client.updatePresence(location.getLatitude(),location.getLongitude(),location.getAccuracy(),"nearby",(done,msg,data)->{});});}
    private void stopSharing(){getSharedPreferences("riderlink_session",MODE_PRIVATE).edit().putBoolean("live",false).apply();try{locations.removeUpdates(this);}catch(SecurityException ignored){}client.goInvisible((ok,m,b)->stopSelf());}
    @Override public void onDestroy(){try{locations.removeUpdates(this);}catch(SecurityException ignored){}if(client!=null)client.shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onProviderEnabled(String provider){}
    @Override public void onProviderDisabled(String provider){}
}
