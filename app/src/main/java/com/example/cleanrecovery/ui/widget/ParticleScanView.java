package com.example.cleanrecovery.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.util.Random;

/**
 * 暗色主题扫描粒子动画（纯 Canvas 实现）。
 *
 * 设计原则：
 *  - 同一批粒子、同一套圆环、同一个圆心，只让参数连续变化（非多模板拼接）。
 *  - 粒子初始化后保持稳定，每帧只更新位置与状态，不重新随机生成。
 *  - 5 阶段时间轴在一个循环内连续演化，平滑过渡。
 *  - Choreographer 驱动，按 deltaTime 更新，设备无关。
 *
 * 绘制顺序（严格）：
 *  1. 深色背景
 *  2. 最外层低透明圆环
 *  3. 中间主发光环
 *  4. 内层圆环
 *  5. 普通粒子
 *  6. 高亮粒子和尾迹
 *  7. 扫描线
 *  8. 中心光核
 *  9. 最上层轻微泛光
 *
 * 兼容：保留 start/stop/setPercent/setFoundCount/setPhaseText 公开方法签名，
 *       但按"禁止文字"要求，内部不再绘制任何文字；外部传入值仅存储不影响动画。
 */
public final class ParticleScanView extends View {

    // ===================== 配置区（所有可调参数集中于此） =====================

    /** 背景：深蓝黑色，纯净无渐变色块/纹理/网格。 */
    static final int COLOR_BG = 0xFF081426;

    /** 三层圆环颜色（青色系，由内到外）。 */
    static final int COLOR_INNER_RING = 0xFF12D6D0; // 内环：细线，低亮度
    static final int COLOR_MID_RING   = 0xFF1DB7D8; // 中环：主发光环
    static final int COLOR_OUTER_RING = 0xFF238CFF; // 外环：粒子运行边界

    /** 粒子颜色限定三色（青/蓝青/蓝）。 */
    static final int[] PARTICLE_COLORS = {
            0xFF12D6D0, // 青色
            0xFF1DB7D8, // 蓝青色
            0xFF238CFF  // 蓝色
    };

    /** 粒子数量（80–140）。 */
    static final int PARTICLE_COUNT = 110;

    /** 粒子半径范围（dp，1.5–5）。 */
    static final float PARTICLE_MIN_RADIUS_DP = 1.5f;
    static final float PARTICLE_MAX_RADIUS_DP = 5.0f;

    /** 高亮粒子占比（少量重点粒子）。 */
    static final float HIGHLIGHT_PARTICLE_RATIO = 0.15f;

    /** 粒子角速度范围（rad/s，0.15–0.55）。 */
    static final float ANGULAR_SPEED_MIN = 0.15f;
    static final float ANGULAR_SPEED_MAX = 0.55f;

    /** 粒子径向扰动幅度（dp，不超过 ±4）。 */
    static final float JITTER_AMP_DP = 4.0f;

    /** 粒子透明度范围。 */
    static final float PARTICLE_ALPHA_MIN = 0.25f;
    static final float PARTICLE_ALPHA_MAX = 1.0f;

    /** 扫描线扫中后：粒子半径放大倍数（1.2–1.5）。 */
    static final float HIGHLIGHT_SCALE = 1.35f;
    /** 扫描线扫中后：高亮持续时长（ms，150–300）。 */
    static final int HIGHLIGHT_DURATION_MS = 220;

    /** 圆环半径占比（相对 view 短边的一半）。 */
    static final float INNER_RING_RATIO = 0.42f;
    static final float MID_RING_RATIO   = 0.60f;
    static final float OUTER_RING_RATIO = 0.80f;

    /** 圆环描边宽度（dp）。 */
    static final float INNER_RING_WIDTH_DP = 1.0f;
    static final float MID_RING_WIDTH_DP   = 2.5f;
    static final float OUTER_RING_WIDTH_DP = 1.0f;

    /** 总循环时长（秒，3.0–4.0）。 */
    static final float CYCLE_DURATION_S = 3.6f;

