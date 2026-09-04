package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local-only DoorDash offer metrics and GPS mileage log. */
public class DeliveryCockpit {
    private static final Pattern PAY = Pattern.compile("\\$\\s*([0-9]+(?:\\.[0-9]{1,2})?)");
    private static final Pattern MILES = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(?:mi|mile|miles)\\b", Pattern.CASE_INSENSITIVE);
    private final SharedPreferences prefs;

    public DeliveryCockpit(Context context) {
        prefs = context.getSharedPreferences("speedometer", Context.MODE_PRIVATE);
        ensureToday();
    }

    public static void recordOffer(Context context, String key, String title, String body, String sub) {
        SharedPreferences p = context.getSharedPreferences("speedometer", Context.MODE_PRIVATE);
        String all = title + "  " + body + "  " + sub;
        double pay = number(PAY.matcher(all));
        double miles = number(MILES.matcher(all));
        p.edit().putFloat("dasher_offer_pay", (float) pay).putFloat("dasher_offer_miles", (float) miles)
                .putFloat("dasher_offer_per_mile", miles > 0 ? (float) (pay / miles) : 0f).apply();
        DeliveryCockpit cockpit = new DeliveryCockpit(context);
        if (!key.equals(p.getString("dasher_last_counted_key", "")) && (pay > 0 || miles > 0)) {
            p.edit().putString("dasher_last_counted_key", key)
                    .putInt("dasher_daily_offers", p.getInt("dasher_daily_offers", 0) + 1)
                    .putFloat("dasher_daily_offer_value", p.getFloat("dasher_daily_offer_value", 0f) + (float) pay).apply();
        }
    }

    private static double number(Matcher matcher) {
        if (!matcher.find()) return 0;
        try { return Double.parseDouble(matcher.group(1)); } catch (Exception ignored) { return 0; }
    }

    private void ensureToday() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (today.equals(prefs.getString("dasher_log_day", ""))) return;
        prefs.edit().putString("dasher_log_day", today).putFloat("dasher_daily_meters", 0f)
                .putInt("dasher_daily_offers", 0).putFloat("dasher_daily_offer_value", 0f)
                .remove("dasher_mileage_lat").remove("dasher_mileage_lon").remove("dasher_mileage_time").apply();
    }

    public boolean shiftActive() { return prefs.getBoolean("dasher_shift_active", false); }
    public void setShiftActive(boolean active) {
        ensureToday();
        boolean was = shiftActive();
        SharedPreferences.Editor edit = prefs.edit().putBoolean("dasher_shift_active", active)
                .remove("dasher_mileage_lat").remove("dasher_mileage_lon").remove("dasher_mileage_time");
        if (active && !was) edit.putFloat("dasher_shift_meters", 0f).putLong("dasher_shift_started", System.currentTimeMillis());
        if (!active && was) edit.putLong("dasher_shift_ended", System.currentTimeMillis());
        edit.apply();
    }

    public void updateMileage(Location location) {
        ensureToday();
        if (!shiftActive() || location == null || location.getAccuracy() > 45f) return;
        long previousTime = prefs.getLong("dasher_mileage_time", 0L);
        if (prefs.contains("dasher_mileage_lat") && previousTime > 0) {
            Location previous = new Location("delivery-log");
            previous.setLatitude(Double.longBitsToDouble(prefs.getLong("dasher_mileage_lat", 0L)));
            previous.setLongitude(Double.longBitsToDouble(prefs.getLong("dasher_mileage_lon", 0L)));
            float distance = previous.distanceTo(location);
            long dt = Math.abs(location.getTime() - previousTime);
            if (dt <= 30000L && distance >= 1f && distance < 300f) {
                prefs.edit().putFloat("dasher_daily_meters", prefs.getFloat("dasher_daily_meters", 0f) + distance)
                        .putFloat("dasher_shift_meters", prefs.getFloat("dasher_shift_meters", 0f) + distance).apply();
            }
        }
        prefs.edit().putLong("dasher_mileage_lat", Double.doubleToRawLongBits(location.getLatitude()))
                .putLong("dasher_mileage_lon", Double.doubleToRawLongBits(location.getLongitude()))
                .putLong("dasher_mileage_time", location.getTime()).apply();
    }

    public double dailyMiles() { ensureToday(); return prefs.getFloat("dasher_daily_meters", 0f) / 1609.344; }
    public double shiftMiles() { return prefs.getFloat("dasher_shift_meters", 0f) / 1609.344; }
    public int dailyOffers() { ensureToday(); return prefs.getInt("dasher_daily_offers", 0); }
    public double dailyOfferValue() { ensureToday(); return prefs.getFloat("dasher_daily_offer_value", 0f); }
    public long shiftMinutes() {
        long start = prefs.getLong("dasher_shift_started", 0L);
        if (start == 0) return 0;
        long end = shiftActive() ? System.currentTimeMillis() : prefs.getLong("dasher_shift_ended", start);
        return Math.max(0, (end - start) / 60000L);
    }
}
