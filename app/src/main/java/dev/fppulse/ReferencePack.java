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
        File target=new File(app.getCacheDir(),"fppulse_reference_tutorial_v10.mp4");
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
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF box=new RectF(),arc=new RectF();
        private final Path sector=new Path();
        private long start=-1L,release=-1L;
        private boolean entered,wasActive;
        private static float clamp(float v){return Math.max(0f,Math.min(1f,v));}
        private static float smooth(float a,float b,float v){
            float t=clamp((v-a)/(b-a));return t*t*(3-2*t);
        }
        private void bitmap(Canvas c,Bitmap b,float alpha){
            if(alpha<=0.001f)return;
            paint.setAlpha(Math.round(255*clamp(alpha)));
            c.drawBitmap(b,null,box,paint);
        }
        boolean draw(View v,Canvas c,float x,float y,boolean active,float q){
            if(!loaded||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(q))return false;
            // Reference capture is 588 pixels across. The same aspect ratio is
            // retained relative to the device display, not arbitrary icon size.
            float unit=v.getResources().getDisplayMetrics().widthPixels/588f;
            if(!Float.isFinite(unit)||unit<=.05f)return false;
            float half=160*unit;
            long now=SystemClock.uptimeMillis();
            boolean anim=ValueAnimator.areAnimatorsEnabled();
            if(start<0)start=now;if(active||!anim)entered=true;
            float intro=entered?1f:clamp((now-start)/210f);
            if(intro>=1f)entered=true;
            if(wasActive&&!active)release=now;
            if(active)release=-1L;
            wasActive=active;
            float restored=!anim||release<0?1f:smooth(0,105,now-release);
            q=clamp(q);
            box.set(x-half,y-half,x+half,y+half);
            if(!active){
                bitmap(c,idle,smooth(.05f,.95f,intro)*restored);
            }else{
                // The captured dotted annulus remains visible on the unfilled arc.
                float a=smooth(0,.055f,q);
                bitmap(c,idle,1-a);
                bitmap(c,dots,a);
                bitmap(c,icon,a);
                float sweep=360*q;
                if(sweep>=359.5f)bitmap(c,ring,1f);
                else if(sweep>.1f){
                    // Reveal the captured gradient starting at twelve o'clock.
                    // Round caps keep the original soft edge of the recorded ring.
                    sector.reset();sector.moveTo(x,y);
                    arc.set(x-half,y-half,x+half,y+half);
                    sector.arcTo(arc,-90,sweep,false);sector.close();
                    float r=122*unit, cap=8*unit;
                    sector.addCircle(x,y-r,cap,Path.Direction.CW);
                    double angle=Math.toRadians(sweep-90f);
                    sector.addCircle(x+r*(float)Math.cos(angle),y+r*(float)Math.sin(angle),cap,Path.Direction.CW);
                    int saved=c.save();
                    try{c.clipPath(sector);bitmap(c,ring,1f);}finally{c.restoreToCount(saved);}
                }
            }
            if((!entered||restored<1f)&&v.isAttachedToWindow()&&v.isShown())v.postInvalidateOnAnimation();
            return true;
        }
    }
}
