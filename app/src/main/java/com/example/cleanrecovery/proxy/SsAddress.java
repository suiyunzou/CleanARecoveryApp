package com.example.cleanrecovery.proxy;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * SS 地址帧编解码：{@code ATYP + ADDR + PORT}。
 *
 * <ul>
 *   <li>0x01：IPv4（4 字节）</li>
 *   <li>0x03：域名（1 字节长度 + 域名）</li>
 *   <li>0x04：IPv6（16 字节）</li>
 * </ul>
 */
public final class SsAddress {

    public static final byte ATYP_IPV4 = 0x01;
    public static final byte ATYP_DOMAIN = 0x03;
    public static final byte ATYP_IPV6 = 0x04;

    private SsAddress() {
    }

    /** 按目标主机/端口构造地址帧；优先按域名（浏览器常见），其次尝试 IPv4/IPv6 解析。 */
    public static byte[] buildFrame(String host, int port) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 简单判断是否为 IP 字面量
        byte[] ipBytes = tryParseIp(host);
        if (ipBytes != null && ipBytes.length == 4) {
            out.write(ATYP_IPV4);
            out.write(ipBytes, 0, 4);
        } else if (ipBytes != null && ipBytes.length == 16) {
            out.write(ATYP_IPV6);
            out.write(ipBytes, 0, 16);
        } else {
            byte[] d = host.getBytes(StandardCharsets.UTF_8);
            if (d.length > 255) {
                // 域名过长，降级为 IPv4 占位（极少触发）
                out.write(ATYP_IPV4);
                out.write(new byte[4], 0, 4);
            } else {
                out.write(ATYP_DOMAIN);
                out.write((byte) d.length);
                out.write(d, 0, d.length);
            }
        }
        out.write((byte) (port >>> 8));
        out.write((byte) (port));
        return out.toByteArray();
    }

    /** 解析地址帧，返回 {host, port, consumed}。 */
    public static Parsed parse(byte[] data, int off) {
        int p = off;
        byte atyp = data[p++];
        String host;
        if (atyp == ATYP_IPV4) {
            host = (data[p] & 0xFF) + "." + (data[p + 1] & 0xFF) + "."
                    + (data[p + 2] & 0xFF) + "." + (data[p + 3] & 0xFF);
            p += 4;
        } else if (atyp == ATYP_DOMAIN) {
            int len = data[p++] & 0xFF;
            host = new String(data, p, len, StandardCharsets.UTF_8);
            p += len;
        } else if (atyp == ATYP_IPV6) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (i > 0 && i % 2 == 0) sb.append(':');
                sb.append(String.format("%02x", data[p + i] & 0xFF));
            }
            host = sb.toString();
            p += 16;
        } else {
            throw new IllegalArgumentException("Unknown ATYP: " + atyp);
        }
        int port = ((data[p++] & 0xFF) << 8) | (data[p++] & 0xFF);
        return new Parsed(host, port, p - off);
    }

    /** 仅解析字面量 IP（不做 DNS 解析），否则返回 null（按域名处理）。 */
    private static byte[] tryParseIp(String host) {
        if (host == null || host.isEmpty()) return null;
        if (isIpv4Literal(host)) {
            try {
                return Inet4Address.getByName(host).getAddress();
            } catch (UnknownHostException e) {
                return null;
            }
        }
        // 含 ':' 一定是 IPv6 字面量（域名不含 ':'），getByName 对字面量不做 DNS
        if (host.indexOf(':') >= 0) {
            try {
                return Inet6Address.getByName(host).getAddress();
            } catch (UnknownHostException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean isIpv4Literal(String s) {
        String[] parts = s.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            int v = Integer.parseInt(p);
            if (v < 0 || v > 255) return false;
        }
        return true;
    }

    public static final class Parsed {
        public final String host;
        public final int port;
        public final int consumed;

        public Parsed(String host, int port, int consumed) {
            this.host = host;
            this.port = port;
            this.consumed = consumed;
        }
    }
}
