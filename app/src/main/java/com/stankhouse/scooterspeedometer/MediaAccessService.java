package com.stankhouse.scooterspeedometer;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;

/** Authorizes media access and mirrors DoorDash driver notifications locally for Dasher Mode. */
public class MediaAccessService extends NotificationListenerService {
    public static final String ACTION_DASHER_UPDATE = "com.stankhouse.scooterspeedometer.DASHER_UPDATE";
    public static final String ACTION_HUD_UPDATE = "com.stankhouse.scooterspeedometer.HUD_UPDATE";
    private TextToSpeech speech;
    private boolean speechReady;

    @Override public void onCreate() {
        super.onCreate();
        speech = new TextToSpeech(this, status -> {
            speechReady = status == TextToSpeech.SUCCESS;
            if (speechReady) speech.setLanguage(java.util.Locale.US);
            if(speechReady) VoiceSettings.apply(this,speech);
        });
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        updateHud(sbn,true);
        if (!isDoorDash(sbn) || !getSharedPreferences("speedometer", MODE_PRIVATE)
                .getBoolean("dasher_mode", false)) return;
        Bundle extras = sbn.getNotification().extras;
        String title = value(extras.getCharSequence(Notification.EXTRA_TITLE));
        String body = value(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (TextUtils.isEmpty(body)) body = value(extras.getCharSequence(Notification.EXTRA_TEXT));
        String sub = value(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) for (CharSequence line : lines) if (line != null && !body.contains(line)) body += " • " + line;
        android.content.SharedPreferences prefs = getSharedPreferences("speedometer", MODE_PRIVATE);
        boolean newOffer = !sbn.getKey().equals(prefs.getString("dasher_key", ""));
        prefs.edit()
                .putString("dasher_key", sbn.getKey()).putString("dasher_package", sbn.getPackageName())
                .putString("dasher_title", title).putString("dasher_body", body)
                .putString("dasher_sub", sub).putLong("dasher_time", System.currentTimeMillis()).apply();
        DeliveryCockpit.recordOffer(this, sbn.getKey(), title, body, sub);
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
        updateHud(sbn,false);
        if (!isDoorDash(sbn)) return;
        String current = getSharedPreferences("speedometer", MODE_PRIVATE).getString("dasher_key", "");
        if (!sbn.getKey().equals(current)) return;
        getSharedPreferences("speedometer", MODE_PRIVATE).edit().remove("dasher_key")
                .remove("dasher_title").remove("dasher_body").remove("dasher_sub").apply();
        sendBroadcast(new Intent(ACTION_DASHER_UPDATE).setPackage(getPackageName()));
    }

    @Override public void onListenerConnected(){super.onListenerConnected();updateHud(null,false);}

    private void updateHud(StatusBarNotification changed,boolean posted){
        android.content.SharedPreferences prefs=getSharedPreferences("speedometer",MODE_PRIVATE);
        if(changed!=null&&posted&&!changed.isOngoing()&&!isGroupSummary(changed.getNotification())){
            Bundle extras=changed.getNotification().extras;String title=value(extras.getCharSequence(Notification.EXTRA_TITLE));String body=value(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));if(TextUtils.isEmpty(body))body=value(extras.getCharSequence(Notification.EXTRA_TEXT));
            boolean message=isMessage(changed),call=isCall(changed);if(message||call){prefs.edit().putString("hud_title",title).putString("hud_body",body).putString("hud_type",call?"call":"message").putLong("hud_time",System.currentTimeMillis()).apply();String last=prefs.getString("hud_last_spoken_key","");if(!changed.getKey().equals(last)&&speechReady&&((message&&prefs.getBoolean("hud_speak_messages",true))||(call&&prefs.getBoolean("hud_speak_calls",true)))){String announcement=call?"Incoming call from "+safe(title):"Message from "+safe(title)+". "+safe(body);speech.speak(announcement,TextToSpeech.QUEUE_ADD,null,"hud-"+changed.getId());prefs.edit().putString("hud_last_spoken_key",changed.getKey()).apply();}}
        }
        int messages=0,calls=0;try{StatusBarNotification[] active=getActiveNotifications();if(active!=null)for(StatusBarNotification item:active){if(isGroupSummary(item.getNotification()))continue;if(isMessage(item))messages++;else if(isCall(item))calls++;}}catch(Exception ignored){}
        prefs.edit().putInt("hud_message_count",messages).putInt("hud_call_count",calls).apply();sendBroadcast(new Intent(ACTION_HUD_UPDATE).setPackage(getPackageName()));
    }
    private String safe(String value){return TextUtils.isEmpty(value)?"unknown":value.replaceAll("https?://\\S+","a link");}
    private boolean isGroupSummary(Notification notification){return notification!=null&&(notification.flags&Notification.FLAG_GROUP_SUMMARY)!=0;}
    private boolean isMessage(StatusBarNotification sbn){if(sbn==null)return false;Notification n=sbn.getNotification();String category=n.category;String pkg=sbn.getPackageName().toLowerCase(java.util.Locale.US);return Notification.CATEGORY_MESSAGE.equals(category)||n.extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON)!=null||pkg.contains("messaging")||pkg.contains("whatsapp")||pkg.contains("signal");}
    private boolean isCall(StatusBarNotification sbn){if(sbn==null)return false;Notification n=sbn.getNotification();String pkg=sbn.getPackageName().toLowerCase(java.util.Locale.US);return Notification.CATEGORY_CALL.equals(n.category)||pkg.contains("dialer")||pkg.contains("incallui");}

    private boolean isDoorDash(StatusBarNotification sbn) {
        return sbn != null && sbn.getPackageName() != null &&
                sbn.getPackageName().toLowerCase(java.util.Locale.US).contains("doordash");
    }
    private String value(CharSequence value) { return value == null ? "" : value.toString().trim(); }

    @Override public void onDestroy() {
        if (speech != null) speech.shutdown();
        super.onDestroy();
    }
}
