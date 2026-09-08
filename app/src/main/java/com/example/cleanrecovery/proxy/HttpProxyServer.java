package com.example.cleanrecovery.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 HTTP 代理服务（仅 127.0.0.1）。
 *
 * <p>实现 {@code CONNECT} 隧道（HTTPS），握手成功后交给 {@link SsRelay} 走 SS。
 * 用于 WebView 在 SOCKS5 路由失败时的备选路径（WebView 经 HTTP 代理）。</p>
 *
 * <p>非 CONNECT 的明文 HTTP 请求本实现不转发（返回 405），因为 WebView 实际流量以
 * HTTPS CONNECT 为主。</p>
 */
public final class HttpProxyServer {

    private static final String TAG = "HttpProxyServer";

    private final ProxyNode node;
    private ServerSocket serverSocket;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;

    public HttpProxyServer(ProxyNode node) {
        this.node = node;
    }

    public int start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "httpproxy-accept");
        acceptThread.start();
        android.util.Log.i(TAG, "HTTP proxy listening on 127.0.0.1:" + serverSocket.getLocalPort());
        return serverSocket.getLocalPort();
    }

    public int getLocalPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (running.get()) android.util.Log.w(TAG, "accept error: " + e.getMessage());
                break;
            }
            workers.execute(() -> handle(client));
        }
    }

    private void handle(Socket client) {
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // 读取请求行
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) { close(client); return; }
            // 读取并丢弃头部
            while (true) {
                String h = readLine(in);
                if (h == null || h.isEmpty()) break;
            }

            // CONNECT host:port HTTP/1.1
            if (requestLine.startsWith("CONNECT ")) {
                String target = requestLine.substring("CONNECT ".length()).split(" ")[0];
                int colon = target.lastIndexOf(':');
                if (colon < 0) { close(client); return; }
                String host = target.substring(0, colon);
                int port;
                try {
                    port = Integer.parseInt(target.substring(colon + 1));
                } catch (NumberFormatException e) {
                    out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                    out.flush();
                    close(client);
                    return;
                }
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                out.flush();
                android.util.Log.i(TAG, "CONNECT " + host + ":" + port + " via " + node.display());
                SsRelay.relay(client, node, host, port);
            } else {
                // 非 CONNECT：不转发明文 HTTP
                out.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".getBytes());
                out.flush();
                close(client);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "handle error: " + e.getMessage());
            close(client);
        }
    }

    /** 读一行（CRLF/LF 结尾），返回不含换行的字符串；遇到 EOF 返回已读部分或 null。 */
    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int prev = -1;
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                if (prev == '\r') {
                    byte[] arr = buf.toByteArray();
                    return new String(arr, 0, arr.length - 1 >= 0 ? arr.length - 1 : 0,
                            java.nio.charset.StandardCharsets.UTF_8);
                }
                return buf.toString(java.nio.charset.StandardCharsets.UTF_8.name());
            }
            buf.write(b);
            prev = b;
            if (buf.size() > 8192) throw new IOException("header too long");
        }
        if (buf.size() == 0) return null;
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8.name());
    }

    private static void close(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) {}
    }
}
