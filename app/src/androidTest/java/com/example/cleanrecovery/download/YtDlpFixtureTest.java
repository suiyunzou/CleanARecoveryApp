package com.example.cleanrecovery.download;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Real Python, HTTP, yt-dlp and FFmpeg against synthetic media; no third-party login involved. */
public class YtDlpFixtureTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Test public void mp4DownloadRetainsAudioVideoAndDuration() throws Exception { check("sample.mp4", true, true); }
    @Test public void mp3DownloadIsAudioOnly() throws Exception { check("sample.mp3", false, true); }
    @Test public void rawAacAudioRetainsFullAndroidPlaybackDuration() throws Exception { check("sample.aac", false, true); }
    @Test public void hlsDownloadsSegmentsIntoPlayableMedia() throws Exception { check("stream.m3u8", true, true); }
    @Test public void dashMergesSeparateTracksIntoPlayableMedia() throws Exception { check("stream.mpd", true, true); }
    @Test public void webmVp9OpusDownloadsPlayableMedia() throws Exception { check("sample.webm", true, true); }
    @Test public void hlsQualitySelectionChangesActualVideoDimensions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            File work = new File(context.getCacheDir(), "yt-quality-" + UUID.randomUUID());
            try {
                YtDlpMedia info = YtDlpEngine.resolve(context, fixture.url("master.m3u8"), work, UUID.randomUUID().toString());
                assertEquals(2, info.videos.size());
                assertEquals(360, info.videos.get(0).height); assertEquals(180, info.videos.get(1).height);
                for (YtDlpMedia.Choice choice : info.videos) {
                    File file = YtDlpEngine.download(context, fixture.url("master.m3u8"), choice, work, UUID.randomUUID().toString(), (p, eta, line) -> {});
                    assertMedia(file, true, true);
                    MediaExtractor extractor = new MediaExtractor();
                    try {
                        extractor.setDataSource(file.getAbsolutePath());
                        int height = 0;
                        for (int i = 0; i < extractor.getTrackCount(); i++) {
                            MediaFormat format = extractor.getTrackFormat(i);
                            if (format.containsKey(MediaFormat.KEY_HEIGHT)) height = format.getInteger(MediaFormat.KEY_HEIGHT);
                        }
                        assertEquals("Chosen quality must match actual output", choice.height, height);
                    } finally { extractor.release(); }
                }
            } finally { delete(work); }
        }
    }

    private void check(String path, boolean video, boolean audio) throws Exception {
        try (Fixture fixture = new Fixture()) {
            File work = new File(context.getCacheDir(), "yt-fixture-" + UUID.randomUUID());
            try {
                String url = fixture.url(path);
                YtDlpMedia info = YtDlpEngine.resolve(context, url, work, UUID.randomUUID().toString());
                YtDlpMedia.Choice choice = video ? info.videos.get(0) : info.audios.get(0);
                File saved = YtDlpEngine.download(context, url, choice, work, UUID.randomUUID().toString(), (p, eta, line) -> {});
                assertMedia(saved, video, audio);
                if (path.equals("stream.mpd")) assertTrue("DASH selection combines streams", choice.selector.contains("+bestaudio"));
                assertTrue("Actually requested the fixture", fixture.requests.size() >= 2);
            } finally { delete(work); }
        }
    }

    static void assertMedia(File file, boolean video, boolean audio) throws Exception {
        assertTrue(file.isFile()); assertTrue(file.length() > 1000);
        MediaExtractor extractor = new MediaExtractor();
        boolean gotVideo = false, gotAudio = false;
        long duration = 0;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                gotVideo |= mime != null && mime.startsWith("video/"); gotAudio |= mime != null && mime.startsWith("audio/");
                if (format.containsKey(MediaFormat.KEY_DURATION)) duration = Math.max(duration, format.getLong(MediaFormat.KEY_DURATION));
                extractor.selectTrack(i);
                assertTrue("Track has readable samples", extractor.readSampleData(java.nio.ByteBuffer.allocate(1024 * 1024), 0) > 0);
                extractor.unselectTrack(i);
            }
            assertEquals(video, gotVideo); assertEquals(audio, gotAudio);
            assertTrue("Full six-second fixture retained: " + duration, duration >= 5_900_000 && duration < 7_000_000);
        } finally { extractor.release(); }
        if (video) {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                android.graphics.Bitmap frame = retriever.getFrameAtTime(1_000_000);
                assertNotNull("Platform decoder produces a video frame", frame); frame.recycle();
            } finally { retriever.release(); }
        }
    }

    static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final ExecutorService threads = Executors.newCachedThreadPool();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicBoolean stopped = new AtomicBoolean();
        volatile boolean slow;
        volatile boolean repairMissing;
        Fixture() throws Exception {
            threads.execute(() -> {
                while (!stopped.get()) try {
                    Socket socket = server.accept(); threads.execute(() -> serve(socket));
                } catch (IOException ignored) { }
            });
        }
        String url(String path) { return "http://127.0.0.1:" + server.getLocalPort() + "/" + path; }
        private void serve(Socket socket) {
            try (Socket close = socket) {
                socket.setSoTimeout(10000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String request = reader.readLine(); if (request == null) return;
                String[] first = request.split(" "); String path = new URI(first[1]).getPath().substring(1);
                Map<String, String> headers = new HashMap<>(); String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int sep = line.indexOf(':'); if (sep > 0) headers.put(line.substring(0, sep).toLowerCase(Locale.ROOT), line.substring(sep + 1).trim());
                }
                requests.add(path + " " + headers.getOrDefault("range", ""));
                if (repairMissing && path.equals("missing.mp4")) path = "sample.mp4";
                if (!path.matches("[a-zA-Z0-9_.-]+")) { respond(socket, "404 Not Found", "text/plain", new byte[0], 0, false, false); return; }
                byte[] data;
                try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("ytdlp/" + path);
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n); data = output.toByteArray();
                } catch (IOException missing) { respond(socket, "404 Not Found", "text/plain", new byte[0], 0, false, false); return; }
                String type = path.endsWith(".mp4") ? "video/mp4" : path.endsWith(".webm") ? "video/webm" : path.endsWith(".aac") ? "audio/aac" : path.endsWith(".mp3") ? "audio/mpeg" : path.endsWith(".mpd") ? "application/dash+xml" : path.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "application/octet-stream";
                int offset = 0; String range = headers.get("range");
                if (range != null && range.matches("bytes=\\d+-.*")) offset = Integer.parseInt(range.substring(6, range.indexOf('-')));
                respond(socket, offset > 0 ? "206 Partial Content" : "200 OK", type, data, offset, "HEAD".equals(first[0]), slow && path.endsWith(".mp4"));
            } catch (Exception ignored) { /* Clients deliberately disconnect during cancel/resume tests. */ }
        }
        private void respond(Socket socket, String status, String type, byte[] data, int offset, boolean head, boolean throttle) throws Exception {
            OutputStream out = socket.getOutputStream();
            if (offset >= data.length && data.length > 0) { out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); return; }
            String header = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + (data.length - offset)
                    + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n";
            if (offset > 0) header += "Content-Range: bytes " + offset + "-" + (data.length - 1) + "/" + data.length + "\r\n";
            out.write((header + "\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush(); if (head) return;
            for (int pos = offset; pos < data.length; pos += 4096) {
                out.write(data, pos, Math.min(4096, data.length - pos)); out.flush(); if (throttle) Thread.sleep(100);
            }
        }
        @Override public void close() throws Exception { stopped.set(true); server.close(); threads.shutdownNow(); }
    }
    static void delete(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); file.delete(); }
}
