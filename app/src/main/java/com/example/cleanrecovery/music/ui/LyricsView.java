package com.example.cleanrecovery.music.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.data.Lyrics;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

/**
 * Scrolling, time-synced lyrics view.
 *
 * <p>Features:
 * <ul>
 *   <li>Auto-scrolls to the active line based on playback position.</li>
 *   <li>Active line is highlighted; other lines are dimmed.</li>
 *   <li>Adjustable font size (small / medium / large).</li>
 *   <li>Switchable color themes (teal / blue / amber / white).</li>
 *   <li>Tap a line to switch back to the cover mode; long-press then drag to locate a line, release to play it.</li>
 * </ul>
 */
public class LyricsView extends RecyclerView {

    public enum FontSize { SMALL, MEDIUM, LARGE }
    public enum Theme { DARK, TEAL, BLUE, AMBER, WHITE }

    /** Notifies the host that the user tapped a lyric line to seek. */
    public interface OnSeekListener { void onSeekTo(long positionMs); }
    public interface OnSingleTapListener { void onSingleTap(); }

    /**
     * 拖动浏览态回调（概念版歌词交互）：用户上下拖动歌词时进入浏览态，
     * 宿主显隐"中轴浮动条"（时间+线+播放钮），并随中轴线扫过的歌词行刷新时间。
     */
    public interface OnBrowseListener {
        void onBrowseChanged(boolean browsing);
        void onCenterLineChanged(long timeMs);
    }

    private LyricsAdapter adapter;
    private Lyrics lyrics = Lyrics.empty();
    private int activeIndex = -1;
    private long positionMs;
    private FontSize fontSize = FontSize.MEDIUM;
    private int customFontSizeSp = 16;
    private Theme theme = Theme.DARK;
    private OnSeekListener seekListener;
    private OnSingleTapListener singleTapListener;
    private OnBrowseListener browseListener;
    /** 浏览态：拖动/长按歌词进入，拖动定位；松手保留，仅点浮条播放钮才跳转，
     *  3 秒无操作自动回到当前播放行（概念版交互）。 */
    private boolean browsing;
    private boolean translationVisible;

