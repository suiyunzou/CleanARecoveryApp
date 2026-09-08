package com.example.cleanrecovery.music.ui;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.api.KrcParser;
import com.example.cleanrecovery.music.data.Lyrics;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

/** Uses an actual downloaded KRC fixture without changing the user's playback queue. */
@RunWith(AndroidJUnit4.class)
public class PlayerLyricsUiTest {
    @Rule public GrantPermissionRule notifications = GrantPermissionRule.grant(
            android.Manifest.permission.POST_NOTIFICATIONS);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void wordHighlightTranslationAndDismissal() throws Exception {
        byte[] raw;
        try (java.io.InputStream input = instrumentation.getContext().getAssets().open("jar-of-love.krc.txt")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            raw = bytes.toByteArray();
        }
        Lyrics lyrics = KrcParser.parse(new String(raw, StandardCharsets.UTF_8));
        int active = lyrics.indexOfActive(15800);
        assertEquals("Why do", lyrics.lines().get(active).text.substring(0,
                lyrics.lines().get(active).sungTextEnd(15800)));
        assertFalse(lyrics.lines().get(active).translation.isEmpty());
        Intent intent = new Intent(instrumentation.getTargetContext(), MusicPlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MusicPlayerActivity activity = (MusicPlayerActivity) instrumentation.startActivitySync(intent);
        try {
            main(() -> {
                // Isolate deterministic rendering from any outstanding metadata callbacks.
                set(activity, "lyricsRequestId", ((Integer) field(activity, "lyricsRequestId")) + 1);
                set(activity, "currentLyrics", lyrics);
                activity.findViewById(R.id.player_cover_panel).setVisibility(View.GONE);
                activity.findViewById(R.id.player_lyrics_panel).setVisibility(View.VISIBLE);
                invoke(activity, "renderLyrics", new Class<?>[]{Lyrics.class}, lyrics);
                ((TextView) activity.findViewById(R.id.player_song_title)).setText("Jar Of Love (Album Version)");
                ((TextView) activity.findViewById(R.id.player_song_artist)).setText("曲婉婷");
                LyricsView view = activity.findViewById(R.id.player_lyrics_view);
                assertNull("Timed highlighting must not cross-fade old and new holders", view.getItemAnimator());
                view.setTheme(LyricsView.Theme.DARK);
                view.updatePosition(15800);
                set(activity, "climaxStartMs", 30800L);
                activity.onProgressChanged(15800, 230000);
                return null;
            });
            SystemClock.sleep(700);
            main(() -> {
                LyricsView view = activity.findViewById(R.id.player_lyrics_view);
                TextView original = view.findViewHolderForAdapterPosition(active).itemView.findViewById(R.id.lyric_line_text);
                Spanned text = (Spanned) original.getText();
                ForegroundColorSpan[] spans = text.getSpans(0, text.length(), ForegroundColorSpan.class);
                assertEquals(1, spans.length);
                assertEquals("Why do", text.subSequence(0, text.getSpanEnd(spans[0])).toString());
                TextView toggle = activity.findViewById(R.id.player_lyrics_translate);
                assertFalse(toggle.isSelected());
                assertTrue(toggle.performClick());
                assertEquals(Color.BLACK, toggle.getCurrentTextColor());
                return null;
            });
            SystemClock.sleep(700);
            screenshot("bilingual.png");
            main(() -> {
                LyricsView view = activity.findViewById(R.id.player_lyrics_view);
                View row = view.findViewHolderForAdapterPosition(active).itemView;
                assertEquals(View.VISIBLE, row.findViewById(R.id.lyric_line_translation).getVisibility());
                activity.findViewById(R.id.player_lyrics_translate).performClick();
                return null;
            });
            instrumentation.waitForIdleSync();
            main(() -> {
                LyricsView view = activity.findViewById(R.id.player_lyrics_view);
                assertEquals(View.GONE, view.findViewHolderForAdapterPosition(active).itemView
                        .findViewById(R.id.lyric_line_translation).getVisibility());
                return null;
            });
            screenshot("original.png");
            main(() -> {
                MusicSeekBar bar = activity.findViewById(R.id.player_seekbar);
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                        bar.positionX(.25f), bar.getHeight() / 2f, 0);
                bar.dispatchTouchEvent(down); down.recycle();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.player_seek_preview).getVisibility());
                return null;
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(200);
            screenshot("seek-preview.png");
            main(() -> {
                MusicSeekBar bar = activity.findViewById(R.id.player_seekbar);
                View preview = activity.findViewById(R.id.player_seek_preview);
                assertTrue(preview.getX() >= bar.getX());
                assertTrue(preview.getX() + preview.getWidth() <= bar.getX() + bar.getWidth());
                long now = SystemClock.uptimeMillis();
                MotionEvent up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP,
                        bar.positionX(.25f), bar.getHeight() / 2f, 0);
                bar.dispatchTouchEvent(up); up.recycle();
                assertEquals(View.INVISIBLE, activity.findViewById(R.id.player_seek_preview).getVisibility());
                activity.findViewById(R.id.player_lyrics_settings).performClick();
                Dialog first = (Dialog) field(activity, "lyricsSettingsDialog");
                invoke(activity, "showLyricsSettings", new Class<?>[0]);
                assertFalse("Reopening must not leave an old sheet underneath", first.isShowing());
                return null;
            });
            SystemClock.sleep(500);
            screenshot("settings.png");
            Dialog dialog = main(() -> (Dialog) field(activity, "lyricsSettingsDialog"));
            int[] blank = main(() -> {
                ViewGroup root = dialog.findViewById(android.R.id.content);
                int[] xy = new int[2]; root.getLocationOnScreen(xy);
                xy[0] += root.getWidth() / 2; xy[1] += 150;
                return xy;
            });
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, blank[0], blank[1], 0);
            MotionEvent up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, blank[0], blank[1], 0);
            instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up);
            down.recycle(); up.recycle(); instrumentation.waitForIdleSync();
            assertFalse("A real tap in the blank region must dismiss the sheet", main(dialog::isShowing));
        } finally {
            main(() -> { activity.finish(); return null; });
        }
    }

    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        instrumentation.runOnMainSync(task);
        return task.get();
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(target, args);
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "player-polish");
        directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }
}
