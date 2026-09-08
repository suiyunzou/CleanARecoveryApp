package com.example.cleanrecovery.ui.browser;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.cleanrecovery.background.BackgroundDownloadService;
import com.example.cleanrecovery.background.DownloadQueueManager;
import com.example.cleanrecovery.background.DownloadTaskDbHelper;
import com.example.cleanrecovery.ui.activity.BrowserDownloadsActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class BrowserDownloadRestoreTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void openingDownloadsResumesPendingRequestAndShowsProgressBeforeCompletion() throws Exception {
        Context context = instrumentation.getTargetContext();
        DownloadTaskDbHelper database = DownloadTaskDbHelper.getInstance(context);
        DownloadQueueManager queue = DownloadQueueManager.getInstance();
        assertTrue("Never change the output directory while a user's download is pending", database.getRestorableTasks().isEmpty());
        synchronized (field(queue, "lock")) {
            assertFalse("Never stop a user's active download executor", (Boolean) field(queue, "running"));
            for (DownloadQueueManager.DownloadTask task : taskMap(queue).values()) {
                assertTrue("Unpersisted user requests also prohibit this fixture",
                        task.status != DownloadQueueManager.DownloadTask.TaskStatus.PENDING
                                && task.status != DownloadQueueManager.DownloadTask.TaskStatus.RUNNING);
            }
        }
        assertEquals("This local HTTP fixture must not change an active user's proxy", Proxy.NO_PROXY, BrowserScriptHttp.currentProxy());

        SharedPreferences storage = context.getSharedPreferences("via_browser_prefs", 0);
        Map<String, Object> original = snapshot(storage);
        String fixture = "download-restore-" + UUID.randomUUID();
        File directory = new File(context.getExternalFilesDir(null), fixture);
        assertTrue(directory.mkdir());
        BrowserDownloadsActivity activity = null;
        int taskId = -1;
        boolean safeToDeleteOutputs = true;
        try (SlowServer server = new SlowServer(fixture)) {
            try {
                // Stop only the already-checked idle service. startActivity below must be the event that restarts it.
                main(() -> context.stopService(new Intent(context, BackgroundDownloadService.class)));
                await("idle service destruction must release the executor", () -> field(queue, "executor") == null, 5000);
                new BrowserPrefs(context).setDownloadDir(directory.getAbsolutePath());
                queue.init(context);
                taskId = queue.enqueue(server.url, "application/octet-stream", null, "Restore fixture",
                        "restored.bin", java.util.Collections.singletonMap("User-Agent", "RestoreFixture/1.0"));
                assertTrue(taskId > 0);
                safeToDeleteOutputs = false;
                Row pending = row(database, server.url);
                assertNotNull(pending);
                assertEquals("PENDING", pending.status);
                assertEquals(0, server.requests.get());
                synchronized (field(queue, "lock")) {
                    assertFalse("The queue must wait for Android to create its executor", (Boolean) field(queue, "running"));
                }

                activity = (BrowserDownloadsActivity) instrumentation.startActivitySync(
                        new Intent(context, BrowserDownloadsActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
                assertTrue("The download page itself must start the real pending HTTP request",
                        server.partialSent.await(8, TimeUnit.SECONDS));
                await("download progress must reach SQLite while the response is still held open", () -> {
                    Row progress = row(database, server.url);
                    return progress != null && "RUNNING".equals(progress.status) && progress.downloaded > 0;
                }, 8000);
                Row progress = row(database, server.url);
                assertEquals("RUNNING", progress.status);
                assertEquals(server.body.length, progress.total);
                assertTrue(progress.downloaded > 0 && progress.downloaded < server.body.length);
                assertEquals("Remaining bytes are deliberately withheld during the progress assertion", 1, server.releaseRest.getCount());
                assertEquals("RestoreFixture/1.0", server.userAgent);

                server.releaseRest.countDown();
                await("the same request must finish after releasing its remaining bytes", () -> terminal(row(database, server.url)), 15000);
                Row completed = row(database, server.url);
                assertEquals("Download failed: " + completed.error, "COMPLETED", completed.status);
                assertEquals(server.body.length, completed.downloaded);
                assertEquals(server.body.length, completed.total);
                assertEquals(server.body.length, completed.fileSize);
                File result = new File(completed.path);
                assertEquals(directory.getCanonicalPath(), result.getCanonicalFile().getParent());
                assertEquals("restored.bin", result.getName());
                assertArrayEquals("Database completion must correspond to the actual original resource bytes",
                        server.body, Files.readAllBytes(result.toPath()));
                assertEquals("Restoration must not duplicate the native request", 1, server.requests.get());
            } finally {
                server.releaseRest.countDown();
                try {
                    if (activity != null) {
                        BrowserDownloadsActivity closing = activity;
                        main(() -> { closing.finish(); return null; });
                        await("destroy the download page before restoring every preference", () -> main(closing::isDestroyed), 5000);
                        instrumentation.waitForIdleSync();
                    }
                    if (taskId > 0) {
                        Row current = row(database, server.url);
                        // If the restore assertion failed, finish only this known fixture before closing its server.
                        if (!terminal(current) && field(queue, "executor") == null) {
                            main(() -> context.startService(new Intent(context, BackgroundDownloadService.class)));
                        }
                        await("settle the fixture task before deleting its row or output", () -> terminal(row(database, server.url)), 20000);
                        await("the fixture worker must finish all completion writes", () -> !(Boolean) field(queue, "running"), 5000);
                        safeToDeleteOutputs = true;
                        main(() -> context.stopService(new Intent(context, BackgroundDownloadService.class)));
                        await("release the fixture's service instance", () -> field(queue, "executor") == null, 5000);
                        synchronized (field(queue, "lock")) {
                            DownloadQueueManager.DownloadTask owned = taskMap(queue).get(server.url);
                            if (owned != null) {
                                assertTrue(owned.status != DownloadQueueManager.DownloadTask.TaskStatus.PENDING
                                        && owned.status != DownloadQueueManager.DownloadTask.TaskStatus.RUNNING);
                                taskMap(queue).remove(server.url);
                            }
                        }
                        assertEquals(1, database.getWritableDatabase().delete("tasks", "id=? AND url=?",
                                new String[]{Integer.toString(taskId), server.url}));
                        assertNull(row(database, server.url));
                    }
                } finally {
                    if (activity != null) {
                        BrowserDownloadsActivity closing = activity;
                        assertTrue("Do not restore preferences while this activity can still write them", main(closing::isDestroyed));
                    }
                    assertTrue("Do not change the output directory underneath a live fixture retry", safeToDeleteOutputs);
                    restore(storage, original);
                }
            }
        } finally {
            assertTrue("Retain fixture output if its worker failed to settle", safeToDeleteOutputs);
            for (File file : Objects.requireNonNull(directory.listFiles())) {
                assertEquals("Delete only this fixture's flat output files", directory.getCanonicalPath(), file.getCanonicalFile().getParent());
                assertTrue(file.isFile());
                assertTrue(file.delete());
            }
            assertTrue(directory.delete());
        }
    }

    private static boolean terminal(Row row) {
        return row != null && !"PENDING".equals(row.status) && !"RUNNING".equals(row.status);
    }

    private static Row row(DownloadTaskDbHelper database, String url) {
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT status,downloaded,total_size,file_size,result_path,error_message FROM tasks WHERE url=?", new String[]{url})) {
            if (!cursor.moveToFirst()) return null;
            Row row = new Row();
            row.status = cursor.getString(0); row.downloaded = cursor.getLong(1); row.total = cursor.getLong(2);
            row.fileSize = cursor.getLong(3); row.path = cursor.getString(4); row.error = cursor.getString(5);
            return row;
        }
    }

    private static final class Row { String status, path, error; long downloaded, total, fileSize; }

    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> result = new FutureTask<>(action);
        instrumentation.runOnMainSync(result);
        return result.get();
    }

    private static void await(String reason, Callable<Boolean> condition, long timeout) throws Exception {
        long end = android.os.SystemClock.uptimeMillis() + timeout;
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (condition.call()) return;
            Thread.sleep(25);
        }
        fail(reason);
    }

    private static Object field(DownloadQueueManager queue, String name) throws Exception {
        Field field = DownloadQueueManager.class.getDeclaredField(name);
        field.setAccessible(true);
        Field lock = DownloadQueueManager.class.getDeclaredField("lock");
        lock.setAccessible(true);
        synchronized (lock.get(queue)) { return field.get(queue); }
    }

    @SuppressWarnings("unchecked") private static Map<String, DownloadQueueManager.DownloadTask> taskMap(DownloadQueueManager queue) throws Exception {
        return (Map<String, DownloadQueueManager.DownloadTask>) field(queue, "taskMap");
    }

    private static Map<String, Object> snapshot(SharedPreferences prefs) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            values.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        }
        return values;
    }

    @SuppressWarnings("unchecked") private static void restore(SharedPreferences prefs, Map<String, Object> values) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof Set) edit.putStringSet(key, new HashSet<>((Set<String>) value));
            else throw new AssertionError("Unexpected preference type: " + key);
        }
        assertTrue("Restore every preference to disk after activity destruction", edit.commit());
        assertEquals(values, snapshot(prefs));
    }

    private static final class SlowServer implements AutoCloseable {
        final ServerSocket listener;
        final Thread worker;
        final String url, path;
        final byte[] body = new byte[64 * 1024];
        final CountDownLatch partialSent = new CountDownLatch(1), releaseRest = new CountDownLatch(1);
        final AtomicInteger requests = new AtomicInteger();
        volatile String userAgent;
        volatile Throwable failure;

        SlowServer(String fixture) throws Exception {
            for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 31 + 7);
            listener = new ServerSocket(0, 5, InetAddress.getByName("127.77.21.9"));
            path = "/" + fixture + ".bin";
            url = "http://127.77.21.9:" + listener.getLocalPort() + path;
            worker = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        assertEquals("GET " + path + " HTTP/1.1", input.readLine());
                        String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) {
                            if (line.regionMatches(true, 0, "User-Agent:", 0, 11)) userAgent = line.substring(11).trim();
                        }
                        requests.incrementAndGet();
                        OutputStream output = socket.getOutputStream();
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        output.write(body, 0, 1024); output.flush();
                        // Force a real transport interval so the downloader emits its ordinary progress callback.
                        Thread.sleep(500);
                        output.write(body, 1024, 8192); output.flush();
                        partialSent.countDown();
                        assertTrue("The test must release the held response", releaseRest.await(20, TimeUnit.SECONDS));
                        output.write(body, 9216, body.length - 9216); output.flush();
                    } catch (Throwable error) {
                        if (!listener.isClosed()) failure = error;
                    }
                }
            }, "browser-download-restore-fixture");
            worker.start();
        }

        @Override public void close() throws Exception {
            releaseRest.countDown();
            listener.close();
            worker.join(5000);
            assertFalse("Fixture server must not outlive the test", worker.isAlive());
            assertNull("No transport or server assertions may be hidden", failure);
        }
    }
}
