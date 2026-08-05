package com.example.cleanrecovery.util;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal helper for the optional, user-initiated root workflow used by the
 * offline partition-carving algorithms. Everything here is strictly read-only
 * against block devices ({@code dd}); nothing is ever written back to a
 * partition. On non-rooted devices {@link #isRootAvailable()} returns false and
 * the offline algorithms stay disabled.
 *
 * <p>Privilege backends (first match wins):
 * <ul>
 *   <li>Magisk-style {@code su -c}</li>
 *   <li>AOSP emulator {@code su 0 sh -c} (only if the app can execute {@code su})</li>
 *   <li>Localhost root bridge on port {@value #BRIDGE_PORT} (emulator helper started
 *       via {@code tools/emulator-rootd/start-rootd.bat})</li>
 * </ul>
 */
public final class RootShell {
    /** Common by-name locations for the userdata partition across vendors. */
    private static final String[] USERDATA_CANDIDATES = {
        "/dev/block/by-name/userdata",
        "/dev/block/bootdevice/by-name/userdata",
        "/dev/block/platform/bootdevice/by-name/userdata",
    };

    /** Emulator root-bridge TCP port (localhost only). */
    public static final int BRIDGE_PORT = 28765;

    private static final String TAG = "RootShell";
    private static final long PROBE_TIMEOUT_MS = 3_000L;
    private static final int BRIDGE_CONNECT_MS = 2_000;

    private enum RootBackend {
        /** Magisk / common custom ROM: {@code su -c command}. */
        MAGISK_C,
        /** AOSP / emulator toybox: {@code su 0 sh -c command}. */
        AOSP_UID,
        /** Localhost TCP bridge started by adb as root. */
        BRIDGE
    }

    private static volatile Boolean rootCache;
    private static volatile RootBackend backendCache;

    private RootShell() {
    }

    /** Drop cached probe results (e.g. after starting the emulator root bridge). */
    public static void clearCache() {
        rootCache = null;
        backendCache = null;
    }

    /** True when a root backend grants uid 0. Cached per process. */
    public static boolean isRootAvailable() {
        Boolean cached = rootCache;
        if (cached != null) {
            return cached;
        }
        boolean available;
        if (!isAndroidRuntime()) {
            // Never shell out to a host `su` during JVM unit tests / tooling.
            available = false;
        } else {
            available = detectBackend() != null;
        }
        rootCache = available;
        return available;
    }

    private static boolean isAndroidRuntime() {
        String vm = System.getProperty("java.vm.name", "").toLowerCase(Locale.US);
        return vm.contains("dalvik") || vm.contains("art");
    }

    /** Locate the userdata block device, or null if none is reachable. */
    public static String detectUserdataDevice() {
        for (String candidate : USERDATA_CANDIDATES) {
            try {
                ShellResult result = runRoot("test -e " + candidate + " && echo OK", PROBE_TIMEOUT_MS);
                if (result != null && result.exitCode == 0 && result.stdout.contains("OK")) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // try next candidate
            }
        }
        return null;
    }

    /** Read the first {@code length} bytes of a block device via {@code dd}. */
    public static byte[] readHeader(String device, int length) {
        if (device == null || length <= 0) {
            return null;
        }
        try {
            try (PartitionStream stream = openPartitionStreamCommand(
                    "dd if=" + device + " bs=" + length + " count=1")) {
                return readUpTo(stream.input, length);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Open a streaming read of an entire block device. The caller MUST close the
     * returned handle, which terminates the underlying root {@code dd} process
     * or bridge connection.
     */
    public static PartitionStream openPartitionStream(String device) throws IOException {
        return openPartitionStreamCommand("dd if=" + device + " bs=1M");
    }

    /** Identify the filesystem from a superblock header (read-only inspection). */
    public static String detectFsType(byte[] header) {
        if (header == null) {
            return "UNKNOWN";
        }
        // ext4: magic 0xEF53 (little-endian) at byte offset 0x438.
        if (header.length >= 0x43A
                && (header[0x438] & 0xFF) == 0x53
                && (header[0x439] & 0xFF) == 0xEF) {
            return "EXT4";
        }
        // F2FS: superblock at offset 0x400, magic 0xF2F52010 (little-endian).
        if (header.length >= 0x404
                && (header[0x400] & 0xFF) == 0x10
                && (header[0x401] & 0xFF) == 0x20
                && (header[0x402] & 0xFF) == 0xF5
                && (header[0x403] & 0xFF) == 0xF2) {
            return "F2FS";
        }
        return "UNKNOWN";
    }

    /** Streaming handle around a root {@code dd} process or bridge socket. */
    public static final class PartitionStream implements Closeable {
        private final Closeable backend;
        public final InputStream input;

        PartitionStream(Closeable backend, InputStream input) {
            this.backend = backend;
            this.input = input;
        }

        @Override
        public void close() {
            try {
                input.close();
            } catch (Exception ignored) {
            }
            try {
                backend.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class ShellResult {
        final int exitCode;
        final String stdout;

        ShellResult(int exitCode, String stdout) {
            this.exitCode = exitCode;
            this.stdout = stdout;
        }
    }

    private static RootBackend detectBackend() {
        RootBackend cached = backendCache;
        if (cached != null) {
            return cached;
        }
        if (probeSu(RootBackend.MAGISK_C)) {
            backendCache = RootBackend.MAGISK_C;
            return RootBackend.MAGISK_C;
        }
        if (probeSu(RootBackend.AOSP_UID)) {
            backendCache = RootBackend.AOSP_UID;
            return RootBackend.AOSP_UID;
        }
        if (probeBridge()) {
            backendCache = RootBackend.BRIDGE;
            return RootBackend.BRIDGE;
        }
        return null;
    }

    private static boolean probeSu(RootBackend backend) {
        try {
            ShellResult result = runSuWithStyle(backend, "id", PROBE_TIMEOUT_MS);
            boolean ok = result != null
                    && result.exitCode == 0
                    && result.stdout.toLowerCase(Locale.US).contains("uid=0");
            Log.i(TAG, "su probe " + backend + " ok=" + ok
                    + " exit=" + (result == null ? -1 : result.exitCode)
                    + " out=" + (result == null ? "null" : result.stdout.trim()));
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "su probe " + backend + " failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean probeBridge() {
        Socket socket = null;
        try {
            socket = connectBridge();
            writeAscii(socket.getOutputStream(), "ID\n");
            String line = readLine(socket.getInputStream(), 4096);
            boolean ok = line != null && line.toLowerCase(Locale.US).contains("uid=0");
            Log.i(TAG, "bridge probe line=" + line + " ok=" + ok);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "bridge probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    private static PartitionStream openPartitionStreamCommand(String command) throws IOException {
        RootBackend backend = detectBackend();
        if (backend == null) {
            throw new IOException("su_unavailable");
        }
        if (backend == RootBackend.BRIDGE) {
            Socket socket = connectBridge();
            writeAscii(socket.getOutputStream(), "STREAM\n" + command + "\n");
            return new PartitionStream(socket, socket.getInputStream());
        }
        Process process = suBuilder(backend, command).redirectErrorStream(false).start();
        return new PartitionStream(new ProcessCloseable(process), process.getInputStream());
    }

    private static ShellResult runRoot(String command, long timeoutMs)
            throws IOException, InterruptedException {
        RootBackend backend = detectBackend();
        if (backend == null) {
            return null;
        }
        if (backend == RootBackend.BRIDGE) {
            return runBridgeExec(command, timeoutMs);
        }
        return runSuWithStyle(backend, command, timeoutMs);
    }

    private static ShellResult runBridgeExec(String command, long timeoutMs) throws IOException {
        Socket socket = connectBridge();
        try {
            writeAscii(socket.getOutputStream(), "EXEC\n" + command + "\n");
            socket.shutdownOutput();
            byte[] raw = readUpTo(socket.getInputStream(), 64 * 1024);
            String out = new String(raw, StandardCharsets.UTF_8);
            int exit = 0;
            int marker = out.lastIndexOf("EXIT:");
            if (marker >= 0) {
                String tail = out.substring(marker + 5).trim();
                int end = 0;
                while (end < tail.length() && Character.isDigit(tail.charAt(end))) {
                    end++;
                }
                if (end > 0) {
                    try {
                        exit = Integer.parseInt(tail.substring(0, end));
                    } catch (NumberFormatException ignored) {
                        exit = 1;
                    }
                }
                out = out.substring(0, marker);
            }
            return new ShellResult(exit, out);
        } finally {
            closeQuietly(socket);
        }
    }

    private static ShellResult runSuWithStyle(RootBackend backend, String command, long timeoutMs)
            throws IOException, InterruptedException {
        Process process = suBuilder(backend, command).redirectErrorStream(true).start();
        String out = new String(readUpTo(process.getInputStream(), 64 * 1024), StandardCharsets.UTF_8);
        boolean finished = waitFor(process, timeoutMs);
        if (!finished) {
            process.destroy();
            return null;
        }
        return new ShellResult(process.exitValue(), out);
    }

    private static ProcessBuilder suBuilder(RootBackend backend, String command) {
        if (backend == RootBackend.AOSP_UID) {
            return new ProcessBuilder("su", "0", "sh", "-c", command);
        }
        return new ProcessBuilder("su", "-c", command);
    }

    private static Socket connectBridge() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", BRIDGE_PORT), BRIDGE_CONNECT_MS);
        socket.setSoTimeout((int) PROBE_TIMEOUT_MS);
        return socket;
    }

    private static void writeAscii(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readLine(InputStream input, int max) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (int i = 0; i < max; i++) {
            int b = input.read();
            if (b < 0) {
                break;
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
        if (buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private static boolean waitFor(Process process, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException stillRunning) {
                Thread.sleep(50L);
            }
        }
        return false;
    }

    private static byte[] readUpTo(InputStream input, int max) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int read;
        while (total < max && (read = input.read(chunk, 0, Math.min(chunk.length, max - total))) >= 0) {
            buffer.write(chunk, 0, read);
            total += read;
        }
        return buffer.toByteArray();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private static final class ProcessCloseable implements Closeable {
        private final Process process;

        ProcessCloseable(Process process) {
            this.process = process;
        }

        @Override
        public void close() {
            try {
                process.getErrorStream().close();
            } catch (Exception ignored) {
            }
            process.destroy();
        }
    }
}
