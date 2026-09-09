package com.stankhouse.scooterspeedometer;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

/** Tracks whether any app screen is visible so private notification processing stops with the UI. */
public class ScooterApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private int visibleActivities;
    @Override public void onCreate(){super.onCreate();getSharedPreferences("speedometer",MODE_PRIVATE).edit().putBoolean("app_visible",false).apply();registerActivityLifecycleCallbacks(this);}
    @Override public void onActivityStarted(Activity activity){visibleActivities++;if(visibleActivities==1)setVisible(true);}
    @Override public void onActivityStopped(Activity activity){visibleActivities=Math.max(0,visibleActivities-1);if(visibleActivities==0&&!activity.isChangingConfigurations())setVisible(false);}
    private void setVisible(boolean visible){getSharedPreferences("speedometer",MODE_PRIVATE).edit().putBoolean("app_visible",visible).apply();sendBroadcast(new Intent(MediaAccessService.ACTION_APP_VISIBILITY_CHANGED).setPackage(getPackageName()));}
    @Override public void onActivityCreated(Activity a,Bundle b){}@Override public void onActivityResumed(Activity a){}@Override public void onActivityPaused(Activity a){}@Override public void onActivitySaveInstanceState(Activity a,Bundle b){}@Override public void onActivityDestroyed(Activity a){}
}
