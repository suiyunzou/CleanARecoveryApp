package com.example.cleanrecovery.background;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.webkit.CookieManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.download.UniversalDownloadManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BrowserFileDownloaderTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private CookieManager cookies;
    private boolean originalAcceptCookies;
    private String originA, originB;
    private File directory;

    @Before public void setup() throws Exception {
        String fixture = "download-" + UUID.randomUUID();
        originA = "http://" + fixture + "-a.test";
        originB = "http://" + fixture + "-b.test";
        instrumentation.runOnMainSync(() -> {
            cookies = CookieManager.getInstance();
            originalAcceptCookies = cookies.acceptCookie();
            cookies.setAcceptCookie(true);
        });
        directory = new File(instrumentation.getTargetContext().getCacheDir(), fixture);
        assertTrue(directory.mkdir());
    }

    @After public void cleanup() throws Exception {
        try {
            if (cookies != null) {
                try {
                    for (String origin : new String[]{originA, originB})
                        setCookie(origin, "browser_session=; Max-Age=0; Path=/");
                    cookies.flush();
                } finally { instrumentation.runOnMainSync(() -> cookies.setAcceptCookie(originalAcceptCookies)); }
            }
        } finally {
            if (directory != null && directory.exists()) {
                File[] files = directory.listFiles();
                assertNotNull(files);
                for (File file : files) assertTrue("Remove only this test's downloaded file", file.delete());
                assertTrue(directory.delete());
            }
        }
    }

    @Test public void redirectsUseTargetCookiesAndKeepTheHlsManifestAsRawBytes() throws Exception {
        setCookie(originA, "browser_session=source; Path=/");
        setCookie(originB, "browser_session=target; Path=/");
        byte[] manifest = "#EXTM3U\n#EXTINF:4,\nsegment-that-must-not-be-fetched.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = requestHeaders();
        headers.put("Cookie", "browser_session=stale-captured-cookie");
        headers.put("Accept-Encoding", "gzip");
        try (HttpFixture proxy = new HttpFixture(request -> {
            if (request.target.equals(originA + "/start.m3u8"))
                return new Response(302, new byte[0]).header("Location", "/next.m3u8")
                        .header("Set-Cookie", "browser_session=updated; Path=/");
            if (request.target.equals(originA + "/next.m3u8"))
                return new Response(307, new byte[0]).header("Location", originB + "/final.m3u8");
            if (request.target.equals(originB + "/final.m3u8"))
                return new Response(200, manifest).header("Content-Type", "application/vnd.apple.mpegurl");
            throw new AssertionError("The raw download must not fetch media segments: " + request.target);
        })) {
            File output = new File(directory, "selected.m3u8");
            new BrowserFileDownloader(proxy.proxy()).download(originA + "/start.m3u8", output, headers, null);
            assertArrayEquals("The selected manifest must retain its original content", manifest, read(output));
            assertEquals(3, proxy.requests.size());
            assertEquals("browser_session=source", proxy.requests.get(0).header("Cookie"));
            assertEquals("browser_session=updated", proxy.requests.get(1).header("Cookie"));
            assertEquals("browser_session=target", proxy.requests.get(2).header("Cookie"));
            assertEquals("Bearer fixture-only", proxy.requests.get(0).header("Authorization"));
            assertEquals("Bearer fixture-only", proxy.requests.get(1).header("Authorization"));
            assertNull("Do not forward credentials to the redirect's other origin", proxy.requests.get(2).header("Authorization"));
            for (Request request : proxy.requests) {
                assertEquals("BrowserFixture/1.0", request.header("User-Agent"));
                assertEquals("https://page.example.test/watch", request.header("Referer"));
                assertEquals("identity", request.header("Accept-Encoding"));
            }
            assertEquals("Set-Cookie is returned to the browser's session", "browser_session=updated", cookies.getCookie(originA + "/"));
        }
    }

    @Test public void dashManifestResumesThroughTheInjectedProxyWithoutFetchingItsSegments() throws Exception {
        setCookie(originA, "browser_session=dash; Path=/");
        byte[] manifest = "<MPD><Period><SegmentURL media=\"do-not-fetch.m4s\"/></Period></MPD>".getBytes(StandardCharsets.UTF_8);
        int offset = 19;
        try (HttpFixture proxy = new HttpFixture(request -> {
            assertEquals(originA + "/movie.mpd", request.target);
            assertEquals("bytes=" + offset + "-", request.header("Range"));
            assertEquals("browser_session=dash", request.header("Cookie"));
            return new Response(206, Arrays.copyOfRange(manifest, offset, manifest.length))
                    .header("Content-Range", "bytes " + offset + "-" + (manifest.length - 1) + "/" + manifest.length)
                    .header("Content-Type", "application/dash+xml");
        })) {
            File output = new File(directory, "movie.mpd");
            File part = new File(output.getAbsolutePath() + UniversalDownloadManager.PART_SUFFIX);
            try (FileOutputStream stream = new FileOutputStream(part)) { stream.write(manifest, 0, offset); }
            new BrowserFileDownloader(proxy.proxy()).download(originA + "/movie.mpd", output, requestHeaders(), null);
            assertArrayEquals(manifest, read(output));
            assertFalse(part.exists());
            assertEquals("Only the selected raw resource is requested", 1, proxy.requests.size());
        }
    }

    @Test public void failedProxyDoesNotFallBackToTheReachableOrigin() throws Exception {
        try (HttpFixture origin = new HttpFixture(request -> new Response(200, new byte[]{1, 2, 3}))) {
            int unavailablePort;
            try (ServerSocket reserve = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                unavailablePort = reserve.getLocalPort();
            }
            Proxy unavailable = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", unavailablePort));
            File output = new File(directory, "no-direct-fallback.bin");
            try {
                new BrowserFileDownloader(unavailable).download(origin.url("/file"), output, null);
                fail("An unavailable selected proxy must fail the download");
            } catch (IOException expected) {
                assertFalse(output.exists());
                assertTrue("The reachable origin must receive no direct request", origin.requests.isEmpty());
            }
        }
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "BrowserFixture/1.0");
        headers.put("Referer", "https://page.example.test/watch");
        headers.put("Authorization", "Bearer fixture-only");
        return headers;
    }

    private void setCookie(String origin, String value) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> cookies.setCookie(origin, value, success -> {
            result.set(success);
            completed.countDown();
        }));
        assertTrue("Fixture CookieManager operation must complete", completed.await(5, TimeUnit.SECONDS));
        assertTrue("Fixture CookieManager operation must succeed", result.get());
    }

    private byte[] read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private interface Handler { Response respond(Request request) throws Exception; }

    private static class Request {
        final String target;
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Request(BufferedReader input) throws IOException {
            String first = input.readLine();
            if (first == null) throw new EOFException("Missing HTTP request line");
            assertTrue(first.startsWith("GET "));
            target = first.split(" ", 3)[1];
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        String header(String name) { return headers.get(name); }
    }

    private static class Response {
        final int status; final byte[] body;
        final Map<String, String> headers = new LinkedHashMap<>();
        Response(int status, byte[] body) { this.status = status; this.body = body; }
        Response header(String key, String value) { headers.put(key, value); return this; }
    }

    private static class HttpFixture implements AutoCloseable {
        final ServerSocket server;
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final Thread thread;
        volatile Throwable failure;
        HttpFixture(Handler handler) throws IOException {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                while (!server.isClosed()) try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    Request request = new Request(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)));
                    requests.add(request);
                    Response response = handler.respond(request);
                    StringBuilder head = new StringBuilder("HTTP/1.1 " + response.status + " Fixture\r\n");
                    head.append("Content-Length: ").append(response.body.length).append("\r\nConnection: close\r\n");
                    for (Map.Entry<String, String> entry : response.headers.entrySet())
                        head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
                    socket.getOutputStream().write(head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(response.body);
                    socket.getOutputStream().flush();
                } catch (Throwable error) { if (!server.isClosed()) { failure = error; return; } }
            }, "browser-file-download-fixture");
            thread.start();
        }
        Proxy proxy() { return new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", server.getLocalPort())); }
        String url(String path) { return "http://127.0.0.1:" + server.getLocalPort() + path; }
        @Override public void close() throws Exception {
            server.close(); thread.join(6000);
            assertFalse("HTTP fixture must finish", thread.isAlive());
            if (failure != null) throw new AssertionError("HTTP fixture failed", failure);
        }
    }
}
