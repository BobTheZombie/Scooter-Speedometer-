package com.stankhouse.scooterspeedometer;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/** Original layered vector weather scene with Sense-inspired depth and motion. */
final class SenseWeatherScene {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final RectF oval=new RectF();

    void draw(Canvas c,float w,float h,int code,long now,boolean night){
        boolean thunder=code>=95,snow=(code>=71&&code<=77)||(code>=85&&code<=86);
        boolean rain=(code>=51&&code<=67)||(code>=80&&code<=82),fog=code==45||code==48;
        boolean cloudy=(code>=1&&code<=3)||rain||snow||thunder;
        int top=thunder?Color.rgb(18,20,39):rain?Color.rgb(40,65,82):snow?Color.rgb(115,139,157):fog?Color.rgb(105,119,126):night?Color.rgb(10,25,48):Color.rgb(32,125,190);
        int bottom=thunder?Color.rgb(2,5,15):rain?Color.rgb(8,23,34):snow?Color.rgb(30,50,65):fog?Color.rgb(35,47,52):night?Color.rgb(2,8,20):Color.rgb(4,43,82);
        p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(0,0,0,h,top,bottom,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(null);

        // Subtle horizon depth gives the flat vectors a dimensional stage.
        p.setShader(new RadialGradient(w*.5f,h*.52f,w*.72f,Color.argb(night?20:75,180,225,255),Color.TRANSPARENT,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(null);
        if(!cloudy&&!fog)drawSunOrMoon(c,w,h,now,night);
        else if(code==1||code==2)drawSunOrMoon(c,w,h,now,night);

        if(cloudy){
            float near=(now%28000L)/28000f*w,far=(now%43000L)/43000f*w;
            drawCloudBank(c,-w*.42f+far,h*.18f,w*.82f,thunder?0:snow?1:2,.55f);
            drawCloudBank(c,w*.45f+far,h*.13f,w*.72f,thunder?0:2,.48f);
            drawCloudBank(c,-w*.58f+near,h*.28f,w*.90f,thunder?0:rain?1:2,.82f);
            drawCloudBank(c,w*.35f+near,h*.24f,w*.88f,thunder?0:rain?1:2,.78f);
        }
        if(rain||thunder)drawRain(c,w,h,now,thunder);
        if(snow)drawSnow(c,w,h,now);
        if(fog)drawFog(c,w,h,now);
        if(thunder)drawLightning(c,w,h,now);

        // Glassy atmospheric vignette; keeps text and gauges readable.
        p.setShader(new LinearGradient(0,0,w,h,Color.argb(25,255,255,255),Color.argb(70,0,7,12),Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(null);
    }

    private void drawSunOrMoon(Canvas c,float w,float h,long now,boolean night){
        float x=w*.79f,y=h*.25f,r=w*.072f,pulse=1f+.035f*(float)Math.sin(now/850d);
        int core=night?Color.rgb(224,236,250):Color.rgb(255,226,103),glow=night?Color.argb(105,160,205,255):Color.argb(155,255,195,45);
        p.setShader(new RadialGradient(x,y,r*3.2f*pulse,new int[]{glow,Color.argb(35,Color.red(core),Color.green(core),Color.blue(core)),Color.TRANSPARENT},new float[]{0,.42f,1},Shader.TileMode.CLAMP));c.drawCircle(x,y,r*3.2f*pulse,p);p.setShader(null);
        p.setColor(core);c.drawCircle(x,y,r,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(2,w/260));p.setColor(Color.argb(night?80:155,255,225,120));
        for(int i=0;i<12;i++){double a=i*Math.PI/6+now/12000d;float ca=(float)Math.cos(a),sa=(float)Math.sin(a);c.drawLine(x+ca*r*1.35f,y+sa*r*1.35f,x+ca*r*1.95f,y+sa*r*1.95f,p);}p.setStyle(Paint.Style.FILL);
        if(night){p.setColor(Color.argb(115,115,145,175));c.drawCircle(x-r*.28f,y-r*.16f,r*.14f,p);c.drawCircle(x+r*.24f,y+r*.28f,r*.10f,p);}
    }

    private void drawCloudBank(Canvas c,float x,float y,float size,int tone,float alpha){
        int shadow=tone==0?Color.rgb(22,27,38):tone==1?Color.rgb(70,88,101):Color.rgb(116,137,150);
        int light=tone==0?Color.rgb(72,78,96):tone==1?Color.rgb(175,194,205):Color.rgb(226,238,244);
        float bh=size*.20f;
        p.setColor(Color.argb((int)(125*alpha),8,15,22));oval.set(x+size*.05f,y+bh*.48f,x+size*.95f,y+bh*1.62f);c.drawOval(oval,p);
        p.setShader(new LinearGradient(0,y,0,y+bh*1.5f,Color.argb((int)(245*alpha),Color.red(light),Color.green(light),Color.blue(light)),Color.argb((int)(235*alpha),Color.red(shadow),Color.green(shadow),Color.blue(shadow)),Shader.TileMode.CLAMP));
        cloudOval(c,x+size*.03f,y+bh*.56f,size*.42f,bh*.82f);cloudOval(c,x+size*.24f,y+bh*.12f,size*.42f,bh*1.18f);cloudOval(c,x+size*.49f,y+bh*.35f,size*.46f,bh*.98f);oval.set(x+size*.10f,y+bh*.65f,x+size*.88f,y+bh*1.45f);c.drawOval(oval,p);p.setShader(null);
        p.setColor(Color.argb((int)(62*alpha),255,255,255));oval.set(x+size*.19f,y+bh*.31f,x+size*.52f,y+bh*.68f);c.drawOval(oval,p);
    }
    private void cloudOval(Canvas c,float x,float y,float w,float h){oval.set(x,y,x+w,y+h);c.drawOval(oval,p);}

    private void drawRain(Canvas c,float w,float h,long now,boolean storm){p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);for(int i=0;i<58;i++){int layer=i%3;float speed=storm?2.1f:1.5f;float x=((i*109f+now*(.055f+layer*.012f))%(w+80))-40;float y=((i*173f+now*(.15f+layer*.035f)*speed)%(h+150))-150;float len=h*(.025f+layer*.012f);p.setStrokeWidth(1f+layer*1.1f);p.setColor(Color.argb(75+layer*42,125,210,255));c.drawLine(x,y,x-w*.014f,y+len,p);}p.setStyle(Paint.Style.FILL);}
    private void drawSnow(Canvas c,float w,float h,long now){for(int i=0;i<46;i++){int layer=i%4;float y=((i*151f+now*(.025f+layer*.009f))%(h+40))-20;float x=((i*97f+(float)Math.sin(now/900d+i)*w*.035f+layer*w*.19f)%(w+30))-15;float r=1.3f+layer*1.05f;p.setColor(Color.argb(105+layer*35,247,252,255));c.drawCircle(x,y,r,p);}}
    private void drawFog(Canvas c,float w,float h,long now){for(int i=0;i<9;i++){float drift=(now/(30f+i*8f))%(w*.55f),y=h*(.12f+i*.095f),thick=h*(.025f+i%2*.014f);p.setShader(new LinearGradient(-w*.3f+drift,y,w*.9f+drift,y,new int[]{Color.TRANSPARENT,Color.argb(42+i*4,235,242,245),Color.TRANSPARENT},new float[]{0,.5f,1},Shader.TileMode.CLAMP));oval.set(-w*.35f+drift,y,w*.92f+drift,y+thick);c.drawRoundRect(oval,thick,thick,p);p.setShader(null);}}
    private void drawLightning(Canvas c,float w,float h,long now){long beat=now%6200L;if(beat>220L)return;p.setColor(Color.argb(beat<95?145:65,220,235,255));c.drawRect(0,0,w,h,p);path.reset();path.moveTo(w*.68f,h*.16f);path.lineTo(w*.55f,h*.39f);path.lineTo(w*.65f,h*.39f);path.lineTo(w*.48f,h*.68f);path.lineTo(w*.58f,h*.45f);path.lineTo(w*.48f,h*.45f);path.close();p.setShader(new LinearGradient(w*.55f,h*.15f,w*.52f,h*.68f,Color.WHITE,Color.rgb(255,210,55),Shader.TileMode.CLAMP));c.drawPath(path,p);p.setShader(null);}
}