    /** 5 阶段边界（占循环比例 0–1）。 */
    static final float PHASE1_END = 0.20f; // 低活跃
    static final float PHASE2_END = 0.40f; // 激活
    static final float PHASE3_END = 0.65f; // 扫描
    static final float PHASE4_END = 0.85f; // 高活跃
    // PHASE5_END = 1.0f              // 收束

    /** 扫描线参数。 */
    static final float SCAN_LINE_CORE_WIDTH_DP = 2.0f;   // 中心高亮线宽
    static final float SCAN_LINE_GLOW_DP = 14.0f;        // 上下光晕半宽
    static final float SCAN_HIT_THRESHOLD_DP = 3.0f;     // 粒子被扫中判定阈值

    /** 中心光核半径占比（相对外环半径）。 */
    static final float CORE_RATIO = 0.18f;

    /** 最上层泛光半径占比（相对外环半径）。 */
    static final float FLARE_RATIO = 1.15f;

    /** 进度百分比文字配置（悬浮球中心）。 */
    static final float PERCENT_TEXT_SIZE_SP = 36.0f;     // 百分比数字字号
    static final int PERCENT_TEXT_COLOR = 0xFFFFFFFF;    // 白色高对比
    static final float PERCENT_TEXT_ALPHA = 0.92f;
    static final float PERCENT_SUFFIX_SIZE_SP = 14.0f;   // % 符号字号

    /** 扫描路径文字配置（停止按钮上方）。 */
    static final float PATH_TEXT_SIZE_SP = 12.0f;        // 路径字号
    static final int PATH_TEXT_COLOR = 0xFFB8C5D6;       // 柔和浅灰蓝
    static final float PATH_TEXT_ALPHA = 0.85f;
    static final float PATH_BOTTOM_PADDING_DP = 8.0f;    // 距视图底部间距（按钮上方）
    static final int PATH_MAX_CHARS = 48;                // 路径最大字符数

    // ===================== 粒子数据结构（初始化后稳定） =====================

    static final class Particle {
        float orbitRatio;        // 0=内环, 1=外环，决定基础轨道半径
        float angle;             // 当前角度（弧度）
        float angularSpeed;      // 角速度（rad/s），有正负
        float baseRadiusDp;      // 粒子基础半径
        boolean highlight;       // 是否为高亮粒子
        int color;               // 粒子颜色（三色之一）
        float jitterPhase;       // 径向扰动相位
        float jitterFreq;        // 径向扰动频率
        float highlightRemainS;  // 被扫描线激活后的高亮剩余秒数
        float alpha;             // 当前透明度（0.25–1.0），平滑过渡
    }

    // ===================== 运行时状态 =====================

    private float density;
    private float cx, cy;            // 固定圆心
    private float innerR, midR, outerR; // 三层圆环半径（固定，不缩放）
    private float coreR, flareR;

    private final Particle[] particles = new Particle[PARTICLE_COUNT];
    private final Random rng = new Random(42); // 固定种子，保证初始化稳定可复现

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile boolean running;
    private long lastFrameMs;
    private float cycleTime;        // 当前循环内累计秒数
    private float globalTime;       // 全局累计秒数（用于扰动相位）

    // 当前帧计算出的阶段参数（供 onDraw 使用）
    private float curActivity;      // 整体活跃度 0–1
    private float curRingAlpha;     // 圆环透明度
    private float curParticleAlphaMul; // 粒子透明度乘数
    private float curSpeedMul;      // 粒子速度乘数
    private boolean scanActive;     // 扫描线是否激活
    private float scanProgress;     // 扫描线进度 0–1（easeInOut）
    private float scanAlpha;        // 扫描线透明度（含渐入渐出）
    private float scanX;            // 扫描线当前 x 坐标

    // 兼容外部调用（仅存储，不绘制文字）
    private int storedPercent;
    private int storedFound;
    private String storedPhase = "";
    private String storedScanPath = "";  // 当前扫描路径（简化后显示）

