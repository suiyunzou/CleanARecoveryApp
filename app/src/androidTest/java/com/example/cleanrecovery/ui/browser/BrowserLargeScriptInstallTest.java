package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.ui.activity.BrowserActivity;
import com.example.cleanrecovery.ui.activity.BrowserSettingsActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserLargeScriptInstallTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private <T> T main(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        instrumentation.runOnMainSync(task); return task.get();
    }
    @Test public void downloadedLargeScriptUsesSmallIntentAndSettingsSave() throws Exception {
        String name = "__large_script_" + java.util.UUID.randomUUID();
        String source = "// ==UserScript==\n// @name " + name
                + "\n// @match https://large-script-fixture.invalid/*\n// ==/UserScript==\n/*"
                + new String(new char[800000]).replace('\0', 'x') + "*/\n";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        AtomicReference<Intent> captured = new AtomicReference<>();
        CountDownLatch launch = new CountDownLatch(1);
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() != null && intent.getComponent().getClassName().equals(BrowserSettingsActivity.class.getName())) {
                    captured.set(new Intent(intent)); launch.countDown();
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        Activity browser = null, editor = null;
        ExecutorService serverThread = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(15000);
            Future<?> served = serverThread.submit(() -> {
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            browser = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.addMonitor(monitor);
            Activity owner = browser;
            main(() -> {
                java.lang.reflect.Method offer = BrowserActivity.class.getDeclaredMethod("offerUserScript", String.class);
                offer.setAccessible(true);
                assertTrue((Boolean) offer.invoke(owner, "http://127.0.0.1:" + server.getLocalPort() + "/fixture.user.js"));
                return null;
            });
            assertTrue("Downloaded script must reach settings installation", launch.await(15, TimeUnit.SECONDS));
            served.get(2, TimeUnit.SECONDS);
            instrumentation.removeMonitor(monitor);
            Intent intent = captured.get();
            Parcel parcel = Parcel.obtain();
            try {
                intent.writeToParcel(parcel, 0);
                assertTrue("Installation Intent must not carry large source: " + parcel.dataSize(), parcel.dataSize() < 65536);
                android.util.Log.i("LargeScriptInstallTest", "intentBytes=" + parcel.dataSize());
            } finally { parcel.recycle(); }
            editor = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity settings = editor;
            main(() -> {
                EditText code = (EditText) find(settings.getWindow().getDecorView(), "脚本源代码", true);
                assertNotNull(code); assertEquals(source, code.getText().toString());
                assertFalse("Large editor text must not enter saved view state", code.isSaveEnabled());
                android.os.Bundle state = new android.os.Bundle();
                instrumentation.callActivityOnSaveInstanceState(settings, state);
                Parcel saved = Parcel.obtain();
                try {
                    state.writeToParcel(saved, 0);
                    assertTrue("Saved state must not carry source: " + saved.dataSize(), saved.dataSize() < 65536);
                    android.util.Log.i("LargeScriptInstallTest", "savedStateBytes=" + saved.dataSize());
                } finally { saved.recycle(); }
                assertEquals(source, BrowserScriptDraftStore.read(settings, state.getString("script_draft_token")));
                find(settings.getWindow().getDecorView(), "保存", false).performClick(); return null;
            });
            long deadline = System.currentTimeMillis() + 10000;
            while (!prefs.scriptNames().contains(name) && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertEquals(source, prefs.scriptCode(name));
            main(() -> {
                assertFalse(new java.io.File(settings.getFilesDir(), "userscript-drafts/" + intent.getStringExtra(BrowserSettingsActivity.EXTRA_SCRIPT_DRAFT) + ".txt").exists());
                return null;
            });
        } finally {
            instrumentation.removeMonitor(monitor);
            Activity closingEditor = editor, closingBrowser = browser;
            main(() -> { if (closingEditor != null) closingEditor.finish(); if (closingBrowser != null) closingBrowser.finish(); return null; });
            prefs.removeScript(name); serverThread.shutdownNow();
        }
    }
    private View find(View view, String text, boolean description) {
        if (description ? text.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
                : view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), text, description); if (found != null) return found;
        }
        return null;
    }
}
