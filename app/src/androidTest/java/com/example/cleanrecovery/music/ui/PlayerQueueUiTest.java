package com.example.cleanrecovery.music.ui;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.player.MusicPlayer;
import com.example.cleanrecovery.music.player.PlaybackQueue;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real MediaPlayer + queue panel; local silence avoids network/entitlement dependencies. */
@RunWith(AndroidJUnit4.class)
public class PlayerQueueUiTest {
    @Rule public GrantPermissionRule notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void pendingSearchHistoryClearAndSessionRecovery() throws Exception {
        Context context = instrumentation.getTargetContext();
        MusicApp app = main(() -> MusicApp.init(context));
        MusicPlayer player = app.player;
        SharedPreferences prefs = context.getSharedPreferences("music_player_prefs", Context.MODE_PRIVATE);
        main(() -> { player.pause(); return null; });
        String existing = prefs.getString("last_playback_session", null);
        // A previous instrumentation process may have crashed before finally could run.
        String saved = existing != null && existing.contains("Queue fixture") ? null : existing;
        File wav = new File(context.getCacheDir(), "queue-test.wav");
        writeSilence(wav);
        SongInfo a = song("Queue A", wav), b = song("Queue B", wav), c = song("Queue C", wav);
        // Separate local paths identify recordings without an online hash lookup.
        File other = new File(context.getCacheDir(), "queue-test-other.wav");
        writeSilence(other);
        SongInfo x = song("Queue X", other);
        MusicPlayerActivity activity = null;
        try {
            main(() -> {
                player.release();
                PlaybackQueue queue = (PlaybackQueue) field(player, "queue");
                queue.restore(new PlaybackQueue().snapshot(), Collections.emptyList());
                player.setMode(MusicPlayer.Mode.SEQUENTIAL);
                player.play(Arrays.asList(a, b, c), 0, MusicPlayer.PlaySource.LOCAL_PLAYLIST, "Fixture playlist", null);
                return null;
            });
            awaitPlaying(player);
            main(() -> { player.pause(); player.playNext(Arrays.asList(b, c)); return null; });
            assertEquals(5, main(() -> player.getQueue().size()).intValue());
            main(() -> { player.playSearch(x, "Search x"); return null; });
            awaitPlaying(player);
            main(() -> {
                player.pause();
                assertEquals(1, player.getQueueIndex()); assertSame(x, player.currentSong());
                assertEquals("Fixture playlist", player.getPlaySourceName());
                player.setMode(MusicPlayer.Mode.SHUFFLE); player.next(); return null;
            });
            awaitPlaying(player);
            main(() -> {
                player.pause(); assertSame(b, player.currentSong()); assertEquals(2, player.getQueueIndex());
                assertFalse(player.isPending(2)); assertTrue(player.isPending(3));
                return null;
            });

            activity = (MusicPlayerActivity) instrumentation.startActivitySync(new Intent(context, MusicPlayerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MusicPlayerActivity screen = activity;
            main(() -> {
                set(screen, "cloudVipKeys", Collections.emptySet());
                invoke(screen, "showQueueSheet"); return null;
            });
            instrumentation.waitForIdleSync(); SystemClock.sleep(500);
            main(() -> {
                RecyclerView.Adapter<?> adapter = (RecyclerView.Adapter<?>) field(screen, "queueAdapter");
                assertEquals(6, adapter.getItemCount());
                Dialog dialog = (Dialog) field(screen, "queueDialog");
                assertNotNull(findDescription(dialog.getWindow().getDecorView(), context.getString(R.string.music_play_next)));
                player.play(Collections.singletonList(x), 0, MusicPlayer.PlaySource.SEARCH, "Other queue");
                return null;
            });
            awaitPlaying(player);
            main(() -> { player.pause(); return null; });
            instrumentation.waitForIdleSync();
            main(() -> {
                player.pause();
                RecyclerView.Adapter<?> pager = (RecyclerView.Adapter<?>) field(screen, "queuePagerAdapter");
                assertEquals(2, pager.getItemCount());
                player.playHistory(player.getQueueHistory().get(0), 4);
                return null;
            });
            awaitPlaying(player);
            main(() -> {
                player.pause(); assertEquals(6, player.getQueue().size()); assertEquals(4, player.getQueueIndex());
                player.release(); player.restoreLastSession(context);
                assertEquals(6, player.getQueue().size()); assertTrue(player.isPending(3));
                assertEquals(4, player.getQueueIndex());
                return null;
            });
            instrumentation.waitForIdleSync(); SystemClock.sleep(400);
            main(() -> {
                Dialog dialog = (Dialog) field(screen, "queueDialog");
                assertTrue(findDescription(dialog.getWindow().getDecorView(), context.getString(R.string.music_queue_clear)).performClick());
                assertEquals("Opening confirmation must not clear the queue", 6, player.getQueue().size());
                return null;
            });
            instrumentation.waitForIdleSync();
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            assertNotNull(root);
            java.util.List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId("android:id/button1");
            assertFalse(buttons.isEmpty());
            assertTrue(buttons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK));
            instrumentation.waitForIdleSync(); SystemClock.sleep(500);
            main(() -> {
                assertTrue(player.getQueue().isEmpty());
                assertEquals(context.getString(R.string.music_queue_empty),
                        ((TextView) screen.findViewById(R.id.player_song_title)).getText().toString());
                assertEquals(0, ((RecyclerView.Adapter<?>) field(screen, "queueAdapter")).getItemCount());
                player.restoreLastSession(context);
                assertTrue(player.getQueue().isEmpty()); assertEquals(MusicPlayer.State.IDLE, player.getState());
                assertFalse(player.getQueueHistory().isEmpty());
                return null;
            });
        } finally {
            MusicPlayerActivity screen = activity;
            main(() -> {
                if (screen != null) screen.finish();
                player.release();
                ((PlaybackQueue) field(player, "queue")).restore(new PlaybackQueue().snapshot(), Collections.emptyList());
                prefs.edit().putString("last_playback_session", saved).commit();
                player.restoreLastSession(context);
                return null;
            });
            wav.delete(); other.delete();
        }
    }

    private void awaitPlaying(MusicPlayer player) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main(player::getState) == MusicPlayer.State.PLAYING) return;
            SystemClock.sleep(50);
        }
        fail("Local audio did not reach PLAYING: " + main(player::getState));
    }
    private static SongInfo song(String title, File file) {
        SongInfo song = new SongInfo(); song.title = title; song.artist = "Queue fixture";
        song.hash = "";
        song.localPath = file.getAbsolutePath(); song.duration = 30; return song;
    }
    private static void writeSilence(File file) throws Exception {
        int size = 8000 * 2 * 30;
        ByteBuffer data = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes()).putInt(36 + size).put("WAVEfmt ".getBytes()).putInt(16);
        data.putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        data.put("data".getBytes()).putInt(size);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(data.array()); }
    }
    private static View findDescription(View view, String text) {
        if (text.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); instrumentation.runOnMainSync(task); return task.get();
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }
}
