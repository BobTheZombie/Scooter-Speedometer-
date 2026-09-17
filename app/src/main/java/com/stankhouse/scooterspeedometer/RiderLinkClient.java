package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import android.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RiderLinkClient {
    public interface Callback { void complete(boolean success, String message, String body); }
    public static final String URL = "https://jwjiujxdcybsjmxqjojl.supabase.co";
    public static final String KEY = "sb_publishable_G4ZDOqnyhCHO8CSg6swLTQ_P0HfD5gG";
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public RiderLinkClient(Context context) { prefs = context.getSharedPreferences("riderlink_session", Context.MODE_PRIVATE); }
    public boolean signedIn() { return !prefs.getString("access", "").isEmpty(); }
    public String userId() { return prefs.getString("user_id", ""); }
    public String email() { return prefs.getString("email", ""); }
    public boolean plusActive(){long expires=prefs.getLong("membership_expires",0);return prefs.getBoolean("membership_plus",false)&&(expires==0||expires>System.currentTimeMillis());}
    public boolean membershipAdmin(){return prefs.getBoolean("membership_admin",false);}

    /** Creates a Supabase OAuth URL using PKCE so no provider secret is stored in the APK. */
    public String oauthUrl(String provider) {
        try {
            byte[] random = new byte[48]; new SecureRandom().nextBytes(random);
            String verifier = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            prefs.edit().putString("oauth_verifier", verifier).putLong("oauth_started", System.currentTimeMillis()).apply();
            return URL + "/auth/v1/authorize?provider=" + Uri.encode(provider)
                    + "&redirect_to=" + Uri.encode("riderlink://auth-callback")
                    + "&code_challenge=" + Uri.encode(challenge) + "&code_challenge_method=s256";
        } catch (Exception e) { return ""; }
    }

    /** Completes either the preferred PKCE response or Supabase's legacy fragment response. */
    public void completeOAuth(Uri redirect, Callback callback) {
        if (redirect == null) { callback.complete(false, "Missing RiderLink sign-in response", ""); return; }
        Uri fragment = Uri.parse("riderlink://auth-callback?" + (redirect.getFragment() == null ? "" : redirect.getFragment()));
        String error = first(redirect.getQueryParameter("error_description"),fragment.getQueryParameter("error_description"));
        if (error == null) error = first(redirect.getQueryParameter("error"),fragment.getQueryParameter("error"));
        if (error != null && !error.isEmpty()) { callback.complete(false, error, ""); return; }
        String code = first(redirect.getQueryParameter("code"),fragment.getQueryParameter("code"));
        String verifier = prefs.getString("oauth_verifier", "");
        if (code != null && !code.isEmpty() && !verifier.isEmpty()) {
            try {
                JSONObject body = new JSONObject().put("auth_code", code).put("code_verifier", verifier);
                request("POST", "/auth/v1/token?grant_type=pkce", body.toString(), false,
                        (ok,msg,data) -> finishOAuth(ok,msg,data,callback));
            } catch (Exception e) { callback.complete(false, e.getMessage(), ""); }
            return;
        }
        String access = first(redirect.getQueryParameter("access_token"),fragment.getQueryParameter("access_token"));
        String refresh = first(redirect.getQueryParameter("refresh_token"),fragment.getQueryParameter("refresh_token"));
        if (access == null || access.isEmpty()) { callback.complete(false,code!=null?"Secure sign-in expired. Tap Google or Facebook and try again.":"RiderLink sign-in did not return a session. Check the provider redirect URL.",""); return; }
        storeOAuthSession(access, refresh == null ? "" : refresh, parseLong(fragment.getQueryParameter("expires_in"),3600), "", "");
        fetchOAuthUser(callback);
    }

    private void finishOAuth(boolean ok,String message,String data,Callback callback){
        if(!ok){callback.complete(false,message,data);return;}
        try{
            JSONObject root=new JSONObject(data),user=root.optJSONObject("user");
            storeOAuthSession(root.optString("access_token"),root.optString("refresh_token"),root.optLong("expires_in",3600),user==null?"":user.optString("id"),user==null?"":user.optString("email"));
            callback.complete(true,"Signed in to RiderLink",data);
        }catch(Exception e){callback.complete(false,"Invalid RiderLink sign-in response",data);}
    }
    private void fetchOAuthUser(Callback callback){
        request("GET","/auth/v1/user",null,true,(ok,msg,data)->{
            if(ok)try{JSONObject user=new JSONObject(data);prefs.edit().putString("user_id",user.optString("id")).putString("email",user.optString("email")).apply();}catch(Exception ignored){}
            callback.complete(ok,ok?"Signed in to RiderLink":msg,data);
        });
    }
    private void storeOAuthSession(String access,String refresh,long expires,String id,String email){prefs.edit().putString("access",access).putString("refresh",refresh).putString("user_id",id).putString("email",email).putLong("expires_at",System.currentTimeMillis()+expires*1000L).remove("oauth_verifier").remove("oauth_started").apply();}
    private long parseLong(String value,long fallback){try{return Long.parseLong(value);}catch(Exception ignored){return fallback;}}
    private String first(String a,String b){return a!=null&&!a.isEmpty()?a:b;}

    public void authenticate(String email, String password, boolean create, Callback callback) {
        try {
            JSONObject body = new JSONObject().put("email", email).put("password", password);
            request("POST", "/auth/v1/" + (create ? "signup" : "token?grant_type=password"), body.toString(), false, (ok, msg, data) -> {
                if (ok) {
                    try {
                        JSONObject root = new JSONObject(data); String access = root.optString("access_token", "");
                        JSONObject user = root.optJSONObject("user");
                        if (access.isEmpty()) { callback.complete(true, "Check your email to confirm the account, then sign in.", data); return; }
                        prefs.edit().putString("access", access).putString("refresh", root.optString("refresh_token", ""))
                                .putString("user_id", user == null ? "" : user.optString("id", ""))
                                .putString("email", email).putLong("expires_at", System.currentTimeMillis() + root.optLong("expires_in", 3600) * 1000L).apply();
                    } catch (Exception e) { callback.complete(false, "Invalid sign-in response", data); return; }
                }
                callback.complete(ok, msg, data);
            });
        } catch (Exception e) { callback.complete(false, e.getMessage(), ""); }
    }

    public void saveProfile(String username, String scooter, String privacy, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("id", userId()).put("username", username).put("scooter", scooter).put("privacy", privacy); }
        catch (Exception ignored) { }
        rest("POST", "/rest/v1/profiles?on_conflict=id", body.toString(), "resolution=merge-duplicates,return=minimal", callback);
    }
    public void saveExtendedProfile(String username, String privacy, String make, String model,
                                    String upgrades, String avatarJson, Callback callback) {
        try {
            JSONObject body = new JSONObject().put("id", userId()).put("username", username)
                    .put("scooter", (make + " " + model).trim()).put("privacy", privacy)
                    .put("scooter_make", make).put("scooter_model", model)
                    .put("scooter_upgrades", upgrades).put("avatar_config", new JSONObject(avatarJson));
            rest("POST", "/rest/v1/profiles?on_conflict=id", body.toString(), "resolution=merge-duplicates,return=minimal", callback);
        } catch (Exception e) { callback.complete(false, e.getMessage(), ""); }
    }
    public void saveAvatar3d(String modelUrl, Callback callback) {
        try { rest("PATCH", "/rest/v1/profiles?id=eq." + userId(),
                new JSONObject().put("avatar_3d_url", modelUrl).toString(), "return=minimal", callback); }
        catch (Exception e) { callback.complete(false, e.getMessage(), ""); }
    }
    public void updatePresence(double lat, double lon, float accuracy, String privacy, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("user_id", userId()).put("latitude", lat).put("longitude", lon).put("accuracy", accuracy)
                .put("privacy", privacy).put("updated_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(new java.util.Date())); }
        catch (Exception ignored) { }
        rest("POST", "/rest/v1/rider_presence?on_conflict=user_id", body.toString(), "resolution=merge-duplicates,return=minimal", callback);
    }
    public void goInvisible(Callback callback) { rest("DELETE", "/rest/v1/rider_presence?user_id=eq." + userId(), null, "return=minimal", callback); }
    public void nearby(double lat, double lon, Callback callback) {
        try { rest("POST", "/rest/v1/rpc/nearby_riders", new JSONObject().put("p_lat", lat).put("p_lon", lon).put("p_radius_km", 25).toString(), "return=representation", callback); }
        catch (Exception e) { callback.complete(false, e.getMessage(), "[]"); }
    }
    public void setRideBeacon(String kind,String note,double lat,double lon,Callback c){try{rest("POST","/rest/v1/ride_beacons?on_conflict=user_id",new JSONObject().put("user_id",userId()).put("kind",kind).put("note",note).put("latitude",lat).put("longitude",lon).toString(),"resolution=merge-duplicates,return=minimal",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void clearRideBeacon(Callback c){rest("DELETE","/rest/v1/ride_beacons?user_id=eq."+userId(),null,"return=minimal",c);}
    public void nearbyRideBeacons(double lat,double lon,Callback c){try{rest("POST","/rest/v1/rpc/nearby_ride_beacons",new JSONObject().put("p_lat",lat).put("p_lon",lon).put("p_radius_km",40).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"[]");}}
    public void sendWave(String riderId,Callback c){try{rest("POST","/rest/v1/rpc/send_rider_wave",new JSONObject().put("p_recipient",riderId).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void riderActivity(Callback c){rest("POST","/rest/v1/rpc/my_rider_activity","{}","return=representation",c);}
    public void markActivityRead(Callback c){rest("POST","/rest/v1/rpc/mark_rider_activity_read","{}","return=minimal",c);}
    public void membership(Callback c){rest("POST","/rest/v1/rpc/my_riderlink_membership","{}","return=representation",(ok,m,b)->{if(ok)cacheMembership(b);c.complete(ok,m,b);});}
    public void redeemMembershipCode(String code,Callback c){try{rest("POST","/rest/v1/rpc/redeem_riderlink_code",new JSONObject().put("p_code",code).toString(),"return=representation",(ok,m,b)->{if(ok)cacheMembership(b);c.complete(ok,m,b);});}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void createMembershipCode(String name,String reward,int days,Callback c){try{rest("POST","/rest/v1/rpc/create_riderlink_code",new JSONObject().put("p_name",name).put("p_reward_type",reward).put("p_complimentary_days",days).put("p_expires_days",30).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    private void cacheMembership(String body){try{JSONArray rows=new JSONArray(body);if(rows.length()==0)return;JSONObject m=rows.getJSONObject(0);String expiry=m.optString("expires_at","");long time=0;if(!expiry.isEmpty()&&!"null".equals(expiry))try{java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",java.util.Locale.US);f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));time=f.parse(expiry.substring(0,19)).getTime();}catch(Exception ignored){}prefs.edit().putString("membership_tier",m.optString("tier","free")).putBoolean("membership_plus",m.optBoolean("plus_access",false)).putBoolean("membership_price",m.optBoolean("founder_price_eligible",false)).putBoolean("membership_admin",m.optBoolean("is_admin",false)).putLong("membership_expires",time).apply();}catch(Exception ignored){}}
    public void nearbySos(double lat, double lon, Callback callback) {
        try { rest("POST", "/rest/v1/rpc/nearby_sos", new JSONObject().put("p_lat", lat).put("p_lon", lon).put("p_radius_km", 40).toString(), "return=representation", callback); }
        catch (Exception e) { callback.complete(false, e.getMessage(), "[]"); }
    }
    public void reportCommunityHazard(String type,double lat,double lon,Callback c){try{rest("POST","/rest/v1/community_hazards",new JSONObject().put("reporter_id",userId()).put("type",type).put("latitude",lat).put("longitude",lon).toString(),"return=minimal",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void nearbyCommunityHazards(double lat,double lon,Callback c){try{rest("POST","/rest/v1/rpc/nearby_community_hazards",new JSONObject().put("p_lat",lat).put("p_lon",lon).put("p_radius_km",50).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"[]");}}
    public void sendSos(double lat, double lon, String note, Callback callback) {
        try { rest("POST", "/rest/v1/sos_alerts", new JSONObject().put("user_id", userId()).put("latitude", lat)
                .put("longitude", lon).put("message", note).put("active", true).toString(), "return=representation", callback); }
        catch (Exception e) { callback.complete(false, e.getMessage(), ""); }
    }
    public void cancelSos(Callback callback) { rest("PATCH", "/rest/v1/sos_alerts?user_id=eq." + userId() + "&active=eq.true", "{\"active\":false}", "return=minimal", callback); }
    public void refreshSession(Callback callback) {
        if (System.currentTimeMillis() < prefs.getLong("expires_at", 0) - 120000L) { callback.complete(true, "Session active", ""); return; }
        try { request("POST", "/auth/v1/token?grant_type=refresh_token", new JSONObject().put("refresh_token", prefs.getString("refresh", "")).toString(), false, (ok,msg,data) -> {
            if (ok) try { JSONObject root=new JSONObject(data); prefs.edit().putString("access",root.getString("access_token"))
                    .putString("refresh",root.optString("refresh_token",prefs.getString("refresh","")))
                    .putLong("expires_at",System.currentTimeMillis()+root.optLong("expires_in",3600)*1000L).apply(); } catch(Exception ignored) { }
            callback.complete(ok,msg,data);
        }); } catch(Exception e) { callback.complete(false,e.getMessage(),""); }
    }
    public void getProfile(Callback c) { rest("GET", "/rest/v1/profiles?id=eq." + userId() + "&select=*", null, null, c); }
    public void discoverRiders(Callback c) { rest("GET", "/rest/v1/profiles?id=neq." + userId() + "&select=id,username,scooter,scooter_make,scooter_model,scooter_upgrades,avatar_config,avatar_3d_url&order=username&limit=100", null, null, c); }
    public void friendRequests(Callback c) { rest("GET", "/rest/v1/friendships?addressee_id=eq." + userId() + "&status=eq.pending&select=id,requester_id,requester:profiles!friendships_requester_id_fkey(username,scooter,scooter_make,scooter_model,avatar_config,avatar_3d_url)", null, null, c); }
    public void friends(Callback c) { rest("POST", "/rest/v1/rpc/my_friends", "{}", "return=representation", c); }
    public void requestFriend(String riderId, Callback c) { try { rest("POST", "/rest/v1/friendships", new JSONObject().put("requester_id",userId()).put("addressee_id",riderId).toString(), "return=minimal", c); } catch(Exception e){c.complete(false,e.getMessage(),"");} }
    public void acceptFriend(long id, Callback c) { rest("PATCH", "/rest/v1/friendships?id=eq." + id + "&addressee_id=eq." + userId(), "{\"status\":\"accepted\"}", "return=minimal", c); }
    public void directMessages(String riderId, Callback c) { rest("GET", "/rest/v1/direct_messages?or=(and(sender_id.eq."+userId()+",recipient_id.eq."+riderId+"),and(sender_id.eq."+riderId+",recipient_id.eq."+userId()+"))&select=*,sender:profiles!direct_messages_sender_id_fkey(username)&order=created_at.asc&limit=100", null, null, c); }
    public void sendDirect(String riderId, String message, Callback c) { try { rest("POST", "/rest/v1/direct_messages", new JSONObject().put("sender_id",userId()).put("recipient_id",riderId).put("body",message).toString(), "return=minimal", c); } catch(Exception e){c.complete(false,e.getMessage(),"");} }
    public void myClubs(Callback c) { rest("POST", "/rest/v1/rpc/my_clubs", "{}", "return=representation", c); }
    public void createClub(String name, Callback c) { try { rest("POST", "/rest/v1/rpc/create_club", new JSONObject().put("p_name",name).toString(), "return=representation", c); } catch(Exception e){c.complete(false,e.getMessage(),"");} }
    public void joinClub(String code, Callback c) { try { rest("POST", "/rest/v1/rpc/join_club", new JSONObject().put("p_code",code.toUpperCase()).toString(), "return=representation", c); } catch(Exception e){c.complete(false,e.getMessage(),"");} }
    public void clubMessages(long clubId, Callback c) { rest("GET", "/rest/v1/club_messages?club_id=eq." + clubId + "&select=*,sender:profiles!club_messages_sender_id_fkey(username)&order=created_at.asc&limit=100", null, null, c); }
    public void sendClub(long clubId, String message, Callback c) { try { rest("POST", "/rest/v1/club_messages", new JSONObject().put("club_id",clubId).put("sender_id",userId()).put("body",message).toString(), "return=minimal", c); } catch(Exception e){c.complete(false,e.getMessage(),"");} }
    public void createGroupRide(String name,String destination,Callback c){try{rest("POST","/rest/v1/rpc/create_group_ride",new JSONObject().put("p_name",name).put("p_destination",destination).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void joinGroupRide(String code,Callback c){try{rest("POST","/rest/v1/rpc/join_group_ride",new JSONObject().put("p_code",code.toUpperCase()).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void myActiveGroupRides(Callback c){rest("POST","/rest/v1/rpc/my_active_group_rides","{}","return=representation",c);}
    public void groupRideMembers(long rideId,Callback c){rest("GET","/rest/v1/group_ride_members?ride_id=eq."+rideId+"&select=*,profile:profiles(username,scooter,avatar_3d_url)&order=joined_at",null,null,c);}
    public void updateGroupRideLocation(long rideId,double lat,double lon,float speed,Callback c){try{rest("POST","/rest/v1/group_ride_presence?on_conflict=ride_id,user_id",new JSONObject().put("ride_id",rideId).put("user_id",userId()).put("latitude",lat).put("longitude",lon).put("speed_mps",speed).put("updated_at",new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",java.util.Locale.US).format(new java.util.Date())).toString(),"resolution=merge-duplicates,return=minimal",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void endGroupRide(long rideId,Callback c){rest("PATCH","/rest/v1/group_rides?id=eq."+rideId+"&owner_id=eq."+userId(),"{\"active\":false}","return=minimal",c);}
    public void createConvoy(String name,String destination,boolean avoidHighways,Callback c){try{rest("POST","/rest/v1/rpc/create_convoy",new JSONObject().put("p_name",name).put("p_destination",destination).put("p_avoid_highways",avoidHighways).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void convoySnapshot(long rideId,Callback c){try{rest("POST","/rest/v1/rpc/convoy_snapshot",new JSONObject().put("p_ride",rideId).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"[]");}}
    public void convoyAlerts(long rideId,Callback c){try{rest("POST","/rest/v1/rpc/convoy_recent_alerts",new JSONObject().put("p_ride",rideId).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"[]");}}
    public void sendConvoyAlert(long rideId,String type,Double lat,Double lon,Callback c){try{JSONObject b=new JSONObject().put("p_ride",rideId).put("p_type",type).put("p_lat",lat==null?JSONObject.NULL:lat).put("p_lon",lon==null?JSONObject.NULL:lon);rest("POST","/rest/v1/rpc/send_convoy_alert",b.toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void leaveConvoy(long rideId,Callback c){try{rest("POST","/rest/v1/rpc/leave_convoy",new JSONObject().put("p_ride",rideId).toString(),"return=minimal",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void stopGroupRideLocation(long rideId,Callback c){rest("DELETE","/rest/v1/group_ride_presence?ride_id=eq."+rideId+"&user_id=eq."+userId(),null,"return=minimal",c);}
    /** Event operations use server-checked RPCs; the client never chooses a host or RSVP owner. */
    public void eventRpc(String action, JSONObject body, Callback callback) {
        rest("POST", "/rest/v1/rpc/" + action, body.toString(), "return=representation", callback);
    }

    public void safetyCircle(Callback c){rest("POST","/rest/v1/rpc/my_safety_circle","{}","return=representation",c);}
    public void addSafetyContact(String username,Callback c){try{rest("POST","/rest/v1/rpc/add_safety_contact",new JSONObject().put("p_username",username).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void acceptSafetyContact(long relationId,Callback c){try{rest("POST","/rest/v1/rpc/accept_safety_contact",new JSONObject().put("p_relation",relationId).toString(),"return=minimal",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void removeSafetyContact(String contactId,Callback c){try{rest("POST","/rest/v1/rpc/remove_safety_contact",new JSONObject().put("p_contact",contactId).toString(),"return=minimal",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void startSafetyTrip(int checkinMinutes,Callback c){try{rest("POST","/rest/v1/rpc/start_safety_trip",new JSONObject().put("p_checkin_minutes",checkinMinutes).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void updateSafetyTrip(double lat,double lon,float accuracy,float speed,Callback c){try{rest("POST","/rest/v1/rpc/update_safety_trip",new JSONObject().put("p_lat",lat).put("p_lon",lon).put("p_accuracy",accuracy).put("p_speed_mps",speed).toString(),"return=minimal",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void checkInSafetyTrip(Callback c){rest("POST","/rest/v1/rpc/check_in_safety_trip","{}","return=representation",c);}
    public void endSafetyTrip(Callback c){rest("POST","/rest/v1/rpc/end_safety_trip","{}","return=minimal",c);}
    public void safetyStatus(Callback c){rest("POST","/rest/v1/rpc/my_safety_status","{}","return=representation",c);}
    public void triggerSafetyEvent(String type,String note,Double lat,Double lon,Callback c){try{rest("POST","/rest/v1/rpc/trigger_safety_event",new JSONObject().put("p_type",type).put("p_note",note).put("p_lat",lat==null?JSONObject.NULL:lat).put("p_lon",lon==null?JSONObject.NULL:lon).toString(),"return=representation",c);}catch(Exception e){c.complete(false,e.getMessage(),"");}}
    public void signOut() { prefs.edit().clear().apply(); }
    public void shutdown() { worker.shutdownNow(); }

    private void rest(String method, String path, String body, String prefer, Callback callback) {
        request(method, path, body, true, (ok, msg, data) -> callback.complete(ok, msg, data), prefer);
    }
    private void request(String method, String path, String body, boolean auth, Callback callback) { request(method, path, body, auth, callback, null); }
    private void request(String method, String path, String body, boolean auth, Callback callback, String prefer) {
        worker.execute(() -> {
            boolean ok = false; String response = ""; String message;
            try {
                if(auth&&System.currentTimeMillis()>=prefs.getLong("expires_at",0)-120000L&&!refreshTokenBlocking())throw new Exception("RiderLink session expired. Sign in again.");
                Response result=perform(method,path,body,auth,prefer);
                if(auth&&(result.code==401||jwtExpired(result.body))&&refreshTokenBlocking())result=perform(method,path,body,true,prefer);
                ok=result.code>=200&&result.code<300;response=result.body;
                message=ok?"Done":parseError(response,result.code==401?"RiderLink session expired. Sign in again.":"RiderLink request failed ("+result.code+")");
            } catch (Exception e) { message = e.getMessage() == null ? "Network error" : e.getMessage(); }
            final boolean deliveredOk = ok; final String deliveredMessage = message, deliveredBody = response;
            main.post(() -> callback.complete(deliveredOk, deliveredMessage, deliveredBody));
        });
    }
    private Response perform(String method,String path,String body,boolean auth,String prefer)throws Exception{HttpURLConnection connection=(HttpURLConnection)new URL(URL+path).openConnection();try{connection.setRequestMethod(method);connection.setConnectTimeout(9000);connection.setReadTimeout(10000);connection.setRequestProperty("apikey",KEY);connection.setRequestProperty("Content-Type","application/json");if(auth)connection.setRequestProperty("Authorization","Bearer "+prefs.getString("access",""));if(prefer!=null)connection.setRequestProperty("Prefer",prefer);if(body!=null){connection.setDoOutput(true);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);try(OutputStream out=connection.getOutputStream()){out.write(bytes);}}int code=connection.getResponseCode();InputStream stream=code>=200&&code<300?connection.getInputStream():connection.getErrorStream();String response="";if(stream!=null){BufferedReader reader=new BufferedReader(new InputStreamReader(stream));StringBuilder text=new StringBuilder();String line;while((line=reader.readLine())!=null)text.append(line);reader.close();response=text.toString();}return new Response(code,response);}finally{connection.disconnect();}}
    private boolean refreshTokenBlocking(){String refresh=prefs.getString("refresh","");if(refresh.isEmpty())return false;try{Response response=perform("POST","/auth/v1/token?grant_type=refresh_token",new JSONObject().put("refresh_token",refresh).toString(),false,null);if(response.code<200||response.code>=300)return false;JSONObject root=new JSONObject(response.body);String access=root.optString("access_token","");if(access.isEmpty())return false;prefs.edit().putString("access",access).putString("refresh",root.optString("refresh_token",refresh)).putLong("expires_at",System.currentTimeMillis()+root.optLong("expires_in",3600)*1000L).apply();return true;}catch(Exception ignored){return false;}}
    private boolean jwtExpired(String body){String value=body==null?"":body.toLowerCase(java.util.Locale.US);return value.contains("jwt expired")||value.contains("token is expired")||value.contains("invalid jwt");}
    private static class Response{final int code;final String body;Response(int code,String body){this.code=code;this.body=body;}}
    private String parseError(String body, String fallback) {
        try { JSONObject error = new JSONObject(body); return error.optString("msg", error.optString("message", fallback)); }
        catch (Exception ignored) { return fallback; }
    }
}
