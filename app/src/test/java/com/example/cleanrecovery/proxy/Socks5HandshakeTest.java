package com.example.cleanrecovery.proxy;

import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import static org.junit.Assert.assertEquals;

/**
 * SOCKS5 握手单测：启动本地 SOCKS5 服务，对它做一次完整握手并校验回复。
 *
 * <p>使用一个指向 127.0.0.1:1 的伪节点（relay 必然连接失败，但握手回复在 relay 之前发出）。</p>
 */
public class Socks5HandshakeTest {

    @Test
    public void socks5HandshakeReturnsSuccess() throws Exception {
        ProxyNode node = new ProxyNode("fake", "127.0.0.1", 65534, "aes-256-gcm", "pw");
        Socks5Server server = new Socks5Server(node);
        int port = server.start();
        try {
            Socket c = new Socket("127.0.0.1", port);
            c.setSoTimeout(5000);
            OutputStream out = c.getOutputStream();
            InputStream in = c.getInputStream();
            // 握手：05 01 00
            out.write(new byte[]{0x05, 0x01, 0x00});
            out.flush();
            assertEquals(0x05, in.read());
            assertEquals(0x00, in.read());
            // CONNECT example.com:443 (ATYP=03 域名)
            byte[] domain = "example.com".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] req = new byte[7 + domain.length];
            req[0] = 0x05; // ver
            req[1] = 0x01; // CONNECT
            req[2] = 0x00; // RSV
            req[3] = 0x03; // domain
            req[4] = (byte) domain.length;
            System.arraycopy(domain, 0, req, 5, domain.length);
            req[5 + domain.length] = 0x01; // port hi (443)
            req[6 + domain.length] = (byte) 0xBB; // port lo
            out.write(req);
            out.flush();
            // 读取成功回复：05 00 00 01 00 00 00 00 00 00
            assertEquals(0x05, in.read());
            assertEquals(0x00, in.read());
            assertEquals(0x00, in.read());
            assertEquals(0x01, in.read());
            c.close();
        } finally {
            server.stop();
        }
    }
}
