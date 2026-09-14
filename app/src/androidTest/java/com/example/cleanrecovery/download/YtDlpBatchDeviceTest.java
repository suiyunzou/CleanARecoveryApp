package com.example.cleanrecovery.download;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.RadioButton;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.activity.UniversalDownloadActivity;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Exercises the actual Activity and foreground service with isolated output and restored settings. */
public class YtDlpBatchDeviceTest {
    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    final Context context = instrumentation.getTargetContext();

    @Test public void realThreePlatformBatchSelectsQualityAndDownloads() throws Exception {
        isolated(fixture -> {
            Activity activity = launch("https://youtu.be/jNQXAC9IVRw\nhttps://www.bilibili.com/video/BV1GJ411x7h7?p=1\nhttps://www.iesdouyin.com/share/video/6982497745948921092/");
            try {
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.READY, 240000);
                onMain(() -> {
                    assertEquals(3, YtDlpDownloadService.state().jobs.size());
                    for (YtDlpDownloadService.Job job : YtDlpDownloadService.state().jobs) {
                        assertEquals(job.url + ": " + job.error, "", job.error);
                        assertNotNull(job.media);
                        YtDlpMedia.Choice choice = job.media.videos.get(job.media.videos.size() - 1);
                        RadioButton button = popupChoice(activity, job, choice);
                        assertNotNull("Actual per-video quality control", button);
                        button.performClick();
                        assertSame(choice, job.choice);
                    }
                    activity.findViewById(R.id.universal_start_download_button).performClick();
                });
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.COMPLETE, 360000);
                List<YtDlpDownloadService.Job> jobs = new ArrayList<>();
                onMain(() -> jobs.addAll(YtDlpDownloadService.state().jobs));
                for (YtDlpDownloadService.Job job : jobs) {
                    assertEquals(job.url + ": " + job.error, "", job.error);
                    assertNotNull(job.url, job.path);
                    android.media.MediaExtractor extractor = new android.media.MediaExtractor();
                    boolean video = false, audio = false; long duration = 0; int height = 0;
                    try {
                        extractor.setDataSource(job.path);
                        for (int i = 0; i < extractor.getTrackCount(); i++) {
                            android.media.MediaFormat format = extractor.getTrackFormat(i);
                            String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                            video |= mime != null && mime.startsWith("video/");
                            audio |= mime != null && mime.startsWith("audio/");
                            if (format.containsKey("height")) height = format.getInteger("height");
                            if (format.containsKey("durationUs")) duration = Math.max(duration, format.getLong("durationUs"));
                        }
                    } finally { extractor.release(); }
                    assertTrue(video); assertTrue(audio); assertEquals(job.choice.height, height);
                    assertTrue(duration >= job.media.durationSeconds * 980000);
                    android.util.Log.i("YtDlpAcceptance", "Batch verified " + job.url + " " + job.choice.selector + " height=" + height + " durationUs=" + duration);
                }
            } finally { onMain(activity::finish); }
        });
    }

    private View findText(ViewGroup root, String text) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) child).getText())) return child;
            if (child instanceof ViewGroup) { View found = findText((ViewGroup) child, text); if (found != null) return found; }
        }
        return null;
    }

    private RadioButton popupChoice(Activity activity, YtDlpDownloadService.Job job, YtDlpMedia.Choice choice) {
        View trigger = activity.findViewById(R.id.universal_quality_card).findViewWithTag(job);
        assertNotNull(trigger); trigger.performClick();
        for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) if (root instanceof ViewGroup) {
            RadioButton found = choiceButton((ViewGroup) root, choice);
            if (found != null) return found;
        }
        return null;
    }

    @Test public void compactInputAndQualityPopup() throws Exception {
        isolated(fixture -> {
            Activity activity = launch(null);
            android.content.ClipData[] previousClip = {null};
            onMain(() -> previousClip[0] = context.getSystemService(android.content.ClipboardManager.class).getPrimaryClip());
            try {
                onMain(() -> {
                    android.widget.EditText input = activity.findViewById(R.id.universal_url_input);
                    input.setText("");
                    assertFalse(activity.findViewById(R.id.universal_download_button).isEnabled());
                    assertEquals(View.VISIBLE, activity.findViewById(R.id.universal_paste_button).getVisibility());
                    assertEquals(View.GONE, activity.findViewById(R.id.universal_clear_button).getVisibility());
                    context.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("link", fixture.url("stream.mpd")));
                    activity.findViewById(R.id.universal_paste_button).performClick();
                    assertEquals(fixture.url("stream.mpd"), input.getText().toString());
                    assertEquals(input.length(), input.getSelectionStart());
                    assertEquals(View.GONE, activity.findViewById(R.id.universal_paste_button).getVisibility());
                    assertEquals(View.VISIBLE, activity.findViewById(R.id.universal_clear_button).getVisibility());
                    activity.findViewById(R.id.universal_clear_button).performClick();
                    assertEquals("", input.getText().toString());
                    input.setText(fixture.url("stream.mpd")); activity.findViewById(R.id.universal_download_button).performClick();
                });
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.READY, 90000);
                onMain(() -> {
                    YtDlpDownloadService.Job job = YtDlpDownloadService.state().jobs.get(0);
                    assertNull(choiceButton((ViewGroup) activity.findViewById(R.id.universal_quality_card), job.choice));
                    RadioButton audio = popupChoice(activity, job, job.media.audios.get(0));
                    assertNotNull(audio);
                });
                instrumentation.waitForIdleSync(); Thread.sleep(3500);
                android.graphics.Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(context.getExternalFilesDir(null), "download-quality-ui.png"))) {
                    assertTrue(screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
                } finally { screen.recycle(); }
                onMain(() -> {
                    for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) if (root instanceof ViewGroup) {
                        RadioButton option = choiceButton((ViewGroup) root, YtDlpDownloadService.state().jobs.get(0).media.audios.get(0));
                        if (option != null) { option.performClick(); break; }
                    }
                    assertTrue(YtDlpDownloadService.state().jobs.get(0).choice.audioOnly);
                });
            } finally { onMain(() -> {
                android.content.ClipboardManager manager = context.getSystemService(android.content.ClipboardManager.class);
                if (previousClip[0] == null) manager.clearPrimaryClip(); else manager.setPrimaryClip(previousClip[0]);
                activity.finish();
            }); }
        });
    }

    private RadioButton choiceButton(ViewGroup parent, YtDlpMedia.Choice choice) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof RadioButton && child.getTag() == choice) return (RadioButton) child;
            if (child instanceof ViewGroup) {
                RadioButton found = choiceButton((ViewGroup) child, choice);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void batchUiSelectsAudioAndContinuesAfterOneFailedLink() throws Exception {
        isolated(fixture -> {
            Activity activity = launch(fixture.url("sample.mp4") + "\n" + fixture.url("missing.mp4") + "\n" + fixture.url("sample.mp3"));
            try {
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.READY, 90000);
                onMain(() -> {
                    assertEquals(3, YtDlpDownloadService.state().jobs.size());
                    assertFalse(YtDlpDownloadService.state().jobs.get(1).error.isEmpty());
                    assertTrue(YtDlpDownloadService.state().jobs.get(2).choice.audioOnly);
                    activity.findViewById(R.id.universal_start_download_button).performClick();
                });
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.COMPLETE, 90000);
                List<String> paths = new ArrayList<>();
                onMain(() -> {
                    assertTrue(YtDlpDownloadService.state().message.contains("成功 2，失败 1"));
                    for (YtDlpDownloadService.Job job : YtDlpDownloadService.state().jobs) if (job.path != null) paths.add(job.path);
                });
                assertEquals(2, paths.size());
                YtDlpFixtureTest.assertMedia(new File(paths.get(0)), true, true);
                YtDlpFixtureTest.assertMedia(new File(paths.get(1)), false, true);
                fixture.repairMissing = true;
                onMain(() -> context.startForegroundService(new Intent(context, YtDlpDownloadService.class).setAction(YtDlpDownloadService.RETRY_RESOLVE)));
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.READY, 90000);
                onMain(() -> {
                    assertEquals(paths.get(0), YtDlpDownloadService.state().jobs.get(0).path);
                    assertTrue(YtDlpDownloadService.state().jobs.get(1).error.isEmpty());
                    activity.findViewById(R.id.universal_start_download_button).performClick();
                });
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.COMPLETE, 90000);
                onMain(() -> assertTrue(YtDlpDownloadService.state().message.contains("成功 3，失败 0")));
            } finally { onMain(activity::finish); }
        });
    }

    @Test public void selectingAudioFromDashDownloadsOnlyAudio() throws Exception {
        isolated(fixture -> {
            Activity activity = launch(fixture.url("stream.mpd"));
            try {
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.READY, 90000);
                onMain(() -> {
                    YtDlpDownloadService.Job job = YtDlpDownloadService.state().jobs.get(0);
                    RadioButton audio = popupChoice(activity, job, job.media.audios.get(0));
                    assertNotNull("Audio option visible in the actual screen", audio); audio.performClick();
                    assertTrue(YtDlpDownloadService.state().jobs.get(0).choice.audioOnly);
                    activity.findViewById(R.id.universal_start_download_button).performClick();
                });
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.COMPLETE, 90000);
                String[] path = {null}; onMain(() -> path[0] = YtDlpDownloadService.state().jobs.get(0).path);
                assertNotNull(path[0]); YtDlpFixtureTest.assertMedia(new File(path[0]), false, true);
                Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(com.example.cleanrecovery.ui.activity.FileBrowserActivity.class.getName(), null, false);
                try {
                    onMain(() -> {
                        View open = findText((ViewGroup) activity.findViewById(R.id.universal_quality_card), "打开文件");
                        assertNotNull(open); open.performClick();
                    });
                    Activity browser = instrumentation.waitForMonitorWithTimeout(monitor, 10000);
                    assertNotNull("Open uses the internal file browser", browser);
                    assertEquals(new File(path[0]).getParent(), browser.getIntent().getStringExtra(com.example.cleanrecovery.ui.activity.FileBrowserActivity.EXTRA_INITIAL_PATH));
                    onMain(browser::finish);
                } finally { instrumentation.removeMonitor(monitor); }

            } finally { onMain(activity::finish); }
        });
    }

    @Test public void pauseReopenResumeUsesRangeAndDoesNotOverwrite() throws Exception {
        isolated(fixture -> {
            Activity activity = launch(fixture.url("sample.mp4"));
            try {
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.READY, 90000);
                fixture.slow = true;
                onMain(() -> activity.findViewById(R.id.universal_start_download_button).performClick());
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.DOWNLOADING && YtDlpDownloadService.state().progress > 1, 30000);
                onMain(() -> activity.findViewById(R.id.universal_pause_button).performClick());
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.PAUSED, 15000);
                onMain(() -> {
                    assertNotNull(YtDlpDownloadService.state().transfer);
                    assertTrue(YtDlpDownloadService.state().transfer.downloaded > 0);
                    assertTrue(YtDlpDownloadService.state().transfer.total > 0);
                    assertTrue(((android.widget.TextView) activity.findViewById(R.id.universal_progress_text)).getText().toString().contains(" / "));
                });
                instrumentation.waitForIdleSync();
                android.graphics.Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(context.getExternalFilesDir(null), "download-progress-ui.png"))) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                } finally { screenshot.recycle(); }
                onMain(activity::finish);
                Activity reopened = launch(null);
                try {
                    fixture.slow = false;
                    onMain(() -> reopened.findViewById(R.id.universal_pause_button).performClick());
                    await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.COMPLETE, 90000);
                    String[] path = {null}; onMain(() -> path[0] = YtDlpDownloadService.state().jobs.get(0).path);
                    assertNotNull(path[0]); YtDlpFixtureTest.assertMedia(new File(path[0]), true, true);
                    assertTrue("Resume requests a nonzero byte range", fixture.requests.stream().anyMatch(r -> r.matches("sample.mp4 bytes=[1-9][0-9]*-.*")));
                    byte[] original = Files.readAllBytes(new File(path[0]).toPath());
                    // A second batch downloading the same item must allocate a separate output file.
                    onMain(() -> {
                        ((android.widget.EditText) reopened.findViewById(R.id.universal_url_input)).setText(fixture.url("sample.mp4"));
                        reopened.findViewById(R.id.universal_download_button).performClick();
                    });
                    await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.READY, 90000);
                    onMain(() -> reopened.findViewById(R.id.universal_start_download_button).performClick());
                    await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.COMPLETE, 90000);
                    onMain(() -> assertNotEquals(path[0], YtDlpDownloadService.state().jobs.get(0).path));
                    assertArrayEquals(original, Files.readAllBytes(new File(path[0]).toPath()));
                } finally { onMain(reopened::finish); }
            } finally { onMain(activity::finish); }
        });
    }
    private static RadioButton audioButton(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RadioButton && child.getTag() instanceof YtDlpMedia.Choice && ((YtDlpMedia.Choice) child.getTag()).audioOnly) return (RadioButton) child;
            if (child instanceof ViewGroup) { RadioButton result = audioButton((ViewGroup) child); if (result != null) return result; }
        }
        return null;
    }
    @Test public void cancelRemovesPartialFilesWithoutReportingSuccess() throws Exception {
        isolated(fixture -> {
            Activity activity = launch(fixture.url("sample.mp4"));
            try {
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.READY, 90000);
                File[] work = {null}; onMain(() -> work[0] = YtDlpDownloadService.state().jobs.get(0).work);
                fixture.slow = true;
                onMain(() -> activity.findViewById(R.id.universal_start_download_button).performClick());
                await(() -> YtDlpDownloadService.state().progress > 1, 30000);
                onMain(() -> activity.findViewById(R.id.universal_cancel_button).performClick());
                await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.IDLE, 15000);
                onMain(() -> assertNull(YtDlpDownloadService.state().jobs.get(0).path));
                assertFalse("Cancelled job removes its own temporary directory", work[0].exists());
                assertEquals(0, new BrowserPrefs(context).downloadDirFile().listFiles().length);
            } finally { onMain(activity::finish); }
        });
    }
    private Activity launch(String urls) {
        Intent intent = new Intent(context, UniversalDownloadActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (urls != null) intent.putExtra(UniversalDownloadActivity.EXTRA_URL, urls);
        return instrumentation.startActivitySync(intent);
    }
    private void onMain(Runnable action) { instrumentation.runOnMainSync(action); }
    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            boolean[] result = {false}; onMain(() -> result[0] = condition.getAsBoolean()); if (result[0]) return;
            Thread.sleep(100);
        }
        String[] message = {""}; onMain(() -> message[0] = YtDlpDownloadService.state().phase + ": " + YtDlpDownloadService.state().message);
        fail("Timed out: " + message[0]);
    }
    private interface TestBody { void run(YtDlpFixtureTest.Fixture fixture) throws Exception; }
    private void isolated(TestBody body) throws Exception {
        boolean[] idle = {false}; onMain(() -> idle[0] = !YtDlpDownloadService.state().busy() && YtDlpDownloadService.state().jobs.isEmpty());
        assumeTrue("Do not disturb an existing user batch", idle[0]);
        BrowserPrefs prefs = new BrowserPrefs(context); String oldDir = prefs.downloadDir();
        File output = new File(context.getCacheDir(), "yt-batch-test-" + UUID.randomUUID()); assertTrue(output.mkdir());
        File journal = new File(context.getFilesDir(), "media-download-state.json");
        byte[] oldJournal = journal.isFile() ? Files.readAllBytes(journal.toPath()) : null;
        try (YtDlpFixtureTest.Fixture fixture = new YtDlpFixtureTest.Fixture()) {
            prefs.setDownloadDir(output.getAbsolutePath()); body.run(fixture);
        } finally {
            onMain(() -> context.startService(new Intent(context, YtDlpDownloadService.class).setAction(YtDlpDownloadService.CANCEL)));
            await(() -> YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.IDLE, 15000);
            onMain(() -> { YtDlpDownloadService.state().jobs.clear(); YtDlpDownloadService.state().url = ""; YtDlpDownloadService.state().phase = YtDlpDownloadService.Phase.IDLE; });
            prefs.setDownloadDir(oldDir); YtDlpFixtureTest.delete(output);
            if (oldJournal != null) Files.write(journal.toPath(), oldJournal); else journal.delete();
        }
    }
}