    public void setTranslationVisible(boolean visible) {
        if (translationVisible == visible) return;
        translationVisible = visible;
        adapter.notifyDataSetChanged();
        post(() -> { if (!browsing) smoothScrollToCentered(activeIndex); });
    }
    private final Runnable browseIdleExit = new Runnable() {
        @Override public void run() { exitBrowseMode(false); }
    };

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
        // Playback colors must change on the current frame, without RecyclerView's
        // old/new-holder cross-fade (which otherwise delays the visible highlight).
        setItemAnimator(null);
        // Center the active line vertically.
        ((LinearLayoutManager) getLayoutManager()).setInitialPrefetchItemCount(5);
        setHasFixedSize(false);
        setNestedScrollingEnabled(true);
        // Subtle vertical item spacing handled inside adapter padding.
        // 拖动歌词即进入浏览态（长按行同样进入）；松手保留浮条不播放，
        // 只有点浮条上的播放小钮才跳转；3 秒无操作自动回当前行
        addOnScrollListener(new OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (lyrics.isEmpty()) return;
                if (newState == SCROLL_STATE_DRAGGING) {
                    enterBrowseMode();
                } else if (newState == SCROLL_STATE_IDLE && browsing) {
                    removeCallbacks(browseIdleExit);
                    postDelayed(browseIdleExit, 3000);
                }
                if (browsing) notifyCenterLine();
            }

            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (browsing) notifyCenterLine();
            }
        });
    }

    public void setOnSeekListener(OnSeekListener l) { this.seekListener = l; }
    public void setOnSingleTapListener(OnSingleTapListener l) { this.singleTapListener = l; }
    public void setOnBrowseListener(OnBrowseListener l) { this.browseListener = l; }

    private void enterBrowseMode() {
        removeCallbacks(browseIdleExit);
        if (!browsing) {
            browsing = true;
            if (browseListener != null) browseListener.onBrowseChanged(true);
            notifyCenterLine();
        }
    }

    /** 退出浏览态：回到当前播放行；jump=true 时同时把播放跳到中轴所在行。 */
    public void exitBrowseMode(boolean jump) {
        removeCallbacks(browseIdleExit);
        if (jump) {
            Lyrics.Line center = lineAtCenter();
            if (center != null && seekListener != null) seekListener.onSeekTo(center.timeMs);
        }
        if (browsing) {
            browsing = false;
            if (browseListener != null) browseListener.onBrowseChanged(false);
        }
        if (!lyrics.isEmpty()) smoothScrollToCentered(Math.max(0, activeIndex));
    }

    /** 中轴所在歌词行的时间；无歌词/无可见行返回 -1。 */
    public long centerLineTime() {
        Lyrics.Line line = lineAtCenter();
        return line == null ? -1 : line.timeMs;
    }

    private Lyrics.Line lineAtCenter() {
        if (lyrics.isEmpty()) return null;
        View centerChild = findChildAtCenterY();
        if (centerChild == null) return null;
        int pos = getChildAdapterPosition(centerChild);
        if (pos == RecyclerView.NO_POSITION || pos >= lyrics.size()) return null;
        return lyrics.lines().get(pos);
    }

    private View findChildAtCenterY() {
        final int center = getHeight() / 2;
        int closest = -1;
        int closestDist = Integer.MAX_VALUE;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int mid = (child.getTop() + child.getBottom()) / 2;
            int dist = Math.abs(mid - center);
            if (dist < closestDist) {
                closestDist = dist;
                closest = i;
            }
        }
        return closest < 0 ? null : getChildAt(closest);
    }

    private void notifyCenterLine() {
        if (!browsing || lyrics.isEmpty()) return;
        Lyrics.Line line = lineAtCenter();
        if (line != null && browseListener != null) browseListener.onCenterLineChanged(line.timeMs);
    }

    public void setLyrics(Lyrics lyrics) {
        if (this.lyrics == lyrics) return;
        exitBrowseMode(false);
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
        this.positionMs = positionMs;
        if (lyrics.isEmpty()) return;
        int idx = lyrics.indexOfActive(positionMs);
        if (idx != activeIndex) {
            int prev = activeIndex;
            activeIndex = idx;
            if (prev >= 0) adapter.notifyItemChanged(prev);
            if (idx >= 0) adapter.notifyItemChanged(idx);
            if (!browsing) smoothScrollToCentered(idx);
        } else if (idx >= 0) {
            ViewHolder holder = findViewHolderForAdapterPosition(idx);
            if (holder instanceof LyricsAdapter.VH) {
                bindOriginal(((LyricsAdapter.VH) holder).text, lyrics.lines().get(idx), true);
            }
        }
    }

    private void bindOriginal(TextView view, Lyrics.Line line, boolean active) {
        view.setTextColor(inactiveColor());
        if (active && !line.words.isEmpty()) {
            int end = line.sungTextEnd(positionMs);
            CharSequence current = view.getText();
            if (current instanceof Spanned && line.text.contentEquals(current)) {
                Spanned styled = (Spanned) current;
                ForegroundColorSpan[] spans = styled.getSpans(0, styled.length(), ForegroundColorSpan.class);
                if (end == 0 && spans.length == 0) return;
                if (spans.length == 1 && styled.getSpanEnd(spans[0]) == end
                        && spans[0].getForegroundColor() == activeColor()) return;
            }
            SpannableString text = new SpannableString(line.text);
            if (end > 0) text.setSpan(new ForegroundColorSpan(activeColor()), 0, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(text);
        } else {
            view.setText(line.text.isEmpty() ? "♪" : line.text);
            // LRC-only songs have no word timing; retain line-level fallback.
            if (active) view.setTextColor(activeColor());
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
            case TEAL:  return Color.parseColor("#14B8A6");
            case DARK:
            default:    return Color.BLACK;
        }
    }

    /** 当前行翻译的颜色：比原文淡一档（概念版双语样式）。 */
    private int activeTranslationColor() {
        return theme == Theme.WHITE
                ? Color.parseColor("#D1D5DB")
                : Color.parseColor("#6B7280");
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
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_lyric_line, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Lyrics.Line line = lyrics.lines().get(position);
            TextView tv = h.text;
            tv.setTextSize(customFontSizeSp);
            boolean active = position == activeIndex;
            bindOriginal(tv, line, active);
            tv.setTypeface(null, android.graphics.Typeface.NORMAL);
            tv.setAlpha(active ? 1f : 0.55f);

            // 翻译行：有内容才显示，字号比原文小一档（概念版双语样式）
            if (h.translation != null) {
                boolean hasTranslation = translationVisible && !line.translation.isEmpty();
                h.translation.setVisibility(hasTranslation ? View.VISIBLE : View.GONE);
                if (hasTranslation) {
                    h.translation.setText(line.translation);
                    h.translation.setTextSize(Math.max(11, customFontSizeSp - 1));
                    h.translation.setTextColor(active ? activeTranslationColor() : inactiveColor());
                    h.translation.setAlpha(active ? 1f : 0.55f);
                }
            }

            // 单击行任意位置（含行间隙）= 切回封面/转动模式（概念版默认行为）；
            // 浏览态（长按拖动）中单击 = 取消定位回到当前行。
            // 监听挂在行根视图上：文本两侧/行间隙也响应，避免点在 padding 上丢手势。
            h.itemView.setOnClickListener(v -> {
                if (browsing) {
                    exitBrowseMode(false);
                    return;
                }
                if (singleTapListener != null) singleTapListener.onSingleTap();
            });
            h.itemView.setOnLongClickListener(v -> {
                if (!lyrics.isEmpty()) {
                    enterBrowseMode();
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() { return lyrics.size(); }

        class VH extends ViewHolder {
            final TextView text;
            final TextView translation;
            VH(View v) {
                super(v);
                text = v.findViewById(R.id.lyric_line_text);
                translation = v.findViewById(R.id.lyric_line_translation);
            }
        }
    }
}
