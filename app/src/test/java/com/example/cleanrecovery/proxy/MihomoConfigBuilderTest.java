package com.example.cleanrecovery.proxy;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MihomoConfigBuilderTest {

    @Test
    public void buildsSelectedGroupAndLegacySsNode() {
        ProxyNode first = new ProxyNode(
                "SS-A", "1.2.3.4", 8388, "aes-256-gcm", "secret");
        ProxyNode second = new ProxyNode(
                "SS-B", "5.6.7.8", 8388, "chacha20-ietf-poly1305", "pw");

        String yaml = MihomoConfigBuilder.build(
                Arrays.asList(first, second), second, 17890, 19090, "token");

        assertTrue(yaml.contains("mixed-port: 17890"));
        assertTrue(yaml.contains("external-controller: 127.0.0.1:19090"));
        assertTrue(yaml.contains("name: 'SS-B'"));
        assertTrue(yaml.indexOf("- 'SS-B'") < yaml.indexOf("- 'SS-A'"));
        assertFalse(yaml.contains("password: null"));
    }

    @Test
    public void preservesNestedTransportOptions() {
        ProxyNode node = new ProxyNode();
        node.name = "VLESS";
        node.protocol = "vless";
        node.server = "example.com";
        node.port = 443;
        node.clashYaml = "- name: VLESS\n"
                + "  type: vless\n"
                + "  server: example.com\n"
                + "  port: 443\n"
                + "  uuid: id\n"
                + "  ws-opts:\n"
                + "    path: /ws\n"
                + "    headers:\n"
                + "      Host: cdn.example.com\n";

        String yaml = MihomoConfigBuilder.build(
                java.util.Collections.singletonList(node), node, 17890, 19090, "token");

        assertTrue(yaml.contains("    ws-opts:\n"
                + "      path: /ws\n"
                + "      headers:\n"
                + "        Host: cdn.example.com"));
    }
}
