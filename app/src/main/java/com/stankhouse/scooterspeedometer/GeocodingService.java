package com.stankhouse.scooterspeedometer;

import android.location.Location;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Key-free geocoding with legal autocomplete and a U.S. address fallback. */
final class GeocodingService {
    static final class Result {
        final String label;
        final double latitude;
        final double longitude;
        final String source;

        Result(String label, double latitude, double longitude, String source) {
            this.label = label;
            this.latitude = latitude;
            this.longitude = longitude;
            this.source = source;
        }
    }

    private GeocodingService() { }

    /** Photon explicitly supports search-as-you-type. Census adds an exact U.S. match. */
    static List<String> suggestions(String query, Location location) throws Exception {
        List<String> labels = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (Result result : photon(query, location, 7)) {
            if (unique.add(result.label)) labels.add(result.label);
        }
        if (looksLikeStreetAddress(query)) {
            Result census = census(query);
            if (census != null && unique.add(census.label)) labels.add(0, census.label);
        }
        return labels;
    }

    /** Resolve with OSM first, Census for missing U.S. addresses, then typo-tolerant Photon. */
    static Result geocode(String query, Location location) throws Exception {
        Result osm = nominatim(query, location);
        if (osm != null) return osm;
        Result census = census(query);
        if (census != null) return census;
        List<Result> photon = photon(query, location, 1);
        if (!photon.isEmpty()) return photon.get(0);
        throw new Exception("Address not found. Try including the street number, city, state, and ZIP.");
    }

    private static Result nominatim(String query, Location location) throws Exception {
        StringBuilder endpoint = new StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&addressdetails=1&q=")
                .append(Uri.encode(query));
        if (location != null) {
            endpoint.append(String.format(Locale.US, "&viewbox=%.5f,%.5f,%.5f,%.5f&bounded=0",
                    location.getLongitude() - .7, location.getLatitude() + .5,
                    location.getLongitude() + .7, location.getLatitude() - .5));
        }
        JSONArray items = new JSONArray(get(endpoint.toString()));
        if (items.length() == 0) return null;
        JSONObject item = items.getJSONObject(0);
        return new Result(item.optString("display_name", query), item.getDouble("lat"),
                item.getDouble("lon"), "OpenStreetMap");
    }

    private static List<Result> photon(String query, Location location, int limit) throws Exception {
        StringBuilder endpoint = new StringBuilder("https://photon.komoot.io/api/?limit=")
                .append(limit).append("&q=").append(Uri.encode(query));
        if (location != null) endpoint.append(String.format(Locale.US, "&lat=%.6f&lon=%.6f",
                location.getLatitude(), location.getLongitude()));
        JSONArray features = new JSONObject(get(endpoint.toString())).optJSONArray("features");
        List<Result> found = new ArrayList<>();
        if (features == null) return found;
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            JSONArray coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates");
            JSONObject p = feature.optJSONObject("properties");
            String label = photonLabel(p, query);
            found.add(new Result(label, coordinates.getDouble(1), coordinates.getDouble(0), "Photon/OSM"));
        }
        return found;
    }

    private static String photonLabel(JSONObject p, String fallback) {
        if (p == null) return fallback;
        List<String> parts = new ArrayList<>();
        String house = p.optString("housenumber", "").trim();
        String street = p.optString("street", "").trim();
        String name = p.optString("name", "").trim();
        String first = (!house.isEmpty() && !street.isEmpty()) ? house + " " + street :
                (!name.isEmpty() ? name : street);
        add(parts, first); add(parts, p.optString("city")); add(parts, p.optString("state"));
        add(parts, p.optString("postcode")); add(parts, p.optString("country"));
        return parts.isEmpty() ? fallback : join(parts);
    }

    private static Result census(String query) throws Exception {
        if (!looksLikeStreetAddress(query)) return null;
        String endpoint = "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress?benchmark=Public_AR_Current&format=json&address="
                + Uri.encode(query);
        JSONObject result = new JSONObject(get(endpoint)).getJSONObject("result");
        JSONArray matches = result.optJSONArray("addressMatches");
        if (matches == null || matches.length() == 0) return null;
        JSONObject match = matches.getJSONObject(0);
        JSONObject coordinates = match.getJSONObject("coordinates");
        return new Result(match.optString("matchedAddress", query), coordinates.getDouble("y"),
                coordinates.getDouble("x"), "U.S. Census");
    }

    private static boolean looksLikeStreetAddress(String query) {
        return query != null && query.length() >= 8 && query.matches(".*\\d.*");
    }

    private static String get(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Scooter-Speedometer/7.8 (github.com/BobTheZombie/Scooter-Speedometer-)");
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new Exception("Address provider returned " + status);
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder body = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) body.append(line);
            reader.close();
            return body.toString();
        } finally { connection.disconnect(); }
    }

    private static void add(List<String> parts, String value) {
        if (value != null && !value.trim().isEmpty() && !parts.contains(value.trim())) parts.add(value.trim());
    }

    private static String join(List<String> parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) { if (result.length() > 0) result.append(", "); result.append(part); }
        return result.toString();
    }
}
