package dev.fppulse;
import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.*;
import android.view.*;

public final class PreviewActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);setContentView(new Demo());}
 private final class Demo extends View {
  private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final PulseRenderer renderer=new PulseRenderer();
  private long began=0;private int step=0;
  Demo(){super(PreviewActivity.this);setClickable(true);setContentDescription("Animation preview only. Tap to simulate a scan.");}
  @Override protected void onDraw(Canvas c){
   c.drawColor(Color.BLACK);float w=getWidth(),h=getHeight();float d=getResources().getDisplayMetrics().density;
   p.setColor(0xff999cae);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.DEFAULT);p.setTextSize(13*d);
   c.drawText("FINGERPRINT PULSE / BETA",w/2,h*.12f,p);
   c.drawText("Preview only · Tap to simulate a scan",w/2,h*.18f,p);
   float t=began==0?2:(SystemClock.uptimeMillis()-began)/580f;
   float q=t<.78f?PulseRenderer.clamp(t/.78f):1f;
   float alpha=t<.78f?Math.min(1,t*7):PulseRenderer.clamp((1.4f-t)/.62f);
   p.setColor(Color.WHITE);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(34*d);
   c.drawText(Math.min(step*10,100)+"%",w/2,h*.51f,p);
   renderer.draw(c,w/2,h*.72f,w*.19f,q,alpha,t<.78f);
   p.setTypeface(Typeface.DEFAULT);p.setTextSize(13*d);p.setColor(0xffa9a9b4);
   c.drawText("Real enrollment remains controlled by Samsung",w/2,h*.92f,p);
   if(t<1.4f)postInvalidateOnAnimation();
  }
  @Override public boolean performClick(){super.performClick();step=step==10?0:step+1;began=SystemClock.uptimeMillis();invalidate();return true;}
  @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_UP){performClick();return true;}return true;}
 }
}
