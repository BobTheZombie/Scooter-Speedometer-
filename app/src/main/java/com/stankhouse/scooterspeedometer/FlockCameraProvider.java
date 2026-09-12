package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hourly public ALPR positions from FlockHopper/DeFlock, filtered locally near the rider. */
public final class FlockCameraProvider {
    public interface Callback { void complete(boolean ok, String json, String message); }
    private static final String INDEX_URL = "https://tiles.dontgetflocked.com/cameras-us-hourly-index.bin";
    private static final long CACHE_MS = 30L * 60L * 1000L;
    private static final double RADIUS_METERS = 50000d;
    private static final int MAX_RESULTS = 750;
    private final File cache;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public FlockCameraProvider(Context context) { cache = new File(context.getCacheDir(), "flockhopper-us-index-v1.bin"); }

    public void nearby(double latitude, double longitude, Callback callback) {
        worker.execute(() -> {
            try {
                byte[] bytes = loadIndex();
                JSONArray result = decodeNearby(bytes, latitude, longitude);
                main.post(() -> callback.complete(true, result.toString(), ""));
            } catch (Exception error) {
                main.post(() -> callback.complete(false, "[]", error.getMessage() == null ? "Camera feed unavailable" : error.getMessage()));
            }
        });
    }

    private byte[] loadIndex() throws Exception {
        if (cache.isFile() && cache.length() > 16 && System.currentTimeMillis() - cache.lastModified() < CACHE_MS) return readAll(new FileInputStream(cache));
        HttpURLConnection connection = (HttpURLConnection) new URL(INDEX_URL).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "Scooter-Speedometer/6.3 (github.com/BobTheZombie/Scooter-Speedometer-)");
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("Referer", "https://dontgetflocked.com/");
        try {
            if (connection.getResponseCode() != 200) throw new Exception("Camera feed HTTP " + connection.getResponseCode());
            byte[] bytes = readAll(connection.getInputStream()); validate(bytes);
            File temp = new File(cache.getParentFile(), cache.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
            if (!temp.renameTo(cache)) { try (FileOutputStream out = new FileOutputStream(cache)) { out.write(bytes); } temp.delete(); }
            return bytes;
        } catch (Exception networkError) {
            if (cache.isFile() && cache.length() > 16) return readAll(new FileInputStream(cache));
            throw networkError;
        } finally { connection.disconnect(); }
    }

    private JSONArray decodeNearby(byte[] bytes, double latitude, double longitude) throws Exception {
        validate(bytes); ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); int count = b.getInt(8);
        int latOffset = 16, lonOffset = 16 + count * 4, brandOffset = 16 + count * 8;
        int minLat = (int)Math.round((latitude - RADIUS_METERS / 111320d) * 1e6);
        int maxLat = (int)Math.round((latitude + RADIUS_METERS / 111320d) * 1e6);
        int start = lowerBound(b, latOffset, count, minLat), end = upperBound(b, latOffset, count, maxLat);
        List<Camera> matches = new ArrayList<>();
        for (int i = start; i < end; i++) {
            double lat = b.getInt(latOffset + i * 4) / 1e6, lon = b.getInt(lonOffset + i * 4) / 1e6;
            double distance = distanceMeters(latitude, longitude, lat, lon);
            if (distance <= RADIUS_METERS) matches.add(new Camera(lat, lon, b.get(brandOffset + i) & 0xff, distance));
        }
        Collections.sort(matches, Comparator.comparingDouble(c -> c.distance));
        JSONArray json = new JSONArray();
        for (int i = 0; i < Math.min(MAX_RESULTS, matches.size()); i++) {
            Camera c = matches.get(i); json.put(new JSONObject().put("latitude", c.lat).put("longitude", c.lon).put("brand_id", c.brand).put("distance_m", Math.round(c.distance)));
        }
        return json;
    }

    private static void validate(byte[] bytes) throws Exception {
        if (bytes.length < 16 || bytes[0] != 'F' || bytes[1] != 'H' || bytes[2] != 'I' || bytes[3] != 'X') throw new Exception("Invalid camera index");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); int version = b.getInt(4), count = b.getInt(8);
        if (version != 1 || count < 0 || bytes.length != 16 + count * 9) throw new Exception("Unsupported camera index");
    }
    private static int lowerBound(ByteBuffer b, int offset, int count, int target) { int lo=0,hi=count;while(lo<hi){int m=(lo+hi)>>>1;if(b.getInt(offset+m*4)<target)lo=m+1;else hi=m;}return lo; }
    private static int upperBound(ByteBuffer b, int offset, int count, int target) { int lo=0,hi=count;while(lo<hi){int m=(lo+hi)>>>1;if(b.getInt(offset+m*4)<=target)lo=m+1;else hi=m;}return lo; }
    private static double distanceMeters(double lat1,double lon1,double lat2,double lon2){double p1=Math.toRadians(lat1),p2=Math.toRadians(lat2),dp=Math.toRadians(lat2-lat1),dl=Math.toRadians(lon2-lon1);double a=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);return 6371000d*2d*Math.atan2(Math.sqrt(a),Math.sqrt(1d-a));}
    private static byte[] readAll(InputStream input) throws Exception { try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] chunk=new byte[32768];int n;while((n=in.read(chunk))>=0)out.write(chunk,0,n);return out.toByteArray();} }
    public void shutdown(){worker.shutdownNow();}
    private static final class Camera { final double lat,lon,distance;final int brand;Camera(double a,double o,int b,double d){lat=a;lon=o;brand=b;distance=d;} }
}
