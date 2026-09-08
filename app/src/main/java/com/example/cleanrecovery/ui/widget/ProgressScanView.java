package com.example.cleanrecovery.ui.widget;

import com.example.cleanrecovery.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * 简白风格扫描进度环（替代旧 ParticleScanView 雷达）。
 * 对外 API 与 ParticleScanView 保持一致：start/stop/setPercent/setFoundCount/setPhaseText/setScanPath，
 * MainActivity 的扫描回调逻辑无需改动。
 */
public final class ProgressScanView extends View {
    private static final long FRAME_MS = 16L;

    private final Paint ringTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringProgress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPercent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textFound = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPhase = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPath = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();

    private int percent;
    private int foundCount;
    private String phaseText = "";
    private String scanPath = "";
    private boolean running;
    private float sweepOffset;
    private long lastFrameAtMs;

    private final Runnable framer = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            final long now = System.nanoTime() / 1_000_000L;
            if (lastFrameAtMs != 0L) {
                sweepOffset = (sweepOffset + (now - lastFrameAtMs) * 0.018f) % 360f;
            }
            lastFrameAtMs = now;
            postInvalidateOnAnimation();
            postDelayed(this, FRAME_MS);
        }
    };

    public ProgressScanView(Context ctx) {
        this(ctx, null);
    }

    public ProgressScanView(Context ctx, android.util.AttributeSet attrs) {
        super(ctx, attrs);
        ringTrack.setStyle(Paint.Style.STROKE);
        ringTrack.setStrokeWidth(dp(6));
        ringTrack.setColor(color(R.color.scan_ring_track));
        ringTrack.setStrokeCap(Paint.Cap.ROUND);

        ringProgress.setStyle(Paint.Style.STROKE);
        ringProgress.setStrokeWidth(dp(6));
        ringProgress.setColor(color(R.color.brand_primary));
        ringProgress.setStrokeCap(Paint.Cap.ROUND);

        textPercent.setColor(color(R.color.text_primary));
        textPercent.setTextAlign(Paint.Align.CENTER);
        textPercent.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        textFound.setColor(color(R.color.text_hint));
        textFound.setTextAlign(Paint.Align.CENTER);

        textPhase.setColor(color(R.color.text_secondary));
        textPhase.setTextAlign(Paint.Align.CENTER);

        textPath.setColor(color(R.color.text_hint));
        textPath.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        textPercent.setTextSize(sp(34));
        textFound.setTextSize(sp(13));
        textPhase.setTextSize(sp(12.5f));
        textPath.setTextSize(sp(11.5f));
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        lastFrameAtMs = 0L;
        postDelayed(framer, FRAME_MS);
    }

    public void stop() {
        running = false;
        removeCallbacks(framer);
    }

    public void setPercent(int v) {
        percent = Math.max(0, Math.min(100, v));
        postInvalidateOnAnimation();
    }

    public void setFoundCount(int v) {
        foundCount = v;
        postInvalidateOnAnimation();
    }

    public void setPhaseText(String s) {
        phaseText = s == null ? "" : s;
        postInvalidateOnAnimation();
    }

    public void setScanPath(String path) {
        scanPath = path == null ? "" : path;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        final int ringRadius = (int) dp(74);
        final float cx = w / 2f;
        final float ringCy = h / 2f - dp(52);
        ringRect.set(cx - ringRadius, ringCy - ringRadius, cx + ringRadius, ringCy + ringRadius);

        canvas.drawArc(ringRect, 0, 360, false, ringTrack);
        if (percent > 0) {
            final float sweep = Math.max(6f, 360f * percent / 100f);
            canvas.drawArc(ringRect, -90 + sweepOffset * 0f, sweep, false, ringProgress);
        }

        final String percentLabel = percent + "%";
        float textY = ringCy - (textPercent.descent() + textPercent.ascent()) / 2f - dp(8);
        canvas.drawText(percentLabel, cx, textY, textPercent);
        textY = ringCy + dp(26);
        canvas.drawText(foundLabel(), cx, textY, textFound);

        final float phaseY = ringCy + ringRadius + dp(44);
        if (!phaseText.isEmpty()) {
            canvas.drawText(phaseText, cx, phaseY, textPhase);
        }
        if (!scanPath.isEmpty()) {
            final float maxW = w - dp(48);
            canvas.drawText(ellipsize(scanPath, textPath, maxW), cx, phaseY + dp(24), textPath);
        }
    }

    private String foundLabel() {
        if (foundCount > 0) {
            return getContext().getString(R.string.scan_found_count_label, foundCount);
        }
        return getContext().getString(R.string.scan_finding_label);
    }

    private static String ellipsize(String text, Paint paint, float maxW) {
        if (paint.measureText(text) <= maxW) {
            return text;
        }
        final String suffix = "…";
        int end = text.length();
        while (end > 0 && paint.measureText(text.substring(0, end) + suffix) > maxW) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }

    private int color(int resId) {
        return getContext().getColor(resId);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
