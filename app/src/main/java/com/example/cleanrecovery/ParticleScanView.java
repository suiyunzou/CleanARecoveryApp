package com.example.cleanrecovery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.SweepGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import java.util.Random;

public final class ParticleScanView extends View {
    private static final int MS = 16;
    private static final int ORBIT1 = 45;
    private static final int ORBIT2 = 60;
    private static final int ORBIT3 = 40;
    private static final int FLOAT = 120;
    private static final int SPARK = 35;
    private static final float ORB_R = 100f;

    private final Paint pFill = new Paint(1);
    private final Paint pDot = new Paint(1);
    private final Paint pRing = new Paint(1);
    private final Paint pGlow = new Paint(1);
    private final Paint pPct = new Paint(1);
    private final Paint pSub = new Paint(1);
    private final Random rng = new Random();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Dot[] r1 = new Dot[ORBIT1];
    private final Dot[] r2 = new Dot[ORBIT2];
    private final Dot[] r3 = new Dot[ORBIT3];
    private final Fdot[] fd = new Fdot[FLOAT];
    private final Sdot[] sd = new Sdot[SPARK];

    private float dp, oR;
    private float cx, cy;
    private long t, last;
    private int rawP, rawF;
    private float dP;
    private int dF;
    private boolean run;
    private String phaseTxt = "";
    private float breathe;
    private boolean breatheUp = true;

