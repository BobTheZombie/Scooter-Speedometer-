package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Bounded on-demand disk cache for OpenNAV+ map tiles. It never bulk-downloads OSM tiles. */
final class MapTileCache {
    private static final long FRESH_MS=7L*24L*60L*60L*1000L;
    private final File directory;private final Context context;private long lastPrune;
    MapTileCache(Context context){this.context=context.getApplicationContext();directory=new File(context.getCacheDir(),"opennav_tiles");directory.mkdirs();}
    WebResourceResponse intercept(WebResourceRequest request){Uri uri=request.getUrl();if(uri==null||!"https".equals(uri.getScheme())||!"tile.openstreetmap.org".equals(uri.getHost()))return null;String path=uri.getPath();if(path==null||!path.matches("/\\d+/\\d+/\\d+\\.png"))return null;File cached=new File(directory,hash(uri.toString())+".png");long now=System.currentTimeMillis();if(cached.isFile()&&now-cached.lastModified()<FRESH_MS)return response(cached,true);HttpURLConnection connection=null;try{connection=(HttpURLConnection)new URL(uri.toString()).openConnection();connection.setConnectTimeout(7000);connection.setReadTimeout(10000);connection.setRequestProperty("User-Agent","OpenNAV+/7.7 (github.com/BobTheZombie/Scooter-Speedometer-)");connection.setRequestProperty("Accept","image/png,image/*");if(connection.getResponseCode()!=200)throw new Exception("tile HTTP "+connection.getResponseCode());byte[] bytes=read(connection.getInputStream());if(bytes.length<100)throw new Exception("empty tile");File temp=new File(directory,cached.getName()+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);}if(!temp.renameTo(cached)){try(FileOutputStream out=new FileOutputStream(cached)){out.write(bytes);}temp.delete();}cached.setLastModified(now);prune(now);return bytesResponse(bytes,false);}catch(Exception ignored){return cached.isFile()?response(cached,true):null;}finally{if(connection!=null)connection.disconnect();}}
    private WebResourceResponse response(File file,boolean hit){try{file.setLastModified(System.currentTimeMillis());return make(new FileInputStream(file),hit);}catch(Exception e){return null;}}
    private WebResourceResponse bytesResponse(byte[] bytes,boolean hit){return make(new ByteArrayInputStream(bytes),hit);}
    private WebResourceResponse make(InputStream stream,boolean hit){Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","public, max-age=604800");headers.put("Access-Control-Allow-Origin","*");headers.put("X-OpenNAV-Cache",hit?"HIT":"MISS");return new WebResourceResponse("image/png",null,200,"OK",headers,stream);}
    private synchronized void prune(long now){if(now-lastPrune<60000)return;lastPrune=now;File[] files=directory.listFiles((d,n)->n.endsWith(".png"));if(files==null)return;long total=0;for(File f:files)total+=f.length();long max=maxBytes(context);if(total<=max)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(File f:files){long size=f.length();if(f.delete())total-=size;if(total<=max*.85)break;}}
    static long maxBytes(Context c){int mb=c.getSharedPreferences("speedometer",Context.MODE_PRIVATE).getInt("opennav_cache_mb",120);return Math.max(32,Math.min(512,mb))*1024L*1024L;}
    static long sizeBytes(Context c){return directorySize(new File(c.getCacheDir(),"opennav_tiles"))+directorySize(new File(c.getFilesDir(),"opennav_offline"));}
    static int clear(Context c){return deleteContents(new File(c.getCacheDir(),"opennav_tiles"))+deleteContents(new File(c.getFilesDir(),"opennav_offline"));}
    private static long directorySize(File d){File[] f=d.listFiles();long n=0;if(f!=null)for(File x:f)n+=x.isDirectory()?directorySize(x):x.length();return n;}
    private static int deleteContents(File d){File[] f=d.listFiles();int n=0;if(f!=null)for(File x:f){if(x.isDirectory())n+=deleteContents(x);if(x.delete())n++;}return n;}
    private byte[] read(InputStream input)throws Exception{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))>=0)out.write(b,0,n);return out.toByteArray();}}
    private String hash(String value){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format("%02x",b));return s.toString();}catch(Exception e){return Integer.toHexString(value.hashCode());}}
}
