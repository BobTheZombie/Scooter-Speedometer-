package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;

/** Small dependency-free visual system shared by every cockpit screen. */
public final class UiKit {
    public static final int SURFACE=Color.rgb(9,20,26),SURFACE_HIGH=Color.rgb(15,35,43),CYAN=Color.rgb(0,211,238),MUTED=Color.rgb(145,170,180);
    private UiKit(){}
    public static android.graphics.drawable.Drawable rounded(Context context,int fill,float radiusDp,int stroke){GradientDrawable shape=new GradientDrawable();shape.setColor(fill);shape.setCornerRadius(radiusDp*context.getResources().getDisplayMetrics().density);if(stroke!=Color.TRANSPARENT)shape.setStroke(Math.max(1,Math.round(context.getResources().getDisplayMetrics().density)),stroke);return new RippleDrawable(ColorStateList.valueOf(Color.argb(65,255,255,255)),shape,null);}
    public static void button(Button button,int fill){button.setAllCaps(false);button.setSingleLine(true);button.setTextColor(Color.WHITE);button.setTextSize(13);button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(dp(button,14),0,dp(button,14),0);button.setBackground(rounded(button.getContext(),fill,14,Color.argb(85,255,255,255)));button.setStateListAnimator(null);}
    public static void card(View view){view.setBackground(rounded(view.getContext(),SURFACE_HIGH,16,Color.rgb(30,62,72)));view.setClickable(true);view.setFocusable(true);view.setElevation(3f*view.getResources().getDisplayMetrics().density);}
    private static int dp(View view,int value){return Math.round(value*view.getResources().getDisplayMetrics().density);}
}
