package com.example.cleanrecovery.background;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class DownloadQueueHeadersTest {
    @Test public void queuedBrowserRequestKeepsItsIdentityUntilExecutorStarts() throws Exception {
        // No process singleton or user database: the pending request belongs only to this fixture.
        DownloadQueueManager queue = isolatedQueue();
        File output = outputFile();
        CountDownLatch executed = new CountDownLatch(2);
        List<DownloadQueueManager.DownloadTask> seen = Collections.synchronizedList(new ArrayList<>());
        try {
            Map<String, String> headers = headers();
            int browserId = queue.enqueue("https://queue-fixture.invalid/playlist.m3u8", "application/x-mpegURL",
                    "https://source.invalid/watch", "Source", "playlist.m3u8", headers);
            int legacyId = queue.enqueue("https://queue-fixture.invalid/old.mp4", "video/mp4", null, null);
            headers.put("User-Agent", "A different tab");
            headers.clear();

            // An enqueue before Android creates the service must remain durable PENDING, not fail.
            synchronized (field(queue, "lock")) {
                assertFalse((Boolean) field(queue, "running"));
                for (DownloadQueueManager.DownloadTask task : tasks(queue).values()) {
                    assertEquals(DownloadQueueManager.DownloadTask.TaskStatus.PENDING, task.status);
                    assertEquals(0, task.retryCount);
                }
            }
            queue.setExecutor(task -> {
                seen.add(task);
                executed.countDown();
                return output;
            });
            assertTrue("setting the service executor must wake both pending requests",
                    executed.await(5, TimeUnit.SECONDS));
            awaitIdle(queue);
            assertEquals(2, seen.size());
            DownloadQueueManager.DownloadTask browser = taskWithId(seen, browserId);
            assertEquals(headers(), browser.requestHeaders);
            assertTrue(browser.rawResource);
            assertEquals("playlist.m3u8", browser.fileName);
            assertEquals(DownloadQueueManager.DownloadTask.TaskStatus.COMPLETED, browser.status);
            try {
                browser.requestHeaders.put("Cookie", "changed");
                fail("another consumer must not alter a queued request's identity");
            } catch (UnsupportedOperationException expected) {
                // Required immutable snapshot.
            }
            DownloadQueueManager.DownloadTask legacy = taskWithId(seen, legacyId);
            assertTrue(legacy.requestHeaders.isEmpty());
            assertFalse("old media queue entrypoints retain their merge behavior", legacy.rawResource);
        } finally {
            queue.setExecutor(null);
            queue.cancelAll();
            awaitIdle(queue);
            assertTrue(output.delete());
        }
    }

    @Test public void enqueueDuringAndAfterDrainHasOneWorkerAndNeverLosesWakeup() throws Exception {
        DownloadQueueManager queue = isolatedQueue();
        File output = outputFile();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstBatch = new CountDownLatch(17);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger count = new AtomicInteger();
        try {
            queue.setExecutor(task -> {
                int active = concurrent.incrementAndGet();
                maximum.accumulateAndGet(active, Math::max);
                try {
                    if (count.getAndIncrement() == 0) {
                        firstStarted.countDown();
                        if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture release timeout");
                    }
                    return output;
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                } finally {
                    concurrent.decrementAndGet();
                    firstBatch.countDown();
                }
            });
            queue.enqueue("https://queue-fixture.invalid/first", null, null, null);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            List<Thread> producers = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final int id = i;
                Thread producer = new Thread(() -> queue.enqueue(
                        "https://queue-fixture.invalid/during-" + id, null, null, null));
                producers.add(producer);
                producer.start();
            }
            releaseFirst.countDown();
            for (Thread producer : producers) producer.join(5000);
            assertTrue("all tasks added around the drain must execute", firstBatch.await(5, TimeUnit.SECONDS));
            awaitIdle(queue);
            // Force repeated real empty -> active transitions, instead of assuming a single batch proves wakeup.
            for (int i = 0; i < 16; i++) {
                queue.enqueue("https://queue-fixture.invalid/after-" + i, null, null, null);
                awaitIdle(queue);
            }
            assertEquals(33, count.get());
            assertEquals("queue downloads must remain serial", 1, maximum.get());
        } finally {
            releaseFirst.countDown();
            queue.setExecutor(null);
            queue.cancelAll();
            awaitIdle(queue);
            assertTrue(output.delete());
        }
    }

    @Test public void requestHeadersAndRawResourceSurviveDatabaseCloseAndRestore() throws Exception {
        IsolatedDatabaseContext context = new IsolatedDatabaseContext();
        DownloadTaskDbHelper helper = new DownloadTaskDbHelper(context);
        try {
            Map<String, String> source = headers();
            DownloadQueueManager.DownloadTask task = new DownloadQueueManager.DownloadTask(41,
                    "https://persist-fixture.invalid/manifest.mpd", "application/dash+xml",
                    "https://source.invalid/watch", "Source", 100, source, true);
            task.fileName = "saved manifest.mpd";
            task.status = DownloadQueueManager.DownloadTask.TaskStatus.RUNNING;
            task.retryCount = 2;
            assertEquals(41, helper.insertTask(task));
            source.clear();
            helper.close();
            helper = new DownloadTaskDbHelper(context);
            DownloadQueueManager.DownloadTask restored = helper.getRestorableTasks().get(0);
            assertEquals(headers(), restored.requestHeaders);
            assertTrue(restored.rawResource);
            assertEquals("saved manifest.mpd", restored.fileName);
            assertEquals(2, restored.retryCount);
            assertEquals(DownloadQueueManager.DownloadTask.TaskStatus.PENDING, restored.status);
            DownloadQueueManager.DownloadTask history = helper.getAllTasks().get(0);
            assertEquals(headers(), history.requestHeaders);
            assertTrue(history.rawResource);
            assertEquals(DownloadQueueManager.DownloadTask.TaskStatus.RUNNING, history.status);
        } finally {
            helper.close();
            assertTrue(context.deleteDatabase("download_tasks.db"));
        }
    }

    @Test public void versionTwoUpgradeKeepsHistoryProgressAndPendingTasks() {
        verifyUpgrade(2);
    }

    @Test public void versionOneUpgradeStillAddsDisplayColumnsBeforeRequestColumns() {
        verifyUpgrade(1);
    }

    private static void verifyUpgrade(int version) {
        IsolatedDatabaseContext context = new IsolatedDatabaseContext();
        try (SQLiteDatabase db = context.openOrCreateDatabase("download_tasks.db", Context.MODE_PRIVATE, null)) {
            db.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE,"
                    + "mime_type TEXT, page_url TEXT, page_title TEXT, priority INTEGER DEFAULT 0,"
                    + "retry_count INTEGER DEFAULT 0, status TEXT NOT NULL DEFAULT 'PENDING',"
                    + "error_message TEXT, result_path TEXT, file_hash TEXT, file_size INTEGER DEFAULT 0,"
                    + "created_at INTEGER NOT NULL"
                    + (version == 2 ? ", file_name TEXT, category TEXT, downloaded INTEGER DEFAULT 0, total_size INTEGER DEFAULT 0" : "")
                    + ")");
            db.execSQL("CREATE INDEX idx_tasks_status ON tasks(status)");
            for (int id = 1; id <= 3; id++) {
                ContentValues row = new ContentValues();
                row.put("id", id);
                row.put("url", "https://old-fixture.invalid/file-" + id);
                row.put("status", id == 1 ? "COMPLETED" : id == 2 ? "RUNNING" : "PENDING");
                row.put("created_at", 1000 + id);
                row.put("page_title", "Existing " + id);
                row.put("result_path", "/fixture/existing-" + id);
                row.put("file_size", 321);
                if (version == 2) {
                    row.put("file_name", "old-" + id + ".mp4");
                    row.put("category", "视频");
                    row.put("downloaded", 123);
                    row.put("total_size", 321);
                }
                assertEquals(id, db.insertOrThrow("tasks", null, row));
            }
            db.setVersion(version);
        }
        DownloadTaskDbHelper helper = new DownloadTaskDbHelper(context);
        try {
            assertEquals(3, helper.getWritableDatabase().getVersion());
            List<DownloadQueueManager.DownloadTask> all = helper.getAllTasks();
            assertEquals("upgrading request metadata must never rebuild existing downloads", 3, all.size());
            for (DownloadQueueManager.DownloadTask task : all) {
                assertTrue(task.requestHeaders.isEmpty());
                assertFalse(task.rawResource);
                assertEquals("Existing " + task.id, task.pageTitle);
                assertEquals("/fixture/existing-" + task.id, task.resultPath);
                assertEquals(321, task.fileSize);
                if (version == 2) assertEquals("old-" + task.id + ".mp4", task.fileName);
            }
            assertEquals(DownloadQueueManager.DownloadTask.TaskStatus.COMPLETED, taskWithId(all, 1).status);
            assertEquals(DownloadQueueManager.DownloadTask.TaskStatus.RUNNING, taskWithId(all, 2).status);
            List<DownloadQueueManager.DownloadTask> pending = helper.getRestorableTasks();
            assertEquals(2, pending.size());
            for (DownloadQueueManager.DownloadTask task : pending) {
                assertTrue(task.id == 2 || task.id == 3);
                assertEquals(DownloadQueueManager.DownloadTask.TaskStatus.PENDING, task.status);
            }
            if (version == 2) for (DownloadTaskDbHelper.DownloadRow row : helper.listRows()) {
                assertEquals(123, row.downloaded);
                assertEquals(321, row.totalSize);
                assertEquals("视频", row.category);
            }
        } finally {
            helper.close();
            assertTrue(context.deleteDatabase("download_tasks.db"));
        }
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Site UA/1.0");
        headers.put("Referer", "https://source.invalid/watch?a=1&b=2");
        headers.put("Cookie", "fixture=\"quoted\"; path=/watch");
        headers.put("X-Fixture", "中文");
        return headers;
    }

    private static DownloadQueueManager.DownloadTask taskWithId(List<DownloadQueueManager.DownloadTask> tasks, int id) {
        for (DownloadQueueManager.DownloadTask task : tasks) if (task.id == id) return task;
        throw new AssertionError("missing fixture task " + id);
    }

    private static DownloadQueueManager isolatedQueue() throws Exception {
        Constructor<DownloadQueueManager> constructor = DownloadQueueManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object field(DownloadQueueManager queue, String name) throws Exception {
        Field field = DownloadQueueManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(queue);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, DownloadQueueManager.DownloadTask> tasks(DownloadQueueManager queue) throws Exception {
        return (Map<String, DownloadQueueManager.DownloadTask>) field(queue, "taskMap");
    }

    private static void awaitIdle(DownloadQueueManager queue) throws Exception {
        long until = android.os.SystemClock.uptimeMillis() + 5000;
        do {
            synchronized (field(queue, "lock")) {
                if (!(Boolean) field(queue, "running")) return;
            }
            Thread.sleep(5);
        } while (android.os.SystemClock.uptimeMillis() < until);
        fail("isolated download worker did not finish");
    }

    private static File outputFile() throws Exception {
        File file = File.createTempFile("download-queue-fixture-", ".bin",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[]{1, 2, 3}); }
        return file;
    }

    /** Each helper opens a unique database name; the production singleton is never reassigned. */
    private static final class IsolatedDatabaseContext extends ContextWrapper {
        private final String prefix = "queue-headers-fixture-" + UUID.randomUUID() + "-";

        IsolatedDatabaseContext() {
            super(InstrumentationRegistry.getInstrumentation().getTargetContext());
        }

        @Override public File getDatabasePath(String name) {
            return getBaseContext().getDatabasePath(prefix + name);
        }

        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return getBaseContext().openOrCreateDatabase(prefix + name, mode, factory);
        }

        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
                                                             DatabaseErrorHandler errorHandler) {
            return getBaseContext().openOrCreateDatabase(prefix + name, mode, factory, errorHandler);
        }

        @Override public boolean deleteDatabase(String name) {
            return getBaseContext().deleteDatabase(prefix + name);
        }
    }
}
