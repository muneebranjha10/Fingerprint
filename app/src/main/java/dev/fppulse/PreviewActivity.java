package dev.fppulse;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class PreviewActivity extends Activity {
 @Override public void onCreate(Bundle b){
  super.onCreate(b);
  LinearLayout layout=new LinearLayout(this);
  layout.setOrientation(LinearLayout.VERTICAL);layout.setGravity(Gravity.CENTER);
  layout.setBackgroundColor(Color.BLACK);int pad=(int)(24*getResources().getDisplayMetrics().density);
  layout.setPadding(pad,pad,pad,pad);
  TextView title=new TextView(this);title.setText("Fingerprint Pulse");title.setTextColor(0xff8171ef);title.setTextSize(28);title.setGravity(Gravity.CENTER);layout.addView(title);
  TextView desc=new TextView(this);desc.setText("0.8 Native Theme Beta\n\nSamsung's original animation is retained. Only the artwork and colors are themed.\n\nEnable this module for Samsung Biometrics only in LSPosed, reboot, turn on Dark mode and add a fingerprint in Settings.\n\nNo simulated preview: test the real enrollment screen.");desc.setTextColor(Color.WHITE);desc.setTextSize(16);desc.setGravity(Gravity.CENTER);desc.setPadding(0,pad,0,0);layout.addView(desc);
  setContentView(layout);
 }
}
