package dev.fppulse;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.*;
import android.animation.ValueAnimator;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.HashSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.TextureView;
import android.widget.Button;
import android.widget.ImageView;
import android.content.res.ColorStateList;
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
        // Immutable shared textures. No regeneration on each enrollment/animation frame.
        private static final Bitmap REST=texture(false), RING_PIXELS=texture(true);
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final RectF bounds=new RectF();
        private long firstDraw=-1L, release=-1L;
        private boolean entered, wasActive, needsFrame;
        private float iconAlpha=1f;
        private static float clamp(float x){return Math.max(0f,Math.min(1f,x));}
        private static float smooth(float a,float b,float x){
            float t=clamp((x-a)/(b-a));return t*t*(3f-2f*t);
        }
        private static Bitmap texture(boolean annulus){
            Bitmap bm=Bitmap.createBitmap(SIZE,SIZE,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(bm); Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            float mid=SIZE*.5f;
            if(!annulus){
                // Very dark indigo illumination, distinct bright individual pixels above it.
                p.setShader(new RadialGradient(mid,mid,175f,
                    new int[]{0x64212552,0x4021274d,0x16141b39,0x00101425},
                    new float[]{0f,.49f,.79f,1f},Shader.TileMode.CLAMP));
                c.drawCircle(mid,mid,175f,p);p.setShader(null);
            }
            // Close-up at 4.4 s: roughly 30-35 visible staggered columns,
            // not a fine noise texture or a solid glowing disc.
            for(int row=-19;row<=19;row++){
                float y=row*8.66f;
                for(int col=-18;col<=18;col++){
                    float x=col*10f+((row&1)!=0?5f:0f);
                    float d=(float)Math.hypot(x,y)/160f;
                    float alpha=annulus?(float)Math.exp(-.5f*Math.pow((d-.90f)/.105f,2)):
                        (1f-smooth(.48f,1.06f,d))*(.98f-.22f*clamp(d));
                    if(alpha<.024f||d>1.12f)continue;
                    int strength=Math.round((annulus?185f:230f)*alpha);
                    p.setColor(Color.argb(strength,202,207,234));
                    c.drawCircle(mid+x,mid+y,annulus?2.15f:2.45f,p);
                }
            }
            return bm;
        }
        void draw(Canvas c,float radius,float iconSize,boolean active,float progress){
            long now=SystemClock.uptimeMillis();
            boolean motion=ValueAnimator.areAnimatorsEnabled();
            if(firstDraw<0)firstDraw=now;
            if(active||!motion)entered=true;
            float t=entered?1f:clamp((now-firstDraw)/210f);
            if(t>=1f)entered=true;
            // Icon appears first; the dot field blooms immediately behind it and settles.
            iconAlpha=smooth(0f,.32f,t);
            float reveal=smooth(.22f,.73f,t);
            float peak=1f+.10f*smooth(.42f,.68f,t)*(1f-smooth(.68f,1f,t));
            if(wasActive&&!active)release=now;
            if(active)release=-1L;
            wasActive=active;
            float returned=(!motion||release<0)?1f:smooth(0f,105f,now-release);
            needsFrame=(!entered)||(!active&&returned<1f);
            float q=Float.isFinite(progress)?clamp(progress):0f;
            float spread=active?smooth(.02f,.25f,q):0f;
            float dissolve=active?smooth(.32f,.88f,q):0f;
            // Physical proportions from the recording, measured against the native icon.
            float base=Math.min(radius*1.13f,iconSize*1.43f);
            if(!Float.isFinite(base)||base<6f)return;
            float inner=base*(.82f+.18f*reveal)*peak*(1f+.12f*spread);
            bounds.set(-inner,-inner,inner,inner);
            paint.setAlpha(Math.round(255f*reveal*(active?1f-spread:returned)));
            if(paint.getAlpha()>1)c.drawBitmap(REST,null,bounds,paint);
            if(active){
                float outer=radius*(.86f+.32f*spread);
                bounds.set(-outer,-outer,outer,outer);
                paint.setAlpha(Math.round(255f*spread*(1f-dissolve)));
                if(paint.getAlpha()>1)c.drawBitmap(RING_PIXELS,null,bounds,paint);
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
                    pass.halo.draw((Canvas)p.thisObject,pass.diameter*.5f,pass.iconSize,pass.active,q);
                    if(!pass.active){
                        pass.icon.setAlpha(Math.round(pass.icon.getAlpha()*pass.halo.iconAlpha));
                    }
                    if(pass.halo.needsFrame && pass.view.isShown() && pass.view.isAttachedToWindow()){
                        pass.view.postInvalidateOnAnimation();
                    }
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
            else p.setColor(0xffc4c6d7);
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
        final Field state,auth,bitmap,icon,ready,progress,owner,display,talkback,register,percent,title,width,height,corner,checkPaint,checkPath,currentProgress,fingerIconSize,firstGuide,texture,skipGuide,recreated,setup,knox;
        Fields(Class<?> frame,Class<?> activity)throws Exception{
            currentProgress=field(frame,"mCurrentProgress",float.class);
            fingerIconSize=field(frame,"mFingerIconSize",int.class);
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
            firstGuide=field(activity,"mFirstGuideScreen",View.class);
            texture=field(activity,"mTextureView",TextureView.class);
            skipGuide=field(activity,"mIsSkipGuideScreen",boolean.class);
            recreated=field(activity,"mIsReCreated",boolean.class);
            setup=field(activity,"mIsFromSetupWizard",boolean.class);
            knox=field(activity,"mImageViewKnoxBi",ImageView.class);
        }
    }
    private static final class Pass {
        final Paint icon,ready,progress,check,oldIcon,oldReady,oldProgress,oldCheck;
        final Bitmap original;
        final float width,height,corner;
        Canvas canvas; DotHalo halo; float diameter,iconSize; boolean active,haloDrawn;
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
    private static boolean eligible(Fields f,Activity a)throws Exception{
        return f.display.getBoolean(a)&&!f.talkback.getBoolean(a)&&!f.setup.getBoolean(a)
            &&a.getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT;
    }
    private static void whiteOnBlack(View v){
        if(v==null)return;
        if(v instanceof Button){
            // Retain the platform button drawable and listeners.
            Button b=(Button)v;b.setTextColor(Color.WHITE);
            b.setBackgroundTintList(ColorStateList.valueOf(0xff219cf4));
        }else if(v instanceof TextView){
            ((TextView)v).setTextColor(Color.WHITE);
        }else if(v instanceof ViewGroup){
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++)whiteOnBlack(g.getChildAt(i));
        }
    }
    private static void themePanel(Fields f,Activity a,View panel)throws Exception{
        if(panel==null)return;
        panel.setBackgroundColor(Color.BLACK);
        // Keep the reference black and readable even when the system is light.
        if((a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES){
            whiteOnBlack(panel);
            ImageView image=(ImageView)f.knox.get(a);
            if(image!=null)image.setColorFilter(Color.WHITE,PorterDuff.Mode.SRC_IN);
        }
    }
    private static void tintGuide(TextureView texture){
        if(texture==null)return;
        // Recolor Samsung's original 60 fps tutorial video without replacing playback.
        // Neutral gray/black and the peach finger stay close to their source colors;
        // green chroma in the sample fingerprint and ring becomes blue.
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(new float[]{
            .92f,.80f,-.72f,0,0,
            .025f,.75f,.225f,0,0,
            -.115f,1.15f,-.035f,0,0,
            0,0,0,1,0
        }));
        texture.setLayerPaint(p);
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
            // Samsung reads the skip flag in onCreate AFTER initScreen. For a fresh
            // normal add-fingerprint session, show its own registration-help page.
            XposedBridge.hookMethod(activity.getDeclaredMethod("initScreen"),new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    if(p.hasThrowable())return;
                    try{
                        Activity a=(Activity)p.thisObject;
                        if(!eligible(f,a)||f.recreated.getBoolean(a))return;
                        f.skipGuide.setBoolean(a,false);
                    }catch(Throwable t){XposedBridge.log(TAG+"native guide preference unchanged: "+t);}
                }
            });
            XposedBridge.hookMethod(activity.getDeclaredMethod("showGuideScreen",int.class),new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    if(p.hasThrowable()||((Integer)p.args[0])!=300)return;
                    try{
                        Activity a=(Activity)p.thisObject;
                        if(!eligible(f,a))return;
                        themePanel(f,a,(View)f.firstGuide.get(a));
                        tintGuide((TextureView)f.texture.get(a));
                    }catch(Throwable t){XposedBridge.log(TAG+"native tutorial theme unavailable: "+t);}
                }
            });
            XposedBridge.hookMethod(frame.getDeclaredMethod("onDraw",Canvas.class),new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(failed)return;
                    View v=(View)p.thisObject; Pass pass=null;
                    try{
                        if(!activity.isInstance(v.getContext()))return;
                        Activity a=(Activity)v.getContext();
                        if(f.owner.get(a)!=v||!f.display.getBoolean(a)||f.talkback.getBoolean(a)||f.auth.getBoolean(v))return;
                        if(a.getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT)return;

                        int state=f.state.getInt(v);
                        if(state<0||state>4)return;
                        pass=new Pass(f,v);p.setObjectExtra("theme-pass",pass);
                        // The real completion path is revealed by Samsung's mTimerCheckIcon.
                        // Recolor that original path, never trigger a success state or change timing.
                        if(state==4){
                            halos.remove(v);
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
                                pass.iconSize=f.fingerIconSize.getInt(v);
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
                        float blur=Math.max(1f,pass.progress.getStrokeWidth()*.12f);
                        pass.progress.setShadowLayer(blur,0,0,0x205f83ee);
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
                        if(!eligible(f,a)||f.owner.get(a)==null)return;

                        View screen=(View)f.register.get(a);themePanel(f,a,screen);
                        TextView percent=(TextView)f.percent.get(a);if(percent!=null)percent.setTextColor(Color.WHITE);
                        TextView title=(TextView)f.title.get(a);if(title!=null)title.setTextColor(Color.WHITE);
                    }catch(Throwable t){XposedBridge.log(TAG+"background left native: "+t);}
                }
            });
            // Reset a reused guide after it leaves the window; never reset on each touch.
            try {
                XposedBridge.hookMethod(View.class.getDeclaredMethod("onDetachedFromWindow"),new XC_MethodHook(){
                    @Override protected void afterHookedMethod(MethodHookParam p){
                        if(frame.isInstance(p.thisObject)){View v=(View)p.thisObject;halos.remove(v);cache.remove(v);}
                    }
                });
            }catch(Throwable ignored){ }
            XposedBridge.log(TAG+"installed: original Samsung drawing and animation retained");
        }catch(Throwable t){fail(t);}
    }
}
