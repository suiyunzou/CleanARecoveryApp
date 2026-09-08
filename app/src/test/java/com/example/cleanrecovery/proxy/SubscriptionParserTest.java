package com.example.cleanrecovery.proxy;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 订阅解析单测：Base64 ss 列表 / Clash YAML / 单个 ss URI。 */
public class SubscriptionParserTest {

    @Test
    public void parsesPlaintextSsList() {
        String ua = Base64.getEncoder().encodeToString("aes-256-gcm:password".getBytes(StandardCharsets.UTF_8));
        String ub = Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:password".getBytes(StandardCharsets.UTF_8));
        String body = "ss://" + ua + "@1.2.3.4:8388#NodeA\n"
                + "ss://" + ub + "@5.6.7.8:8388#NodeB\n";
        List<ProxyNode> nodes = SubscriptionManager.parse(body);
        assertEquals(2, nodes.size());
        assertEquals("NodeA", nodes.get(0).name);
        assertEquals("1.2.3.4", nodes.get(0).server);
        assertEquals(8388, nodes.get(0).port);
        assertEquals("aes-256-gcm", nodes.get(0).cipher);
        assertEquals("password", nodes.get(0).password);
        assertEquals("NodeB", nodes.get(1).name);
        assertEquals("chacha20-ietf-poly1305", nodes.get(1).cipher);
    }

    @Test
    public void parsesBase64EncodedSsList() {
        String ua = Base64.getEncoder().encodeToString("aes-256-gcm:password".getBytes(StandardCharsets.UTF_8));
        String ub = Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:password".getBytes(StandardCharsets.UTF_8));
        String plain = "ss://" + ua + "@1.2.3.4:8388#A\n"
                + "ss://" + ub + "@5.6.7.8:8388#B\n";
        String b64 = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        List<ProxyNode> nodes = SubscriptionManager.parse(b64);
        assertEquals(2, nodes.size());
        assertEquals("A", nodes.get(0).name);
        assertEquals("B", nodes.get(1).name);
    }

    @Test
    public void parsesSsUriWithWholeBase64Userinfo() {
        // userinfo 为 base64(method:password)
        String userinfo = Base64.getEncoder().encodeToString("aes-256-gcm:secret".getBytes(StandardCharsets.UTF_8));
        String uri = "ss://" + userinfo + "@example.com:8388#MyNode";
        ProxyNode n = SubscriptionManager.parseSsUri(uri);
        assertNotNull(n);
        assertEquals("aes-256-gcm", n.cipher);
        assertEquals("secret", n.password);
        assertEquals("example.com", n.server);
        assertEquals(8388, n.port);
        assertEquals("MyNode", n.name);
    }

    @Test
    public void parsesClashYamlAllProtocols() {
        String yaml = "port: 7890\n"
                + "proxies:\n"
                + "  - name: \"hk-1\"\n"
                + "    type: ss\n"
                + "    server: 1.2.3.4\n"
                + "    port: 8388\n"
                + "    cipher: aes-256-gcm\n"
                + "    password: \"p@ss\"\n"
                + "  - name: vmess-1\n"
                + "    type: vmess\n"
                + "    server: 5.6.7.8\n"
                + "    port: 443\n"
                + "proxy-groups:\n"
                + "  - name: auto\n";
        List<ProxyNode> nodes = SubscriptionManager.parseClashYaml(yaml);
        assertEquals(2, nodes.size());
        assertEquals("hk-1", nodes.get(0).name);
        assertEquals("aes-256-gcm", nodes.get(0).cipher);
        assertEquals("p@ss", nodes.get(0).password);
        assertEquals(8388, nodes.get(0).port);
        assertEquals("vmess", nodes.get(1).protocol);
        assertTrue(nodes.get(1).clashYaml.contains("type: vmess"));
    }

    @Test
    public void parseAutoDetectsClash() {
        String yaml = "proxies:\n  - name: x\n    type: ss\n    server: 9.9.9.9\n    port: 1234\n    cipher: aes-256-gcm\n    password: pw\n";
        List<ProxyNode> nodes = SubscriptionManager.parse(yaml);
        assertTrue(nodes.size() == 1);
        assertEquals("x", nodes.get(0).name);
    }

    @Test
    public void parsesVlessAndTrojanUris() {
        String body = "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443"
                + "?security=tls&type=ws&host=cdn.example.com&path=%2Fws&sni=example.com#VLESS-A\n"
                + "trojan://secret@example.net:443?sni=edge.example.net#Trojan-B";
        List<ProxyNode> nodes = SubscriptionManager.parse(body);
        assertEquals(2, nodes.size());
        assertEquals("vless", nodes.get(0).protocol);
        assertTrue(nodes.get(0).clashYaml.contains("ws-opts:"));
        assertTrue(nodes.get(0).clashYaml.contains("uuid:"));
        assertEquals("trojan", nodes.get(1).protocol);
        assertTrue(nodes.get(1).clashYaml.contains("password: 'secret'"));
    }

    @Test
    public void parsesInlineClashNode() {
        String yaml = "proxies:\n"
                + "  - {name: Inline, type: hysteria2, server: hy.example.com, "
                + "port: 443, password: pw, sni: hy.example.com}\n"
                + "rules:\n  - MATCH,DIRECT\n";
        List<ProxyNode> nodes = SubscriptionManager.parseClashYaml(yaml);
        assertEquals(1, nodes.size());
        assertEquals("Inline", nodes.get(0).name);
        assertEquals("hysteria2", nodes.get(0).protocol);
    }

    @Test
    public void preservesNestedYamlListsInsideNode() {
        String yaml = "proxies:\n"
                + "  - name: TLS node\n"
                + "    type: vless\n"
                + "    server: one.example.com\n"
                + "    port: 443\n"
                + "    alpn:\n"
                + "      - h2\n"
                + "      - http/1.1\n"
                + "  - name: next\n"
                + "    type: trojan\n"
                + "    server: two.example.com\n"
                + "    port: 443\n";
        List<ProxyNode> nodes = SubscriptionManager.parseClashYaml(yaml);
        assertEquals(2, nodes.size());
        assertTrue(nodes.get(0).clashYaml.contains("      - h2"));
        assertTrue(nodes.get(0).clashYaml.contains("      - http/1.1"));
        assertEquals("next", nodes.get(1).name);
    }
}
