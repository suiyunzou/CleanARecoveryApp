package com.example.cleanrecovery.proxy;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/** Lifecycle wrapper for the official embedded Mihomo Android executable. */
public final class MihomoProcess {
    private static final String TAG = "MihomoProcess";
    private static final int START_TIMEOUT_MS = 12_000;
    private static final int MAX_LOG_LINES = 80;

    private final Context context;
    private final ArrayDeque<String> recentLogs = new ArrayDeque<>();

    private Process process;
    private Thread logThread;
    private int mixedPort = -1;
    private int controllerPort = -1;

    public MihomoProcess(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized int start(List<ProxyNode> nodes, ProxyNode selected) throws Exception {
        if (isRunning()) return mixedPort;

        File binary = binaryFile();
        if (!binary.isFile() || !binary.canExecute()) {
            throw new IllegalStateException("Mihomo 内核不可执行: " + binary);
        }

        mixedPort = reservePort();
        controllerPort = reservePort();
        File home = new File(context.getFilesDir(), "mihomo");
        if (!home.exists() && !home.mkdirs()) {
            throw new IllegalStateException("无法创建 Mihomo 工作目录");
        }
        String secret = randomSecret();
        File config = new File(home, "config.yaml");
        String yaml = MihomoConfigBuilder.build(
                nodes, selected, mixedPort, controllerPort, secret);
        writeUtf8(config, yaml);

        ProcessBuilder builder = new ProcessBuilder(
                binary.getAbsolutePath(),
                "-d", home.getAbsolutePath(),
                "-f", config.getAbsolutePath());
        builder.directory(home);
        builder.redirectErrorStream(true);
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        process = builder.start();
        startLogReader(process);

        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) {
                String detail = recentLogText();
                stop();
                throw new IllegalStateException("Mihomo 提前退出\n" + detail);
            }
            if (canConnect(mixedPort)) {
                Log.i(TAG, "Mihomo ready on mixed port " + mixedPort);
                return mixedPort;
            }
            Thread.sleep(100);
        }
        String detail = recentLogText();
        stop();
        throw new IllegalStateException("Mihomo 启动超时\n" + detail);
    }

    public synchronized void stop() {
        Process active = process;
        process = null;
        if (active != null) {
            active.destroy();
        }
        if (logThread != null) {
            logThread.interrupt();
            logThread = null;
        }
        mixedPort = -1;
        controllerPort = -1;
    }

    public synchronized boolean isRunning() {
        if (process == null) return false;
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    public int mixedPort() {
        return mixedPort;
    }

    public int controllerPort() {
        return controllerPort;
    }

    public String recentLogText() {
        synchronized (recentLogs) {
            StringBuilder out = new StringBuilder();
            for (String line : recentLogs) out.append(line).append('\n');
            return out.toString().trim();
        }
    }

    public String version() throws Exception {
        Process check = new ProcessBuilder(binaryFile().getAbsolutePath(), "-v")
                .redirectErrorStream(true)
                .start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(check.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line;
        } finally {
            check.destroy();
        }
    }

    private File binaryFile() {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libmihomo.so");
    }

    private void startLogReader(Process active) {
        logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(active.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, line);
                    synchronized (recentLogs) {
                        if (recentLogs.size() >= MAX_LOG_LINES) recentLogs.removeFirst();
                        recentLogs.addLast(line);
                    }
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Log.w(TAG, "log reader stopped: " + e.getMessage());
                }
            }
        }, "mihomo-log");
        logThread.setDaemon(true);
        logThread.start();
    }

    private static int reservePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static boolean canConnect(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte value : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return out.toString();
    }

    private static void writeUtf8(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
}
