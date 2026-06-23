package com.example.cleanrecovery.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

public final class ParticleScanView extends View {
    private static final int COLOR_BG_TOP = 0xFF07111F;
    private static final int COLOR_BG_BOTTOM = 0xFF0A1D2D;
    private static final int COLOR_PANEL = 0xFF102338;
    private static final int COLOR_TEAL = 0xFF18E6D2;
    private static final int COLOR_BLUE = 0xFF3B82F6;
    private static final int COLOR_CYAN = 0xFF38BDF8;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xFFB7C8D9;
    private static final int COLOR_TEXT_MUTED = 0xFF7B91A8;

    private static final int BLOCK_COUNT = 78;
    private static final float CYCLE_DURATION_S = 3.8f;
    private static final float FOUND_PULSE_DURATION_S = 0.72f;
    private static final float BLOCK_HIT_DURATION_S = 0.32f;
    private static final float SWEEP_WIDTH_DEG = 42f;
    private static final float PATH_TEXT_SIZE_SP = 12f;
    private static final float PERCENT_TEXT_SIZE_SP = 42f;
    private static final float LABEL_TEXT_SIZE_SP = 11f;

    private static final int[] ACCENTS = {
            COLOR_TEAL,
            COLOR_BLUE,
            COLOR_CYAN
    };

    private static final class FileBlock {
        float angleDeg;
        float radiusRatio;
        float sizeDp;
        float speedDegPerS;
        float phase;
        float alpha;
        int color;
        float hitRemainS;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final Path sectorPath = new Path();
    private final Random rng = new Random(42);
    private final FileBlock[] blocks = new FileBlock[BLOCK_COUNT];

    private float density;
    private float cx;
    private float cy;
    private float outerR;
    private float midR;
    private float innerR;
    private float coreR;
    private float globalTime;
    private float sweepDeg;
    private float activity;
    private float visualPercent;
    private float foundPulseS;

    private volatile boolean running;
    private long lastFrameMs;

    private int storedPercent;
    private int storedFound;
    private int lastFound;
    private String storedPhase = "";
    private String storedScanPath = "";

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) {
                return;
            }
            long now = frameTimeNanos / 1_000_000L;
            long dt = lastFrameMs == 0 ? 16 : now - lastFrameMs;
            lastFrameMs = now;
            if (dt > 64) {
                dt = 64;
            }
            if (dt < 0) {
                dt = 0;
            }
            tick(dt / 1000f);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public ParticleScanView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        initBlocks();
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        lastFrameMs = 0;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    public void setPercent(int v) {
        storedPercent = clamp(v, 0, 100);
    }

    public void setFoundCount(int v) {
        int next = Math.max(0, v);
        if (next > storedFound) {
            foundPulseS = FOUND_PULSE_DURATION_S;
        }
        storedFound = next;
    }

    public void setPhaseText(String s) {
        storedPhase = s == null ? "" : s;
    }

    public void setScanPath(String path) {
        storedScanPath = simplifyPath(path);
    }

