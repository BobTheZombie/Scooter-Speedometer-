package com.stankhouse.scooterspeedometer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import org.json.JSONObject;

/** Lightweight vector RiderLink avatar: customizable rider plus their scooter. */
public class RiderAvatarView extends View {
    public static final int[] SKINS = {0xfff6d0b1,0xffe8b98d,0xffce946b,0xffa96f4f,0xff7d4d38,0xff553326,0xff3a231b,0xffffdfc4};
    public static final int[] HAIR_COLORS = {0xff17120f,0xff4b2b18,0xff8b552f,0xffd5ad63,0xffb52820,0xff653090,0xff1596a8,0xffd8d8d8,0xfff26db4,0xff111827};
    public static final int[] OUTFITS = {0xff111827,0xff0ea5e9,0xffef4444,0xff22c55e,0xfff59e0b,0xff8b5cf6,0xffec4899,0xffe5e7eb,0xff78350f,0xff06b6d4};
    public static final int[] SCOOTERS = {0xffe11d48,0xff0284c7,0xff16a34a,0xfff59e0b,0xff7c3aed,0xfff4f4f5,0xff111827,0xfff97316,0xffdb2777,0xff64748b};
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private AvatarSpec spec = new AvatarSpec();

    public RiderAvatarView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
    public void setSpec(AvatarSpec value) { spec = value == null ? new AvatarSpec() : value; invalidate(); }
    public AvatarSpec getSpec() { return spec; }

