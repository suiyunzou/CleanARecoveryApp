package com.example.cleanrecovery.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal helper for the optional, user-initiated root workflow used by the
 * offline partition-carving algorithms. Everything here is strictly read-only
 * against block devices ({@code dd}); nothing is ever written back to a
 * partition. On non-rooted devices {@link #isRootAvailable()} returns false and
 * the offline algorithms stay disabled.
 */
public final class RootShell {
    /** Common by-name locations for the userdata partition across vendors. */
    private static final String[] USERDATA_CANDIDATES = {
        "/dev/block/by-name/userdata",
        "/dev/block/bootdevice/by-name/userdata",
        "/dev/block/platform/bootdevice/by-name/userdata",
    };

    private static final long PROBE_TIMEOUT_MS = 8_000L;

    private static volatile Boolean rootCache;

    private RootShell() {
    }

    /** True when {@code su} is present and grants uid 0. Cached per process. */
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
            try {
                ShellResult result = runSu("id", PROBE_TIMEOUT_MS);
                available = result != null
                        && result.exitCode == 0
                        && result.stdout.toLowerCase(Locale.US).contains("uid=0");
            } catch (Exception ignored) {
                available = false;
            }
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
                ShellResult result = runSu("test -e " + candidate + " && echo OK", PROBE_TIMEOUT_MS);
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
            Process process = new ProcessBuilder("su", "-c", "dd if=" + device + " bs=" + length + " count=1")
                    .redirectErrorStream(false)
                    .start();
            byte[] data = readUpTo(process.getInputStream(), length);
            process.getErrorStream().close();
            process.destroy();
            return data;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Open a streaming read of an entire block device. The caller MUST close the
     * returned handle, which terminates the underlying {@code su dd} process.
     */
    public static PartitionStream openPartitionStream(String device) throws IOException {
        Process process = new ProcessBuilder("su", "-c", "dd if=" + device + " bs=1M")
                .redirectErrorStream(false)
                .start();
        return new PartitionStream(process);
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

    /** Streaming handle around a {@code su dd} process. */
    public static final class PartitionStream implements Closeable {
        private final Process process;
        public final InputStream input;

        PartitionStream(Process process) {
            this.process = process;
            this.input = process.getInputStream();
        }

        @Override
        public void close() {
            try {
                input.close();
            } catch (Exception ignored) {
            }
            try {
                process.getErrorStream().close();
            } catch (Exception ignored) {
            }
            process.destroy();
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

    private static ShellResult runSu(String command, long timeoutMs) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        String out = new String(readUpTo(process.getInputStream(), 64 * 1024), StandardCharsets.UTF_8);
        boolean finished = waitFor(process, timeoutMs);
        if (!finished) {
            process.destroy();
            return null;
        }
        return new ShellResult(process.exitValue(), out);
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
}
