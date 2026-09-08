package com.example.cleanrecovery.music.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.appcompat.widget.AppCompatSeekBar;

/** Playback seek bar with a metadata-backed climax marker. */
public class MusicSeekBar extends AppCompatSeekBar {
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long climaxMs = -1;
    private long durationMs;

    public MusicSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        markerPaint.setColor(Color.parseColor("#BFC1C9"));
    }

    public void setClimax(long startMs, long durationMs) {
        this.climaxMs = startMs;
        this.durationMs = durationMs;
        invalidate();
    }

    public float positionX(float fraction) {
        fraction = Math.max(0, Math.min(1, fraction));
        if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) fraction = 1 - fraction;
        return getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight()) * fraction;
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (climaxMs < 0 || durationMs <= 0 || climaxMs >= durationMs) return;
        float x = positionX((float) climaxMs / durationMs);
        float thumbX = positionX((float) getProgress() / getMax());
        float density = getResources().getDisplayMetrics().density;
        // The moving thumb visually covers the marker when they overlap.
        if (Math.abs(x - thumbX) < 8 * density) return;
        canvas.drawCircle(x, getPaddingTop()
                + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f, 3 * density, markerPaint);
    }
}