    @Override protected void onDraw(Canvas c) {
        float w=getWidth(),h=getHeight(),s=Math.min(w,h)/300f;
        p.setStyle(Paint.Style.FILL);p.setColor(0xff071219);c.drawRoundRect(new RectF(0,0,w,h),24*s,24*s,p);
        p.setColor(0xff0d2630);c.drawCircle(w*.5f,h*.48f,w*.43f,p);
        drawScooter(c,w,h,s);drawRider(c,w,h,s);
        p.setColor(0xff00e5ff);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3*s);c.drawRoundRect(new RectF(2*s,2*s,w-2*s,h-2*s),22*s,22*s,p);
    }
    private void drawRider(Canvas c,float w,float h,float s){
        int skin=SKINS[Math.floorMod(spec.skin,SKINS.length)],hair=HAIR_COLORS[Math.floorMod(spec.hairColor,HAIR_COLORS.length)],outfit=OUTFITS[Math.floorMod(spec.outfit,OUTFITS.length)];
        float cx=w*.48f,headY=h*.29f,headR=35*s;
        p.setStyle(Paint.Style.FILL);p.setColor(outfit);c.drawRoundRect(new RectF(cx-47*s,h*.39f,cx+48*s,h*.69f),28*s,28*s,p);
        if(spec.outfitStyle%3==1){p.setColor(0xffd8dee5);p.setStrokeWidth(5*s);c.drawRect(cx-3*s,h*.40f,cx+3*s,h*.68f,p);}
        if(spec.outfitStyle%3==2){p.setColor(0xff1f2937);c.drawRect(cx-47*s,h*.53f,cx+48*s,h*.60f,p);}
        p.setColor(skin);c.drawCircle(cx,headY,headR,p);c.drawRoundRect(new RectF(cx-10*s,headY+25*s,cx+10*s,h*.43f),8*s,8*s,p);
        drawHair(c,cx,headY,headR,hair,s);drawFace(c,cx,headY,s);
        if(spec.helmet>0){p.setColor(SCOOTERS[Math.floorMod(spec.helmet-1,SCOOTERS.length)]);c.drawArc(new RectF(cx-headR-5*s,headY-headR-6*s,cx+headR+5*s,headY+headR+8*s),185,170,true,p);p.setColor(0xaa8be8ff);c.drawArc(new RectF(cx-31*s,headY-8*s,cx+34*s,headY+32*s),190,140,true,p);}
    }
    private void drawHair(Canvas c,float cx,float y,float r,int color,float s){
        p.setColor(color);int style=Math.floorMod(spec.hairStyle,10);
        if(style==0)return;
        if(style<=2)c.drawArc(new RectF(cx-r,y-r-5*s,cx+r,y+r),180,180,true,p);
        else if(style==3){for(int i=-3;i<=3;i++)c.drawCircle(cx+i*10*s,y-r+i%2*5*s,10*s,p);}
        else if(style==4){c.drawRect(cx-r,y-r,cx+r,y-10*s,p);c.drawRoundRect(new RectF(cx+r-5*s,y-15*s,cx+r+14*s,y+45*s),8*s,8*s,p);}
        else if(style==5){for(int i=-3;i<=3;i++){Path q=new Path();q.moveTo(cx+i*9*s,y-r+8*s);q.lineTo(cx+i*7*s,y-r-25*s-Math.abs(i)*2*s);q.lineTo(cx+i*14*s,y-r+8*s);c.drawPath(q,p);}}
        else if(style==6){c.drawArc(new RectF(cx-r-5*s,y-r-8*s,cx+r+5*s,y+r),180,180,true,p);c.drawCircle(cx-r,y+12*s,13*s,p);c.drawCircle(cx+r,y+12*s,13*s,p);}
        else if(style==7){c.drawArc(new RectF(cx-r,y-r,cx+r,y+r),180,180,true,p);c.drawOval(new RectF(cx-9*s,y-r-30*s,cx+12*s,y-r+2*s),p);}
        else if(style==8){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(7*s);for(int i=-3;i<=3;i++)c.drawArc(new RectF(cx+i*7*s-8*s,y-r-5*s,cx+i*7*s+8*s,y+15*s),170,190,false,p);p.setStyle(Paint.Style.FILL);}
        else {c.drawArc(new RectF(cx-r,y-r,cx+r,y+r),180,180,true,p);c.drawRect(cx-r,y-5*s,cx-r+9*s,y+50*s,p);c.drawRect(cx+r-9*s,y-5*s,cx+r,y+50*s,p);}
    }
    private void drawFace(Canvas c,float cx,float y,float s){
        p.setColor(0xff211814);int face=Math.floorMod(spec.face,6);float eyeY=y+(face==2?0:-3*s);
        if(face==4){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3*s);c.drawCircle(cx-13*s,eyeY,8*s,p);c.drawCircle(cx+13*s,eyeY,8*s,p);c.drawLine(cx-5*s,eyeY,cx+5*s,eyeY,p);p.setStyle(Paint.Style.FILL);}
        else {c.drawCircle(cx-13*s,eyeY,3.5f*s,p);c.drawCircle(cx+13*s,eyeY,3.5f*s,p);}
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f*s);
        if(face==1)c.drawArc(new RectF(cx-14*s,y+4*s,cx+14*s,y+21*s),5,170,false,p);
        else if(face==3)c.drawLine(cx-12*s,y+17*s,cx+12*s,y+17*s,p);
        else c.drawArc(new RectF(cx-12*s,y+7*s,cx+12*s,y+21*s),15,150,false,p);
        if(face==5){p.setStrokeWidth(5*s);c.drawArc(new RectF(cx-22*s,y+3*s,cx+22*s,y+32*s),10,160,false,p);}p.setStyle(Paint.Style.FILL);
    }
    private void drawScooter(Canvas c,float w,float h,float s){
        int color=SCOOTERS[Math.floorMod(spec.scooterColor,SCOOTERS.length)];float y=h*.77f;
        p.setColor(0xff101317);c.drawCircle(w*.28f,y,24*s,p);c.drawCircle(w*.76f,y,24*s,p);p.setColor(0xff89949c);c.drawCircle(w*.28f,y,11*s,p);c.drawCircle(w*.76f,y,11*s,p);
        p.setColor(color);Path body=new Path();body.moveTo(w*.25f,y-15*s);body.quadTo(w*.47f,y-64*s,w*.72f,y-31*s);body.lineTo(w*.80f,y-10*s);body.lineTo(w*.35f,y-7*s);body.close();c.drawPath(body,p);
        if(spec.scooterStyle%3==1){p.setColor(0xffdbeafe);c.drawRoundRect(new RectF(w*.43f,y-43*s,w*.69f,y-24*s),8*s,8*s,p);}
        else if(spec.scooterStyle%3==2){p.setColor(0xff111827);c.drawRect(w*.36f,y-19*s,w*.72f,y-10*s,p);}
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6*s);p.setColor(0xff9aa7af);c.drawLine(w*.68f,y-31*s,w*.72f,y-83*s,p);c.drawLine(w*.69f,y-82*s,w*.82f,y-82*s,p);p.setStyle(Paint.Style.FILL);
        p.setColor(0xfffff2a8);c.drawCircle(w*.74f,y-48*s,8*s,p);p.setColor(0xff1f2937);c.drawRoundRect(new RectF(w*.31f,y-55*s,w*.51f,y-43*s),7*s,7*s,p);
    }

    public static class AvatarSpec {
        public int skin=2,face=0,hairStyle=2,hairColor=1,outfit=1,outfitStyle=0,helmet=0,scooterStyle=0,scooterColor=0;
        public String toJson(){try{return new JSONObject().put("skin",skin).put("face",face).put("hairStyle",hairStyle).put("hairColor",hairColor).put("outfit",outfit).put("outfitStyle",outfitStyle).put("helmet",helmet).put("scooterStyle",scooterStyle).put("scooterColor",scooterColor).toString();}catch(Exception e){return "{}";}}
        public static AvatarSpec fromJson(String json){AvatarSpec s=new AvatarSpec();try{JSONObject o=new JSONObject(json==null?"{}":json);s.skin=o.optInt("skin",s.skin);s.face=o.optInt("face",0);s.hairStyle=o.optInt("hairStyle",2);s.hairColor=o.optInt("hairColor",1);s.outfit=o.optInt("outfit",1);s.outfitStyle=o.optInt("outfitStyle",0);s.helmet=o.optInt("helmet",0);s.scooterStyle=o.optInt("scooterStyle",0);s.scooterColor=o.optInt("scooterColor",0);}catch(Exception ignored){}return s;}
    }
}
