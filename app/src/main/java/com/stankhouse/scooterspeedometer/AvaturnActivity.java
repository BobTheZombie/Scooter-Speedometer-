package com.stankhouse.scooterspeedometer;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.json.JSONObject;

/** Full-screen Avaturn creator bound to the RiderLink profile. */
public class AvaturnActivity extends Activity {
    private static final String CREATOR = "https://scooter-speedometer.avaturn.dev/iframe";
    private static final int CAMERA_REQUEST = 401;
    private WebView web;
    private RiderLinkClient client;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Fullscreen.apply(this); client = new RiderLinkClient(this);
        FrameLayout frame=new FrameLayout(this);web = new WebView(this); web.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);
        frame.addView(web,new FrameLayout.LayoutParams(-1,-1));ProgressBar progress=new ProgressBar(this);
        FrameLayout.LayoutParams progressParams=new FrameLayout.LayoutParams(dp(52),dp(52),android.view.Gravity.CENTER);frame.addView(progress,progressParams);setContentView(frame);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128 Mobile Safari/537.36");
        web.addJavascriptInterface(new AvatarBridge(),"native_app");
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request){
                Uri origin=request.getOrigin();
                if(origin==null||!"scooter-speedometer.avaturn.dev".equalsIgnoreCase(origin.getHost())||
                        checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){request.deny();return;}
                for(String resource:request.getResources())if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)){request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});return;}
                request.deny();
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                String host=request.getUrl().getHost();if(host==null)return true;host=host.toLowerCase(java.util.Locale.US);
                return !(host.equals("scooter-speedometer.avaturn.dev")||host.endsWith(".avaturn.me")||host.endsWith(".avaturn.dev")||host.equals("accounts.google.com")||host.endsWith(".googleapis.com")||host.endsWith(".firebaseapp.com"));
            }
            @Override public void onPageStarted(WebView view,String url,Bitmap icon){view.evaluateJavascript("window.avaturnFirebaseUseSignInWithRedirect=true;",null);}
            @Override public void onPageFinished(WebView view,String url){progress.setVisibility(android.view.View.GONE);view.evaluateJavascript("window.avaturnFirebaseUseSignInWithRedirect=true;",null);}
        });
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_REQUEST);
        web.loadUrl(CREATOR);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private class AvatarBridge {
        @JavascriptInterface public void postMessage(String raw){
            try{
                JSONObject event=new JSONObject(raw);if(!"v2.avatar.exported".equals(event.optString("eventName")))return;
                JSONObject data=event.getJSONObject("data");String type=data.optString("urlType"),url=data.optString("url");
                runOnUiThread(()->{
                    if(!"httpURL".equals(type)||!url.startsWith("https://")){Toast.makeText(AvaturnActivity.this,"Set Avaturn export type to httpURL in the developer portal",Toast.LENGTH_LONG).show();return;}
                    client.saveAvatar3d(url,(ok,message,body)->{Toast.makeText(AvaturnActivity.this,ok?"3D RiderLink avatar saved":message,Toast.LENGTH_LONG).show();if(ok)finish();});
                });
            }catch(Exception ignored){}
        }
    }
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)Fullscreen.apply(this);}
    @Override protected void onDestroy(){if(web!=null){web.removeJavascriptInterface("native_app");web.destroy();}if(client!=null)client.shutdown();super.onDestroy();}
}
