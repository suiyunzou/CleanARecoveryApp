package com.example.cleanrecovery.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** Real HTTP framing tests: only complete, correctly assembled bytes may become a final file. */
public class UniversalDownloadManagerHttpTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void validPartialResponsePreservesPrefixAndAppendsExactRemainingBytes() throws Exception {
        byte[] full = payload();
        File output = outputWithPrefix(full, 333);
        try (LocalServer server = new LocalServer(new Response("bytes=333-", 206,
                Arrays.copyOfRange(full, 333, full.length), "bytes 333-1023/1024"))) {
            download(server, output, 1, null);
            server.assertFinished();
            assertCompleteBytes(full, output);
        }
    }

    @Test
    public void wrongPartialRangeRequiresFreshRequestInsteadOfPublishingOnlyTheTail() throws Exception {
        byte[] full = payload();
        File output = outputWithPrefix(full, 128);
        try (LocalServer server = new LocalServer(
                new Response("bytes=128-", 206,
                        Arrays.copyOfRange(full, 256, full.length), "bytes 256-1023/1024"),
                new Response(null, 200, full, null))) {
            download(server, output, 1, null);
            server.assertFinished();
            assertCompleteBytes(full, output);
        }
    }

    @Test
    public void unknownPartialTotalRequiresFullResponseInsteadOfPublishingTheRangeEnd() throws Exception {
        byte[] full = payload();
        File output = outputWithPrefix(full, 333);
        try (LocalServer server = new LocalServer(
                new Response("bytes=333-", 206,
                        Arrays.copyOfRange(full, 333, 512), "bytes 333-511/*"),
                new Response(null, 200, full, null))) {
            download(server, output, 1, null);
            server.assertFinished();
            assertCompleteBytes(full, output);
        }
    }

    @Test
    public void rangeNotSatisfiableMustNotTreatAlmostCompletePartAsComplete() throws Exception {
        byte[] full = payload();
        File output = outputWithPrefix(full, full.length - 49);
        try (LocalServer server = new LocalServer(
                new Response("bytes=975-", 416, new byte[0], "bytes */1024"),
                new Response(null, 200, full, null))) {
            download(server, output, 1, null);
            server.assertFinished();
            assertCompleteBytes(full, output);
        }
    }

    @Test
    public void rangeRecoveryMustRemoveCallerRangeBeforeRequestingTheCompleteFile() throws Exception {
        byte[] full = payload();
        File output = outputWithPrefix(full, full.length - 49);
        try (LocalServer server = new LocalServer(
                new Response("bytes=975-", 416, new byte[0], "bytes */1024"),
                new Response(null, 200, full, null))) {
            new UniversalDownloadManager(2_000, 2_000, 1, 0).download(server.url(), output,
                    Collections.singletonMap("Range", "bytes=975-"), null);
            server.assertFinished();
            assertCompleteBytes(full, output);
        }
    }

    @Test
    public void prematureEofFailsWithoutPublishingAndKeepsBytesForResume() throws Exception {
        byte[] full = payload();
        byte[] prefix = Arrays.copyOf(full, 333);
        File output = new File(temporaryFolder.getRoot(), "download.bin");
        CompletionRecorder callback = new CompletionRecorder();
        try (LocalServer server = new LocalServer(
                new Response(null, 200, prefix, null, full.length))) {
            try {
                download(server, output, 1, callback);
                fail("A body shorter than Content-Length must not succeed");
            } catch (IOException expected) {
                // The failure contract is IOException, independent of its transport-specific message.
            }
            server.assertFinished();
            assertFalse("An incomplete response must not publish a final file", output.exists());
            assertArrayEquals("Received bytes must remain available for a later resume",
                    prefix, Files.readAllBytes(partFile(output).toPath()));
            assertEquals("Incomplete bytes must never trigger completion", 0, callback.completions);
        }
    }

    @Test
    public void retryAfterPrematureEofResumesFromReceivedBytesAndCompletesExactlyOnce() throws Exception {
        byte[] full = payload();
        File output = new File(temporaryFolder.getRoot(), "download.bin");
        CompletionRecorder callback = new CompletionRecorder();
        try (LocalServer server = new LocalServer(
                new Response(null, 200, Arrays.copyOf(full, 333), null, full.length),
                new Response("bytes=333-", 206,
                        Arrays.copyOfRange(full, 333, full.length), "bytes 333-1023/1024"))) {
            download(server, output, 2, callback);
            server.assertFinished();
            assertCompleteBytes(full, output);
            assertEquals("Retries must publish completion only after all bytes arrive",
                    1, callback.completions);
        }
    }

    private static byte[] payload() {
        byte[] bytes = new byte[1024];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + i / 251);
        }
        return bytes;
    }

    private File outputWithPrefix(byte[] full, int length) throws IOException {
        File output = new File(temporaryFolder.getRoot(), "download.bin");
        Files.write(partFile(output).toPath(), Arrays.copyOf(full, length));
        return output;
    }

    private static File partFile(File output) {
        return new File(output.getAbsolutePath() + UniversalDownloadManager.PART_SUFFIX);
    }

    private static void download(LocalServer server, File output, int attempts,
                                 DownloadProgressCallback callback) throws IOException {
        new UniversalDownloadManager(2_000, 2_000, attempts, 0)
                .download(server.url(), output, callback);
    }

    private static void assertCompleteBytes(byte[] expected, File output) throws IOException {
        assertTrue("A complete response must publish the final file", output.isFile());
        assertArrayEquals("The published file must contain the complete original representation",
                expected, Files.readAllBytes(output.toPath()));
        assertFalse("Successful publication must consume the temporary file", partFile(output).exists());
    }

    private static final class CompletionRecorder implements DownloadProgressCallback {
        int completions;

        @Override
        public void onProgress(long downloaded, long total, long speed, int percent) {}

        @Override
        public void onStatusChanged(String status, String message) {}

        @Override
        public void onComplete(String path) {
            completions++;
        }

        @Override
        public void onError(String code, String message) {}
    }

    private static final class Response {
        final String expectedRange;
        final int status;
        final byte[] body;
        final String contentRange;
        final int contentLength;

        Response(String expectedRange, int status, byte[] body, String contentRange) {
            this(expectedRange, status, body, contentRange, body.length);
        }

        Response(String expectedRange, int status, byte[] body, String contentRange, int contentLength) {
            this.expectedRange = expectedRange;
            this.status = status;
            this.body = body;
            this.contentRange = contentRange;
            this.contentLength = contentLength;
        }
    }

    /** A finite response script; closing each connection also permits deliberately truncated bodies. */
    private static final class LocalServer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread worker;
        private volatile Socket active;
        private volatile boolean closed;
        private volatile Throwable failure;

        LocalServer(Response... responses) throws IOException {
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(4_000);
            worker = new Thread(() -> {
                try {
                    for (Response response : responses) {
                        try (Socket socket = listener.accept()) {
                            active = socket;
                            socket.setSoTimeout(2_000);
                            BufferedReader input = new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.US_ASCII));
                            assertEquals("GET /download HTTP/1.1", input.readLine());
                            String range = null;
                            String line;
                            while ((line = input.readLine()) != null && !line.isEmpty()) {
                                int colon = line.indexOf(':');
                                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Range")) {
                                    range = line.substring(colon + 1).trim();
                                }
                            }
                            assertEquals("Resume must use the requested offset; restart must omit Range",
                                    response.expectedRange, range);
                            String headers = "HTTP/1.1 " + response.status + " Test\r\n"
                                    + "Content-Length: " + response.contentLength + "\r\n"
                                    + "Connection: close\r\n"
                                    + (response.contentRange == null ? ""
                                    : "Content-Range: " + response.contentRange + "\r\n") + "\r\n";
                            OutputStream output = socket.getOutputStream();
                            output.write(headers.getBytes(StandardCharsets.US_ASCII));
                            output.write(response.body);
                            output.flush();
                        } finally {
                            active = null;
                        }
                    }
                } catch (Throwable error) {
                    if (!closed) failure = error;
                }
            }, "download-http-test");
            worker.start();
        }

        String url() {
            return "http://127.0.0.1:" + listener.getLocalPort() + "/download";
        }

        void assertFinished() throws InterruptedException {
            worker.join(5_000);
            assertFalse("The downloader must consume the expected HTTP exchange", worker.isAlive());
            if (failure != null) throw new AssertionError("Local HTTP exchange failed", failure);
        }

        @Override
        public void close() throws Exception {
            closed = true;
            try {
                listener.close();
            } finally {
                Socket socket = active;
                try {
                    if (socket != null) socket.close();
                } finally {
                    worker.join(3_000);
                    assertFalse("The local HTTP server must release its worker", worker.isAlive());
                }
            }
        }
    }
}
