package com.example.cleanrecovery.ui.feed;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.ui.PlayerView;
import androidx.viewpager2.widget.ViewPager2;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.extractor.BilibiliFeedProvider;
import com.example.cleanrecovery.extractor.ExtractorHttp;
import com.example.cleanrecovery.ytdlp.PersistentCookieJar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 抖音模式：垂直滑动视频流（单 ExoPlayer 实例 + PlayerView 复用）。
 *
 * <p>数据链：入口 URL → {@link BilibiliFeedProvider#root} 解析直链 →
 * B站官方相关视频 API 无限追加（非 B站链接则单条播放）。
 * 预加载策略：提前解析 position+1 的直链（消除切换时的解析网络延迟），
 * 播放缓冲由 ExoPlayer LOAD_CONTROL 默认策略覆盖。</p>
 *
 * <p>防盗链：每条流的 Referer/UA 取自解析器的 Format.httpHeaders，
 * 经 DefaultHttpDataSource 附加。</p>
 */
public final class VideoFeedActivity extends Activity {

    public static final String EXTRA_URL = "extra_url";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<BilibiliFeedProvider.FeedItem> items = new ArrayList<>();
    private final Set<String> seenKeys = BilibiliFeedProvider.newSeenSet();
    private final Set<Integer> resolving = new HashSet<>();

    private ExoPlayer player;
    private ViewPager2 pager;
    private FeedAdapter adapter;
    private String startUrl;
    private int currentPos = -1;
    private volatile boolean loadingMore;
    private volatile boolean destroyed;
    private boolean rootFailed;
    private TextView topHint;
    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (!destroyed) main.postDelayed(this, 500);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_feed);
        startUrl = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_URL);
        if (startUrl == null || startUrl.isEmpty()) {
            finish();
            return;
        }
        pager = findViewById(R.id.feed_pager);
        topHint = findViewById(R.id.feed_top_hint);
        ImageButton back = findViewById(R.id.feed_back);
        back.setOnClickListener(v -> finish());
        // 解析请求带上应用内浏览器会话（登录 B站 后清晰度更高、匿名 try_look 403 更少）
        try {
            ExtractorHttp.setGlobalCookieJar(PersistentCookieJar.getInstance(this));
        } catch (Throwable ignored) {
        }

        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), /* handleAudioFocus= */ true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                showError(currentPos, "播放失败：" + error.getMessage());
                syncPauseIcon();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                syncPauseIcon();
            }
        });

        adapter = new FeedAdapter();
        pager.setAdapter(adapter);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        pager.setOffscreenPageLimit(1);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentPos = position;
                bind(position);
                ensureResolved(position + 1);
                if (position >= items.size() - 3) loadMore();
            }
        });

        bootstrap();
        main.postDelayed(progressTicker, 500);
    }

    // ===== 数据装载 =====

    private void bootstrap() {
        final String url = startUrl;
        executor.execute(() -> {
            try {
                BilibiliFeedProvider.FeedItem rootItem = BilibiliFeedProvider.root(url);
                List<BilibiliFeedProvider.FeedItem> related = new ArrayList<>();
                try {
                    related = BilibiliFeedProvider.related(rootItem);
                } catch (Throwable ignored) {
                    // 相关列表失败不阻断首播
                }
                final List<BilibiliFeedProvider.FeedItem> relatedFinal = related;
                main.post(() -> {
                    if (destroyed) return;
                    seenKeys.add(rootItem.key());
                    items.add(rootItem);
                    items.addAll(BilibiliFeedProvider.filterNew(relatedFinal, seenKeys));
                    rootFailed = false;
                    adapter.notifyDataSetChanged();
                    bind(0);
                    ensureResolved(1);
                });
            } catch (Throwable t) {
                main.post(() -> {
                    if (destroyed) return;
                    rootFailed = true;
                    GlassToast.makeText(VideoFeedActivity.this,
                            "加载失败：" + friendly(t), GlassToast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadMore() {
        if (loadingMore || items.isEmpty()) return;
        loadingMore = true;
        final BilibiliFeedProvider.FeedItem anchor = items.get(items.size() - 1);
        executor.execute(() -> {
            try {
                List<BilibiliFeedProvider.FeedItem> more =
                        BilibiliFeedProvider.filterNew(BilibiliFeedProvider.related(anchor), seenKeys);
                main.post(() -> {
                    if (destroyed || more.isEmpty()) return;
                    items.addAll(more);
                    adapter.notifyDataSetChanged();
                });
            } catch (Throwable ignored) {
            } finally {
                loadingMore = false;
            }
        });
    }

    // ===== 播放绑定 =====

    private void bind(int position) {
        if (position < 0 || position >= items.size()) return;
        BilibiliFeedProvider.FeedItem item = items.get(position);
        FeedAdapter.Holder holder = adapter.holderAt(position);
        if (holder != null) {
            if (player != null && holder.playerView.getPlayer() != player) {
                holder.playerView.setPlayer(player);
            }
            holder.title.setText(item.title == null || item.title.isEmpty()
                    ? item.webUrl : item.title);
            holder.uploader.setVisibility(item.uploader == null || item.uploader.isEmpty()
                    ? View.GONE : View.VISIBLE);
            holder.uploader.setText(item.uploader == null ? "" : "@" + item.uploader);
        }
        if (item.resolved) {
            attachSource(item, position);
        } else if (item.error != null) {
            showError(position, item.error);
        } else {
            showLoading(position, true);
            ensureResolved(position);
        }
    }

    private void ensureResolved(int position) {
        if (position < 0 || position >= items.size() || destroyed) return;
        final BilibiliFeedProvider.FeedItem item = items.get(position);
        if (item.resolved || item.error != null || resolving.contains(position)) return;
        resolving.add(position);
        executor.execute(() -> {
            try {
                BilibiliFeedProvider.resolve(item);
                main.post(() -> {
                    resolving.remove(position);
                    if (destroyed) return;
                    showLoading(position, false);
                    if (position == currentPos) {
                        attachSource(item, position);
                    }
                });
            } catch (Throwable t) {
                item.error = friendly(t);
                main.post(() -> {
                    resolving.remove(position);
                    if (destroyed) return;
                    showLoading(position, false);
                    if (position == currentPos) showError(position, item.error);
                });
            }
        });
    }

    private void attachSource(BilibiliFeedProvider.FeedItem item, int position) {
        if (destroyed || player == null) return;
        FeedAdapter.Holder holder = adapter.holderAt(position);
        if (holder == null || position != currentPos) return;
        // 关键：初始 bind(0) 时 holder 可能早于 currentPos 赋值完成绑定（当时 setPlayer(null)），
        // 这里必须重新挂载，否则解码运行但画面不渲染（黑屏）
        if (holder.playerView.getPlayer() != player) holder.playerView.setPlayer(player);
        holder.error.setVisibility(View.GONE);
        try {
            DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(30_000)
                    .setDefaultRequestProperties(item.headers != null
                            ? item.headers : Collections.<String, String>emptyMap());
            MediaSource videoSource = item.hls
                    ? new HlsMediaSource.Factory(factory)
                            .createMediaSource(MediaItem.fromUri(Uri.parse(item.videoUrl)))
                    : new ProgressiveMediaSource.Factory(factory)
                            .createMediaSource(MediaItem.fromUri(Uri.parse(item.videoUrl)));
            if (item.audioUrl != null && !item.audioUrl.isEmpty()) {
                // B站 DASH：音视频轨合并播放（同 Referer 头）
                MediaSource audioSource = new ProgressiveMediaSource.Factory(factory)
                        .createMediaSource(MediaItem.fromUri(Uri.parse(item.audioUrl)));
                videoSource = new MergingMediaSource(videoSource, audioSource);
            }
            player.setMediaSource(videoSource, /* resetPosition= */ true);
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (Exception e) {
            showError(position, "构建播放源失败：" + e.getMessage());
        }
    }

    private void retry(int position) {
        if (position < 0 || position >= items.size()) return;
        BilibiliFeedProvider.FeedItem item = items.get(position);
        item.error = null;
        if (rootFailed) {
            // 根视频加载失败整页重试
            rootFailed = false;
            bootstrap();
            return;
        }
        showLoading(position, true);
        ensureResolved(position);
        if (position == currentPos && item.resolved) attachSource(item, position);
    }

    // ===== 页面状态 =====

    private void showLoading(int position, boolean show) {
        FeedAdapter.Holder holder = adapter.holderAt(position);
        if (holder != null) {
            holder.loading.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) holder.error.setVisibility(View.GONE);
        }
    }

    private void showError(int position, String message) {
        FeedAdapter.Holder holder = adapter.holderAt(position);
        if (holder != null) {
            holder.loading.setVisibility(View.GONE);
            holder.error.setVisibility(View.VISIBLE);
            holder.error.setText((message == null ? "未知错误" : message) + "\n（点击重试）");
        }
    }

    private void syncPauseIcon() {
        FeedAdapter.Holder holder = adapter.holderAt(currentPos);
        if (holder == null || player == null) return;
        boolean paused = player.getPlaybackState() == Player.STATE_IDLE
                || !player.getPlayWhenReady();
        holder.pauseIcon.setVisibility(paused ? View.VISIBLE : View.GONE);
    }

    private void updateProgress() {
        FeedAdapter.Holder holder = adapter.holderAt(currentPos);
        if (holder == null || player == null) return;
        long duration = player.getDuration();
        if (duration <= 0) {
            holder.progress.setVisibility(View.GONE);
            return;
        }
        holder.progress.setVisibility(View.VISIBLE);
        holder.progress.setProgress((int) (player.getCurrentPosition() * 1000 / duration));
    }

    private void togglePlayPause() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_IDLE) {
            bind(currentPos);
            return;
        }
        player.setPlayWhenReady(!player.getPlayWhenReady());
    }

    private static String friendly(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.isEmpty() ? t.getClass().getSimpleName() : msg;
    }

    // ===== 生命周期 =====

    @Override
    protected void onPause() {
        if (player != null) player.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null && currentPos >= 0) player.play();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacks(progressTicker);
        if (player != null) {
            player.release();
            player = null;
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    // ===== Adapter =====

    private final class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.Holder> {

        private final Map<Integer, Holder> holders = new HashMap<>();

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.view_feed_page, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holders.put(position, holder);
            holder.error.setOnClickListener(v -> retry(position));
            holder.playerView.setOnClickListener(v -> togglePlayPause());
            // 单 Player 复用：仅当前页挂载播放器
            if (position == currentPos && player != null) {
                holder.playerView.setPlayer(player);
                syncPauseIcon();
            } else {
                holder.playerView.setPlayer(null);
            }
            BilibiliFeedProvider.FeedItem item = items.get(position);
            holder.title.setText(item.title == null || item.title.isEmpty()
                    ? item.webUrl : item.title);
            holder.uploader.setVisibility(item.uploader == null || item.uploader.isEmpty()
                    ? View.GONE : View.VISIBLE);
            holder.uploader.setText(item.uploader == null ? "" : "@" + item.uploader);
            holder.error.setVisibility(item.error != null ? View.VISIBLE : View.GONE);
            holder.error.setText(item.error == null ? "" : item.error + "\n（点击重试）");
            holder.loading.setVisibility(item.resolved || item.error != null
                    ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onViewRecycled(@NonNull Holder holder) {
            holder.playerView.setPlayer(null);
            holders.values().remove(holder);
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        Holder holderAt(int position) {
            return holders.get(position);
        }

        final class Holder extends RecyclerView.ViewHolder {
            final PlayerView playerView;
            final ImageView pauseIcon;
            final ProgressBar loading;
            final TextView error;
            final TextView title;
            final TextView uploader;
            final ProgressBar progress;

            Holder(@NonNull View v) {
                super(v);
                playerView = v.findViewById(R.id.feed_player);
                pauseIcon = v.findViewById(R.id.feed_pause_icon);
                loading = v.findViewById(R.id.feed_loading);
                error = v.findViewById(R.id.feed_error);
                title = v.findViewById(R.id.feed_title);
                uploader = v.findViewById(R.id.feed_uploader);
                progress = v.findViewById(R.id.feed_progress);
                FrameLayout root = (FrameLayout) v;
                root.setOnClickListener(null);
            }
        }
    }
}
