package com.example.cleanrecovery.proxy;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * SS 单连接双向中继：把本地客户端与 SS 服务器之间打通。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>TCP 连接 SS 服务器（{@code node.server:node.port}）。</li>
 *   <li>写出 salt + 加密地址帧（ATYP+ADDR+PORT）。</li>
 *   <li>客户端→服务器：明文喂 {@link SsEncryptor}，密文写 SS socket。</li>
 *   <li>服务器→客户端：密文喂 {@link SsDecryptor}，明文写回客户端。</li>
 * </ol>
 */
public final class SsRelay {

    private static final int BUF = 16 * 1024;

    private SsRelay() {
    }

    /**
     * 执行一次中继，直到任一方向关闭或出错。
     *
     * @param client     本地客户端 socket（已完成 SOCKS5/HTTP 握手）
     * @param node       SS 节点
     * @param targetHost 目标主机
     * @param targetPort 目标端口
     */
    public static void relay(Socket client, ProxyNode node, String targetHost, int targetPort) {
        Socket remote = null;
        try {
            client.setTcpNoDelay(true);
            SsCrypto.CipherSpec spec = SsCrypto.specByName(node.cipher);
            if (spec == null) {
                android.util.Log.w("SsRelay", "unsupported cipher: " + node.cipher);
                return;
            }
            byte[] masterKey = SsCrypto.deriveKey(node.password, spec.keyLen);

            remote = new Socket(node.server, node.port);
            remote.setTcpNoDelay(true);
            OutputStream remoteOut = remote.getOutputStream();
            InputStream remoteIn = remote.getInputStream();

            SsEncryptor enc = new SsEncryptor(spec, masterKey);
            // 1) salt
            remoteOut.write(enc.salt());
            // 2) 地址帧作为明文流前缀
            byte[] addrFrame = SsAddress.buildFrame(targetHost, targetPort);
            remoteOut.write(enc.update(addrFrame, 0, addrFrame.length));
            remoteOut.flush();

            final Socket fRemote = remote;
            final SsEncryptor fEnc = enc;
            final InputStream clientIn = client.getInputStream();
            final OutputStream clientOut = client.getOutputStream();
            final InputStream fRemoteIn = remoteIn;

            Thread up = new Thread(() -> {
                try {
                    byte[] buf = new byte[BUF];
                    int n;
                    while ((n = clientIn.read(buf)) > 0) {
                        byte[] cipher = fEnc.update(buf, 0, n);
                        if (cipher.length > 0) fRemote.getOutputStream().write(cipher);
                        byte[] tail = fEnc.flush();
                        if (tail.length > 0) fRemote.getOutputStream().write(tail);
                        fRemote.getOutputStream().flush();
                    }
                } catch (Exception ignored) {
                } finally {
                    try { fRemote.shutdownOutput(); } catch (Exception ignored) {}
                }
            }, "ss-up");

            Thread down = new Thread(() -> {
                try {
                    SsDecryptor dec = new SsDecryptor(spec, masterKey);
                    byte[] buf = new byte[BUF];
                    int n;
                    while ((n = fRemoteIn.read(buf)) > 0) {
                        byte[] plain = dec.update(buf, 0, n);
                        if (plain.length > 0) clientOut.write(plain);
                        clientOut.flush();
                    }
                } catch (Exception ignored) {
                } finally {
                    try { client.shutdownOutput(); } catch (Exception ignored) {}
                }
            }, "ss-down");

            up.start();
            down.start();
            up.join();
            down.join();
        } catch (Exception e) {
            android.util.Log.w("SsRelay", "relay error: " + e.getMessage());
        } finally {
            closeQuiet(remote);
            closeQuiet(client);
        }
    }

    private static void closeQuiet(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) {}
    }
}
