package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One foreground-only voice lane shared by navigation, safety, weather and communications. */
public final class CopilotAlertQueue implements TextToSpeech.OnInitListener {
    public static final int INFO=20, COMMUNICATION=40, SPEED=50, WEATHER=60,
            NAVIGATION=70, HAZARD=80, CRITICAL=100;
    private static CopilotAlertQueue instance;
    public static synchronized CopilotAlertQueue get(Context context){
        if(instance==null)instance=new CopilotAlertQueue(context.getApplicationContext());
        return instance;
    }

    private static final class Alert {
        final int priority;final String key,message;final long created,expires;final long sequence;
        Alert(int p,String k,String m,long e,long s){priority=p;key=k;message=m;created=System.currentTimeMillis();expires=created+e;sequence=s;}
    }
    private final Context context;
    private final SharedPreferences prefs;
    private final AudioManager audio;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Alert> waiting=new ArrayList<>();
    private final Map<String,Long> spoken=new HashMap<>();
    private TextToSpeech speech;
    private AudioFocusRequest focusRequest;
    private Alert current;
    private boolean ready,foreground;
    private long sequence;
    private String lastMessage="";

    private CopilotAlertQueue(Context context){
        this.context=context;prefs=context.getSharedPreferences("speedometer",Context.MODE_PRIVATE);
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        speech=new TextToSpeech(context,this);
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){}
            @Override public void onDone(String id){main.post(CopilotAlertQueue.this::finishCurrent);}
            @Override public void onError(String id){main.post(CopilotAlertQueue.this::finishCurrent);}
        });
    }
    @Override public void onInit(int status){ready=status==TextToSpeech.SUCCESS;if(ready){speech.setLanguage(Locale.US);VoiceSettings.apply(context,speech);dispatch();}}
    public synchronized void setForeground(boolean active){foreground=active;if(!active){waiting.clear();current=null;if(speech!=null)speech.stop();releaseFocus();}else dispatch();}
    public synchronized void refreshVoice(){if(ready)VoiceSettings.apply(context,speech);}
    public synchronized int pendingCount(){return waiting.size()+(current==null?0:1);}
    public synchronized String lastMessage(){return lastMessage;}

    public void enqueue(int priority,String key,String message,long cooldownMs){enqueue(priority,key,message,cooldownMs,120000L);}
    public synchronized void enqueue(int priority,String key,String message,long cooldownMs,long expiresMs){
        if(!foreground||!ready||message==null||message.trim().isEmpty()||!allowed(priority))return;
        long now=System.currentTimeMillis(),last=spoken.containsKey(key)?spoken.get(key):0L;
        if(now-last<cooldownMs)return;
        for(Alert alert:waiting)if(alert.key.equals(key))return;
        Alert incoming=new Alert(priority,key,message,Math.max(5000L,expiresMs),++sequence);
        boolean prioritized=prefs.getBoolean("copilot_prioritized_queue",true);
        if(current!=null&&prioritized&&incoming.priority>current.priority){
            speech.stop();waiting.add(current);current=null;
        }
        waiting.add(incoming);
        sort(prioritized);
        dispatch();
    }
    public synchronized void repeatLast(){if(!lastMessage.isEmpty())enqueue(CRITICAL,"repeat:"+System.currentTimeMillis(),lastMessage,0,30000L);}
    private boolean allowed(int priority){int level=prefs.getInt("copilot_alert_level",0);return level==0||level==1&&priority>=WEATHER||level>=2&&priority>=HAZARD;}
    private void sort(boolean prioritized){waiting.sort(prioritized?Comparator.comparingInt((Alert a)->a.priority).reversed().thenComparingLong(a->a.sequence):Comparator.comparingLong(a->a.sequence));}
    private synchronized void dispatch(){
        if(!foreground||!ready||current!=null)return;
        long now=System.currentTimeMillis();while(!waiting.isEmpty()&&waiting.get(0).expires<now)waiting.remove(0);
        if(waiting.isEmpty()){releaseFocus();return;}
        current=waiting.remove(0);spoken.put(current.key,now);lastMessage=current.message;requestFocus();
        speech.speak(current.message,TextToSpeech.QUEUE_FLUSH,null,"copilot-"+current.sequence);
    }
    private synchronized void finishCurrent(){current=null;dispatch();}
    private void requestFocus(){if(audio==null)return;if(Build.VERSION.SDK_INT>=26){AudioAttributes a=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(a).setOnAudioFocusChangeListener(c->{}).setWillPauseWhenDucked(false).build();audio.requestAudioFocus(focusRequest);}else audio.requestAudioFocus(null,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);}
    private void releaseFocus(){if(audio==null)return;if(Build.VERSION.SDK_INT>=26&&focusRequest!=null){audio.abandonAudioFocusRequest(focusRequest);focusRequest=null;}else audio.abandonAudioFocus(null);}
}
