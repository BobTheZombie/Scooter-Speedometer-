package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Dependency-free design system shared by the complete riding experience. */
public final class UiKit {
    public static final int BACKGROUND=Color.rgb(3,9,13),SURFACE=Color.rgb(8,20,27),SURFACE_HIGH=Color.rgb(16,31,38),SURFACE_ACTIVE=Color.rgb(19,55,64),BORDER=Color.rgb(41,74,86),CYAN=Color.rgb(53,217,240),CYAN_BRIGHT=Color.rgb(106,232,249),TEXT=Color.rgb(242,249,251),MUTED=Color.rgb(145,170,180),AMBER=Color.rgb(255,184,77),RED=Color.rgb(255,92,98),GREEN=Color.rgb(72,222,149);
    private UiKit(){}
    public static android.graphics.drawable.Drawable rounded(Context context,int fill,float radiusDp,int stroke){GradientDrawable shape=new GradientDrawable();shape.setColor(fill);shape.setCornerRadius(radiusDp*context.getResources().getDisplayMetrics().density);if(stroke!=Color.TRANSPARENT)shape.setStroke(Math.max(1,Math.round(context.getResources().getDisplayMetrics().density)),stroke);return new RippleDrawable(ColorStateList.valueOf(Color.argb(65,255,255,255)),shape,null);}
    public static android.graphics.drawable.Drawable screenBackground(Context context){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{BACKGROUND,Color.rgb(6,20,27),Color.rgb(3,10,14)});g.setGradientType(GradientDrawable.LINEAR_GRADIENT);return g;}
    public static void button(Button button,int fill){button.setAllCaps(false);button.setSingleLine(true);button.setTextColor(TEXT);button.setTextSize(14);button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(dp(button.getContext(),48));button.setPadding(dp(button.getContext(),16),0,dp(button.getContext(),16),0);button.setBackground(rounded(button.getContext(),fill,24,fill==CYAN?CYAN_BRIGHT:BORDER));button.setStateListAnimator(null);button.setElevation(0);}
    public static Button iconButton(Context context,String icon,String label,int fill){Button b=new Button(context);b.setText(icon+"  "+label);button(b,fill);return b;}
    public static TextView text(Context context,String value,float size,int color,boolean medium){TextView t=new TextView(context);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    public static TextView section(Context context,String icon,String title){TextView t=text(context,icon+"  "+title.toUpperCase(),11,CYAN,true);t.setLetterSpacing(.11f);t.setPadding(dp(context,4),dp(context,14),dp(context,4),dp(context,7));return t;}
    public static LinearLayout header(Context context,String icon,String title,String subtitle){LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(context,18),dp(context,10),dp(context,14),dp(context,10));TextView mark=text(context,icon,25,CYAN_BRIGHT,true);mark.setGravity(Gravity.CENTER);mark.setBackground(rounded(context,SURFACE_ACTIVE,16,BORDER));row.addView(mark,new LinearLayout.LayoutParams(dp(context,48),dp(context,48)));LinearLayout copy=new LinearLayout(context);copy.setOrientation(LinearLayout.VERTICAL);copy.setPadding(dp(context,13),0,0,0);TextView name=text(context,title,20,TEXT,true),sub=text(context,subtitle,11,MUTED,false);copy.addView(name,new LinearLayout.LayoutParams(-1,dp(context,27)));copy.addView(sub,new LinearLayout.LayoutParams(-1,dp(context,20)));row.addView(copy,new LinearLayout.LayoutParams(0,dp(context,52),1));return row;}
    public static void card(View view){view.setBackground(rounded(view.getContext(),SURFACE_HIGH,20,BORDER));view.setClickable(true);view.setFocusable(true);view.setElevation(dp(view.getContext(),1));}
    public static void field(EditText field){field.setTextColor(TEXT);field.setHintTextColor(Color.rgb(113,143,154));field.setTextSize(16);field.setSingleLine(true);field.setMinHeight(dp(field.getContext(),56));field.setPadding(dp(field.getContext(),16),0,dp(field.getContext(),16),0);field.setBackground(rounded(field.getContext(),SURFACE,16,BORDER));}
    public static int dp(Context context,float value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
