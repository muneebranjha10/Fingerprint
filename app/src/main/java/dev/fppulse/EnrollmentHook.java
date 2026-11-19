package dev.fppulse;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.*;
import java.lang.reflect.Method;
import java.util.HashSet;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.WeakHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Native animation, theme only. Never replaces a Canvas path or an animation callback. */
public final class EnrollmentHook implements IXposedHookLoadPackage {
    private static final String PKG="com.samsung.android.biometrics.app.setting";
    private static final String BASE=PKG+".fingerprint.enroll.";
    private static final String TAG="FingerprintPulse native theme: ";
    private static final WeakHashMap<View,Artwork> cache=new WeakHashMap<>();
    private static final WeakHashMap<View,DotHalo> halos=new WeakHashMap<>();
    private static final ThreadLocal<Pass> drawing=new ThreadLocal<>();
    private static boolean haloAvailable;
    private static boolean haloFailed;
    private static final class DotHalo {
        private static final int SIZE=384;
        private final Bitmap rest=texture(false), ring=texture(true);
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF bounds=new RectF();
        private static float clamp(float x){return Math.max(0f,Math.min(1f,x));}
        private static float smooth(float start,float end,float x){
            float t=clamp((x-start)/(end-start));return t*t*(3f-2f*t);
        }
        private static Bitmap texture(boolean annulus) {
            Bitmap b=Bitmap.createBitmap(SIZE,SIZE,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(b);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            // A dense, staggered gray pixel field, with an elliptical falloff at rest
            // and a wider textured annulus during the first part of a native scan.
            for(int row=-25;row<=25;row++){
                float yy=row*7f;
                for(int col=-25;col<=25;col++){
                    float xx=col*7f+((row&1)!=0?3.5f:0f);
                    float d=(float)Math.hypot(xx,yy)/160f;
                    float a;
                    if(annulus){
                        float v=(d-.91f)/.12f;
                        a=(float)Math.exp(-v*v*.5f);
                    }else{
                        float ex=xx/104f,ey=yy/90f;
                        a=(float)Math.exp(-(ex*ex+ey*ey)*1.10f);
                    }
                    if(a<.035f||d>1.12f)continue;
                    int strength=Math.round((annulus?145f:142f)*a);
                    p.setColor(Color.argb(strength,185,185,195));
                    c.drawCircle(SIZE/2f+xx,SIZE/2f+yy,annulus?1.28f:1.36f,p);
                }
            }
            return b;
        }
        void draw(Canvas c,float radius,boolean active,float progress){
            // Idle is an explicit stable rendering, independent of any touch or elapsed time.
            float q=Float.isFinite(progress)?clamp(progress):0f;
            float spread=active?smooth(0f,.28f,q):0f;
            float dissolve=active?smooth(.44f,.94f,q):0f;
            float inner=radius*(1.24f+.12f*spread);
            bounds.set(-inner,-inner,inner,inner);
            paint.setAlpha(active?Math.round(255f*(1f-spread)):255);
            if(paint.getAlpha()>1)c.drawBitmap(rest,null,bounds,paint);
            if(active){
                float outer=radius*(.89f+.32f*spread);
                bounds.set(-outer,-outer,outer,outer);
                paint.setAlpha(Math.round(255f*spread*(1f-dissolve)));
                if(paint.getAlpha()>1)c.drawBitmap(ring,null,bounds,paint);
            }
        }
    }
    private static void installHaloHook() {
        // Hook only the bitmap draw overload used by the verified Samsung guide.
        // ThreadLocal and exact canvas/paint identity restrict the effect to its onDraw.
        HashSet<Method> methods=new HashSet<>();
        for(String n:new String[]{"android.graphics.Canvas","android.graphics.BaseCanvas","android.graphics.BaseRecordingCanvas","android.graphics.RecordingCanvas","android.view.DisplayListCanvas"}){
            try{
                Class<?> k=Class.forName(n);
                Method m=k.getDeclaredMethod("drawBitmap",Bitmap.class,float.class,float.class,Paint.class);
                if(!java.lang.reflect.Modifier.isAbstract(m.getModifiers()))methods.add(m);
            }catch(Throwable ignored){}
        }
        XC_MethodHook hook=new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                Pass pass=drawing.get();
                if(haloFailed||pass==null||pass.haloDrawn||pass.canvas!=p.thisObject||p.args[3]!=pass.icon||pass.halo==null)return;
                pass.haloDrawn=true;
                try{
                    // onDraw has already updated the native per-scan progress by this point.
                    float q=pass.progressField.getFloat(pass.view);
                    pass.halo.draw((Canvas)p.thisObject,pass.diameter*.5f,pass.active,q);
                }catch(Throwable t){
                    haloFailed=true;XposedBridge.log(TAG+"dot texture disabled: "+t);
                }
            }
        };
        for(Method m:methods){
            try{XposedBridge.hookMethod(m,hook);haloAvailable=true;}catch(Throwable ignored){}
        }
        XposedBridge.log(TAG+"dot texture hook available="+haloAvailable);
    }
    // Angles begin at the right and advance clockwise in Android's local canvas.
    private static final SweepGradient RING=new SweepGradient(0,0,
        new int[]{0xff888ff1,0xff6fc2e5,0xff65b1ea,0xff5c94f9,0xff6083f0,0xff557cf0,0xff57a7fc,0xff56c2e6,0xff60cfef,0xff6abcdf,0xff8e88e8,0xff8074eb,0xff7b70e6,0xff888ff1},
        new float[]{0,22f/360,66f/360,88f/360,110f/360,132f/360,176f/360,198f/360,220f/360,242f/360,264f/360,308f/360,330f/360,1});
    private static final class Artwork {
        final Bitmap source, bright, idle;
        Artwork(Bitmap src) {
            source=src; int w=src.getWidth(),h=src.getHeight();
            if(w<1||h<1||w>1024||h>1024)throw new IllegalArgumentException("icon dimensions");
            bright=tint(src,true); idle=tint(src,false);
        }
        private static Bitmap tint(Bitmap source,boolean active) {
            int w=source.getWidth(),h=source.getHeight();
            Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            b.setDensity(source.getDensity());
            Canvas c=new Canvas(b); Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(source,0,0,p);
            if(active)p.setShader(new LinearGradient(0,h,w*.55f,0,0xff59aefa,0xff8175ee,Shader.TileMode.CLAMP));
            else p.setColor(0xff56565b);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            c.drawRect(0,0,w,h,p);
            return b;
        }
    }
    private static Field field(Class<?> c,String n,Class<?> expected) throws Exception {
        Field f=c.getDeclaredField(n);
        if(!expected.isAssignableFrom(f.getType()))throw new IllegalStateException(n);
        f.setAccessible(true);return f;
    }
    private static final class Fields {
        final Field state,auth,bitmap,icon,ready,progress,owner,display,talkback,register,percent,title,width,height,corner,checkPaint,checkPath,currentProgress;
        Fields(Class<?> frame,Class<?> activity)throws Exception{
            currentProgress=field(frame,"mCurrentProgress",float.class);
            state=field(frame,"mState",int.class);auth=field(frame,"mIsAuthMode",boolean.class);
            bitmap=field(frame,"mBmpFingerIcon",Bitmap.class);
            width=field(frame,"mFrameWidth",float.class);height=field(frame,"mFrameHeight",float.class);corner=field(frame,"mGuideFrameCornerSize",float.class);
            icon=field(frame,"mPntFingerIcon",Paint.class);
            ready=field(frame,"mPntGuideFrameReady",Paint.class);
            progress=field(frame,"mPntGuideFrameProgress",Paint.class);
            checkPaint=field(frame,"mPntCheckIcon",Paint.class);checkPath=field(frame,"mPathCheckIcon",Path.class);
            owner=field(activity,"mEnrollGuideFrame",frame);
            display=field(activity,"mIsDisplaySensor",boolean.class);
            talkback=field(activity,"mIsTalkbackEnabled",boolean.class);
            register=field(activity,"mRegisterScreen",View.class);
            percent=field(activity,"mTxtViewProgress",TextView.class);
            title=field(activity,"mGuideTitle",TextView.class);
        }
    }
    private static final class Pass {
        final Paint icon,ready,progress,check,oldIcon,oldReady,oldProgress,oldCheck;
        final Bitmap original;
        final float width,height,corner;
        Canvas canvas; DotHalo halo; float diameter; boolean active,haloDrawn;
        View view; Field progressField;
        Pass(Fields f,View v)throws Exception{
            icon=(Paint)f.icon.get(v);ready=(Paint)f.ready.get(v);progress=(Paint)f.progress.get(v);
            check=(Paint)f.checkPaint.get(v);
            oldIcon=new Paint(icon);oldReady=new Paint(ready);oldProgress=new Paint(progress);oldCheck=new Paint(check);
            original=(Bitmap)f.bitmap.get(v);
            width=f.width.getFloat(v);height=f.height.getFloat(v);corner=f.corner.getFloat(v);
        }
        void restore(Fields f,View v)throws Exception{
            icon.set(oldIcon);ready.set(oldReady);progress.set(oldProgress);check.set(oldCheck);
            f.width.setFloat(v,width);f.height.setFloat(v,height);f.corner.setFloat(v,corner);
            if(original!=null&&f.bitmap.get(v)!=original)f.bitmap.set(v,original);
        }
    }
    private static boolean failed;
    private static void fail(Throwable t){
        if(!failed){failed=true;XposedBridge.log(TAG+"stock fallback: "+t);}
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if(!PKG.equals(lp.packageName)||!PKG.equals(lp.processName))return;
        try{
            Class<?> frame=Class.forName(BASE+"FingerprintEnrollGuideFrame",false,lp.classLoader);
            Class<?> activity=Class.forName(BASE+"FingerprintEnrollActivity",false,lp.classLoader);
            Fields f=new Fields(frame,activity);
            installHaloHook();
            XposedBridge.hookMethod(frame.getDeclaredMethod("onDraw",Canvas.class),new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(failed)return;
                    View v=(View)p.thisObject; Pass pass=null;
                    try{
                        if(!activity.isInstance(v.getContext()))return;
                        Activity a=(Activity)v.getContext();
                        if(f.owner.get(a)!=v||!f.display.getBoolean(a)||f.talkback.getBoolean(a)||f.auth.getBoolean(v))return;
                        if(a.getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT)return;
                        if((a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES)return;
                        int state=f.state.getInt(v);
                        if(state<0||state>4)return;
                        pass=new Pass(f,v);p.setObjectExtra("theme-pass",pass);
                        // The real completion path is revealed by Samsung's mTimerCheckIcon.
                        // Recolor that original path, never trigger a success state or change timing.
                        if(state==4){
                            RectF b=new RectF();((Path)f.checkPath.get(v)).computeBounds(b,true);
                            if(b.width()>0&&b.height()>0){
                                int a0=pass.check.getAlpha();
                                pass.check.setShader(new LinearGradient(b.left,b.bottom,b.right,b.top,0xff59aefa,0xff8175ee,Shader.TileMode.CLAMP));
                                pass.check.setStrokeCap(Paint.Cap.ROUND);pass.check.setStrokeJoin(Paint.Join.ROUND);
                                pass.check.setAlpha(a0);
                            }
                            return;
                        }
                        // Native path math uses these fields only during drawing. Touch geometry is not changed.
                        float diameter=Math.min(pass.width,pass.height);
                        if(!Float.isFinite(diameter)||diameter<12)throw new IllegalStateException("invalid guide size");
                        f.width.setFloat(v,diameter);f.height.setFloat(v,diameter);f.corner.setFloat(v,diameter);
                        if(haloAvailable&&!haloFailed&&pass.original!=null){
                            try{
                                DotHalo halo=halos.get(v);
                                if(halo==null){halo=new DotHalo();halos.put(v,halo);}
                                pass.canvas=(Canvas)p.args[0];pass.halo=halo;pass.diameter=diameter;
                                pass.active=state==2;pass.progressField=f.currentProgress;pass.view=v;
                                drawing.set(pass);
                            }catch(Throwable t){
                                haloFailed=true; drawing.remove();XposedBridge.log(TAG+"dot texture left native: "+t);
                            }
                        }
                        int readyAlpha=pass.ready.getAlpha();
                        pass.ready.setColor(state==2?0xff111116:0xff09090b);pass.ready.setAlpha(state==2?readyAlpha:Math.min(readyAlpha,48));
                        int progressAlpha=pass.progress.getAlpha();
                        pass.progress.setShader(RING);pass.progress.setAlpha(progressAlpha);
                        pass.progress.setStrokeCap(Paint.Cap.ROUND);
                        // Subtle luminous edge without changing Samsung's path, stroke width or timing.
                        float blur=Math.max(1f,pass.progress.getStrokeWidth()*.15f);
                        pass.progress.setShadowLayer(blur,0,0,0x285f83ee);
                        if(pass.original!=null){
                            Artwork art=cache.get(v);
                            if(art==null||art.source!=pass.original){art=new Artwork(pass.original);cache.put(v,art);}
                            f.bitmap.set(v,state==2?art.bright:art.idle);
                            pass.icon.setColorFilter(null);
                        }
                        // Original onDraw executes once, including all native timers, progress,
                        // guide translation, scale, clipping, sensor mask and invalidation.
                    }catch(Throwable t){
                        drawing.remove();
                        if(pass!=null)try{pass.restore(f,v);}catch(Throwable ignored){}
                        p.setObjectExtra("theme-pass",null);fail(t);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    drawing.remove();
                    Pass pass=(Pass)p.getObjectExtra("theme-pass");if(pass==null)return;
                    try{pass.restore(f,(View)p.thisObject);}catch(Throwable t){fail(t);}
                }
            });
            XposedBridge.hookMethod(activity.getDeclaredMethod("startEnrollStep"),new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    if(failed||p.hasThrowable())return;
                    try{
                        Activity a=(Activity)p.thisObject;
                        if(!f.display.getBoolean(a)||f.talkback.getBoolean(a)||f.owner.get(a)==null)return;
                        if((a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES)return;
                        View screen=(View)f.register.get(a);if(screen!=null)screen.setBackgroundColor(Color.BLACK);
                        TextView percent=(TextView)f.percent.get(a);if(percent!=null)percent.setTextColor(Color.WHITE);
                        TextView title=(TextView)f.title.get(a);if(title!=null)title.setTextColor(Color.WHITE);
                    }catch(Throwable t){XposedBridge.log(TAG+"background left native: "+t);}
                }
            });
            XposedBridge.log(TAG+"installed: original Samsung drawing and animation retained");
        }catch(Throwable t){fail(t);}
    }
}
