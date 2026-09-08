package com.example.cleanrecovery.ui.browser;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.cleanrecovery.ui.activity.BrowserSiteSettingsActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class BrowserDownloadDialogTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void knownMetadataUsesNativeDayNightControlsWithoutProbingOrDisablingEmptyNames() throws Exception {
        withActivity(activity -> {
            BrowserPrefs prefs = new BrowserPrefs(activity);
            for (boolean night : new boolean[]{false, true}) {
                prefs.setNightMode(night);
                AtomicReference<String> confirmedName = new AtomicReference<>();
                BrowserDownloadDialog dialog = main(() -> BrowserDownloadDialog.show(activity,
                        "https://dialog-fixture.invalid/archive.tar.gz", null, "application/octet-stream", 90000000,
                        (name, mime) -> confirmedName.set(name)));
                try {
                    main(() -> {
                        EditText input = input(dialog);
                        assertEquals("archive.tar.gz", input.getText().toString());
                        assertEquals(0, input.getSelectionStart());
                        assertEquals("Only the last short extension is excluded from the initial selection", 11, input.getSelectionEnd());
                        assertNotNull(find(dialog, "你想要下载此文件吗？"));
                        assertNotNull(find(dialog, "文件大小：85.8 MB"));
                        assertEquals("Known lengths skip the HEAD-only copy hiding branch", View.VISIBLE, find(dialog, "复制链接").getVisibility());
                        assertNull(field(dialog, "metadataWorker"));
                        ViewGroup content = dialog.findViewById(android.R.id.content);
                        GradientDrawable background = (GradientDrawable) content.getChildAt(0).getBackground();
                        assertEquals(night ? 0xff1c1b1d : 0xffffffff, background.getColor().getDefaultColor());
                        assertEquals(night ? 0xbeffffff : 0xde000000, input.getCurrentTextColor());
                        input.setText("name.abcdef"); input.clearFocus(); input.requestFocus();
                        assertEquals("A suffix of six letters is selected with the whole name", input.length(), input.getSelectionEnd());
                        input.setText("  ");
                        View confirm = find(dialog, activity.getString(android.R.string.ok));
                        assertTrue("Via keeps confirmation enabled while editing or when the trimmed name is empty", confirm.isEnabled());
                        assertTrue(confirm.performClick());
                        assertEquals("", confirmedName.get());
                        assertFalse(dialog.isShowing());
                        return null;
                    });
                } finally { main(() -> { dialog.dismiss(); return null; }); }
            }
        });
    }

    @Test public void successfulHeadReplacesAnEditedNameAndUsesItsOwnUrlCookieAndReferer() throws Exception {
        assertEquals("Local metadata tests never alter the user's proxy", Proxy.NO_PROXY, BrowserScriptHttp.currentProxy());
        withActivity(activity -> {
            String cookieName = "dialog" + UUID.randomUUID().toString().replace("-", "");
            CookieManager cookies = CookieManager.getInstance();
            boolean accepted = cookies.acceptCookie();
            try (HeadServer server = new HeadServer(200, 64198569,
                    "application/zip; charset=UTF-8", "attachment; filename*=UTF-8''server%20package.apk")) {
                BrowserDownloadDialog dialog = null;
                try {
                    main(() -> { cookies.setAcceptCookie(true); return null; });
                    setCookie(server.url, cookieName + "=fixture; Path=/; Max-Age=600");
                    AtomicReference<String> result = new AtomicReference<>();
                    dialog = main(() -> BrowserDownloadDialog.show(activity, server.url, null, null, -1,
                            (name, mime) -> result.set(name + "|" + mime)));
                    BrowserDownloadDialog open = dialog;
                    assertTrue(server.received.await(5, TimeUnit.SECONDS));
                    main(() -> { input(open).setText("my edited name.zip"); return null; });
                    assertEquals("HEAD", server.method);
                    assertEquals(server.url, server.headers.get("referer"));
                    assertTrue(server.headers.get("cookie").contains(cookieName + "=fixture"));
                    server.release.countDown();
                    finishMetadata(dialog);
                    main(() -> {
                        assertEquals("Via overwrites a user's pending edit when HEAD metadata succeeds", "server package.apk", input(open).getText().toString());
                        assertEquals(14, input(open).getSelectionEnd());
                        assertEquals(View.GONE, find(open, "复制链接").getVisibility());
                        assertNotNull(find(open, "文件大小：61.2 MB"));
                        input(open).setText("  renamed.pdf  ");
                        assertTrue(find(open, activity.getString(android.R.string.ok)).performClick());
                        assertEquals("Confirmation derives MIME from the final edited extension", "renamed.pdf|application/pdf", result.get());
                        return null;
                    });
                    assertEquals(1, server.requests.get());
                } finally {
                    server.release.countDown();
                    if (dialog != null) { BrowserDownloadDialog closing = dialog; main(() -> { closing.dismiss(); return null; }); finishMetadata(dialog); }
                    try { setCookie(server.url, cookieName + "=; Path=/; Max-Age=0"); }
                    finally { main(() -> { cookies.setAcceptCookie(accepted); return null; }); }
                }
            }
        });
    }

    @Test public void cancellingBeforeHeadFinishesCannotChangeTheClosedDialogOrConfirmDownload() throws Exception {
        assertEquals(Proxy.NO_PROXY, BrowserScriptHttp.currentProxy());
        withActivity(activity -> {
            try (HeadServer server = new HeadServer(200, 2048, "application/pdf", "attachment; filename=late.pdf")) {
                AtomicInteger confirmations = new AtomicInteger();
                BrowserDownloadDialog dialog = main(() -> BrowserDownloadDialog.show(activity, server.url, null, null, -1,
                        (name, mime) -> confirmations.incrementAndGet()));
                try {
                    assertTrue(server.received.await(5, TimeUnit.SECONDS));
                    main(() -> {
                        input(dialog).setText("keep this edit");
                        assertTrue(find(dialog, activity.getString(android.R.string.cancel)).performClick());
                        assertFalse(dialog.isShowing());
                        return null;
                    });
                    server.release.countDown(); finishMetadata(dialog);
                    main(() -> {
                        assertEquals("keep this edit", input(dialog).getText().toString());
                        assertNotNull(find(dialog, "文件大小：-1.0 B"));
                        assertEquals(0, confirmations.get());
                        return null;
                    });
                } finally { server.release.countDown(); main(() -> { dialog.dismiss(); return null; }); finishMetadata(dialog); }
            }
        });
    }

    @Test public void failedHeadKeepsTheEditedNameAndCopyClosesWithoutConfirming() throws Exception {
        assertEquals(Proxy.NO_PROXY, BrowserScriptHttp.currentProxy());
        withActivity(activity -> {
            await(() -> main(() -> activity.getWindow().getDecorView().hasWindowFocus()));
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData oldClipboard = main(clipboard::getPrimaryClip);
            try (HeadServer server = new HeadServer(405, 0, null, null)) {
                AtomicInteger confirmations = new AtomicInteger();
                BrowserDownloadDialog dialog = main(() -> BrowserDownloadDialog.show(activity, server.url, null, null, -1,
                        (name, mime) -> confirmations.incrementAndGet()));
                try {
                    assertTrue(server.received.await(5, TimeUnit.SECONDS));
                    main(() -> { input(dialog).setText("edited.bin"); return null; });
                    server.release.countDown(); finishMetadata(dialog);
                    main(() -> {
                        assertEquals("A server rejecting HEAD does not prevent manual download", "edited.bin", input(dialog).getText().toString());
                        assertTrue(find(dialog, "复制链接").performClick());
                        assertFalse(dialog.isShowing());
                        return null;
                    });
                    await(() -> main(() -> clipboard.hasPrimaryClip()
                            && server.url.contentEquals(clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity))));
                    assertEquals(0, confirmations.get());
                } finally {
                    server.release.countDown(); main(() -> { dialog.dismiss(); return null; }); finishMetadata(dialog);
                    main(() -> {
                        if (oldClipboard == null) clipboard.clearPrimaryClip(); else clipboard.setPrimaryClip(oldClipboard);
                        return null;
                    });
                }
            }
        });
    }

    @Test public void filenameAndSizeRulesFollowInstalledMetadataUtilities() {
        assertEquals("video", BrowserDownloadDialog.guessFilename("https://fixture.invalid/video", null, "application/octet-stream"));
        assertEquals("photo.png", BrowserDownloadDialog.guessFilename("https://fixture.invalid/photo", null, "image/unknown"));
        assertEquals("image.png", BrowserDownloadDialog.guessFilename("https://fixture.invalid/image.unknown?q=1", null, "image/png"));
        assertEquals("video.mp4", BrowserDownloadDialog.guessFilename("https://fixture.invalid/video.mp4", null, "text/plain"));
        assertEquals("报告.txt", BrowserDownloadDialog.guessFilename("https://fixture.invalid/", "attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.txt", "text/plain"));
        assertEquals("server.unknown", BrowserDownloadDialog.guessFilename("https://fixture.invalid/a", "attachment; filename=server.unknown", "image/png"));
        assertEquals("0.8 KB", BrowserDownloadDialog.formatSize(820));
        assertEquals("819.0 B", BrowserDownloadDialog.formatSize(819));
        assertEquals("0.8 MB", BrowserDownloadDialog.formatSize(838861));
        assertEquals("-1.0 B", BrowserDownloadDialog.formatSize(-1));
    }

    private void finishMetadata(BrowserDownloadDialog dialog) throws Exception {
        Thread worker = (Thread) field(dialog, "metadataWorker");
        if (worker != null) { worker.join(5000); assertFalse("The real HEAD request must finish", worker.isAlive()); }
        instrumentation.waitForIdleSync();
    }

    private interface HostTest { void run(Activity activity) throws Exception; }

    private void withActivity(HostTest test) throws Exception {
        Context context = instrumentation.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("via_browser_prefs", 0);
        Map<String, Object> original = snapshot(prefs);
        Activity activity = instrumentation.startActivitySync(new Intent(context, BrowserSiteSettingsActivity.class)
                .putExtra(BrowserSiteSettingsActivity.EXTRA_HOST, "dialog-" + UUID.randomUUID() + ".invalid")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        try { test.run(activity); }
        finally {
            main(() -> { activity.finish(); return null; });
            await(() -> main(activity::isDestroyed));
            instrumentation.waitForIdleSync();
            restore(prefs, original);
        }
    }

    private static EditText input(BrowserDownloadDialog dialog) throws Exception { return (EditText) field(dialog, "filename"); }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static View find(BrowserDownloadDialog dialog, String text) { return find(dialog.getWindow().getDecorView(), text); }
    private static View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = find(((ViewGroup) root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> result = new FutureTask<>(action); instrumentation.runOnMainSync(result); return result.get();
    }
    private static void await(Callable<Boolean> condition) throws Exception {
        long end = android.os.SystemClock.uptimeMillis() + 5000;
        while (android.os.SystemClock.uptimeMillis() < end) { if (condition.call()) return; Thread.sleep(25); }
        fail("Expected dialog lifecycle state did not arrive");
    }
    private void setCookie(String url, String value) throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        main(() -> { CookieManager.getInstance().setCookie(url, value, result::complete); return null; });
        assertTrue(result.get(5, TimeUnit.SECONDS)); CookieManager.getInstance().flush();
    }
    private static Map<String, Object> snapshot(SharedPreferences prefs) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) values.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return values;
    }
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences prefs, Map<String, Object> values) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) edit.putString(key, (String) value); else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value); else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value); else if (value instanceof Set) edit.putStringSet(key, new HashSet<>((Set<String>) value));
            else throw new AssertionError("Unexpected preference type: " + key);
        }
        assertTrue(edit.commit()); assertEquals(values, snapshot(prefs));
    }

    private static final class HeadServer implements AutoCloseable {
        final ServerSocket listener;
        final Thread worker;
        final String url;
        final CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        final Map<String, String> headers = new HashMap<>();
        final AtomicInteger requests = new AtomicInteger();
        volatile Throwable failure;
        volatile String method;
        HeadServer(int status, long size, String type, String disposition) throws Exception {
            listener = new ServerSocket(0, 5, InetAddress.getByName("127.77.22.10"));
            url = "http://127.77.22.10:" + listener.getLocalPort() + "/probe-" + UUID.randomUUID();
            worker = new Thread(() -> {
                while (!listener.isClosed()) try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = input.readLine(); assertNotNull(first); method = first.split(" ")[0];
                    assertEquals("Metadata must not fetch the file body", "HEAD", method);
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':'); if (colon > 0) headers.put(line.substring(0, colon).toLowerCase(java.util.Locale.ROOT), line.substring(colon + 1).trim());
                    }
                    requests.incrementAndGet(); received.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    String response = "HTTP/1.1 " + status + " Fixture\r\nContent-Length: " + size + "\r\nConnection: close\r\n"
                            + (type == null ? "" : "Content-Type: " + type + "\r\n")
                            + (disposition == null ? "" : "Content-Disposition: " + disposition + "\r\n") + "\r\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
                } catch (Throwable error) { if (!listener.isClosed()) failure = error; }
            }, "download-dialog-head-fixture");
            worker.start();
        }
        @Override public void close() throws Exception {
            release.countDown(); listener.close(); worker.join(5000);
            assertFalse(worker.isAlive()); assertNull("Preserve all fixture transport failures", failure);
        }
    }
}
