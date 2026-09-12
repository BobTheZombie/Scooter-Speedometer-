package com.stankhouse.scooterspeedometer;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;

/** Authorizes media access and mirrors DoorDash driver notifications locally for Dasher Mode. */
public class MediaAccessService extends NotificationListenerService {
    public static final String ACTION_DASHER_UPDATE = "com.stankhouse.scooterspeedometer.DASHER_UPDATE";
    public static final String ACTION_HUD_UPDATE = "com.stankhouse.scooterspeedometer.HUD_UPDATE";
    public static final String ACTION_MONITORING_CHANGED = "com.stankhouse.scooterspeedometer.MONITORING_CHANGED";
    public static final String ACTION_APP_VISIBILITY_CHANGED = "com.stankhouse.scooterspeedometer.APP_VISIBILITY_CHANGED";
    private TextToSpeech speech;
    private boolean speechReady;
    private final BroadcastReceiver settingsReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(processingAllowed()){createSpeech();updateHud(null,false);}else clearPrivateState();}};

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter filter=new IntentFilter(ACTION_MONITORING_CHANGED);
        filter.addAction(ACTION_APP_VISIBILITY_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(settingsReceiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(settingsReceiver,filter);
        if(!processingAllowed()){clearPrivateState();return;}
        createSpeech();
    }

    private void createSpeech(){
        if(speech!=null)return;
        speech = new TextToSpeech(this, status -> {
            speechReady = status == TextToSpeech.SUCCESS;
            if (speechReady) speech.setLanguage(java.util.Locale.US);
            if(speechReady) VoiceSettings.apply(this,speech);
        });
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if(!processingAllowed()){clearPrivateState();return;}
        updateHud(sbn,true);
        if (!categoryEnabled("monitor_delivery",true)||!isDoorDash(sbn) || !getSharedPreferences("speedometer", MODE_PRIVATE).getBoolean("dasher_mode", false)) return;
        Bundle extras = sbn.getNotification().extras;
        String title = value(extras.getCharSequence(Notification.EXTRA_TITLE));
        String body = notificationText(sbn.getNotification());
        String sub = value(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        android.content.SharedPreferences prefs = getSharedPreferences("speedometer", MODE_PRIVATE);
        String offerKey=sbn.getKey()+":"+(title+"|"+body+"|"+sub).hashCode();
        boolean newOffer = !offerKey.equals(prefs.getString("dasher_key", ""));
        prefs.edit()
                .putString("dasher_key", offerKey).putString("dasher_notification_key",sbn.getKey()).putString("dasher_package", sbn.getPackageName())
                .putString("dasher_title", title).putString("dasher_body", body)
                .putString("dasher_sub", sub).putLong("dasher_time", System.currentTimeMillis()).apply();
        DeliveryCockpit.recordOffer(this, offerKey, title, body, sub,"notification");
        if (newOffer && prefs.getBoolean("dasher_voice", true) && speechReady) {
            float pay = prefs.getFloat("dasher_offer_pay", 0f), miles = prefs.getFloat("dasher_offer_miles", 0f);
            String restaurant = prefs.getString("dasher_restaurant", title);
            String announcement = "New DoorDash offer. " + (pay > 0 ? String.format(java.util.Locale.US, "%.2f dollars. ", pay) : "") +
                    (miles > 0 ? String.format(java.util.Locale.US, "%.1f miles. ", miles) : "") + restaurant;
            speech.speak(announcement, TextToSpeech.QUEUE_FLUSH, null, "dasher-offer");
        }
        sendBroadcast(new Intent(ACTION_DASHER_UPDATE).setPackage(getPackageName()));
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if(!processingAllowed())return;
        updateHud(sbn,false);
        if (!isDoorDash(sbn)) return;
        String current = getSharedPreferences("speedometer", MODE_PRIVATE).getString("dasher_notification_key", "");
        if (!sbn.getKey().equals(current)) return;
        getSharedPreferences("speedometer", MODE_PRIVATE).edit().remove("dasher_key")
                .remove("dasher_title").remove("dasher_body").remove("dasher_sub").apply();
        sendBroadcast(new Intent(ACTION_DASHER_UPDATE).setPackage(getPackageName()));
    }

    @Override public void onListenerConnected(){super.onListenerConnected();if(!processingAllowed()){clearPrivateState();return;}createSpeech();updateHud(null,false);}

    private void updateHud(StatusBarNotification changed,boolean posted){
        android.content.SharedPreferences prefs=getSharedPreferences("speedometer",MODE_PRIVATE);
        if(changed!=null&&posted&&!changed.isOngoing()&&!isGroupSummary(changed.getNotification())){
            Bundle extras=changed.getNotification().extras;String title=value(extras.getCharSequence(Notification.EXTRA_TITLE));String body=value(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));if(TextUtils.isEmpty(body))body=value(extras.getCharSequence(Notification.EXTRA_TEXT));
            boolean message=categoryEnabled("monitor_messages",true)&&isMessage(changed),call=categoryEnabled("monitor_calls",true)&&isCall(changed);if(message||call){prefs.edit().putString("hud_title",title).putString("hud_body",body).putString("hud_type",call?"call":"message").putLong("hud_time",System.currentTimeMillis()).apply();String last=prefs.getString("hud_last_spoken_key","");if(!changed.getKey().equals(last)&&speechReady&&((message&&prefs.getBoolean("hud_speak_messages",true))||(call&&prefs.getBoolean("hud_speak_calls",true)))){String announcement=call?"Incoming call from "+safe(title):"Message from "+safe(title)+". "+safe(body);speech.speak(announcement,TextToSpeech.QUEUE_ADD,null,"hud-"+changed.getId());prefs.edit().putString("hud_last_spoken_key",changed.getKey()).apply();}}
        }
        int messages=0,calls=0;try{StatusBarNotification[] active=getActiveNotifications();if(active!=null)for(StatusBarNotification item:active){if(isGroupSummary(item.getNotification()))continue;if(isMessage(item))messages++;else if(isCall(item))calls++;}}catch(Exception ignored){}
        if(!categoryEnabled("monitor_messages",true))messages=0;if(!categoryEnabled("monitor_calls",true))calls=0;
        prefs.edit().putInt("hud_message_count",messages).putInt("hud_call_count",calls).apply();sendBroadcast(new Intent(ACTION_HUD_UPDATE).setPackage(getPackageName()));
    }
    private boolean monitoringEnabled(){return getSharedPreferences("speedometer",MODE_PRIVATE).getBoolean("notification_monitoring",false);}
    private boolean processingAllowed(){android.content.SharedPreferences p=getSharedPreferences("speedometer",MODE_PRIVATE);return monitoringEnabled()&&p.getBoolean("app_visible",false);}
    private boolean categoryEnabled(String key,boolean fallback){return getSharedPreferences("speedometer",MODE_PRIVATE).getBoolean(key,fallback);}
    private void clearPrivateState(){getSharedPreferences("speedometer",MODE_PRIVATE).edit().remove("hud_title").remove("hud_body").remove("hud_type").remove("hud_time").remove("hud_last_spoken_key").putInt("hud_message_count",0).putInt("hud_call_count",0).remove("dasher_key").remove("dasher_title").remove("dasher_body").remove("dasher_sub").apply();sendBroadcast(new Intent(ACTION_HUD_UPDATE).setPackage(getPackageName()));sendBroadcast(new Intent(ACTION_DASHER_UPDATE).setPackage(getPackageName()));if(speech!=null)speech.stop();}
    private String safe(String value){return TextUtils.isEmpty(value)?"unknown":value.replaceAll("https?://\\S+","a link");}
    private boolean isGroupSummary(Notification notification){return notification!=null&&(notification.flags&Notification.FLAG_GROUP_SUMMARY)!=0;}
    private boolean isMessage(StatusBarNotification sbn){if(sbn==null)return false;Notification n=sbn.getNotification();String category=n.category;String pkg=sbn.getPackageName().toLowerCase(java.util.Locale.US);return Notification.CATEGORY_MESSAGE.equals(category)||n.extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON)!=null||pkg.contains("messaging")||pkg.contains("whatsapp")||pkg.contains("signal");}
    private boolean isCall(StatusBarNotification sbn){if(sbn==null)return false;Notification n=sbn.getNotification();String pkg=sbn.getPackageName().toLowerCase(java.util.Locale.US);return Notification.CATEGORY_CALL.equals(n.category)||pkg.contains("dialer")||pkg.contains("incallui");}

    private boolean isDoorDash(StatusBarNotification sbn) {
        return sbn != null && sbn.getPackageName() != null &&
                sbn.getPackageName().toLowerCase(java.util.Locale.US).contains("doordash");
    }
    private String value(CharSequence value) { return value == null ? "" : value.toString().trim(); }
    private String notificationText(Notification notification){java.util.LinkedHashSet<String> values=new java.util.LinkedHashSet<>();Bundle extras=notification.extras;String[] keys={Notification.EXTRA_TEXT,Notification.EXTRA_BIG_TEXT,Notification.EXTRA_SUB_TEXT,Notification.EXTRA_INFO_TEXT,Notification.EXTRA_SUMMARY_TEXT,Notification.EXTRA_TITLE_BIG};for(String key:keys){CharSequence item=extras.getCharSequence(key);if(!TextUtils.isEmpty(item))values.add(item.toString().trim());}CharSequence[] lines=extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);if(lines!=null)for(CharSequence line:lines)if(!TextUtils.isEmpty(line))values.add(line.toString().trim());if(notification.actions!=null)for(Notification.Action action:notification.actions)if(action!=null&&!TextUtils.isEmpty(action.title))values.add(action.title.toString().trim());StringBuilder out=new StringBuilder();for(String item:values){if(out.length()>0)out.append(" • ");out.append(item);}return out.toString();}

    @Override public void onDestroy() {
        try{unregisterReceiver(settingsReceiver);}catch(Exception ignored){}
        if (speech != null) speech.shutdown();
        super.onDestroy();
    }
}
