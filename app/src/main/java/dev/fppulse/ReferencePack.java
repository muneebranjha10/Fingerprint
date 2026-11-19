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
        File target=new File(app.getCacheDir(),"fppulse_reference_tutorial_v11.mp4");
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
        private final Paint arcPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF box=new RectF(),arc=new RectF(),scaled=new RectF();
        private final Matrix transform=new Matrix();
        private final BitmapShader ringShader=new BitmapShader(ring,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        private long start=-1L,last=-1L,scanStart=-1L,release=-1L,lastChange=-1L;
        private boolean entered,wasActive;
        private float shown,previousTarget,releasedProgress,releasedBlend;
        Renderer(){
            arcPaint.setStyle(Paint.Style.STROKE);
            arcPaint.setStrokeCap(Paint.Cap.ROUND);
            arcPaint.setShader(ringShader);
        }
        private static float clamp(float v){return Math.max(0f,Math.min(1f,v));}
        private static float smooth(float a,float b,float v){float t=clamp((v-a)/(b-a));return t*t*(3-2*t);}
        private void bitmap(Canvas c,Bitmap b,float alpha,float scale){
            if(alpha<=.001f)return;
            paint.setAlpha(Math.round(255*clamp(alpha)));
            if(scale==1f){c.drawBitmap(b,null,box,paint);return;}
            float hw=box.width()*.5f*scale,hh=box.height()*.5f*scale;
            float x=box.centerX(),y=box.centerY();
            scaled.set(x-hw,y-hh,x+hw,y+hh);
            c.drawBitmap(b,null,scaled,paint);
        }
        private void gradientArc(Canvas c,float x,float y,float unit,float q,float alpha){
            if(q<=.0005f||alpha<=.001f)return;
            if(q>=.9995f){bitmap(c,ring,alpha,1f);return;}
            // Sample the captured ring as a bitmap shader. A hardware stroked arc
            // produces a subpixel, antialiased moving edge, with no clipPath mask.
            transform.setScale(unit,unit);transform.postTranslate(box.left,box.top);
            ringShader.setLocalMatrix(transform);
            float r=120.5f*unit;
            arc.set(x-r,y-r,x+r,y+r);
            arcPaint.setStrokeWidth(36f*unit);
            arcPaint.setAlpha(Math.round(clamp(alpha)*255));
            c.drawArc(arc,-90f,360f*q,false,arcPaint);
        }
        boolean draw(View v,Canvas c,float x,float y,boolean active,float q){
            if(!loaded||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(q))return false;
            float unit=v.getResources().getDisplayMetrics().widthPixels/588f;
            if(!Float.isFinite(unit)||unit<=.05f)return false;
            float half=160f*unit;
            long now=SystemClock.uptimeMillis();
            float dt=last<0?16f:Math.min(64f,Math.max(1f,now-last));last=now;
            boolean anim=ValueAnimator.areAnimatorsEnabled();
            if(start<0)start=now;
            if(active||!anim)entered=true;
            float intro=entered?1f:clamp((now-start)/180f);
            if(intro>=1f)entered=true;
            float target=clamp(q);
            if(active&&!wasActive){
                scanStart=now;shown=0;previousTarget=0;lastChange=now;release=-1L;
            }
            if(active){
                if(target<previousTarget-.12f){shown=0;scanStart=now;}
                if(Math.abs(target-previousTarget)>.0005f)lastChange=now;
                previousTarget=target;
                // Low-latency interpolation: never display progress ahead of Samsung.
                if(!anim||target>=.9995f)shown=target;
                else if(target<shown)shown=target;
                else shown+=(target-shown)*(1f-(float)Math.exp(-dt/24f));
                if(target-shown<.0015f)shown=target;
            }
            if(!active&&wasActive){
                release=now;releasedProgress=shown;releasedBlend=1f;
            }
            wasActive=active;
            box.set(x-half,y-half,x+half,y+half);
            boolean more=!entered;
            if(active){
                // Reference frames: brighter pixel cluster, expanding annulus,
                // blue icon, then the clockwise ring. Native q drives the reveal.
                float appear=anim?smooth(0f,65f,now-scanStart):1f;
                float spread=smooth(.005f,.16f,shown);
                float ringAlpha=smooth(0f,.018f,shown);
                float dotAlpha=(1f-smooth(.34f,.98f,shown))*appear;
                bitmap(c,idle,1f-appear*spread,1f-.12f*appear*(1f-spread));
                bitmap(c,dots,dotAlpha,.67f+.33f*spread);
                bitmap(c,icon,appear*smooth(0f,.11f,shown),1f);
                gradientArc(c,x,y,unit,shown,ringAlpha);
                more=anim&&(appear<1f||Math.abs(target-shown)>.001f||
                    (shown<.999f&&lastChange>=0&&now-lastChange<180));
            }else{
                float fade=(!anim||release<0)?1f:smooth(0f,70f,now-release);
                if(release>=0&&fade<1f){
                    bitmap(c,icon,(1f-fade)*releasedBlend,1f);
                    gradientArc(c,x,y,unit,releasedProgress,1f-fade);
                }
                bitmap(c,idle,smooth(.04f,.95f,intro)*fade,1f);
                more|=fade<1f;
            }
            // Refresh only during a visual transition; idle does not burn frames.
            if(more&&v.isAttachedToWindow()&&v.isShown())v.postInvalidateOnAnimation();
            return true;
        }
    }
}
