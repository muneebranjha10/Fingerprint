package dev.fppulse;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.*;
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
    // Angles begin at the right and advance clockwise in Android's local canvas.
    private static final SweepGradient RING=new SweepGradient(0,0,
        new int[]{0xff8973ee,0xff54cce9,0xff6594ef,0xff6590ed,0xff53bee5,0xff58c9ed,0xff8078ef,0xff8973ee},
        new float[]{0,.125f,.25f,.375f,.5f,.625f,.75f,1});
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
            if(active)p.setShader(new LinearGradient(0,h,w*.55f,0,0xff58b8f7,0xff8271f2,Shader.TileMode.CLAMP));
            else p.setColor(0xff303036);
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
        final Field state,auth,bitmap,icon,ready,progress,owner,display,talkback,register,percent,title,width,height,corner;
        Fields(Class<?> frame,Class<?> activity)throws Exception{
            state=field(frame,"mState",int.class);auth=field(frame,"mIsAuthMode",boolean.class);
            bitmap=field(frame,"mBmpFingerIcon",Bitmap.class);
            width=field(frame,"mFrameWidth",float.class);height=field(frame,"mFrameHeight",float.class);corner=field(frame,"mGuideFrameCornerSize",float.class);
            icon=field(frame,"mPntFingerIcon",Paint.class);
            ready=field(frame,"mPntGuideFrameReady",Paint.class);
            progress=field(frame,"mPntGuideFrameProgress",Paint.class);
            owner=field(activity,"mEnrollGuideFrame",frame);
            display=field(activity,"mIsDisplaySensor",boolean.class);
            talkback=field(activity,"mIsTalkbackEnabled",boolean.class);
            register=field(activity,"mRegisterScreen",View.class);
            percent=field(activity,"mTxtViewProgress",TextView.class);
            title=field(activity,"mGuideTitle",TextView.class);
        }
    }
    private static final class Pass {
        final Paint icon,ready,progress,oldIcon,oldReady,oldProgress;
        final Bitmap original;
        final float width,height,corner;
        Pass(Fields f,View v)throws Exception{
            icon=(Paint)f.icon.get(v);ready=(Paint)f.ready.get(v);progress=(Paint)f.progress.get(v);
            oldIcon=new Paint(icon);oldReady=new Paint(ready);oldProgress=new Paint(progress);
            original=(Bitmap)f.bitmap.get(v);
            width=f.width.getFloat(v);height=f.height.getFloat(v);corner=f.corner.getFloat(v);
        }
        void restore(Fields f,View v)throws Exception{
            icon.set(oldIcon);ready.set(oldReady);progress.set(oldProgress);
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
                        // State 4: native completion check, deliberately never touched.
                        if(state<0||state>=4)return;
                        pass=new Pass(f,v);p.setObjectExtra("theme-pass",pass);
                        // Native path math uses these fields only during drawing. Touch geometry is not changed.
                        float diameter=Math.min(pass.width,pass.height);
                        if(!Float.isFinite(diameter)||diameter<12)throw new IllegalStateException("invalid guide size");
                        f.width.setFloat(v,diameter);f.height.setFloat(v,diameter);f.corner.setFloat(v,diameter);
                        int readyAlpha=pass.ready.getAlpha();
                        pass.ready.setColor(0xff111116);pass.ready.setAlpha(readyAlpha);
                        int progressAlpha=pass.progress.getAlpha();
                        pass.progress.setShader(RING);pass.progress.setAlpha(progressAlpha);
                        // Subtle luminous edge without changing Samsung's path, stroke width or timing.
                        float blur=Math.max(1f,pass.progress.getStrokeWidth()*.42f);
                        pass.progress.setShadowLayer(blur,0,0,0x665f83ee);
                        if(pass.original!=null){
                            Artwork art=cache.get(v);
                            if(art==null||art.source!=pass.original){art=new Artwork(pass.original);cache.put(v,art);}
                            f.bitmap.set(v,state==2?art.bright:art.idle);
                            pass.icon.setColorFilter(null);
                        }
                        // Original onDraw executes once, including all native timers, progress,
                        // guide translation, scale, clipping, sensor mask and invalidation.
                    }catch(Throwable t){
                        if(pass!=null)try{pass.restore(f,v);}catch(Throwable ignored){}
                        p.setObjectExtra("theme-pass",null);fail(t);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
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
                        TextView percent=(TextView)f.percent.get(a);if(percent!=null){percent.setTextColor(Color.WHITE);percent.setTypeface(Typeface.create("sans-serif",Typeface.BOLD_ITALIC));}
                        TextView title=(TextView)f.title.get(a);if(title!=null)title.setTextColor(Color.WHITE);
                    }catch(Throwable t){XposedBridge.log(TAG+"background left native: "+t);}
                }
            });
            XposedBridge.log(TAG+"installed: original Samsung drawing and animation retained");
        }catch(Throwable t){fail(t);}
    }
}
