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
 * 本地 SOCKS5 服务（仅 127.0.0.1，无认证，仅 CONNECT）。
 *
 * <p>接受本地客户端（WebView 经 ProxyController 路由）的 SOCKS5 请求，
 * 解析目标后交给 {@link SsRelay} 走 SS 远程连接。</p>
 *
 * <p>端口自动选择：绑定 {@code 127.0.0.1:0}，启动后通过 {@link #getLocalPort()} 暴露实际端口。</p>
 */
public final class Socks5Server {

    private static final String TAG = "Socks5Server";

    private final ProxyNode node;
    private ServerSocket serverSocket;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;

    public Socks5Server(ProxyNode node) {
        this.node = node;
    }

    /** 启动服务，返回本地端口；失败抛 IOException。 */
    public int start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "socks5-accept");
        acceptThread.start();
        android.util.Log.i(TAG, "SOCKS5 listening on 127.0.0.1:" + serverSocket.getLocalPort());
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

            // 握手：05 NMETHODS METHODS...
            int ver = in.read();
            if (ver != 0x05) { close(client); return; }
            int nMethods = in.read();
            if (nMethods < 0) { close(client); return; }
            byte[] methods = new byte[nMethods];
            readFully(in, methods);
            // 无需认证
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            // 请求：05 CMD 00 ATYP ADDR PORT
            if (in.read() != 0x05) { close(client); return; }
            int cmd = in.read();
            if (cmd != 0x01) { // 仅支持 CONNECT
                out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                close(client);
                return;
            }
            in.read(); // RSV
            int atyp = in.read();
            String host;
            if (atyp == 0x01) {
                byte[] ip = new byte[4];
                readFully(in, ip);
                host = (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
            } else if (atyp == 0x03) {
                int len = in.read() & 0xFF;
                byte[] d = new byte[len];
                readFully(in, d);
                host = new String(d, java.nio.charset.StandardCharsets.UTF_8);
            } else if (atyp == 0x04) {
                byte[] ip = new byte[16];
                readFully(in, ip);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 16; i++) {
                    if (i > 0 && i % 2 == 0) sb.append(':');
                    sb.append(String.format("%02x", ip[i] & 0xFF));
                }
                host = sb.toString();
            } else {
                close(client);
                return;
            }
            int port = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);

            // 回复成功
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();

            android.util.Log.i(TAG, "CONNECT " + host + ":" + port + " via " + node.display());
            SsRelay.relay(client, node, host, port);
        } catch (Exception e) {
            android.util.Log.w(TAG, "handle error: " + e.getMessage());
            close(client);
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("unexpected EOF");
            off += n;
        }
    }

    private static void close(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) {}
    }
}