    private void initBlocks() {
        for (int i = 0; i < BLOCK_COUNT; i++) {
            FileBlock block = new FileBlock();
            block.angleDeg = i * (360f / BLOCK_COUNT) + rng.nextFloat() * 8f;
            block.radiusRatio = 0.42f + rng.nextFloat() * 0.52f;
            block.sizeDp = 1.4f + rng.nextFloat() * 2.6f;
            block.speedDegPerS = (rng.nextBoolean() ? 1f : -1f) * (3f + rng.nextFloat() * 10f);
            block.phase = rng.nextFloat() * 6.28318f;
            block.alpha = 0.16f + rng.nextFloat() * 0.24f;
            block.color = ACCENTS[rng.nextInt(ACCENTS.length)];
            blocks[i] = block;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h * 0.43f;
        float maxByWidth = w * 0.42f;
        float maxByHeight = h * 0.31f;
        outerR = Math.max(96f * density, Math.min(maxByWidth, maxByHeight));
        midR = outerR * 0.72f;
        innerR = outerR * 0.42f;
        coreR = outerR * 0.28f;
    }

    private void tick(float dt) {
        if (dt <= 0f) {
            return;
        }
        globalTime += dt;
        float phaseProgress = (globalTime % CYCLE_DURATION_S) / CYCLE_DURATION_S;
        activity = 0.58f + 0.42f * smooth((float) Math.sin(phaseProgress * 6.28318f) * 0.5f + 0.5f);
        visualPercent += (storedPercent - visualPercent) * Math.min(1f, dt * 5.6f);
        sweepDeg = (sweepDeg + dt * (92f + activity * 52f)) % 360f;

        if (foundPulseS > 0f) {
            foundPulseS = Math.max(0f, foundPulseS - dt);
        }
        if (storedFound != lastFound) {
            lastFound = storedFound;
        }

        int accent = phaseAccent();
        for (FileBlock block : blocks) {
            block.angleDeg = normalizeDeg(block.angleDeg + block.speedDegPerS * dt * (0.7f + activity * 0.8f));
            float diff = angularDistance(block.angleDeg, sweepDeg);
            if (diff < SWEEP_WIDTH_DEG * 0.42f) {
                block.hitRemainS = BLOCK_HIT_DURATION_S;
                if (diff < 3.5f && rng.nextFloat() < 0.12f) {
                    block.color = accent;
                }
            }
            if (block.hitRemainS > 0f) {
                block.hitRemainS = Math.max(0f, block.hitRemainS - dt);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0 || outerR <= 0f) {
            return;
        }
        drawBackground(canvas);
        drawInstrumentShell(canvas);
        drawScanSector(canvas);
        drawDataBlocks(canvas);
        drawProgressArc(canvas);
        drawCore(canvas);
        drawTelemetry(canvas);
        drawScanPath(canvas);
    }

    private void drawBackground(Canvas canvas) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
                0f, 0f, 0f, getHeight(),
                COLOR_BG_TOP, COLOR_BG_BOTTOM,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
        paint.setShader(null);

        paint.setShader(new RadialGradient(
                cx, cy, outerR * 1.55f,
                new int[]{
                        applyAlpha(phaseAccent(), 0.20f),
                        applyAlpha(COLOR_BLUE, 0.07f),
                        applyAlpha(COLOR_BG_BOTTOM, 0f)
                },
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, outerR * 1.55f, paint);
        paint.setShader(null);
    }

    private void drawInstrumentShell(Canvas canvas) {
        drawFilledCircle(canvas, outerR * 1.04f, COLOR_PANEL, 0.26f);
        drawRing(canvas, outerR, 1.4f, COLOR_CYAN, 0.35f);
        drawRing(canvas, midR, 1.0f, COLOR_TEAL, 0.22f);
        drawRing(canvas, innerR, 1.0f, COLOR_BLUE, 0.30f);

        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 96; i++) {
            float deg = i * 3.75f;
            float rad = toRad(deg);
            boolean major = i % 8 == 0;
            float startR = outerR - (major ? 13f : 7f) * density;
            float endR = outerR - 1f * density;
            float alpha = major ? 0.45f : 0.18f;
            paint.setStrokeWidth((major ? 1.5f : 0.8f) * density);
            paint.setColor(applyAlpha(COLOR_TEXT_SECONDARY, alpha));
            canvas.drawLine(
                    cx + (float) Math.cos(rad) * startR,
                    cy + (float) Math.sin(rad) * startR,
                    cx + (float) Math.cos(rad) * endR,
                    cy + (float) Math.sin(rad) * endR,
                    paint);
        }

        float pulse = foundPulseS <= 0f ? 0f : foundPulseS / FOUND_PULSE_DURATION_S;
        if (pulse > 0f) {
            drawRing(canvas, outerR + (1f - pulse) * 14f * density, 1.6f, COLOR_TEAL, pulse * 0.42f);
        }
    }

    private void drawScanSector(Canvas canvas) {
        float start = sweepDeg - SWEEP_WIDTH_DEG * 0.5f;
        int accent = phaseAccent();

        sectorPath.reset();
        sectorPath.moveTo(cx, cy);
        arcRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        sectorPath.arcTo(arcRect, start, SWEEP_WIDTH_DEG);
        sectorPath.close();

        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(
                cx, cy, outerR,
                new int[]{
                        applyAlpha(accent, 0.12f * activity),
                        applyAlpha(accent, 0.055f * activity),
                        applyAlpha(accent, 0f)
                },
                new float[]{0f, 0.58f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawPath(sectorPath, paint);
        paint.setShader(null);

        float tipRad = toRad(sweepDeg);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(1.6f * density);
        paint.setColor(applyAlpha(COLOR_TEXT, 0.54f));
        canvas.drawLine(cx, cy,
                cx + (float) Math.cos(tipRad) * outerR,
                cy + (float) Math.sin(tipRad) * outerR,
                paint);

        paint.setStrokeWidth(7f * density);
        paint.setColor(applyAlpha(accent, 0.07f));
        canvas.drawLine(cx, cy,
                cx + (float) Math.cos(tipRad) * outerR,
                cy + (float) Math.sin(tipRad) * outerR,
                paint);
    }

    private void drawDataBlocks(Canvas canvas) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        for (FileBlock block : blocks) {
            float rad = toRad(block.angleDeg);
            float drift = (float) Math.sin(globalTime * 1.4f + block.phase) * 3.5f * density;
            float r = innerR + (outerR - innerR) * block.radiusRatio + drift;
            float x = cx + (float) Math.cos(rad) * r;
            float y = cy + (float) Math.sin(rad) * r;
            float hit = block.hitRemainS / BLOCK_HIT_DURATION_S;
            float size = (block.sizeDp + hit * 1.6f) * density;
            float alpha = block.alpha * (0.68f + activity * 0.22f) + hit * 0.24f;

            paint.setColor(applyAlpha(block.color, Math.min(1f, alpha)));
            canvas.drawRoundRect(x - size, y - size * 0.62f, x + size, y + size * 0.62f,
                    2f * density, 2f * density, paint);

            if (hit > 0f) {
                paint.setColor(applyAlpha(block.color, hit * 0.10f));
                canvas.drawCircle(x, y, size * 2.0f, paint);
            }
        }
    }

    private void drawProgressArc(Canvas canvas) {
        float arcInset = outerR * 0.12f;
        arcRect.set(cx - outerR + arcInset, cy - outerR + arcInset,
                cx + outerR - arcInset, cy + outerR - arcInset);

        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(7f * density);
        paint.setColor(applyAlpha(COLOR_TEXT, 0.065f));
        canvas.drawArc(arcRect, -90f, 360f, false, paint);

        paint.setStrokeWidth(7f * density);
        paint.setColor(applyAlpha(phaseAccent(), 0.78f));
        canvas.drawArc(arcRect, -90f, visualPercent * 3.6f, false, paint);

        paint.setStrokeWidth(14f * density);
        paint.setColor(applyAlpha(phaseAccent(), 0.055f));
        canvas.drawArc(arcRect, -90f, visualPercent * 3.6f, false, paint);
    }

    private void drawCore(Canvas canvas) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(
                cx, cy, coreR,
                new int[]{
                        applyAlpha(COLOR_TEXT, 0.90f),
                        applyAlpha(phaseAccent(), 0.38f + activity * 0.16f),
                        applyAlpha(COLOR_BG_BOTTOM, 0f)
                },
                new float[]{0f, 0.34f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, coreR, paint);
        paint.setShader(null);

        drawRing(canvas, coreR * 1.08f, 1.2f, COLOR_TEXT, 0.28f);
        drawRing(canvas, coreR * 0.68f, 1.0f, phaseAccent(), 0.46f);
    }

    private void drawTelemetry(Canvas canvas) {
        String number = String.valueOf(Math.round(visualPercent));
        String suffix = "%";
        textPaint.reset();
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(applyAlpha(COLOR_TEXT, 0.96f));
        textPaint.setTextSize(PERCENT_TEXT_SIZE_SP * density);

        float numWidth = textPaint.measureText(number);
        float numberSize = textPaint.getTextSize();
        textPaint.setTextSize(16f * density);
        float suffixWidth = textPaint.measureText(suffix);
        float startX = cx - (numWidth + suffixWidth) / 2f;
        float baseline = cy + numberSize * 0.30f;

        textPaint.setTextSize(numberSize);
        canvas.drawText(number, startX, baseline, textPaint);
        textPaint.setTextSize(16f * density);
        canvas.drawText(suffix, startX + numWidth + 1.5f * density, baseline, textPaint);

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(LABEL_TEXT_SIZE_SP * density);
        textPaint.setColor(applyAlpha(COLOR_TEXT_SECONDARY, 0.84f));
        String phaseLabel = storedPhase == null || storedPhase.isEmpty() ? "SCANNING" : trimToFit(storedPhase, outerR * 1.16f, textPaint);
        canvas.drawText(phaseLabel, cx, cy + coreR + 24f * density, textPaint);

        textPaint.setTextSize(10f * density);
        textPaint.setColor(applyAlpha(COLOR_TEXT_MUTED, 0.76f));
        canvas.drawText("FOUND " + storedFound, cx, cy + coreR + 42f * density, textPaint);
    }

    private void drawScanPath(Canvas canvas) {
        if (storedScanPath == null || storedScanPath.isEmpty()) {
            return;
        }
        textPaint.reset();
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(PATH_TEXT_SIZE_SP * density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(applyAlpha(COLOR_TEXT_SECONDARY, 0.86f));
        String display = ellipsizeMiddle(storedScanPath, getWidth() - 48f * density, textPaint);
        float y = getHeight() - 12f * density - textPaint.descent();
        canvas.drawText(display, cx, y, textPaint);
    }

    private void drawRing(Canvas canvas, float radius, float strokeDp, int color, float alpha) {
        if (radius <= 0f || alpha <= 0f) {
            return;
        }
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeDp * density);
        paint.setColor(applyAlpha(color, alpha));
        canvas.drawCircle(cx, cy, radius, paint);
    }

    private void drawFilledCircle(Canvas canvas, float radius, int color, float alpha) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(applyAlpha(color, alpha));
        canvas.drawCircle(cx, cy, radius, paint);
    }

    private int phaseAccent() {
        if (storedPhase == null || storedPhase.isEmpty()) {
            return COLOR_TEAL;
        }
        int hash = Math.abs(storedPhase.hashCode());
        return ACCENTS[hash % ACCENTS.length];
    }

    private static String simplifyPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        String[] parts = normalized.split("/");
        int n = parts.length;
        if (n <= 2) {
            return normalized;
        }
        return ".../" + parts[n - 2] + "/" + parts[n - 1];
    }

    private static String trimToFit(String text, float maxWidth, Paint paint) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return ellipsizeMiddle(text, maxWidth, paint);
    }

    private static String ellipsizeMiddle(String text, float maxWidth, Paint paint) {
        if (text == null || text.isEmpty() || paint.measureText(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "...";
        int len = text.length();
        int low = 1;
        int high = len - 1;
        int best = 1;
        while (low <= high) {
            int mid = (low + high) / 2;
            String candidate = text.substring(0, mid) + ellipsis + text.substring(len - mid);
            if (paint.measureText(candidate) <= maxWidth) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, best) + ellipsis + text.substring(len - best);
    }

    private static float toRad(float deg) {
        return (float) (deg * Math.PI / 180.0);
    }

    private static float normalizeDeg(float deg) {
        float v = deg % 360f;
        return v < 0f ? v + 360f : v;
    }

    private static float angularDistance(float a, float b) {
        float diff = Math.abs(normalizeDeg(a) - normalizeDeg(b));
        return diff > 180f ? 360f - diff : diff;
    }

    private static float smooth(float t) {
        t = clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int applyAlpha(int color, float alpha) {
        int a = (int) (clamp(alpha, 0f, 1f) * 255f + 0.5f);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }
}
