package com.example.cleanrecovery.music.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.data.Lyrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrolling, time-synced lyrics view.
 *
 * <p>Features:
 * <ul>
 *   <li>Auto-scrolls to the active line based on playback position.</li>
 *   <li>Active line is highlighted; other lines are dimmed.</li>
 *   <li>Adjustable font size (small / medium / large).</li>
 *   <li>Switchable color themes (teal / blue / amber / white).</li>
 *   <li>Tap a line to seek (via {@link OnSeekListener}).</li>
 * </ul>
 */
public class LyricsView extends RecyclerView {

    public enum FontSize { SMALL, MEDIUM, LARGE }
    public enum Theme { TEAL, BLUE, AMBER, WHITE }

    /** Notifies the host that the user tapped a lyric line to seek. */
    public interface OnSeekListener { void onSeekTo(long positionMs); }
    public interface OnSingleTapListener { void onSingleTap(); }

    private LyricsAdapter adapter;
    private Lyrics lyrics = Lyrics.empty();
    private int activeIndex = -1;
    private FontSize fontSize = FontSize.MEDIUM;
    private int customFontSizeSp = 16;
    private Theme theme = Theme.TEAL;
    private OnSeekListener seekListener;
    private OnSingleTapListener singleTapListener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int pendingTapPosition = -1;
    private long pendingTapAt;
    private Runnable pendingSingleTap;

    public LyricsView(@NonNull Context context) {
        super(context);
        init();
    }

    public LyricsView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LyricsView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new LyricsAdapter();
        setAdapter(adapter);
        // Center the active line vertically.
        ((LinearLayoutManager) getLayoutManager()).setInitialPrefetchItemCount(5);
        setHasFixedSize(false);
        setNestedScrollingEnabled(true);
        // Subtle vertical item spacing handled inside adapter padding.
    }

    public void setOnSeekListener(OnSeekListener l) { this.seekListener = l; }
    public void setOnSingleTapListener(OnSingleTapListener l) { this.singleTapListener = l; }

    public void setLyrics(Lyrics lyrics) {
        this.lyrics = lyrics == null ? Lyrics.empty() : lyrics;
        this.activeIndex = -1;
        adapter.notifyDataSetChanged();
        if (!this.lyrics.isEmpty()) {
            scrollToPosition(0);
        }
    }

    public Lyrics getLyrics() { return lyrics; }

    public boolean hasLyrics() { return !lyrics.isEmpty(); }

    public void setFontSize(FontSize size) {
        this.fontSize = size;
        this.customFontSizeSp = fontSizeSp(size);
        adapter.notifyDataSetChanged();
    }

    public FontSize getFontSize() { return fontSize; }

    public void setCustomFontSizeSp(int sp) {
        customFontSizeSp = Math.max(12, Math.min(24, sp));
        adapter.notifyDataSetChanged();
    }

    public int getCurrentFontSizeSp() { return customFontSizeSp; }

    public void cycleFontSize() {
        FontSize[] sizes = FontSize.values();
        fontSize = sizes[(fontSize.ordinal() + 1) % sizes.length];
        adapter.notifyDataSetChanged();
    }

    public void setTheme(Theme theme) {
        this.theme = theme;
        adapter.notifyDataSetChanged();
    }

    public Theme getTheme() { return theme; }

    public void cycleTheme() {
        Theme[] themes = Theme.values();
        theme = themes[(theme.ordinal() + 1) % themes.length];
        adapter.notifyDataSetChanged();
    }

    /**
     * Update the active line based on the current playback position. Auto-scrolls
     * to keep the active line roughly centered. No-op if lyrics are empty.
     */
    public void updatePosition(long positionMs) {
        if (lyrics.isEmpty()) return;
        int idx = lyrics.indexOfActive(positionMs);
        if (idx != activeIndex) {
            activeIndex = idx;
            adapter.notifyItemRangeChanged(Math.max(0, idx - 1), 3);
            smoothScrollToCentered(idx);
        }
    }

    private void smoothScrollToCentered(int position) {
        if (position < 0) return;
        RecyclerView.LayoutManager lm = getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) return;
        LinearSmoothScroller scroller = new LinearSmoothScroller(getContext()) {
            @Override
            protected int getVerticalSnapPreference() {
                return SNAP_TO_START;
            }
            @Override
            public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int snapPreference) {
                // Center the target view within the visible box.
                int viewCenter = viewStart + (viewEnd - viewStart) / 2;
                int boxCenter = boxStart + (boxEnd - boxStart) / 2;
                return boxCenter - viewCenter;
            }
        };
        scroller.setTargetPosition(position);
        lm.startSmoothScroll(scroller);
    }

    private int fontSizeSp(FontSize size) {
        switch (size) {
            case SMALL: return 13;
            case LARGE: return 19;
            case MEDIUM:
            default: return 16;
        }
    }

    private int activeColor() {
        switch (theme) {
            case BLUE:  return Color.parseColor("#3B82F6");
            case AMBER: return Color.parseColor("#F59E0B");
            case WHITE: return Color.parseColor("#FFFFFF");
            case TEAL:
            default:    return Color.parseColor("#14B8A6");
        }
    }

    private int inactiveColor() {
        // White theme uses a dark surface; dim with gray.
        if (theme == Theme.WHITE) return Color.parseColor("#9CA3AF");
        return Color.parseColor("#9CA3AF");
    }

    // ---- Adapter ----

    private class LyricsAdapter extends Adapter<LyricsAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = (TextView) LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_lyric_line, parent, false);
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Lyrics.Line line = lyrics.lines().get(position);
            TextView tv = h.text;
            tv.setText(line.text.isEmpty() ? "♪" : line.text);
            tv.setTextSize(customFontSizeSp);
            boolean active = position == activeIndex;
            tv.setTextColor(active ? activeColor() : inactiveColor());
            tv.setTypeface(tv.getTypeface(), active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tv.setAlpha(active ? 1f : 0.55f);
            tv.setOnClickListener(v -> {
                long now = System.currentTimeMillis();
                if (pendingTapPosition == position
                        && now - pendingTapAt <= ViewConfiguration.getDoubleTapTimeout()) {
                    if (pendingSingleTap != null) {
                        ui.removeCallbacks(pendingSingleTap);
                        pendingSingleTap = null;
                    }
                    pendingTapPosition = -1;
                    long t = lyrics.lines().get(position).timeMs;
                    if (seekListener != null) seekListener.onSeekTo(t);
                    return;
                }
                pendingTapPosition = position;
                pendingTapAt = now;
                if (pendingSingleTap != null) ui.removeCallbacks(pendingSingleTap);
                pendingSingleTap = () -> {
                    pendingTapPosition = -1;
                    pendingSingleTap = null;
                    if (singleTapListener != null) singleTapListener.onSingleTap();
                };
                ui.postDelayed(pendingSingleTap, ViewConfiguration.getDoubleTapTimeout());
            });
        }

        @Override
        public int getItemCount() { return lyrics.size(); }

        class VH extends ViewHolder {
            final TextView text;
            VH(TextView v) {
                super(v);
                text = v;
            }
        }
    }
}
