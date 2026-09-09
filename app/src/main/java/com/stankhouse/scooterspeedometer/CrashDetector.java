package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;

/** Conservative impact detector: significant riding speed plus a high-g impact and sudden stop. */
public final class CrashDetector implements SensorEventListener {
    public interface Callback{void probableCrash(float forceG);}
    private final SensorManager sensors;private final Sensor accelerometer;private final Callback callback;private boolean enabled;private float recentSpeed;private long movingAt,impactAt,lastAlert;
    public CrashDetector(Context c,Callback cb){sensors=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);accelerometer=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);callback=cb;}
    public void setEnabled(boolean value){if(enabled==value)return;enabled=value;if(value&&accelerometer!=null)sensors.registerListener(this,accelerometer,SensorManager.SENSOR_DELAY_GAME);else sensors.unregisterListener(this);}
    public void update(Location l){if(!enabled||l==null)return;float speed=l.hasSpeed()?l.getSpeed():0;if(speed>3.5f){recentSpeed=speed;movingAt=System.currentTimeMillis();}if(impactAt>0&&System.currentTimeMillis()-impactAt<4500&&speed<1.2f&&recentSpeed>3.5f&&System.currentTimeMillis()-movingAt<8000&&System.currentTimeMillis()-lastAlert>120000){lastAlert=System.currentTimeMillis();impactAt=0;callback.probableCrash(0);}}
    @Override public void onSensorChanged(SensorEvent e){if(!enabled)return;float g=(float)Math.sqrt(e.values[0]*e.values[0]+e.values[1]*e.values[1]+e.values[2]*e.values[2])/SensorManager.GRAVITY_EARTH;if(g>3.8f&&recentSpeed>3.5f)impactAt=System.currentTimeMillis();}
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    public void stop(){setEnabled(false);}
}
