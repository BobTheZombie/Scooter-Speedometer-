package com.stankhouse.scooterspeedometer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks signed APK releases from the project's official GitHub repository. */
public final class UpdateManager {
    private static final String LATEST="https://api.github.com/repos/BobTheZombie/Scooter-Speedometer-/releases/latest";
    private static final long CHECK_INTERVAL=12L*60L*60L*1000L;
    private final Activity activity;private final SharedPreferences prefs;private final ExecutorService network=Executors.newSingleThreadExecutor();private boolean registered;
    private final BroadcastReceiver downloads=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){long id=i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1);if(id==prefs.getLong("update_download_id",-2))installCompletedDownload();}};
    public UpdateManager(Activity activity){this.activity=activity;this.prefs=activity.getSharedPreferences("app_updates",Context.MODE_PRIVATE);}
    public void check(boolean manual){long now=System.currentTimeMillis();if(!manual&&now-prefs.getLong("last_check",0)<CHECK_INTERVAL){resumePendingInstall();return;}prefs.edit().putLong("last_check",now).apply();network.execute(()->{HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(LATEST).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(10000);c.setRequestProperty("Accept","application/vnd.github+json");c.setRequestProperty("User-Agent","Scooter-Speedometer-Updater");if(c.getResponseCode()!=200)throw new Exception("GitHub returned "+c.getResponseCode());BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream()));StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null)b.append(line);r.close();JSONObject release=new JSONObject(b.toString());String tag=release.optString("tag_name","");String notes=release.optString("name",tag);String apk="";JSONArray assets=release.optJSONArray("assets");if(assets!=null)for(int n=0;n<assets.length();n++){JSONObject a=assets.getJSONObject(n);if(a.optString("name","").toLowerCase(Locale.US).endsWith(".apk")){apk=a.optString("browser_download_url","");break;}}String url=apk;if(newer(tag,currentVersion())&&!url.isEmpty())activity.runOnUiThread(()->showUpdate(tag,notes,url));else if(manual)activity.runOnUiThread(()->Toast.makeText(activity,"You already have the latest version (v"+currentVersion()+")",Toast.LENGTH_LONG).show());}catch(Exception e){if(manual)activity.runOnUiThread(()->Toast.makeText(activity,"Update check failed: "+e.getMessage(),Toast.LENGTH_LONG).show());}finally{if(c!=null)c.disconnect();}});}
    private void showUpdate(String tag,String title,String url){if(activity.isFinishing())return;new AlertDialog.Builder(activity).setTitle("Update available • "+tag).setMessage((title.isEmpty()?"A new signed Scooter Speedometer release is available.":title)+"\n\nThe APK will be downloaded from the official GitHub release and verified by Android during installation.").setNegativeButton("Later",null).setPositiveButton("Download & install",(d,w)->download(tag,url)).show();}
    private void download(String tag,String url){DownloadManager manager=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url)).setTitle("Scooter Speedometer "+tag).setDescription("Downloading app update").setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(activity,android.os.Environment.DIRECTORY_DOWNLOADS,"Scooter-Speedometer-"+tag+"-"+System.currentTimeMillis()+".apk");long id=manager.enqueue(request);prefs.edit().putLong("update_download_id",id).apply();register();Toast.makeText(activity,"Downloading "+tag+"…",Toast.LENGTH_LONG).show();}
    private void register(){if(registered)return;IntentFilter f=new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);if(Build.VERSION.SDK_INT>=33)activity.registerReceiver(downloads,f,Context.RECEIVER_EXPORTED);else activity.registerReceiver(downloads,f);registered=true;}
    public void resumePendingInstall(){if(prefs.getLong("update_download_id",-1)>0)installCompletedDownload();}
    private void installCompletedDownload(){long id=prefs.getLong("update_download_id",-1);if(id<0)return;DownloadManager manager=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(id));try{if(!cursor.moveToFirst())return;int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));if(status==DownloadManager.STATUS_FAILED){prefs.edit().remove("update_download_id").apply();Toast.makeText(activity,"Update download failed",Toast.LENGTH_LONG).show();return;}if(status!=DownloadManager.STATUS_SUCCESSFUL){register();return;}}finally{cursor.close();}if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){new AlertDialog.Builder(activity).setTitle("Allow app updates").setMessage("Android needs permission to install the signed APK downloaded from your GitHub release. Enable ‘Allow from this source,’ then return here.").setNegativeButton("Cancel",null).setPositiveButton("Open setting",(d,w)->activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())))).show();return;}Uri apk=manager.getUriForDownloadedFile(id);if(apk==null)return;Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(apk,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);activity.startActivity(install);prefs.edit().remove("update_download_id").apply();}
    private String currentVersion(){try{return activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName;}catch(Exception e){return "0";}}
    private boolean newer(String candidate,String current){int[] a=parts(candidate),b=parts(current);for(int i=0;i<Math.max(a.length,b.length);i++){int x=i<a.length?a[i]:0,y=i<b.length?b[i]:0;if(x!=y)return x>y;}return false;}
    private int[] parts(String value){String clean=value.replaceAll("^[^0-9]*","").replaceAll("[^0-9.].*$","");String[] p=clean.split("\\.");int[] out=new int[p.length];for(int i=0;i<p.length;i++)try{out[i]=Integer.parseInt(p[i]);}catch(Exception ignored){}return out;}
    public void destroy(){if(registered)try{activity.unregisterReceiver(downloads);}catch(Exception ignored){}registered=false;network.shutdownNow();}
}
