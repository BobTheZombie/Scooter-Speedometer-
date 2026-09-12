package com.stankhouse.scooterspeedometer;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.LinkedHashSet;

/** Explicit opt-in, DoorDash-only screen text capture for offer fields omitted from notifications. */
public class DasherAccessibilityService extends AccessibilityService {
    private int lastFingerprint;
    private long lastCapture;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event==null||event.getPackageName()==null)return;
        String packageName=event.getPackageName().toString().toLowerCase(java.util.Locale.US);
        if(!packageName.contains("doordash"))return;
        android.content.SharedPreferences prefs=getSharedPreferences("speedometer",MODE_PRIVATE);
        if(!prefs.getBoolean("dasher_mode",false)||!prefs.getBoolean("dasher_screen_capture",false)||!prefs.getBoolean("dasher_shift_active",false))return;
        long now=SystemClock.elapsedRealtime();if(now-lastCapture<700)return;
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
        LinkedHashSet<String> values=new LinkedHashSet<>();collect(root,values,0);
        StringBuilder text=new StringBuilder();for(String value:values){if(text.length()>0)text.append(" • ");text.append(value);}
        String raw=text.toString();if(raw.length()<12||(!raw.contains("$")&&!raw.toLowerCase(java.util.Locale.US).contains("offer")))return;
        int fingerprint=raw.hashCode();if(fingerprint==lastFingerprint)return;lastFingerprint=fingerprint;lastCapture=now;
        String key="screen:"+fingerprint;DeliveryCockpit.recordOffer(this,key,"DoorDash offer",raw,"Dasher screen","screen");
        prefs.edit().putString("dasher_key",key).putString("dasher_package",event.getPackageName().toString())
                .putString("dasher_title","DoorDash offer").putString("dasher_body",raw).putString("dasher_sub","Dasher screen")
                .putLong("dasher_time",System.currentTimeMillis()).apply();
        sendBroadcast(new android.content.Intent(MediaAccessService.ACTION_DASHER_UPDATE).setPackage(getPackageName()));
    }

    private void collect(AccessibilityNodeInfo node,LinkedHashSet<String> values,int depth){if(node==null||depth>18)return;CharSequence text=node.getText();if(!TextUtils.isEmpty(text)){String clean=text.toString().trim().replaceAll("\\s+"," ");if(clean.length()>1&&clean.length()<300)values.add(clean);}CharSequence description=node.getContentDescription();if(!TextUtils.isEmpty(description)){String clean=description.toString().trim().replaceAll("\\s+"," ");if(clean.length()>1&&clean.length()<300)values.add(clean);}for(int i=0;i<node.getChildCount();i++)collect(node.getChild(i),values,depth+1);}
    @Override public void onInterrupt() { }
}
