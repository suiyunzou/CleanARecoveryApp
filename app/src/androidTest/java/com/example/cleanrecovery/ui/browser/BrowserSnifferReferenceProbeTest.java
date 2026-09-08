package com.example.cleanrecovery.ui.browser;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Opt-in original-Via UI observation; no settings, cookies, playback or downloads are changed. */
@RunWith(AndroidJUnit4.class)
public class BrowserSnifferReferenceProbeTest {
    private static final String HOST = "127.73.19.42";
    private static final int PORT = 39842;
    private static final String TITLE = "ViaSnifferProbe-20260906";

    @Test public void observeInstalledViaEagerResourcesAndRetainedPageBack() throws Exception {
        org.junit.Assume.assumeTrue("Manual Via UI probe requires explicit opt-in",
                "true".equals(InstrumentationRegistry.getArguments().getString("viaSnifferReferenceProbe")));
        long deadline = SystemClock.elapsedRealtime() + 180000;
        Map<String, Integer> counts = new LinkedHashMap<>();
        boolean finished = false;
        try (ServerSocket server = new ServerSocket(PORT, 8, InetAddress.getByName(HOST))) {
            String base = "http://" + HOST + ":" + PORT;
            evidence("START " + TITLE + " entry=" + base + "/a finish=" + base + "/finish");
            InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(base + "/a"))
                            .setComponent(new ComponentName("mark.via.gp", "mark.via.Shell"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            while (!finished && SystemClock.elapsedRealtime() < deadline) {
                server.setSoTimeout((int)Math.max(1, deadline - SystemClock.elapsedRealtime()));
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout((int)Math.max(1, Math.min(2000, deadline - SystemClock.elapsedRealtime())));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = reader.readLine();
                    if (first == null) continue;
                    String line;
                    while (true) {
                        long remaining = deadline - SystemClock.elapsedRealtime();
                        if (remaining <= 0) throw new SocketTimeoutException("Probe deadline reached");
                        socket.setSoTimeout((int)Math.min(2000, remaining));
                        line = reader.readLine();
                        if (line == null || line.isEmpty()) break;
                        // Never log headers.
                    }
                    String[] request = first.split(" ", 3);
                    assertEquals("A fixture request must have an HTTP request line", 3, request.length);
                    String path = Uri.parse(request[1]).getPath();
                    assertNotNull(path);
                    counts.put(path, counts.containsKey(path) ? counts.get(path) + 1 : 1);
                    evidence("REQUEST " + request[0] + " " + path + " count=" + counts.get(path));

                    int status = 200;
                    String type = "text/html; charset=utf-8";
                    byte[] body;
                    if ("/a".equals(path) || "/b".equals(path)) {
                        body = page(path.substring(1)).getBytes(StandardCharsets.UTF_8);
                    } else if ("/a.mp4".equals(path) || "/b.mp4".equals(path)) {
                        type = "video/mp4";
                        body = new byte[]{0, 1, 2, (byte)("/a.mp4".equals(path) ? 3 : 4)};
                    } else if ("/event/a-fetched".equals(path) || "/event/b-fetched".equals(path)) {
                        type = "text/plain; charset=utf-8";
                        body = "ok".getBytes(StandardCharsets.UTF_8);
                        evidence("FETCH_COMPLETE " + path);
                    } else if ("/finish".equals(path)) {
                        body = document("Finished", "<h1>Probe finished</h1><p>Local server is closing.</p>").getBytes(StandardCharsets.UTF_8);
                        finished = true;
                    } else {
                        status = 404;
                        type = "text/plain; charset=utf-8";
                        body = "Fixture path not found".getBytes(StandardCharsets.UTF_8);
                    }
                    socket.getOutputStream().write(("HTTP/1.1 " + status + " Fixture\r\nContent-Type: " + type
                            + "\r\nCache-Control: no-store\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    if (!"HEAD".equals(request[0])) socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } catch (SocketTimeoutException idleConnection) {
                    // Empty speculative connections must not extend the overall 180-second deadline.
                }
            }
        }
        evidence((finished ? "FINISH" : "TIMEOUT") + " requestCounts=" + counts);
        assertTrue("Open the fixture's /finish link within 180 seconds to end the manual probe", finished);
    }

    private static String page(String name) {
        String label = name.toUpperCase(java.util.Locale.ROOT);
        String link = "a".equals(name) ? "<a class='action' href='/b'>Link to B</a>"
                : "<p>Use Via Back to return to retained A, then inspect its resources.</p>";
        return document(label, "<h1>" + TITLE + " " + label + "</h1><p id='fetch-state'>Inline fetch pending</p>"
                + "<button class='action' id='fetch-now' style='font:inherit;width:100%;text-align:left'>Fetch resource now</button>"
                + link + "<a class='action' href='/finish'>Finish probe</a>"
                + "<script>function fetchResource(){document.querySelector('#fetch-state').textContent='Fetch pending';"
                + "return fetch('/" + name + ".mp4',{cache:'no-store'}).then(r=>r.arrayBuffer()).then(bytes=>{"
                + "document.querySelector('#fetch-state').textContent='Fetch complete: '+bytes.byteLength+' bytes';"
                + "return fetch('/event/" + name + "-fetched',{cache:'no-store'});"
                + "}).catch(()=>document.querySelector('#fetch-state').textContent='Fetch failed');}"
                + "document.querySelector('#fetch-now').onclick=fetchResource;fetchResource();</script>");
    }

    private static String document(String suffix, String body) {
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<link rel='icon' href='data:,'><title>" + TITLE + " " + suffix + "</title>"
                + "<style>body{font:20px sans-serif;margin:24px;line-height:1.5}h1{font-size:24px}"
                + ".action{display:block;padding:20px;margin:20px 0;background:#dceaff;color:#12376b}</style>"
                + "</head><body>" + body + "</body></html>";
    }

    private static void evidence(String message) {
        Bundle evidence = new Bundle();
        evidence.putString("stream", "\nVIA_SNIFFER_REFERENCE: " + message + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence);
    }
}
