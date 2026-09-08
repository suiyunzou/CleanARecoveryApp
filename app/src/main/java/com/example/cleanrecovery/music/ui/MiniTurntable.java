package com.example.cleanrecovery.music.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/** 迷你条黑胶唱机：播放=唱臂落盘+唱片匀速旋转；暂停=唱臂抬起+唱片停在当前角度。 */
final class MiniTurntable {

    private static final long ARM_SWING_MS = 280L;
    /** 抬臂角：顺时针摆到盘缘（Android Rotation 正值=顺时针）。 */
    private static final float ARM_REST_ANGLE = 32f;

    private ObjectAnimator spin;
    private ObjectAnimator armSwing;
    private boolean armOnDisc;

    /** 唱臂支点：矢量图内支点位于右上（约 86.7% 宽、20% 高），布局完成后设置并复位到抬起态。 */
    void bindArmPivot(View tonearm) {
        if (tonearm == null) return;
        tonearm.post(() -> {
            tonearm.setPivotX(tonearm.getWidth() * 0.867f);
            tonearm.setPivotY(tonearm.getHeight() * 0.20f);
            if (!armOnDisc && tonearm.getRotation() == 0f) {
                tonearm.setRotation(ARM_REST_ANGLE);
            }
        });
    }

    void update(View vinyl, View tonearm, boolean playing) {
        // 唱臂摆动（绕支点旋转，仅在状态变化时动画）
        if (tonearm != null && armOnDisc != playing) {
            armOnDisc = playing;
            float from = tonearm.getRotation();
            float to = playing ? 0f : ARM_REST_ANGLE;
            if (armSwing != null) armSwing.cancel();
            armSwing = ObjectAnimator.ofFloat(tonearm, View.ROTATION, from, to);
            armSwing.setDuration(ARM_SWING_MS);
            armSwing.setInterpolator(new AccelerateDecelerateInterpolator());
            armSwing.start();
        }
        // 唱片旋转
        if (vinyl == null) return;
        if (playing) {
            if (spin == null) {
                spin = ObjectAnimator.ofFloat(vinyl, View.ROTATION, 0f, 360f);
                spin.setDuration(3600L);
                spin.setInterpolator(new LinearInterpolator());
                spin.setRepeatCount(ValueAnimator.INFINITE);
            }
            if (spin.isPaused()) {
                spin.resume();
            } else if (!spin.isRunning()) {
                spin.start();
            }
        } else if (spin != null && spin.isRunning()) {
            spin.pause();
        }
    }
}
