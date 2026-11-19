package dev.fppulse;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class EnrollmentHook implements IXposedHookLoadPackage {
    private static final String PKG="com.samsung.android.biometrics.app.setting";
    private static final String BASE=PKG+".fingerprint.enroll.";
    private static final String FRAME=BASE+"FingerprintEnrollGuideFrame";
    private static final String ACT=BASE+"FingerprintEnrollActivity";
    private static final String TAG="FingerprintPulse: ";
    private static final WeakHashMap<View,Visual> visuals=new WeakHashMap<>();
    private static boolean failed;
    private static final class Visual {
        final PulseRenderer renderer=new PulseRenderer();
        float lastProgress=0, lastAlpha=0;
        long lastTime=0;
    }
    private static final class Fields {
        final Field state,auth,sensor,loc,width,height,progress,icon,ready,arc,owner,display,talkback,register,percent,title;
        Fields(Class<?> f,Class<?> a) throws Exception {
            state=find(f,"mState",int.class); auth=find(f,"mIsAuthMode",boolean.class);
            sensor=find(f,"mSensorPos",PointF.class); loc=find(f,"mCurrentLoc",PointF.class);
            width=find(f,"mFrameWidth",float.class); height=find(f,"mFrameHeight",float.class);
            progress=find(f,"mCurrentProgress",float.class);
            icon=find(f,"mPntFingerIcon",Paint.class); ready=find(f,"mPntGuideFrameReady",Paint.class); arc=find(f,"mPntGuideFrameProgress",Paint.class);
            owner=find(a,"mEnrollGuideFrame",f); display=find(a,"mIsDisplaySensor",boolean.class); talkback=find(a,"mIsTalkbackEnabled",boolean.class);
            register=find(a,"mRegisterScreen",View.class); percent=find(a,"mTxtViewProgress",TextView.class); title=find(a,"mGuideTitle",TextView.class);
        }
    }
    private static Field find(Class<?> c,String name,Class<?> type) throws Exception {
        Field f=c.getDeclaredField(name);
        if(!type.isAssignableFrom(f.getType())) throw new IllegalStateException(name+" type changed");
        f.setAccessible(true); return f;
    }
    private static final class DrawPass {
        final Paint i,r,a; final int ia,ra,aa; final Visual visual; final int state;
        DrawPass(Paint i,Paint r,Paint a,Visual v,int state) {
            this.i=i;this.r=r;this.a=a;this.visual=v;this.state=state;
            ia=i.getAlpha();ra=r.getAlpha();aa=a.getAlpha();
        }
        void hide(){i.setAlpha(0);r.setAlpha(0);a.setAlpha(0);}
        void restore(){i.setAlpha(ia);r.setAlpha(ra);a.setAlpha(aa);}
    }
    private static void fail(Throwable t){ if(!failed){failed=true;XposedBridge.log(TAG+"native fallback: "+t.getClass().getSimpleName());} }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if(!PKG.equals(lpp.packageName)||!PKG.equals(lpp.processName))return;
        try {
            Class<?> frame=Class.forName(FRAME,false,lpp.classLoader);
            Class<?> activity=Class.forName(ACT,false,lpp.classLoader);
            Fields f=new Fields(frame,activity);
            Method draw=frame.getDeclaredMethod("onDraw",Canvas.class);
            XposedBridge.hookMethod(draw,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if(failed)return;
                    DrawPass pass=null;
                    try {
                        View v=(View)p.thisObject;
                        if(!activity.isInstance(v.getContext()))return;
                        Activity a=(Activity)v.getContext();
                        if(f.owner.get(a)!=v || !f.display.getBoolean(a) || f.talkback.getBoolean(a) || f.auth.getBoolean(v))return;
                        if(a.getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT)return;
                        int state=f.state.getInt(v);
                        if(state<0||state>3)return;
                        PointF center=(PointF)f.sensor.get(v); PointF offset=(PointF)f.loc.get(v);
                        float size=Math.min(f.width.getFloat(v),f.height.getFloat(v));
                        if(center==null||offset==null||!Float.isFinite(size)||size<12||v.getWidth()<50||v.getHeight()<50)return;
                        float x=center.x+offset.x,y=center.y+offset.y;
                        if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>v.getWidth()||y<0||y>v.getHeight())return;
                        Visual visual=visuals.get(v);if(visual==null){visual=new Visual();visuals.put(v,visual);}
                        pass=new DrawPass((Paint)f.icon.get(v),(Paint)f.ready.get(v),(Paint)f.arc.get(v),visual,state);
                        p.setObjectExtra("fp-pass",pass);pass.hide();
                    }catch(Throwable t){if(pass!=null)pass.restore();p.setObjectExtra("fp-pass",null);fail(t);}
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    DrawPass pass=(DrawPass)p.getObjectExtra("fp-pass");if(pass==null)return;
                    pass.restore();
                    if(p.hasThrowable()||failed)return;
                    try {
                        View v=(View)p.thisObject;
                        PointF center=(PointF)f.sensor.get(v),offset=(PointF)f.loc.get(v);
                        float x=center.x+offset.x,y=center.y+offset.y;
                        float nativeSize=Math.min(f.width.getFloat(v),f.height.getFloat(v));
                        float radius=Math.min(v.getWidth()*.19f, Math.max(nativeSize*.50f,Math.min(nativeSize*.90f,v.getWidth()*.19f)));
                        radius=Math.min(radius,Math.min(Math.min(x,v.getWidth()-x),Math.min(y,v.getHeight()-y))*.94f);
                        if(radius<8||!Float.isFinite(radius))throw new IllegalStateException("guide geometry");
                        float q=PulseRenderer.clamp(f.progress.getFloat(v));
                        Visual s=pass.visual;long now=SystemClock.uptimeMillis();
                        float dt=s.lastTime==0?16:Math.min(100,Math.max(1,now-s.lastTime));s.lastTime=now;
                        boolean scanning=pass.state==1;
                        if(scanning){s.lastProgress=q;s.lastAlpha=Math.min(1,s.lastAlpha+dt/85f);}
                        else {s.lastAlpha=Math.max(0,s.lastAlpha-dt/155f);if(s.lastAlpha==0)s.lastProgress=0;}
                        s.renderer.draw((Canvas)p.args[0],x,y,radius,s.lastProgress,s.lastAlpha,scanning);
                    }catch(Throwable t){fail(t);((View)p.thisObject).postInvalidateOnAnimation();}
                }
            });
            XposedBridge.hookMethod(activity.getDeclaredMethod("startEnrollStep"),new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    if(failed||p.hasThrowable())return;
                    try{
                        Activity a=(Activity)p.thisObject;
                        if(!f.display.getBoolean(a)||f.talkback.getBoolean(a)||f.owner.get(a)==null)return;
                        if((a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES)return;
                        View screen=(View)f.register.get(a);if(screen!=null)screen.setBackgroundColor(Color.BLACK);
                        TextView percent=(TextView)f.percent.get(a);if(percent!=null)percent.setTextColor(Color.WHITE);
                        TextView title=(TextView)f.title.get(a);if(title!=null)title.setTextColor(Color.WHITE);
                    }catch(Throwable t){XposedBridge.log(TAG+"theme left native: "+t.getClass().getSimpleName());}
                }
            });
            XposedBridge.log(TAG+"enrollment visual hooks installed; no security hooks");
        }catch(Throwable t){fail(t);}
    }
}