    // 文字绘制专用 Paint（独立于图形 paint，避免复用冲突）
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            long now = frameTimeNanos / 1_000_000L; // ns -> ms
            long dt = lastFrameMs == 0 ? 16 : (now - lastFrameMs);
            lastFrameMs = now;
            // 限制 dt，避免后台返回时大跳
            if (dt > 64) dt = 64;
            if (dt < 0) dt = 0;
            tick(dt / 1000f);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public ParticleScanView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        initParticles();
    }

    /** 初始化粒子：一次性生成，之后保持稳定，只更新位置与状态。 */
    private void initParticles() {
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = new Particle();
            // 轨道半径在内环与外环之间均匀分布 + 轻微扰动
            p.orbitRatio = (float) i / (PARTICLE_COUNT - 1); // 0..1 均匀
            p.orbitRatio += (rng.nextFloat() - 0.5f) * 0.08f;
            p.orbitRatio = clamp(p.orbitRatio, 0f, 1f);
            // 角度均匀分布 + 扰动，避免聚集
            p.angle = (float) (i * 2.0 * Math.PI / PARTICLE_COUNT) + rng.nextFloat() * 0.3f;
            // 角速度：方向随机，大小在范围内
            float speedMag = ANGULAR_SPEED_MIN + rng.nextFloat() * (ANGULAR_SPEED_MAX - ANGULAR_SPEED_MIN);
            p.angularSpeed = rng.nextBoolean() ? speedMag : -speedMag;
            // 半径：大部分小粒子，少量大粒子
            p.baseRadiusDp = PARTICLE_MIN_RADIUS_DP
                    + rng.nextFloat() * (PARTICLE_MAX_RADIUS_DP - PARTICLE_MIN_RADIUS_DP);
            // 高亮粒子（少量，半径偏大）
            p.highlight = rng.nextFloat() < HIGHLIGHT_PARTICLE_RATIO;
            if (p.highlight) {
                p.baseRadiusDp = PARTICLE_MAX_RADIUS_DP * 0.75f
                        + rng.nextFloat() * (PARTICLE_MAX_RADIUS_DP * 0.25f);
            }
            // 颜色：三色轮换 + 轻微随机
            p.color = PARTICLE_COLORS[rng.nextInt(PARTICLE_COLORS.length)];
            // 扰动参数
            p.jitterPhase = rng.nextFloat() * (float) (2.0 * Math.PI);
            p.jitterFreq = 0.4f + rng.nextFloat() * 0.6f; // 低频平滑
            p.highlightRemainS = 0f;
            p.alpha = PARTICLE_ALPHA_MIN;
            particles[i] = p;
        }
    }

    // ===================== 公开 API（保留签名兼容调用方） =====================

    public void start() {
        if (running) return;
        running = true;
        lastFrameMs = 0;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    public void setPercent(int v) { storedPercent = clamp(v, 0, 100); }

    public void setFoundCount(int v) { storedFound = Math.max(0, v); }

    public void setPhaseText(String s) { storedPhase = s == null ? "" : s; }

    /** 设置当前扫描路径（用于在停止按钮上方显示，自动简化为相对路径）。 */
    public void setScanPath(String path) {
        storedScanPath = simplifyPath(path);
    }

    // ===================== 尺寸与圆心 =====================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 圆心固定在视图中心，禁止漂移
        cx = w / 2f;
        cy = h / 2f;
        float half = Math.min(w, h) / 2f;
        innerR = half * INNER_RING_RATIO;
        midR   = half * MID_RING_RATIO;
        outerR = half * OUTER_RING_RATIO;
        coreR  = outerR * CORE_RATIO;
        flareR = outerR * FLARE_RATIO;
    }

    // ===================== 每帧更新（按 deltaTime） =====================

    private void tick(float dt) {
        if (dt <= 0) return;
        globalTime += dt;
        cycleTime += dt;
        if (cycleTime >= CYCLE_DURATION_S) {
            cycleTime -= CYCLE_DURATION_S; // 平滑进入下一轮，不重置粒子
        }
        float progress = cycleTime / CYCLE_DURATION_S; // 0..1

        // —— 计算 5 阶段连续参数 ——
        computePhaseParams(progress);

        // —— 更新粒子 ——
        for (Particle p : particles) {
            // 角度更新（速度乘数影响，但方向不变）
            p.angle += p.angularSpeed * curSpeedMul * dt;
            // 径向扰动：平滑正弦，不随机
            float jitter = (float) Math.sin(globalTime * p.jitterFreq * 2f * (float) Math.PI + p.jitterPhase)
                    * JITTER_AMP_DP * density;
            // 高亮衰减
            if (p.highlightRemainS > 0) {
                p.highlightRemainS -= dt;
                if (p.highlightRemainS < 0) p.highlightRemainS = 0;
            }
            // 透明度：基础值（按阶段乘数）+ 高亮增量，平滑趋近
            float targetAlpha = clamp(PARTICLE_ALPHA_MIN
                    + (PARTICLE_ALPHA_MAX - PARTICLE_ALPHA_MIN) * curParticleAlphaMul,
                    PARTICLE_ALPHA_MIN, PARTICLE_ALPHA_MAX);
            if (p.highlightRemainS > 0) {
                targetAlpha = PARTICLE_ALPHA_MAX;
            }
            p.alpha += (targetAlpha - p.alpha) * Math.min(1f, dt * 6f); // 平滑过渡

            // —— 扫描线扫中检测 ——
            if (scanActive && scanAlpha > 0.05f) {
                float px = cx + (float) Math.cos(p.angle) * (orbitRadius(p) + jitter);
                if (Math.abs(px - scanX) < SCAN_HIT_THRESHOLD_DP * density + p.baseRadiusDp * density) {
                    p.highlightRemainS = HIGHLIGHT_DURATION_MS / 1000f;
                }
            }
        }
    }

    /** 根据当前粒子轨道比例计算实际半径（内环到外环之间）。 */
    private float orbitRadius(Particle p) {
        return innerR + (outerR - innerR) * p.orbitRatio;
    }

    /** 计算 5 阶段连续参数（用 smoothstep 平滑过渡，避免突变）。 */
    private void computePhaseParams(float progress) {
        // 各阶段目标活跃度
        float activity, ringAlpha, particleAlphaMul, speedMul;
        boolean scan = false;
        float scanProg = 0f, scanA = 0f;

        if (progress < PHASE1_END) {
            // 阶段1 低活跃 0%–20%
            float t = progress / PHASE1_END;
            activity = lerp(0.15f, 0.20f, smooth(t));
            ringAlpha = lerp(0.25f, 0.30f, smooth(t));
            particleAlphaMul = lerp(0.30f, 0.40f, smooth(t));
            speedMul = lerp(0.55f, 0.65f, smooth(t));
        } else if (progress < PHASE2_END) {
            // 阶段2 激活 20%–40%
            float t = (progress - PHASE1_END) / (PHASE2_END - PHASE1_END);
            activity = lerp(0.20f, 0.60f, smooth(t));
            ringAlpha = lerp(0.30f, 0.70f, smooth(t));
            particleAlphaMul = lerp(0.40f, 0.90f, smooth(t));
            speedMul = lerp(0.65f, 1.00f, smooth(t));
        } else if (progress < PHASE3_END) {
            // 阶段3 扫描 40%–65%
            float t = (progress - PHASE2_END) / (PHASE3_END - PHASE2_END);
            activity = lerp(0.60f, 0.90f, smooth(t));
            ringAlpha = lerp(0.70f, 1.00f, smooth(t));
            particleAlphaMul = lerp(0.90f, 1.00f, smooth(t));
            speedMul = lerp(1.00f, 1.10f, smooth(t));
            scan = true;
            scanProg = easeInOut(t);
            // 扫描线渐入渐出（前 15% 渐入，后 15% 渐出）
            if (t < 0.15f) scanA = t / 0.15f;
            else if (t > 0.85f) scanA = (1f - t) / 0.15f;
            else scanA = 1f;
        } else if (progress < PHASE4_END) {
            // 阶段4 高活跃 65%–85%
            float t = (progress - PHASE3_END) / (PHASE4_END - PHASE3_END);
            activity = lerp(0.90f, 1.00f, smooth(t));
            ringAlpha = 1.00f;
            particleAlphaMul = 1.00f;
            speedMul = lerp(1.10f, 1.00f, smooth(t));
            // 扫描线已收束（scanA 在阶段末尾已渐出到 0）
        } else {
            // 阶段5 收束 85%–100%
            float t = (progress - PHASE4_END) / (1f - PHASE4_END);
            activity = lerp(1.00f, 0.15f, smooth(t));
            ringAlpha = lerp(1.00f, 0.25f, smooth(t));
            particleAlphaMul = lerp(1.00f, 0.30f, smooth(t));
            speedMul = lerp(1.00f, 0.55f, smooth(t));
        }

        curActivity = activity;
        curRingAlpha = ringAlpha;
        curParticleAlphaMul = particleAlphaMul;
        curSpeedMul = speedMul;
        scanActive = scan;
        scanProgress = scanProg;
        scanAlpha = scanA;
        // 扫描线 x：从左到右穿过圆心
        scanX = cx - outerR + scanProg * (2f * outerR);
    }

    // ===================== 绘制（严格按顺序） =====================

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (cx == 0 && cy == 0) return;

        // 1. 深色背景（纯净，无渐变色块/纹理/网格）
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_BG);
        c.drawRect(0, 0, getWidth(), getHeight(), paint);

        // 2. 最外层低透明圆环
        drawRing(c, outerR, COLOR_OUTER_RING, OUTER_RING_WIDTH_DP, curRingAlpha * 0.55f);

        // 3. 中间主发光环（带柔和外发光）
        drawGlowRing(c, midR, COLOR_MID_RING, MID_RING_WIDTH_DP, curRingAlpha);

        // 4. 内层圆环
        drawRing(c, innerR, COLOR_INNER_RING, INNER_RING_WIDTH_DP, curRingAlpha * 0.85f);

        // 5. 普通粒子
        drawParticles(c, false);
        // 6. 高亮粒子和尾迹
        drawParticles(c, true);

        // 7. 扫描线
        if (scanActive && scanAlpha > 0.01f) {
            drawScanLine(c);
        }

        // 8. 中心光核（强度随活跃度变化，半径固定不缩放）
        drawCore(c);

        // 9. 最上层轻微泛光
        drawFlare(c);

        // 10. 中心进度百分比（悬浮球核心，覆盖在光核之上）
        drawPercent(c);

        // 11. 底部扫描路径（停止按钮上方）
        drawScanPath(c);
    }

    /** 绘制单层圆环。 */
    private void drawRing(Canvas c, float radius, int color, float widthDp, float alpha) {
        if (alpha <= 0.01f || radius <= 0) return;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(widthDp * density);
        paint.setColor(applyAlpha(color, alpha));
        c.drawCircle(cx, cy, radius, paint);
    }

    /** 绘制带柔和外发光的主发光环。 */
    private void drawGlowRing(Canvas c, float radius, int color, float widthDp, float alpha) {
        if (alpha <= 0.01f || radius <= 0) return;
        // 外发光（宽描边低透明）
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(widthDp * density * 4f);
        paint.setColor(applyAlpha(color, alpha * 0.18f));
        c.drawCircle(cx, cy, radius, paint);
        // 主线
        paint.setStrokeWidth(widthDp * density);
        paint.setColor(applyAlpha(color, alpha));
        c.drawCircle(cx, cy, radius, paint);
    }

    /** 绘制粒子。highlightOnly=true 时只绘制高亮粒子（含尾迹），否则只绘制普通粒子。 */
    private void drawParticles(Canvas c, boolean highlightOnly) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        for (Particle p : particles) {
            if (p.highlight != highlightOnly) continue;
            if (p.alpha <= 0.02f) continue;

            // 径向扰动（平滑正弦）
            float jitter = (float) Math.sin(globalTime * p.jitterFreq * 2f * (float) Math.PI + p.jitterPhase)
                    * JITTER_AMP_DP * density;
            float r = orbitRadius(p) + jitter;
            float x = cx + (float) Math.cos(p.angle) * r;
            float y = cy + (float) Math.sin(p.angle) * r;

            // 高亮时半径放大
            float radiusDp = p.baseRadiusDp;
            if (p.highlightRemainS > 0) {
                radiusDp *= HIGHLIGHT_SCALE;
            }
            float radiusPx = radiusDp * density;

            // 高亮粒子带短尾迹（沿运动反方向）
            if (p.highlight && p.highlightRemainS > 0) {
                drawTrail(c, p, x, y, r, radiusPx);
            }

            // 粒子主体
            paint.setColor(applyAlpha(p.color, p.alpha));
            c.drawCircle(x, y, radiusPx, paint);

            // 高亮粒子额外光晕
            if (p.highlight) {
                paint.setColor(applyAlpha(p.color, p.alpha * 0.25f));
                c.drawCircle(x, y, radiusPx * 2.2f, paint);
            }
        }
    }

    /** 绘制粒子短尾迹（沿切线反方向，淡出）。 */
    private void drawTrail(Canvas c, Particle p, float x, float y, float r, float radiusPx) {
        // 切线方向（垂直于半径方向），取运动反方向
        float tx = -(float) Math.sin(p.angle) * Math.signum(p.angularSpeed);
        float ty = (float) Math.cos(p.angle) * Math.signum(p.angularSpeed);
        float trailLen = radiusPx * 4f;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(radiusPx * 0.8f);
        // 尾迹用线性渐变（从粒子位置淡出）
        LinearGradient g = new LinearGradient(
                x, y,
                x - tx * trailLen, y - ty * trailLen,
                applyAlpha(p.color, p.alpha * 0.6f),
                applyAlpha(p.color, 0f),
                Shader.TileMode.CLAMP);
        paint.setShader(g);
        c.drawLine(x, y, x - tx * trailLen, y - ty * trailLen, paint);
        paint.setShader(null);
    }

    /** 绘制水平扫描线：中心高亮线 + 上下光晕 + 两端渐隐。 */
    private void drawScanLine(Canvas c) {
        float left = cx - outerR;
        float right = cx + outerR;
        float y = cy;
        // 接近圆心时亮度最强
        float centerFactor = 1f - Math.abs(scanX - cx) / outerR; // 0..1
        float brightness = 0.6f + 0.4f * centerFactor;

        paint.reset();
        paint.setAntiAlias(true);

        // 上下柔和光晕（垂直线性渐变填充矩形）
        float glowHalf = SCAN_LINE_GLOW_DP * density;
        LinearGradient glowGrad = new LinearGradient(
                scanX, y - glowHalf,
                scanX, y + glowHalf,
                new int[]{applyAlpha(COLOR_INNER_RING, 0f),
                          applyAlpha(COLOR_INNER_RING, scanAlpha * brightness * 0.5f),
                          applyAlpha(COLOR_INNER_RING, scanAlpha * brightness * 0.5f),
                          applyAlpha(COLOR_INNER_RING, 0f)},
                new float[]{0f, 0.5f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(glowGrad);
        // 两端渐隐：用水平 alpha 渐变叠加（通过 PorterDuff 较复杂，这里用分段绘制简化）
        c.drawRect(left, y - glowHalf, right, y + glowHalf, paint);
        paint.setShader(null);

        // 中心高亮线（水平两端渐隐）
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(SCAN_LINE_CORE_WIDTH_DP * density);
        LinearGradient coreGrad = new LinearGradient(
                left, y, right, y,
                new int[]{applyAlpha(0xFFFFFFFF, 0f),
                          applyAlpha(0xFFFFFFFF, scanAlpha * brightness * 0.4f),
                          applyAlpha(0xFFFFFFFF, scanAlpha * brightness),
                          applyAlpha(0xFFFFFFFF, scanAlpha * brightness * 0.4f),
                          applyAlpha(0xFFFFFFFF, 0f)},
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f},
                Shader.TileMode.CLAMP);
        paint.setShader(coreGrad);
        c.drawLine(left, y, right, y, paint);
        paint.setShader(null);
    }

    /** 绘制中心光核（半径固定，强度随活跃度变化）。 */
    private void drawCore(Canvas c) {
        if (coreR <= 0) return;
        float intensity = 0.3f + 0.7f * curActivity;
        RadialGradient g = new RadialGradient(
                cx, cy, coreR,
                new int[]{applyAlpha(0xFFFFFFFF, intensity),
                          applyAlpha(COLOR_INNER_RING, intensity * 0.6f),
                          applyAlpha(COLOR_INNER_RING, 0f)},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(g);
        c.drawCircle(cx, cy, coreR, paint);
        paint.setShader(null);
    }

    /** 绘制最上层轻微泛光（半径固定，强度低）。 */
    private void drawFlare(Canvas c) {
        if (flareR <= 0) return;
        float intensity = 0.06f + 0.10f * curActivity;
        RadialGradient g = new RadialGradient(
                cx, cy, flareR,
                new int[]{applyAlpha(COLOR_INNER_RING, intensity),
                          applyAlpha(COLOR_INNER_RING, 0f)},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(g);
        c.drawCircle(cx, cy, flareR, paint);
        paint.setShader(null);
    }

    /** 绘制中心进度百分比（悬浮球核心位置，覆盖在光核之上）。 */
    private void drawPercent(Canvas c) {
        if (storedPercent < 0) return;
        String numText = String.valueOf(storedPercent);
        String suffix = "%";

        textPaint.reset();
        textPaint.setAntiAlias(true);
        textPaint.setColor(applyAlpha(PERCENT_TEXT_COLOR, PERCENT_TEXT_ALPHA));
        textPaint.setTextSize(PERCENT_TEXT_SIZE_SP * density);
        textPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        // 测量数字与后缀宽度，整体水平居中
        float numWidth = textPaint.measureText(numText);
        float originalSize = textPaint.getTextSize();
        textPaint.setTextSize(PERCENT_SUFFIX_SIZE_SP * density);
        float suffixWidth = textPaint.measureText(suffix);
        textPaint.setTextSize(originalSize);

        float totalWidth = numWidth + suffixWidth;
        float startX = cx - totalWidth / 2f;
        float baselineY = cy + originalSize / 3f; // 视觉居中补偿

        // 数字
        textPaint.setTextSize(originalSize);
        textPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(numText, startX, baselineY, textPaint);

        // % 后缀（较小，紧贴数字右侧，垂直对齐基线）
        textPaint.setTextSize(PERCENT_SUFFIX_SIZE_SP * density);
        c.drawText(suffix, startX + numWidth, baselineY, textPaint);
    }

    /** 绘制底部扫描路径（停止按钮上方，单行省略）。 */
    private void drawScanPath(Canvas c) {
        if (storedScanPath == null || storedScanPath.isEmpty()) return;

        textPaint.reset();
        textPaint.setAntiAlias(true);
        textPaint.setColor(applyAlpha(PATH_TEXT_COLOR, PATH_TEXT_ALPHA));
        textPaint.setTextSize(PATH_TEXT_SIZE_SP * density);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // 限制最大宽度（视图宽度 - 两侧留白）
        float maxWidth = getWidth() - 48 * density;
        String display = storedScanPath;
        float textWidth = textPaint.measureText(display);
        if (textWidth > maxWidth) {
            display = ellipsizeMiddle(display, maxWidth, textPaint);
        }

        float baselineY = getHeight() - PATH_BOTTOM_PADDING_DP * density - textPaint.descent();
        c.drawText(display, cx, baselineY, textPaint);
    }

    /** 路径简化：取最后 2 级目录/文件名，前缀加 .../。 */
    private static String simplifyPath(String path) {
        if (path == null || path.isEmpty()) return "";
        // 统一分隔符
        String normalized = path.replace('\\', '/');
        String[] parts = normalized.split("/");
        int n = parts.length;
        if (n <= 2) return normalized;
        return ".../" + parts[n - 2] + "/" + parts[n - 1];
    }

    /** 超宽时中间省略（保留首尾，中间用 … 代替）。 */
    private static String ellipsizeMiddle(String text, float maxWidth, Paint paint) {
        if (paint.measureText(text) <= maxWidth) return text;
        String ellipsis = "…";
        int len = text.length();
        int low = 1, high = len - 1, best = 1;
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

    // ===================== 工具函数 =====================

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** smoothstep 平滑过渡。 */
    private static float smooth(float t) {
        t = clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** easeInOut（与扫描线要求一致）。 */
    private static float easeInOut(float t) {
        t = clamp(t, 0f, 1f);
        return t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2f) / 2f;
    }

    /** 给颜色应用 alpha（0–1），保留原 RGB。 */
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
