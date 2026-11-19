package dev.fppulse;

import android.graphics.*;

/** Draws only the enrollment artwork. All geometry is normalized to a radius of 100. */
final class PulseRenderer {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF(-100,-100,100,100);
    private final SweepGradient spectrum = new SweepGradient(0,0,
        new int[]{0xff83a1f4,0xff57d2ee,0xff6891ed,0xff4e9df0,0xff56c5eb,0xff8580f3,0xff83a1f4},
        new float[]{0,.15f,.34f,.50f,.65f,.84f,1});
    private final LinearGradient iconGradient = new LinearGradient(-20,40,10,-40,
        0xff57aff5,0xff8d75f5,Shader.TileMode.CLAMP);
    private final Path fingerprint = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final Path partial = new Path();
    PulseRenderer() {
        fingerprint.moveTo(-29,31);
        fingerprint.cubicTo(-40,10,-39,-12,-25,-25);
        fingerprint.cubicTo(-7,-43,19,-40,32,-17);
        fingerprint.cubicTo(45,7,37,21,24,21);
        fingerprint.cubicTo(10,21,0,9,0,0);
        fingerprint.moveTo(-8,37);
        fingerprint.cubicTo(-18,29,-26,18,-26,5);
        fingerprint.moveTo(-25,-8);
        fingerprint.cubicTo(-17,-24,1,-27,14,-17);
        fingerprint.cubicTo(21,-12,25,-2,24,4);
        fingerprint.cubicTo(22,13,11,11,9,3);
        fingerprint.cubicTo(6,-8,-8,-13,-12,-1);
        fingerprint.cubicTo(-16,13,-1,31,16,33);
    }
    static float clamp(float f) { return Math.max(0f,Math.min(1f,f)); }
    private void stroke(Canvas c, float start, float sweep, float width, int alpha) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        p.setShader(spectrum); p.setStrokeWidth(width); p.setAlpha(alpha);
        c.drawArc(ring,start,sweep,false,p);
    }
    void draw(Canvas c,float x,float y,float radius,float progress,float illumination,boolean scanning) {
        int save=c.save();
        try {
            c.translate(x,y); c.scale(radius/100f,radius/100f);
            float q=clamp(progress), a=clamp(illumination);
            p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xff14151e); p.setStrokeWidth(3.4f); c.drawArc(ring,0,360,false,p);
            float sweep=360f*q;
            if(sweep>0.1f && a>0.01f) {
                stroke(c,-90,sweep,12,Math.round(13*a));
                stroke(c,-90,sweep,8.5f,Math.round(32*a));
                stroke(c,-90,sweep,5.5f,Math.round(95*a));
                stroke(c,-90,sweep,3.9f,Math.round(255*a));
            }
            p.setShader(iconGradient); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5.1f); p.setStrokeJoin(Paint.Join.ROUND);
            p.setAlpha(Math.round(35+220*a)); c.drawPath(fingerprint,p);
            p.setShader(null); p.setAlpha(255);
        } finally { c.restoreToCount(save); }
    }
}
