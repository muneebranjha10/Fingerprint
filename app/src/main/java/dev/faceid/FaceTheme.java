package dev.faceid;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Cosmetic only; never reads camera frames, enrollment tokens or authentication results. */
public final class FaceTheme implements IXposedHookLoadPackage {
    private static final String BIO="com.samsung.android.biometrics.app.setting";
    private static final String BASE=BIO+".face.";
    private static final int WHITE=0xfffef9fb,GREEN=0xffa6efb3,CYAN=0xff36b9e8;
    private static final ThreadLocal<Pass> drawing=new ThreadLocal<>();
    private static final HashSet<Method> installed=new HashSet<>();
    private static final String TAG="SamsungFaceID: ";
    private static boolean visualDisabled;
    private static final class Fields {
        final Field back,inner,base,progress,scan,trace,check,mask,state,start,error,errorDone,setup,talkback,scanning,progressValue,owner,scanStart;
        Fields(Class<?> v,Class<?> a)throws Exception{
            back=f(v,"mPntBackground");inner=f(v,"mPntInnerCircle");base=f(v,"mPntProgressBaseLine");
            progress=f(v,"mPntProgressLine");scan=f(v,"mPntScanLine");trace=f(v,"mPntTraceLine");
            check=f(v,"mPntCheckIcon");mask=f(v,"mPntGreenMask");state=f(v,"mState");
            start=f(v,"mStartingTimestamp");error=f(v,"mIsErrorStarted");errorDone=f(v,"mIsErrorFinished");
            setup=f(a,"mIsSetupWizard");talkback=f(a,"mIsTalkbackEnabled");
            scanning=f(v,"mIsScanning");progressValue=f(v,"mProgress");owner=f(a,"mEnrollAnimationView");scanStart=f(v,"mScanStartTimestamp");
        }
    }
    private static Field f(Class<?> k,String name)throws Exception{Field f=k.getDeclaredField(name);f.setAccessible(true);return f;}
    private static final class Pass {
        final Canvas canvas;final Paint background,inner,base,progress,scan,trace,check,mask;
        final Paint[] original, copies;
        final int state; final long elapsed; final boolean error, scanning; final float progressValue;
        boolean nested,referenceSweep; float ringX,ringY,ringR; final long scanElapsed;
        Pass(Fields f,View v,Canvas c)throws Exception{
            canvas=c;
            background=(Paint)f.back.get(v);inner=(Paint)f.inner.get(v);base=(Paint)f.base.get(v);
            progress=(Paint)f.progress.get(v);scan=(Paint)f.scan.get(v);trace=(Paint)f.trace.get(v);
            check=(Paint)f.check.get(v);mask=(Paint)f.mask.get(v);
            original=new Paint[]{background,inner,base,progress,scan,trace,check,mask};
            copies=new Paint[original.length];for(int i=0;i<original.length;i++)copies[i]=new Paint(original[i]);
            state=f.state.getInt(v);elapsed=Math.max(0,SystemClock.elapsedRealtime()-f.start.getLong(v));
            error=f.error.getBoolean(v)&&!f.errorDone.getBoolean(v);
            scanning=f.scanning.getBoolean(v);progressValue=f.progressValue.getFloat(v);scanElapsed=Math.max(0,SystemClock.elapsedRealtime()-f.scanStart.getLong(v));
        }
        void apply(){
            background.setColor(Color.BLACK);
            if(!error){ recolor(inner,WHITE);recolor(base,WHITE);recolor(progress,GREEN);recolor(check,GREEN); }
            // Keep native mask opacity and error feedback.
        }
        void restore(){for(int i=0;i<original.length;i++)original[i].set(copies[i]);}
    }
    private static void recolor(Paint p,int color){int a=p.getAlpha();p.setColor(color);p.setAlpha(a);}
    private static final class Art {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        final float[] lines=new float[64*4];
        final Path corners=new Path();
        final RectF rect=new RectF();
        final PathMeasure measure=new PathMeasure();
        final Path ribbon=new Path();
        final float[] pos=new float[2],tan=new float[2],edgeX=new float[49],edgeY=new float[49];
        void outline(Canvas c,float x,float y,float r,int col,int alpha){
            p.reset();p.setAntiAlias(true);p.setStyle(Paint.Style.STROKE);p.setColor(col);p.setAlpha(alpha);p.setStrokeWidth(Math.max(1,r*.009f));c.drawCircle(x,y,r,p);
        }
        void ticks(Canvas c,float x,float y,float rx,float ry,float start,float sweep,int color,int alpha,float length){
            if(rx<4||ry<4||!Float.isFinite(rx)||!Float.isFinite(ry))return;
            p.reset();p.setAntiAlias(true);p.setColor(color);p.setAlpha(alpha);p.setStrokeWidth(Math.max(1,Math.min(rx,ry)*.013f));p.setStrokeCap(Paint.Cap.ROUND);
            int n=0;
            for(int i=0;i<64;i++){
                float angle=-90+i*360f/64f;
                float delta=((angle-start)%360+360)%360;
                if(sweep<359.99f&&delta>sweep)continue;
                double a=Math.toRadians(angle);float cos=(float)Math.cos(a),sin=(float)Math.sin(a);
                // White and green marks share their inner endpoint; green grows outward.
                lines[n++]=x+rx*cos*.93f;lines[n++]=y+ry*sin*.93f;
                lines[n++]=x+rx*cos*(.93f+length);lines[n++]=y+ry*sin*(.93f+length);
            }
            if(n>0)c.drawLines(lines,0,n,p);
        }
        void brackets(Canvas c,float x,float y,float r,int alpha){
            p.reset();p.setAntiAlias(true);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(Math.max(1.5f,r*.018f));p.setColor(WHITE);p.setAlpha(alpha);
            float z=r*.83f,l=z*.30f,k=l*.68f;
            corners.reset();
            for(int i=0;i<4;i++){
                int sx=(i&1)==0?-1:1,sy=(i&2)==0?-1:1;
                float a=x+sx*z,b=y+sy*z;
                corners.moveTo(a-sx*l,b);corners.lineTo(a-sx*k,b);
                corners.quadTo(a,b,a,b-sy*k);corners.lineTo(a,b-sy*l);
            }
            c.drawPath(corners,p);
        }
        // A projected meridian: both ends remain pinned to the same circular rim.
        // The leading edge is bright cyan; its curved translucent trail falls away
        // behind it. No dependence on the shape of Samsung's supplied scan paths.
        private final Path rimClip=new Path(), curve=new Path();
        private void meridian(Canvas c,float x,float y,float r,float bend,boolean vertical,float intensity){
            if(intensity<=.005f)return;
            int save=c.save();
            try{
                c.translate(x,y);
                if(!vertical)c.rotate(90);
                float k=bend*r;
                float width=r*(.034f+.095f*Math.abs(bend));
                float sign=bend<0?-1f:1f;
                p.reset();p.setAntiAlias(true);
                for(int band=8;band>=1;band--){
                    float w=width*band/8f;
                    ribbon.reset();ribbon.moveTo(0,-r);
                    ribbon.cubicTo(k*1.33f,-r*.56f,k*1.33f,r*.56f,0,r);
                    ribbon.cubicTo((k-sign*w)*1.33f,r*.56f,(k-sign*w)*1.33f,-r*.56f,0,-r);
                    ribbon.close();
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(band>4?0xff176791:0xff35b9e8);
                    p.setAlpha(Math.round(intensity*(band>4?15:37)));
                    c.drawPath(ribbon,p);
                }
                curve.reset();curve.moveTo(0,-r);
                curve.cubicTo(k*1.33f,-r*.56f,k*1.33f,r*.56f,0,r);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(.75f,r*.0068f));p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(0xff67d7f3);p.setAlpha(Math.round(intensity*200));
                c.drawPath(curve,p);
            } finally {c.restoreToCount(save);}
        }
        void sweep(Canvas c,float x,float y,float radius,long elapsed){
            float r=radius*.923f;
            if(!Float.isFinite(r)||r<8)return;
            int save=c.save();
            try{
                rimClip.reset();rimClip.addCircle(x,y,r,Path.Direction.CW);c.clipPath(rimClip);
                float phase=(elapsed%3600L)/3600f;
                // Alternating orthogonal spherical sweeps with a short crossing interval.
                float v=(float)Math.sin(phase*Math.PI*2);
                float h=(float)Math.sin(phase*Math.PI*2+Math.PI*.63);
                float vGlow=(float)Math.pow(Math.abs(Math.sin(phase*Math.PI*2+.34)),2);
                float hGlow=(float)Math.pow(Math.abs(Math.sin(phase*Math.PI*2+Math.PI*.63+.34)),3);
                meridian(c,x,y,r,v*.84f,true,.12f+.88f*vGlow);
                meridian(c,x,y,r,h*.79f,false,.07f+.93f*hGlow);
            } finally {c.restoreToCount(save);}
        }
    }
    private static final ThreadLocal<Art> arts=ThreadLocal.withInitial(Art::new);
    private static void hookDraw(String name,Class<?>[] signature,int kind){
        for(String cls:new String[]{"android.graphics.Canvas","android.graphics.BaseCanvas","android.graphics.BaseRecordingCanvas","android.graphics.RecordingCanvas"}){
            try{
                Method m=Class.forName(cls).getDeclaredMethod(name,signature);
                if(Modifier.isAbstract(m.getModifiers())||!installed.add(m))continue;
                XposedBridge.hookMethod(m,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam param){
                        Pass pass=drawing.get();if(pass==null||pass.nested||visualDisabled||pass.error||param.thisObject!=pass.canvas)return;
                        Paint paint=(Paint)param.args[param.args.length-1];
                        if(paint==null)return;
                        pass.nested=true;
                        try{
                            Art art=arts.get();Canvas c=pass.canvas;
                            boolean done=false;
                            if(kind==0){
                                float x=(Float)param.args[0],y=(Float)param.args[1],r=(Float)param.args[2];
                                if(paint==pass.base){
                                    if(pass.state==1){
                                        if(pass.elapsed>=650){art.outline(c,x,y,r,WHITE,paint.getAlpha());}
                                        done=true;
                                    }else if(pass.state==2){
                                        // Native radius; the first thin white circle becomes short ticks.
                                        float q=Math.max(0,Math.min(1,(pass.elapsed-1400)/170f));
                                        if(!ValueAnimator.areAnimatorsEnabled())q=1;
                                        pass.ringX=x;pass.ringY=y;pass.ringR=r;
                                        art.ticks(c,x,y,r,r,-90,360,WHITE,Math.round(paint.getAlpha()*q),.041f);
                                        if(pass.scanning&&ValueAnimator.areAnimatorsEnabled()){
                                            art.sweep(c,x,y,r,pass.scanElapsed);pass.referenceSweep=true;
                                        }
                                        if(q<1)art.outline(c,x,y,r,WHITE,Math.round(paint.getAlpha()*(1-q)));
                                        done=true;
                                    }
                                }else if(paint==pass.inner){
                                    if(pass.state==1){
                                        if(pass.elapsed<650){art.brackets(c,x,y,r,255);}
                                        done=true;
                                    }else if(pass.state==2){done=true;}
                                }else if(paint==pass.mask&&pass.state==3){done=true;}
                            }else if(kind==1&&paint==pass.progress&&pass.state==2){
                                float l=(Float)param.args[0],t=(Float)param.args[1],r=(Float)param.args[2],b=(Float)param.args[3];
                                float start=(Float)param.args[4],sweep=(Float)param.args[5];
                                if(sweep>0){
                                    float rx=pass.ringR>0?pass.ringR:(r-l)*.5f;
                                    float ry=pass.ringR>0?pass.ringR:(b-t)*.5f;
                                    float x=pass.ringR>0?pass.ringX:(l+r)*.5f;
                                    float y=pass.ringR>0?pass.ringY:(t+b)*.5f;
                                    art.ticks(c,x,y,rx,ry,start,sweep,GREEN,paint.getAlpha(),.164f);
                                }
                                done=true;
                            }else if(kind==2&&(paint==pass.scan||paint==pass.trace)&&pass.state==2){
                                // Only suppress Samsung's decorative path if our sweep was actually drawn.
                                done=pass.referenceSweep;
                            }else if(kind==2&&pass.state==3&&paint==pass.progress){
                                // Real native completion path; retain position, use thin mint line.
                                Path path=(Path)param.args[0];art.p.reset();art.p.setAntiAlias(true);art.p.setStyle(Paint.Style.STROKE);art.p.setColor(GREEN);art.p.setStrokeWidth(Math.max(1,paint.getStrokeWidth()*.38f));c.drawPath(path,art.p);done=true;
                            }else if(kind==2&&pass.state==3&&paint==pass.check){
                                // Reference's captured completion is just an outline; native success text stays.
                                done=true;
                            }
                            if(done)param.setResult(null);
                        }catch(Throwable e){visualDisabled=true;XposedBridge.log(TAG+"stock visual fallback "+e);}
                        finally{pass.nested=false;}
                    }
                });
            }catch(Throwable ignored){}
        }
    }
    private static Activity activity(Context context){
        for(int i=0;i<8&&context!=null;i++){
            if(context instanceof Activity)return (Activity)context;
            if(!(context instanceof android.content.ContextWrapper))break;
            Context next=((android.content.ContextWrapper)context).getBaseContext();if(next==context)break;context=next;
        }return null;
    }
    private static boolean eligible(Fields f,Activity a)throws Exception{
        return a!=null&&!f.setup.getBoolean(a)&&!f.talkback.getBoolean(a)&&a.getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT;
    }
    private static void theme(View v){
        if(v instanceof Button){Button b=(Button)v;b.setTextColor(Color.WHITE);b.setBackgroundTintList(ColorStateList.valueOf(0xff239cf0));}
        else if(v instanceof TextView){((TextView)v).setTextColor(WHITE);}
        else if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)theme(g.getChildAt(i));}
    }
    private static final class Intro extends Drawable {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private final Art art=new Art();
        private final long began=SystemClock.uptimeMillis();private final RectF arc=new RectF();
        @Override public void draw(Canvas c){
            Rect b=getBounds();c.drawRect(b,newBlack());
            float x=b.exactCenterX(),y=b.exactCenterY(),r=Math.min(b.width(),b.height())*.38f;
            if(r<=1)return;
            float q=ValueAnimator.areAnimatorsEnabled()?Math.min(1,(SystemClock.uptimeMillis()-began)/500f):1f;
            float a=q*q*(3-2*q);
            art.ticks(c,x,y,r,r,-90,360,WHITE,Math.round(255*a),.055f);
            p.setColor(WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(Math.max(1,r*.018f));p.setAlpha(Math.round(255*a));
            float z=r*.38f;arc.set(x-z,y-z,x+z,y+z);c.drawOval(arc,p);
            c.drawLine(x-r*.14f,y-r*.11f,x-r*.14f,y-r*.045f,p);c.drawLine(x+r*.14f,y-r*.11f,x+r*.14f,y-r*.045f,p);
            arc.set(x-r*.16f,y+r*.015f,x+r*.16f,y+r*.20f);c.drawArc(arc,18,144,false,p);
            c.drawLine(x,y-r*.07f,x,y+r*.015f,p);
            if(q<1)invalidateSelf();
        }
        private Paint newBlack(){p.setShader(null);p.setColor(Color.BLACK);p.setStyle(Paint.Style.FILL);p.setAlpha(255);return p;}
        @Override public void setAlpha(int a){}
        @Override public void setColorFilter(ColorFilter f){}
        @Override public int getOpacity(){return PixelFormat.OPAQUE;}
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp)throws Throwable{
        if("com.android.settings".equals(lp.packageName)&&lp.packageName.equals(lp.processName)){
            try {
                Class<?> controller=Class.forName("com.samsung.android.settings.biometrics.face.FaceSettingsPreferenceController",false,lp.classLoader);
                Class<?> pref=Class.forName("androidx.preference.Preference",false,lp.classLoader);
                Method title=pref.getMethod("setTitle",CharSequence.class);
                XposedBridge.hookMethod(controller.getDeclaredMethod("updateState",pref),new XC_MethodHook(){
                    @Override protected void afterHookedMethod(MethodHookParam p){if(!p.hasThrowable())try{title.invoke(p.args[0],"Samsung Face ID");}catch(Throwable ignored){}}
                });
                Class<?> fragment=Class.forName("com.samsung.android.settings.biometrics.face.FaceSettings",false,lp.classLoader);
                XposedBridge.hookMethod(fragment.getDeclaredMethod("onResume"),new XC_MethodHook(){
                    @Override protected void afterHookedMethod(MethodHookParam p){try{
                        Object a=fragment.getMethod("getActivity").invoke(p.thisObject);
                        if(a instanceof Activity)((Activity)a).setTitle("Samsung Face ID");
                    }catch(Throwable ignored){}}
                });
            }catch(Throwable e){XposedBridge.log(TAG+"Settings label fallback "+e);}
            return;
        }
        if(!BIO.equals(lp.packageName)||!BIO.equals(lp.processName))return;
        try{
            Class<?> v=Class.forName(BASE+"FaceRegisterEffectView",false,lp.classLoader);
            Class<?> a=Class.forName(BASE+"FaceEnrollActivity",false,lp.classLoader);
            Fields fields=new Fields(v,a);
            hookDraw("drawCircle",new Class<?>[]{float.class,float.class,float.class,Paint.class},0);
            hookDraw("drawArc",new Class<?>[]{float.class,float.class,float.class,float.class,float.class,float.class,boolean.class,Paint.class},1);
            hookDraw("drawPath",new Class<?>[]{Path.class,Paint.class},2);
            XposedBridge.hookMethod(v.getDeclaredMethod("onDraw",Canvas.class),new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(visualDisabled)return;
                    Pass pass=null;
                    try{View view=(View)p.thisObject;Activity host=activity(view.getContext());if(!a.isInstance(host)||!eligible(fields,host)||fields.owner.get(host)!=view)return;
                        pass=new Pass(fields,view,(Canvas)p.args[0]);p.setObjectExtra("face.pass",pass);pass.apply();drawing.set(pass);
                    }catch(Throwable e){drawing.remove();if(pass!=null)pass.restore();p.setObjectExtra("face.pass",null);visualDisabled=true;XposedBridge.log(TAG+e);}
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    drawing.remove();Pass pass=(Pass)p.getObjectExtra("face.pass");if(pass!=null)pass.restore();
                }
            });
            for(String name:new String[]{"FaceGuideFragment","FaceEnrollFragment"}){
                try{
                    Class<?> fragment=Class.forName(BASE+name,false,lp.classLoader);
                    Field owner=f(fragment,"mActivity");
                    boolean intro=name.equals("FaceGuideFragment");
                    XposedBridge.hookMethod(fragment.getDeclaredMethod("onCreateView",LayoutInflater.class,ViewGroup.class),new XC_MethodHook(){
                        @Override protected void afterHookedMethod(MethodHookParam p){
                            if(p.hasThrowable()||!(p.getResult() instanceof View))return;
                            try{Activity host=(Activity)owner.get(p.thisObject);if(!eligible(fields,host))return;
                                View root=(View)p.getResult();root.setBackgroundColor(Color.BLACK);host.setTitle("Samsung Face ID");theme(root);
                                if(intro){
                                    // Verified tutorial-only illustration container, never the live camera.
                                    View box=root.findViewById(0x7f0a00f3);
                                    View clip=root.findViewById(0x7f0a00f4);
                                    if(box!=null&&clip instanceof TextureView&&box!=clip){box.setForeground(new Intro());}
                                    Button register=root.findViewById(0x7f0a0188);
                                    if(register!=null&&"en".equals(host.getResources().getConfiguration().getLocales().get(0).getLanguage()))register.setText("Get Started");
                                }
                            }catch(Throwable e){XposedBridge.log(TAG+"panel artwork fallback "+e);}
                        }
                    });
                }catch(Throwable e){XposedBridge.log(TAG+"panel hook unavailable "+e);}
            }
            XposedBridge.log(TAG+"native face enrollment visual hooks installed");
        }catch(Throwable e){visualDisabled=true;XposedBridge.log(TAG+"stock fallback "+e);}
    }
}
