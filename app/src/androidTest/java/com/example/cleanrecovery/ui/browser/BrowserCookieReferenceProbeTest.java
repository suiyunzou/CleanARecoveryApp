package com.example.cleanrecovery.ui.browser;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Interactive original-Via probe; no production preferences or real-site cookies are used. */
@RunWith(AndroidJUnit4.class)
public class BrowserCookieReferenceProbeTest {
    @Test public void observeInstalledViaCookieClearAgainstIsolatedLoopbackServer() throws Exception {
        org.junit.Assume.assumeTrue("Manual Via UI probe requires explicit opt-in",
                "true".equals(InstrumentationRegistry.getArguments().getString("viaCookieReferenceProbe")));
        String host = "127.73.19.41";
        try (ServerSocket server = new ServerSocket(39841, 8, InetAddress.getByName(host))) {
            server.setSoTimeout(180000);
            String base = "http://" + host + ":39841";
            InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(base + "/seed"))
                    .setComponent(new ComponentName("mark.via.gp", "mark.via.Shell"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            boolean finished = false;
            while (!finished) try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String request = reader.readLine(); if (request == null) continue;
                String line, cookies = "";
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.regionMatches(true, 0, "Cookie:", 0, 7)) cookies = line.substring(7).trim();
                }
                String path = request.split(" ")[1];
                Bundle evidence = new Bundle();
                evidence.putString("stream", "\nVIA_COOKIE_REFERENCE_REQUEST: path=" + path + " fixtureCookies=" + cookies + "\n");
                InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence);
                String set = "";
                if (path.equals("/seed")) set = "Set-Cookie: via_probe_root=root; Path=/; Max-Age=600; HttpOnly\r\n"
                        + "Set-Cookie: via_probe_other=other; Path=/other; Max-Age=600; HttpOnly\r\n";
                if (path.equals("/finish")) {
                    finished = true;
                    set = "Set-Cookie: via_probe_root=; Path=/; Max-Age=0; HttpOnly\r\n"
                            + "Set-Cookie: via_probe_other=; Path=/other; Max-Age=0; HttpOnly\r\n";
                }
                byte[] body = ("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><link rel='icon' href='data:,'><title>Cookie Reference Probe</title></head>"
                        + "<body><h1>Cookie Reference Probe</h1><p>" + path + "</p>"
                        + "<p><a href='/other/before'>Before: other path</a></p><p><a href='/other/after'>After: other path</a></p>"
                        + "<p><a href='/root-after'>After: root path</a></p><p><a href='/finish'>Finish and clear fixtures</a></p></body></html>")
                        .getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n"
                        + set + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body); socket.getOutputStream().flush();
            }
            assertTrue("Finish via the fixture's cleanup response", finished);
        }
    }
}
