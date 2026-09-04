package com.stankhouse.scooterspeedometer;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

/** Authorizes media access and mirrors DoorDash driver notifications locally for Dasher Mode. */
public class MediaAccessService extends NotificationListenerService {
    public static final String ACTION_DASHER_UPDATE = "com.stankhouse.scooterspeedometer.DASHER_UPDATE";

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (!isDoorDash(sbn) || !getSharedPreferences("speedometer", MODE_PRIVATE)
                .getBoolean("dasher_mode", false)) return;
        Bundle extras = sbn.getNotification().extras;
        String title = value(extras.getCharSequence(Notification.EXTRA_TITLE));
        String body = value(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (TextUtils.isEmpty(body)) body = value(extras.getCharSequence(Notification.EXTRA_TEXT));
        String sub = value(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        getSharedPreferences("speedometer", MODE_PRIVATE).edit()
                .putString("dasher_key", sbn.getKey()).putString("dasher_package", sbn.getPackageName())
                .putString("dasher_title", title).putString("dasher_body", body)
                .putString("dasher_sub", sub).putLong("dasher_time", System.currentTimeMillis()).apply();
        sendBroadcast(new Intent(ACTION_DASHER_UPDATE).setPackage(getPackageName()));
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!isDoorDash(sbn)) return;
        String current = getSharedPreferences("speedometer", MODE_PRIVATE).getString("dasher_key", "");
        if (!sbn.getKey().equals(current)) return;
        getSharedPreferences("speedometer", MODE_PRIVATE).edit().remove("dasher_key")
                .remove("dasher_title").remove("dasher_body").remove("dasher_sub").apply();
        sendBroadcast(new Intent(ACTION_DASHER_UPDATE).setPackage(getPackageName()));
    }

    private boolean isDoorDash(StatusBarNotification sbn) {
        return sbn != null && sbn.getPackageName() != null &&
                sbn.getPackageName().toLowerCase(java.util.Locale.US).contains("doordash");
    }
    private String value(CharSequence value) { return value == null ? "" : value.toString().trim(); }
}
