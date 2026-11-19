package dev.fppulse;

import android.animation.ValueAnimator;
import android.app.Application;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.graphics.*;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import java.io.*;
import java.util.zip.*;
import java.util.HashMap;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;

/** Recorded artwork only; no fake scan progress or biometric inputs. */
final class ReferencePack {
    static String modulePath;
    private static boolean attempted,loaded;
    private static Bitmap idle,ring,icon,dots;
    private static byte[] tutorial;
    private static synchronized File tutorialFile(Application app)throws IOException{
        File target=new File(app.getCacheDir(),"fppulse_reference_tutorial_v12.mp4");
        if(target.isFile()&&target.length()==tutorial.length)return target;
        File tmp=File.createTempFile("fp_tutorial_",".mp4",app.getCacheDir());
        try{
            try(FileOutputStream out=new FileOutputStream(tmp)){out.write(tutorial);}
            if(!tmp.renameTo(target))throw new IOException("tutorial cache rename");
            return target;
        }finally{if(tmp.exists())tmp.delete();}
    }
    static synchronized boolean load(){
        if(attempted)return loaded;
        attempted=true;
        if(modulePath==null)return false;
        try(ZipFile apk=new ZipFile(modulePath)){
            ZipEntry entry=apk.getEntry("assets/reference_assets.zip");
            if(entry==null)throw new IOException("Missing reference_assets.zip");
            HashMap<String,byte[]> data=new HashMap<>();
            try(ZipInputStream zip=new ZipInputStream(apk.getInputStream(entry))){
                ZipEntry next;
                while((next=zip.getNextEntry())!=null){
                    String n=next.getName();
                    if(!n.equals("idle.png")&&!n.equals("ring.png")&&!n.equals("icon.png")&&!n.equals("dots.png")&&!n.equals("tutorial.mp4"))continue;
                    ByteArrayOutputStream out=new ByteArrayOutputStream();
                    byte[] buf=new byte[8192];int count;
                    while((count=zip.read(buf))!=-1){
                        out.write(buf,0,count);if(out.size()>4*1024*1024)throw new IOException("asset too large");
                    }
                    data.put(n,out.toByteArray());
                }
            }
            idle=decode(data.get("idle.png"));ring=decode(data.get("ring.png"));
            icon=decode(data.get("icon.png"));dots=decode(data.get("dots.png"));
            tutorial=data.get("tutorial.mp4");
            if(tutorial==null||tutorial.length<1024)throw new IOException("missing tutorial");
            loaded=true;
            XposedBridge.log("FingerprintPulse: actual extracted reference assets loaded");
        }catch(Throwable e){XposedBridge.log("FingerprintPulse: reference pack unavailable, keep native theme: "+e);}
        return loaded;
    }
    private static Bitmap decode(byte[] data)throws IOException{
        if(data==null)throw new IOException("missing sprite");
        Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length);
        if(b==null||b.getWidth()!=320||b.getHeight()!=320)throw new IOException("unexpected sprite size");
        return b;
    }
    static void installTutorialHook(){
        try{
            XposedBridge.hookMethod(Resources.class.getDeclaredMethod("openRawResourceFd",int.class),new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    if(p.hasThrowable()||!loaded||!(p.getResult() instanceof AssetFileDescriptor))return;
                    try{
                        Resources r=(Resources)p.thisObject;int id=(Integer)p.args[0];
                        if(!"com.samsung.android.biometrics.app.setting".equals(r.getResourcePackageName(id))||
                            !"sec_fingerprint_v_02".equals(r.getResourceEntryName(id)))return;
                        // Only Samsung's verified static portrait help movie is replaced.
                        // No camera or face/fingerprint capture surface is affected.
                        Class<?> thread=Class.forName("android.app.ActivityThread");
                        java.lang.reflect.Method current=thread.getDeclaredMethod("currentApplication");current.setAccessible(true);
                        Application app=(Application)current.invoke(null);
                        if(app==null)return;
                        File file=tutorialFile(app);
                        ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
                        AssetFileDescriptor replacement=new AssetFileDescriptor(fd,0,file.length());
                        AssetFileDescriptor old=(AssetFileDescriptor)p.getResult();
                        p.setResult(replacement);
                        try{old.close();}catch(IOException ignored){}
                        XposedBridge.log("FingerprintPulse: reference tutorial movie supplied");
                    }catch(Throwable e){XposedBridge.log("FingerprintPulse: native tutorial fallback: "+e);}
                }
            });
        }catch(Throwable e){XposedBridge.log("FingerprintPulse: tutorial hook unavailable: "+e);}
    }
    static final class Renderer {
        // Coordinates measured in the 588px reference, centered on the native icon.
        // Three contours traced from the recorded bright icon at its alpha midpoint.
        private static final int[][] CONTOURS={
            {-30,4,-31,6,-30,18,-26,29,-24,31,-24,33,-12,47,-9,49,-7,49,-5,46,-6,44,-15,36,-21,27,-25,16,-25,8,-27,4},
            {-12,-28,-16,-26,-25,-18,-29,-11,-28,-7,-25,-7,-21,-14,-19,-15,-16,-19,-10,-23,-7,-23,-4,-25,5,-25,15,-21,23,-14,26,-9,26,-7,28,-4,28,4,25,7,22,7,19,5,17,-5,11,-12,8,-14,1,-16,-2,-16,-10,-12,-16,-5,-18,2,-18,7,-16,12,-16,16,-10,28,-2,36,3,39,13,43,19,43,21,44,23,43,23,39,21,38,15,38,5,34,-7,23,-12,10,-12,0,-9,-6,-5,-9,-2,-10,5,-9,11,-4,14,4,14,7,19,12,25,13,29,11,33,6,33,-6,31,-9,31,-12,27,-18,22,-22,21,-24,13,-28,7,-30,-6,-30,-9,-28},
            {35,-32,24,-40,15,-44,12,-44,7,-46,-7,-46,-11,-44,-15,-44,-18,-42,-20,-42,-29,-36,-40,-23,-44,-14,-44,-11,-46,-8,-46,-1,-47,0,-46,21,-44,24,-42,33,-38,40,-35,41,-33,39,-33,37,-37,31,-37,29,-39,26,-39,22,-41,18,-41,10,-42,9,-41,-6,-39,-10,-39,-13,-35,-21,-24,-33,-13,-39,-9,-39,-5,-41,5,-41,9,-39,13,-39,16,-37,18,-37,20,-35,22,-35,36,-22,36,-20,40,-14,40,-10,42,-6,42,7,37,17,32,21,22,24,21,23,14,22,5,12,5,10,3,7,3,3,1,1,-2,2,-2,9,0,12,0,15,4,21,12,27,15,27,18,29,29,28,39,23,45,15,45,12,47,9,47,-9,45,-13,45,-16,43,-18,41,-24}
        };
        private final Path fingerprint=new Path();
        private final RectF arc=new RectF(-120.5f,-120.5f,120.5f,120.5f);
        private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final LinearGradient iconShader=new LinearGradient(0,-48,0,49,
            new int[]{0xff807cff,0xff7688fe,0xff60a7fa},null,Shader.TileMode.CLAMP);
        private final SweepGradient ringShader=new SweepGradient(0,0,
            new int[]{0xff6e98e7,0xff57c8ea,0xff53aae5,0xff5b8deb,0xff5c86f6,0xff5880ea,0xff589bf1,0xff58bde2,0xff66aae2,0xff7183e2,0xff7b75e9,0xff7b77e5,0xff6e98e7},null);
        private static final int BINS=12;
        private final float[][] points=new float[BINS][3600];
        private final int[] counts=new int[BINS];
        private final float[] gx=new float[1800],gy=new float[1800],rad=new float[1800],angle=new float[1800];
        private int dotCount;
        private long began=-1L,scanStart=-1L,release=-1L;
        private boolean entered,wasActive;
        private float lastQ;
        Renderer(){
            for(int[] a:CONTOURS){
                int n=a.length;float lx=(a[n-2]+a[0])*.5f,ly=(a[n-1]+a[1])*.5f;
                fingerprint.moveTo(lx,ly);
                for(int j=0;j<n;j+=2){
                    int k=(j+2)%n;
                    fingerprint.quadTo(a[j],a[j+1],(a[j]+a[k])*.5f,(a[j+1]+a[k+1])*.5f);
                }
                fingerprint.close();
            }
            ink.setStyle(Paint.Style.FILL);
            ringPaint.setStyle(Paint.Style.STROKE);ringPaint.setStrokeCap(Paint.Cap.ROUND);ringPaint.setShader(ringShader);
            dotPaint.setStyle(Paint.Style.STROKE);dotPaint.setStrokeCap(Paint.Cap.ROUND);
            // Measured idle lattice: about 7 reference pixels per row and column,
            // with alternate rows offset by half a column.
            for(int row=-20;row<=20;row++)for(int col=-20;col<=20;col++){
                float x=col*7f+((row&1)==0?0:3.5f),y=row*7f;
                float d=(float)Math.hypot(x,y);
                if(d>142f||dotCount>=gx.length)continue;
                gx[dotCount]=x;gy[dotCount]=y;rad[dotCount]=d;
                angle[dotCount]=(float)((Math.toDegrees(Math.atan2(y,x))+450)%360);
                dotCount++;
            }
        }
        private static float clamp(float x){return Math.max(0,Math.min(1,x));}
        private static float smooth(float a,float b,float x){float t=clamp((x-a)/(b-a));return t*t*(3-2*t);}
        private void pixels(Canvas c,float expansion,float q,float alpha){
            java.util.Arrays.fill(counts,0);
            float spread=1f+.085f*expansion;
            for(int i=0;i<dotCount;i++){
                float r=rad[i]*spread;
                // Diffuse idle disk -> hollow bright annulus, with the lit arc
                // progressively covering the dots instead of fading the entire halo.
                float idleA=(1f-smooth(40,141,r))*.55f;
                float band=smooth(68,93,r)*(1f-smooth(119,138,r));
                float covered=smooth(-8f,12f,q*360f-angle[i]);
                float a=alpha*((1-expansion)*idleA+expansion*band*.89f*(1f-.91f*covered));
                int bin=Math.min(BINS-1,Math.round(a*(BINS-1)));
                if(bin==0)continue;
                int n=counts[bin];points[bin][n]=gx[i]*spread;points[bin][n+1]=gy[i]*spread;counts[bin]=n+2;
            }
            dotPaint.setStrokeWidth(2.0f+.68f*expansion);
            for(int i=1;i<BINS;i++)if(counts[i]>0){
                dotPaint.setColor(0xffc5c5d7);dotPaint.setAlpha(Math.round(i*210f/(BINS-1)));
                c.drawPoints(points[i],0,counts[i],dotPaint);
            }
        }
        private void icon(Canvas c,float active,float alpha){
            if(alpha<=.001f)return;
            ink.setShader(null);ink.setColor(0xff73737e);ink.setAlpha(Math.round(255*alpha*(1-active)));
            if(ink.getAlpha()>0)c.drawPath(fingerprint,ink);
            ink.setShader(iconShader);ink.setAlpha(Math.round(255*alpha*active));
            if(ink.getAlpha()>0)c.drawPath(fingerprint,ink);
            ink.setShader(null);
        }
        private void ring(Canvas c,float q,float alpha){
            if(q<=.0001f||alpha<=.001f)return;
            // Core stroke and restrained optical edge. All curves are rendered
            // at the destination resolution, not enlarged compressed bitmaps.
            float sweep=360f*q;
            ringPaint.setStrokeWidth(15f);ringPaint.setAlpha(Math.round(38*alpha));
            c.drawArc(arc,-90,sweep,false,ringPaint);
            ringPaint.setStrokeWidth(11f);ringPaint.setAlpha(Math.round(105*alpha));
            c.drawArc(arc,-90,sweep,false,ringPaint);
            ringPaint.setStrokeWidth(8.4f);ringPaint.setAlpha(Math.round(248*alpha));
            c.drawArc(arc,-90,sweep,false,ringPaint);
        }
        boolean draw(View v,Canvas c,float x,float y,boolean active,float q){
            if(!loaded||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(q))return false;
            float unit=v.getResources().getDisplayMetrics().widthPixels/588f;
            if(!Float.isFinite(unit)||unit<=.05f)return false;
            long now=SystemClock.uptimeMillis();
            boolean motion=ValueAnimator.areAnimatorsEnabled();
            if(began<0)began=now;
            if(active||!motion)entered=true;
            float intro=entered?1f:clamp((now-began)/180f);
            if(intro>=1f)entered=true;
            q=clamp(q);
            if(active&&!wasActive){scanStart=now;release=-1L;lastQ=0;}
            if(active&&q<lastQ-.12f)scanStart=now;
            if(!active&&wasActive)release=now;
            wasActive=active;
            boolean more=!entered;
            int save=c.save();
            try{
                c.translate(x,y);c.scale(unit,unit);
                if(active){
                    // Keep Samsung's per-scan progress unchanged: no low-pass delay.
                    float expansion=motion?Math.max(smooth(0,.20f,q),smooth(0,105,now-scanStart)):1f;
                    float tint=motion?Math.max(smooth(0,.055f,q),smooth(0,76,now-scanStart)):1f;
                    pixels(c,expansion,q,1f);
                    icon(c,tint,1f);
                    ring(c,q,1f);
                    lastQ=q;
                    more=motion&&(expansion<1f||tint<1f);
                }else{
                    float fade=(!motion||release<0)?1f:smooth(0,90,now-release);
                    float reveal=smooth(.05f,.95f,intro);
                    pixels(c,1-fade,lastQ*(1-fade),reveal);
                    icon(c,1-fade,reveal);
                    if(fade<1f)ring(c,lastQ,1-fade);
                    more|=fade<1f;
                }
            }finally{c.restoreToCount(save);}
            if(more&&v.isAttachedToWindow()&&v.isShown())v.postInvalidateOnAnimation();
            return true;
        }
    }
}
