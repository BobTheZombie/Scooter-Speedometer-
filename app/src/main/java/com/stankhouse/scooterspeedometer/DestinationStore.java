package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Local-only recent destinations and favorites. */
final class DestinationStore {
    private final SharedPreferences prefs;
    DestinationStore(Context c){prefs=c.getSharedPreferences("opennav_destinations",Context.MODE_PRIVATE);}
    List<String> display(){List<String> out=new ArrayList<>();for(String s:read("favorites"))out.add("★  "+s);for(String s:read("recent"))if(!out.contains("★  "+s))out.add("↻  "+s);return out;}
    void used(String value){value=clean(value);if(value.isEmpty())return;List<String> list=read("recent");list.remove(value);list.add(0,value);while(list.size()>12)list.remove(list.size()-1);write("recent",list);}
    boolean toggleFavorite(String value){value=clean(value);List<String> list=read("favorites");boolean added;if(list.remove(value))added=false;else{list.add(0,value);added=true;}write("favorites",list);return added;}
    boolean isFavorite(String value){return read("favorites").contains(clean(value));}
    static String clean(String value){if(value==null)return "";return value.replaceFirst("^[★↻]\\s*","").trim();}
    private List<String> read(String key){List<String> out=new ArrayList<>();try{JSONArray a=new JSONArray(prefs.getString(key,"[]"));for(int i=0;i<a.length();i++){String s=a.optString(i).trim();if(!s.isEmpty()&&!out.contains(s))out.add(s);}}catch(Exception ignored){}return out;}
    private void write(String key,List<String> values){JSONArray a=new JSONArray();for(String s:values)a.put(s);prefs.edit().putString(key,a.toString()).apply();}
}
