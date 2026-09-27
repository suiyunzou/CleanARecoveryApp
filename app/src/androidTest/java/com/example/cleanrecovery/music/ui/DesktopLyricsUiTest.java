package com.example.cleanrecovery.music.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.Lyrics;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.player.DesktopLyricsController;
import com.example.cleanrecovery.music.player.DesktopLyricsSettings;
import com.example.cleanrecovery.music.player.MusicPlayer;
import com.example.cleanrecovery.music.player.MusicService;
import com.example.cleanrecovery.music.player.PlaybackQueue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real system overlay and input routing, with local lyric fixtures (no network dependency). */
@RunWith(AndroidJUnit4.class)
public class DesktopLyricsUiTest {
    @Rule public GrantPermissionRule notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void mediaSessionAndNotificationCanUnlockLyrics() throws Exception {
        Context context = instrumentation.getTargetContext();
        assertTrue(Settings.canDrawOverlays(context));
        SharedPreferences prefs = DesktopLyricsSettings.get(context);
        Map<String, ?> saved = prefs.getAll();
        MusicPlayerActivity activity = (MusicPlayerActivity) instrumentation.startActivitySync(
                new Intent(context, MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PlaybackQueue queue = main(() -> (PlaybackQueue) field(MusicPlayer.get(), "queue"));
        PlaybackQueue.Snapshot previous = main(queue::snapshot);
        List<PlaybackQueue.Snapshot> history = main(queue::history);
        try {
            main(() -> {
                SongInfo song = new SongInfo(); song.hash = ""; song.title = "桌面歌词测试"; song.artist = "本地测试";
                queue.replace(Collections.singletonList(song), 0, "UNKNOWN", "", null);
                activity.onSongChanged(song);
                activity.findViewById(com.example.cleanrecovery.R.id.player_more_button).performClick();
                return null;
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(450);
            screenshot("desktop-lyrics-menu.png");
            assertFalse(instrumentation.getUiAutomation().getRootInActiveWindow()
                    .findAccessibilityNodeInfosByText(context.getString(com.example.cleanrecovery.R.string.music_desktop_lyrics)).isEmpty());
            pressBack();
            SystemClock.sleep(400);
            main(() -> { DesktopLyricsDialog.show(activity); return null; });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(450);
            screenshot("desktop-lyrics-settings.png");
            pressBack();
            main(() -> {
                prefs.edit().putBoolean(DesktopLyricsSettings.ENABLED, true)
                        .putBoolean(DesktopLyricsSettings.LOCKED, true).commit();
                activity.startService(new Intent(activity, MusicService.class));
                return null;
            });
            instrumentation.waitForIdleSync();
            instrumentation.getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
            android.media.session.MediaController media = null;
            android.media.session.MediaSessionManager sessions = context.getSystemService(android.media.session.MediaSessionManager.class);
            for (int i = 0; i < 30 && media == null; i++) {
                for (android.media.session.MediaController candidate : sessions.getActiveSessions(null)) {
                    if (context.getPackageName().equals(candidate.getPackageName())) media = candidate;
                }
                if (media == null) SystemClock.sleep(100);
            }
            assertNotNull("The actual music service must publish a media session", media);
            boolean hasUnlock = false;
            for (android.media.session.PlaybackState.CustomAction action : media.getPlaybackState().getCustomActions()) {
                if (MusicService.ACTION_UNLOCK_LYRICS.equals(action.getAction())) hasUnlock = true;
            }
            assertTrue("Android 13+ must receive the unlock action through PlaybackState", hasUnlock);
            media.getTransportControls().sendCustomAction(MusicService.ACTION_UNLOCK_LYRICS, null);
            for (int i = 0; i < 30 && prefs.getBoolean(DesktopLyricsSettings.LOCKED, false); i++) SystemClock.sleep(100);
            assertFalse(prefs.getBoolean(DesktopLyricsSettings.LOCKED, true));
            main(() -> { prefs.edit().putBoolean(DesktopLyricsSettings.LOCKED, true).commit(); return null; });
            instrumentation.waitForIdleSync();
            boolean sent = false;
            for (android.service.notification.StatusBarNotification notification :
                    context.getSystemService(android.app.NotificationManager.class).getActiveNotifications()) {
                if (notification.getId() != 1001) continue;
                for (android.app.Notification.Action action : notification.getNotification().actions) {
                    if (context.getString(com.example.cleanrecovery.R.string.desktop_lyrics_unlock).contentEquals(action.title)) {
                        action.actionIntent.send(); sent = true;
                    }
                }
            }
            assertTrue("Legacy notification must include a working unlock PendingIntent", sent);
            for (int i = 0; i < 30 && prefs.getBoolean(DesktopLyricsSettings.LOCKED, false); i++) SystemClock.sleep(100);
            assertFalse(prefs.getBoolean(DesktopLyricsSettings.LOCKED, true));
        } finally {
            instrumentation.getUiAutomation().dropShellPermissionIdentity();
            main(() -> { context.stopService(new Intent(context, MusicService.class)); return null; });
            instrumentation.waitForIdleSync();
            main(() -> { queue.restore(previous, history); restore(prefs, saved); activity.finish(); return null; });
        }
    }

    @Test public void overlaySurvivesPageAndLockedTouchesReachUnderlyingWindow() throws Exception {
        Context context = instrumentation.getTargetContext();
        assertTrue("Grant SYSTEM_ALERT_WINDOW app-op before running this test", Settings.canDrawOverlays(context));
        SharedPreferences prefs = DesktopLyricsSettings.get(context);
        Map<String, ?> saved = prefs.getAll();
        MusicPlayerActivity activity = (MusicPlayerActivity) instrumentation.startActivitySync(
                new Intent(context, MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DesktopLyricsController[] controller = new DesktopLyricsController[1];
        PlaybackQueue queue = main(() -> (PlaybackQueue) field(MusicPlayer.get(), "queue"));
        PlaybackQueue.Snapshot previous = main(queue::snapshot);
        List<PlaybackQueue.Snapshot> history = main(queue::history);
        AtomicInteger taps = new AtomicInteger();
        try {
            main(() -> {
                FrameLayout target = new FrameLayout(activity);
                target.setBackgroundColor(0xFFF0F4F2);
                target.setOnClickListener(v -> taps.incrementAndGet());
                TextView explanation = new TextView(activity);
                explanation.setText("桌面歌词测试：锁定后，下方页面仍可点击");
                explanation.setTextSize(20);
                FrameLayout.LayoutParams caption = new FrameLayout.LayoutParams(-1, -2);
                caption.topMargin = 650;
                target.addView(explanation, caption);
                activity.addContentView(target, new android.view.ViewGroup.LayoutParams(-1, -1));
                SongInfo song = new SongInfo(); song.hash = "desktop-fixture-a"; song.title = "桌面歌词测试"; song.artist = "本地测试";
                @SuppressWarnings("unchecked") Map<String, Lyrics> cache = (Map<String, Lyrics>) field(MusicApp.get().lyrics, "cache");
                cache.put(song.hash, Lyrics.parse("[00:00]第一句歌词\n[00:05]第二句歌词\n[00:10]第三句歌词"));
                queue.replace(Collections.singletonList(song), 0, "UNKNOWN", "", null);
                prefs.edit().clear().putBoolean(DesktopLyricsSettings.ENABLED, true)
                        .putBoolean(DesktopLyricsSettings.LOCKED, false)
                        .putInt(DesktopLyricsSettings.COLOR, 1).commit();
                controller[0] = new DesktopLyricsController(context, () -> {});
                return null;
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(350);
            main(() -> {
                assertNotNull(field(controller[0], "root"));
                android.widget.LinearLayout overlay = (android.widget.LinearLayout) field(controller[0], "root");
                assertEquals("The settings bar is hidden during normal lyric display", View.GONE,
                        ((View) field(controller[0], "settingsBar")).getVisibility());
                assertEquals(0, android.graphics.Color.alpha(((android.graphics.drawable.ColorDrawable) overlay.getBackground()).getColor()));
                TextView primary = (TextView) field(controller[0], "primary");
                assertEquals("第一句歌词", primary.getText().toString());
                invoke(controller[0], "render", new Class<?>[]{int.class}, 6000);
                assertEquals("第二句歌词", primary.getText().toString());
                invoke(controller[0], "render", new Class<?>[]{int.class}, 1000);
                assertEquals("第一句歌词", primary.getText().toString());
                return null;
            });
            int[] start = main(() -> center((View) field(controller[0], "primary")));
            tap(start[0], start[1]);
            instrumentation.waitForIdleSync();
            assertEquals(View.VISIBLE, (int) main(() -> ((View) field(controller[0], "settingsBar")).getVisibility()));
            main(() -> {
                TextView primary = (TextView) field(controller[0], "primary");
                TextView preview = (TextView) field(controller[0], "preview");
                View bar = (View) field(controller[0], "settingsBar");
                assertEquals(View.GONE, primary.getVisibility());
                assertEquals(View.VISIBLE, preview.getVisibility());
                assertEquals(primary.getText().toString(), preview.getText().toString());
                assertEquals("Controls start at the original lyric top", ((View) field(controller[0], "root")).getPaddingTop(), bar.getTop());
                invoke(controller[0], "render", new Class<?>[]{int.class}, 6000);
                assertEquals(primary.getText().toString(), preview.getText().toString());
                invoke(controller[0], "render", new Class<?>[]{int.class}, 1000);
                return null;
            });
            assertEquals(4, (int) main(() -> ((android.view.ViewGroup) field(controller[0], "settingsBar")).getChildCount()));
            screenshot("desktop-lyrics-quick-settings.png");
            SystemClock.sleep(2200);
            assertEquals("Two idle seconds should restore lyrics-only display", View.GONE,
                    (int) main(() -> ((View) field(controller[0], "settingsBar")).getVisibility()));
            assertEquals(View.VISIBLE, (int) main(() -> ((View) field(controller[0], "primary")).getVisibility()));
            assertEquals(View.GONE, (int) main(() -> ((View) field(controller[0], "preview")).getVisibility()));
            tap(start[0], start[1]);
            int[] settings = main(() -> center(((android.view.ViewGroup) field(controller[0], "settingsBar")).getChildAt(3)));
            tap(settings[0], settings[1]);
            instrumentation.waitForIdleSync();
            assertTrue(main(() -> ((android.app.AlertDialog) field(controller[0], "settingsDialog")).isShowing()));
            SystemClock.sleep(500);
            android.view.accessibility.AccessibilityNodeInfo dialogRoot = instrumentation.getUiAutomation().getRootInActiveWindow();
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> teal = dialogRoot.findAccessibilityNodeInfosByText(
                    context.getString(com.example.cleanrecovery.R.string.music_lyrics_theme_teal));
            assertFalse("Overlay settings must be visible above another app", teal.isEmpty());
            android.graphics.Rect choiceBounds = new android.graphics.Rect();
            teal.get(0).getBoundsInScreen(choiceBounds);
            tap(choiceBounds.centerX(), choiceBounds.centerY());
            assertEquals(2, prefs.getInt(DesktopLyricsSettings.COLOR, 0));
            SystemClock.sleep(200);
            screenshot("desktop-lyrics-overlay-dialog.png");
            main(() -> { ((android.app.AlertDialog) field(controller[0], "settingsDialog")).dismiss(); return null; });
            start = main(() -> center((View) field(controller[0], "primary")));
            longDrag(start[0], start[1], start[0] + 40, start[1] + 180);
            assertTrue(main(() -> prefs.contains("y_" + context.getResources().getConfiguration().orientation)));
            screenshot("desktop-lyrics-unlocked.png");
            main(() -> { prefs.edit().putBoolean(DesktopLyricsSettings.LOCKED, true).commit(); return null; });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(350);
            int[] hit = main(() -> {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) field(controller[0], "params");
                assertTrue((params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
                assertTrue(params.alpha <= .8f);
                return center((View) field(controller[0], "primary"));
            });
            tap(hit[0], hit[1]);
            instrumentation.waitForIdleSync();
            assertEquals("Android must deliver the tap through the overlay to the underlying app", 1, taps.get());
            screenshot("desktop-lyrics-locked.png");
            Context testContext = instrumentation.getContext();
            assertNotEquals("The touch target must belong to a different UID", context.getApplicationInfo().uid,
                    testContext.getApplicationInfo().uid);
            context.startActivity(new Intent().setComponent(new android.content.ComponentName(testContext.getPackageName(),
                    OverlayTouchTargetActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(600);
            assertFalse(instrumentation.getUiAutomation().getRootInActiveWindow()
                    .findAccessibilityNodeInfosByText("Cross-app touch target").isEmpty());
            tap(hit[0], hit[1]);
            SystemClock.sleep(250);
            assertFalse("Touch must also pass through Android's cross-UID security check",
                    instrumentation.getUiAutomation().getRootInActiveWindow()
                            .findAccessibilityNodeInfosByText("Cross-app touch received").isEmpty());
            screenshot("desktop-lyrics-cross-app.png");
            pressBack();
            main(() -> { activity.finish(); return null; });
            instrumentation.waitForIdleSync();
            assertTrue(main(() -> ((View) field(controller[0], "root")).isAttachedToWindow()));
            main(() -> {
                SongInfo next = new SongInfo(); next.hash = "desktop-fixture-b"; next.title = "下一首";
                @SuppressWarnings("unchecked") Map<String, Lyrics> cache = (Map<String, Lyrics>) field(MusicApp.get().lyrics, "cache");
                cache.put(next.hash, Lyrics.parse("[00:00]后台切歌成功"));
                queue.replace(Collections.singletonList(next), 0, "UNKNOWN", "", null);
                controller[0].onSongChanged(next);
                return null;
            });
            instrumentation.waitForIdleSync();
            assertEquals("后台切歌成功", main(() -> ((TextView) field(controller[0], "primary")).getText().toString()));
            String longLine = "让这一句很长很长的歌词完整地显示在屏幕上，无论是横屏游戏还是竖屏聊天，都要自然换行，不能省略文字，也不能显示下一句。";
            main(() -> {
                SongInfo next = new SongInfo(); next.hash = "desktop-fixture-long"; next.title = "长句";
                @SuppressWarnings("unchecked") Map<String, Lyrics> cache = (Map<String, Lyrics>) field(MusicApp.get().lyrics, "cache");
                cache.put(next.hash, Lyrics.parse("[00:00]" + longLine + "\n[00:30]这句不应显示"));
                queue.replace(Collections.singletonList(next), 0, "UNKNOWN", "", null);
                controller[0].onSongChanged(next);
                return null;
            });
            context.startActivity(new Intent().setComponent(new android.content.ComponentName(testContext.getPackageName(),
                    OverlayTouchTargetActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertTrue(instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0));
            SystemClock.sleep(800);
            assertWrappedInsideScreen(controller[0], longLine);
            int portraitWidth = main(() -> ((View) field(controller[0], "root")).getWidth());
            int portraitY = main(() -> ((WindowManager.LayoutParams) field(controller[0], "params")).y);
            screenshot("desktop-lyrics-long-portrait.png");
            assertTrue(instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90));
            SystemClock.sleep(1000);
            assertWrappedInsideScreen(controller[0], longLine);
            assertTrue("Landscape should use the extra horizontal space",
                    main(() -> ((View) field(controller[0], "root")).getWidth()) > portraitWidth);
            screenshot("desktop-lyrics-long-landscape.png");
            assertTrue(instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0));
            SystemClock.sleep(800);
            assertEquals("Returning to portrait restores its saved position", portraitY,
                    (int) main(() -> ((WindowManager.LayoutParams) field(controller[0], "params")).y));
            pressBack();
            main(() -> {
                invoke(controller[0], "render", new Class<?>[]{int.class}, -1);
                assertEquals("No title, preview or loading placeholder before a lyric starts", "",
                        ((TextView) field(controller[0], "primary")).getText().toString());
                assertEquals(View.INVISIBLE, ((View) field(controller[0], "root")).getVisibility());
                prefs.edit().putBoolean(DesktopLyricsSettings.ENABLED, false).commit();
                assertNull(field(controller[0], "root"));
                return null;
            });
        } finally {
            instrumentation.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
            main(() -> {
                if (controller[0] != null) controller[0].close();
                queue.restore(previous, history);
                restore(prefs, saved);
                if (!activity.isFinishing()) activity.finish();
                return null;
            });
        }
    }

    private void assertWrappedInsideScreen(DesktopLyricsController controller, String text) throws Exception {
        main(() -> {
            TextView view = (TextView) field(controller, "primary");
            assertEquals(text, view.getText().toString());
            assertTrue("Long sentences must wrap", view.getLineCount() > 1);
            for (int i = 0; i < view.getLineCount(); i++) assertEquals(0, view.getLayout().getEllipsisCount(i));
            assertEquals(text.length(), view.getLayout().getLineEnd(view.getLineCount() - 1));
            View root = (View) field(controller, "root");
            int[] xy = new int[2]; root.getLocationOnScreen(xy);
            android.graphics.Point screen = new android.graphics.Point();
            instrumentation.getTargetContext().getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(screen);
            assertTrue(xy[0] >= 0 && xy[1] >= 0);
            assertTrue(xy[0] + root.getWidth() <= screen.x);
            assertTrue(xy[1] + root.getHeight() <= screen.y);
            return null;
        });
    }

    private static void restore(SharedPreferences prefs, Map<String, ?> saved) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            if (entry.getValue() instanceof Boolean) edit.putBoolean(entry.getKey(), (Boolean) entry.getValue());
            if (entry.getValue() instanceof Integer) edit.putInt(entry.getKey(), (Integer) entry.getValue());
        }
        edit.commit();
    }

    private int[] center(View view) {
        int[] xy = new int[2]; view.getLocationOnScreen(xy);
        xy[0] += view.getWidth() / 2; xy[1] += view.getHeight() / 2;
        return xy;
    }
    private void tap(int x, int y) { swipe(x, y, x, y); }
    private void longDrag(int x, int y, int toX, int toY) {
        long downTime = SystemClock.uptimeMillis();
        injectTouch(downTime, MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 150);
        injectTouch(downTime, MotionEvent.ACTION_MOVE, toX, toY);
        injectTouch(downTime, MotionEvent.ACTION_UP, toX, toY);
    }
    private void injectTouch(long downTime, int action, int x, int y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); event.recycle();
    }
    private void swipe(int x, int y, int toX, int toY) {
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 1 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_UP;
            MotionEvent event = MotionEvent.obtain(now, now + i * 100, action, i == 0 ? x : toX, i == 0 ? y : toY, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); event.recycle();
        }
    }
    private void pressBack() {
        assertTrue(instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
    }
    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); instrumentation.runOnMainSync(task); return task.get();
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void invoke(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(object, args);
    }
    private void screenshot(String name) throws Exception {
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "desktop-lyrics-test");
        directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        image.recycle();
    }
}