    public ParticleScanView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = ctx.getResources().getDisplayMetrics().density;
        oR = ORB_R * dp;
        for (int i = 0; i < ORBIT1; i++) r1[i] = new Dot();
        for (int i = 0; i < ORBIT2; i++) r2[i] = new Dot();
        for (int i = 0; i < ORBIT3; i++) r3[i] = new Dot();
        for (int i = 0; i < FLOAT; i++) fd[i] = new Fdot();
        for (int i = 0; i < SPARK; i++) sd[i] = new Sdot();
    }

    public void start() { t = 0; last = System.currentTimeMillis(); run = true; h.post(loop); }
    public void stop() { run = false; h.removeCallbacks(loop); }
    public void setPercent(int v) { rawP = Math.min(100, Math.max(0, v)); }
    public void setFoundCount(int v) { rawF = Math.max(0, v); }
    public void setPhaseText(String s) { phaseTxt = s; }

    private final Runnable loop = new Runnable() {
        public void run() {
            if (!run) return;
            long n = System.currentTimeMillis();
            long dt = n - last; if (dt > 50) dt = 50; if (dt <= 0) dt = MS;
            last = n; t += dt;
            tick(dt);
            invalidate();
            h.postDelayed(this, MS);
        }
    };

    private void tick(long dt) {
        float ds = dt / 1000f;
        if (dP < rawP) { dP += Math.max(0.1f, (rawP - dP) / 8f); if (dP > rawP) dP = rawP; }
        if (dF < rawF) { dF += Math.max(1, (rawF - dF) / 5); if (dF > rawF) dF = rawF; }
        if (cx == 0 && getWidth() > 0) { cx = getWidth() / 2f; cy = getHeight() / 2f; }
        if (cx == 0) return;

        // Breathing effect
        if (breatheUp) {
            breathe += ds * 0.35f;
            if (breathe >= 1f) { breathe = 1f; breatheUp = false; }
        } else {
            breathe -= ds * 0.35f;
            if (breathe <= 0f) { breathe = 0f; breatheUp = true; }
        }
        float breathScale = 1f + breathe * 0.04f;
        float curR = oR * breathScale;

        for (Dot d : r1) uDot(d, ds, curR * 1.08f);
        for (Dot d : r2) uDot(d, ds, curR * 1.22f);
        for (Dot d : r3) uDot(d, ds, curR * 1.05f);
        for (Fdot d : fd) uFd(d, ds);
        for (Sdot d : sd) uSd(d, ds);
    }

    private void uDot(Dot d, float ds, float bR) {
        d.t += ds; if (d.t >= d.l) rDt(d);
        float r = d.t / d.l;
        d.ang += d.spd * ds * (0.12f + (1f - r) * 0.88f);
        float rad = bR + d.amp * (float) Math.sin(r * d.fq * Math.PI);
        d.x = cx + (float) Math.cos(d.ang) * rad;
        d.y = cy + (float) Math.sin(d.ang) * rad;
        d.a = (int)(255 * (1f - r*r));
    }
    private void uFd(Fdot d, float ds) {
        d.t += ds; if (d.t >= d.l) rFd(d);
        float r = d.t / d.l, e = r * r;
        float a = d.sa + d.ad * r, rad = d.sr + (d.er - d.sr) * e;
        d.x = cx + (float) Math.cos(a) * rad;
        d.y = cy + (float) Math.sin(a) * rad - e * 40f * dp;
        d.a = (int)(220 * (1f - r*r*r));
    }
    private void uSd(Sdot d, float ds) {
        d.t += ds; if (d.t >= d.l) rSd(d);
        float r = d.t / d.l, e = r * r;
        float a = d.sa + d.ad * r, rad = d.sr + (d.er - d.sr) * e;
        d.x = cx + (float) Math.cos(a) * rad;
        d.y = cy + (float) Math.sin(a) * rad;
        d.a = (int)(255 * (1f - r*r));
        d.sz = d.bs * (1f - r * 0.5f);
    }

    private void rDt(Dot d) { d.t = 0; d.l = 2.5f + rng.nextFloat() * 5f; d.ang = rng.nextFloat() * 6.283f; d.spd = 0.35f + rng.nextFloat() * 3f; d.amp = 2f*dp + rng.nextFloat()*12f*dp; d.fq = 1.3f + rng.nextFloat()*3f; d.a = 255; }
    private void rFd(Fdot d) { d.t = 0; d.l = 2f + rng.nextFloat() * 4f; d.sa = rng.nextFloat() * 6.283f; d.ad = (rng.nextFloat() - 0.5f) * 3f; d.sr = oR * (0.02f + rng.nextFloat() * 0.25f); d.er = oR * (1.8f + rng.nextFloat() * 4f); d.a = 220; }
    private void rSd(Sdot d) { d.t = 0; d.l = 0.8f + rng.nextFloat() * 1.8f; d.sa = rng.nextFloat() * 6.283f; d.ad = (rng.nextFloat() - 0.5f) * 2.2f; d.sr = oR * (0.4f + rng.nextFloat() * 0.6f); d.er = oR * (2f + rng.nextFloat() * 4f); d.bs = 1.5f + rng.nextFloat() * 3.5f; d.sz = d.bs; d.a = 255; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); cx = w/2f; cy = h/2f - 30f*dp; }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (cx == 0) return;

        // Full dark gradient background
        Paint bp = new Paint(1);
        RadialGradient bgG = new RadialGradient(cx, cy, Math.max(getWidth(), getHeight())*1.1f,
            new int[]{0xFF1A2540, 0xFF111827, 0xFF070B14}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        bp.setShader(bgG);
        c.drawRect(0, 0, getWidth(), getHeight(), bp);

        float breathScale = 1f + breathe * 0.04f;
        float curR = oR * breathScale;

        // Outer halo (breathes with orb)
        float hR = curR * 2.3f;
        float glowAlpha = 0.28f + breathe * 0.08f;
        RadialGradient hg = new RadialGradient(cx, cy, hR,
            new int[]{(int)(glowAlpha*255)<<24|0xA7F3D0 & 0xFFFFFF,
                      (int)(glowAlpha*0.5f*255)<<24|0x14B8A6 & 0xFFFFFF,
                      0x00000000},
            new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        pGlow.setShader(hg); pGlow.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, hR, pGlow);

        // Pulsing ripple ring
        float pt = (t % 2000f) / 2000f;
        pRing.setStyle(Paint.Style.STROKE); pRing.setStrokeWidth(1.5f*dp);
        float rippleA = 35f + breathe * 20f;
        pRing.setColor(Color.argb((int)(rippleA*(1f-pt)), 45, 212, 191)); pRing.setShader(null);
        c.drawCircle(cx, cy, curR * (1.1f + 0.25f * (float)Math.sin(pt*6.283f)), pRing);

        // Orb body
        RadialGradient og = new RadialGradient(cx - curR*0.1f, cy - curR*0.22f, curR,
            new int[]{0xFFF0FFFC, 0xFFBDF3E9, 0xFF60D8C7, 0xFF14B8A6, 0xFF0D9488, 0xFF0A7268},
            new float[]{0f, 0.2f, 0.46f, 0.7f, 0.89f, 1f}, Shader.TileMode.CLAMP);
        pFill.setShader(og); pFill.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, curR, pFill);

        // Glass highlight
        RadialGradient gh = new RadialGradient(cx - curR*0.22f, cy - curR*0.34f, curR*0.38f,
            new int[]{0xA0FFFFFF, 0x20FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        pGlow.setShader(gh); pGlow.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, curR, pGlow);

        // Bottom shadow
        RadialGradient gs = new RadialGradient(cx + curR*0.15f, cy + curR*0.15f, curR*0.55f,
            new int[]{0x00000000, 0x40000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        Paint sp = new Paint(1); sp.setShader(gs); sp.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, curR, sp);

        // Orbit dots
        drawDots(c, r1, curR);
        drawDots(c, r2, curR);
        drawDots(c, r3, curR);
        // Float dots
        for (Fdot d : fd) if (d.a > 8) {
            float h = (d.sa % 6.283f) / 6.283f;
            int cl = h < 0.3f ? Color.argb(d.a, 45,212,191) : h < 0.55f ? Color.argb(d.a, 20,184,166) : h < 0.78f ? Color.argb(d.a, 100,220,240) : Color.argb(d.a, 200,245,255);
            pDot.setColor(cl); pDot.setStyle(Paint.Style.FILL); pDot.setShader(null);
            c.drawCircle(d.x, d.y, dp*1.2f, pDot);
        }
        // Spark dots
        for (Sdot d : sd) if (d.a > 10) {
            float h = (d.sa % 6.283f) / 6.283f;
            int cl = h < 0.35f ? Color.argb(d.a, 220,255,245) : h < 0.65f ? Color.argb(d.a, 90,225,215) : Color.argb(d.a, 200,245,255);
            pDot.setColor(cl); pDot.setStyle(Paint.Style.FILL); pDot.setShader(null);
            c.drawCircle(d.x, d.y, d.sz * dp, pDot);
        }

        // Sweep ring
        pRing.setStyle(Paint.Style.STROKE); pRing.setStrokeWidth(4f*dp);
        float rot = t / 5500f;
        SweepGradient sg = new SweepGradient(cx, cy,
            new int[]{0xFF45D4BF, 0x5014B8A6, 0xFF45D4BF, 0x5014B8A6},
            new float[]{rot%1f, (rot+0.3f)%1f, (rot+0.55f)%1f, (rot+0.82f)%1f});
        pRing.setShader(sg);
        c.drawCircle(cx, cy, curR + 5f*dp, pRing);

        // Mid ring
        pRing.setStrokeWidth(2.5f*dp); pRing.setShader(null); pRing.setColor(0x40FFFFFF);
        c.drawCircle(cx, cy, curR + 2f*dp, pRing);
        // Inner ring
        pRing.setStrokeWidth(1.5f*dp); pRing.setColor(0x50FFFFFF);
        c.drawCircle(cx, cy, curR - 4f*dp, pRing);

        // === Center text inside orb ===
        // Measure percentage text width for precise centering
        String pctText = ((int)dP) + "%";
        float pctWidth = pPct.measureText(pctText);
        // Percentage — vertically centered in orb
        pPct.setColor(0xFFFFFFFF); pPct.setTextSize(50f*dp); pPct.setTextAlign(Paint.Align.CENTER);
        pPct.setFakeBoldText(true); pPct.setShadowLayer(12f*dp, 0, 2f*dp, 0x80000000);
        c.drawText(pctText, cx, cy - 4f*dp, pPct);

        // Phase label — below percentage inside orb
        pSub.setColor(0xFFB0E8DF); pSub.setTextSize(13f*dp); pSub.setTextAlign(Paint.Align.CENTER);
        pSub.setFakeBoldText(false); pSub.setShadowLayer(3f*dp, 0, 1f*dp, 0x60000000);
        c.drawText(phaseTxt, cx, cy + 24f*dp, pSub);

        // Found count — below orb entirely
        if (dF > 0) {
            float foundY = cy + curR + 32f*dp;
            pSub.setColor(0xFFCCE8E2); pSub.setTextSize(15f*dp); pSub.setTextAlign(Paint.Align.CENTER);
            pSub.setFakeBoldText(false); pSub.setShadowLayer(4f*dp, 0, 1f*dp, 0x80000000);
            c.drawText(getResources().getString(R.string.scan_stats_format, dF), cx, foundY, pSub);
        }
    }

    private void drawDots(Canvas c, Dot[] dots, float curR) {
        for (Dot d : dots) if (d.a > 8) {
            float dist = (float) Math.sqrt((d.x-cx)*(d.x-cx)+(d.y-cy)*(d.y-cy));
            float intensity = dist / (curR * 1.3f);
            int a = (int)(d.a * (0.4f + 0.6f * (1f - Math.abs(intensity - 0.5f) * 2f)));
            pDot.setColor(Color.argb(Math.min(255,a), 200, 250, 240));
            pDot.setStyle(Paint.Style.FILL); pDot.setShader(null);
            c.drawCircle(d.x, d.y, dp*2.5f, pDot);
        }
    }

    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); stop(); }

    static class Dot { float x, y, ang, spd, amp, fq, t, l; int a; }
    static class Fdot { float x, y, sa, ad, sr, er, t, l; int a; }
    static class Sdot { float x, y, sa, ad, sr, er, bs, sz, t, l; int a; }
}
