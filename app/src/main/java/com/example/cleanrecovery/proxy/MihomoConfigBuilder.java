package com.example.cleanrecovery.proxy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds the minimal Clash configuration consumed by the embedded Mihomo core. */
public final class MihomoConfigBuilder {

    public static final String GROUP_NAME = "Browser";

    private MihomoConfigBuilder() {
    }

    public static String build(
            List<ProxyNode> sourceNodes,
            ProxyNode selected,
            int mixedPort,
            int controllerPort,
            String secret) {
        List<ProxyNode> nodes = new ArrayList<>();
        if (selected == null || !selected.isValid()) {
            throw new IllegalArgumentException("selected node is invalid");
        }
        nodes.add(selected);
        if (sourceNodes != null) {
            for (ProxyNode node : sourceNodes) {
                if (node != null && node.isValid()
                        && !selected.name.equals(node.name)) {
                    nodes.add(node);
                }
            }
        }
        if (mixedPort <= 0 || controllerPort <= 0) {
            throw new IllegalArgumentException("invalid local port");
        }

        StringBuilder out = new StringBuilder(4096);
        out.append("mixed-port: ").append(mixedPort).append('\n');
        out.append("allow-lan: false\n");
        out.append("bind-address: 127.0.0.1\n");
        out.append("mode: rule\n");
        out.append("log-level: info\n");
        out.append("ipv6: true\n");
        out.append("external-controller: 127.0.0.1:")
                .append(controllerPort).append('\n');
        out.append("secret: ").append(quote(secret)).append('\n');
        out.append("proxies:\n");
        for (ProxyNode node : nodes) {
            appendNode(out, node);
        }

        Set<String> names = new LinkedHashSet<>();
        names.add(selected.name);
        for (ProxyNode node : nodes) names.add(node.name);
        out.append("proxy-groups:\n");
        out.append("  - name: ").append(quote(GROUP_NAME)).append('\n');
        out.append("    type: select\n");
        out.append("    proxies:\n");
        for (String name : names) {
            out.append("      - ").append(quote(name)).append('\n');
        }
        out.append("rules:\n");
        out.append("  - MATCH,").append(GROUP_NAME).append('\n');
        return out.toString();
    }

    private static void appendNode(StringBuilder out, ProxyNode node) {
        String yaml = node.clashYaml;
        if (yaml == null || yaml.trim().isEmpty()) {
            if (!node.isLegacySs()) {
                throw new IllegalArgumentException("missing Clash YAML: " + node.name);
            }
            out.append("  - name: ").append(quote(node.name)).append('\n');
            out.append("    type: ss\n");
            out.append("    server: ").append(quote(node.server)).append('\n');
            out.append("    port: ").append(node.port).append('\n');
            out.append("    cipher: ").append(quote(node.cipher)).append('\n');
            out.append("    password: ").append(quote(node.password)).append('\n');
            return;
        }

        String[] lines = yaml.replace("\r", "").split("\n");
        int baseIndent = leadingSpaces(firstNonBlank(lines));
        boolean first = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) continue;
            int remove = Math.min(baseIndent, leadingSpaces(line));
            String normalized = line.substring(remove);
            if (first && !normalized.startsWith("-")) {
                normalized = "- " + normalized;
            }
            out.append("  ").append(normalized).append('\n');
            first = false;
        }
    }

    private static String firstNonBlank(String[] lines) {
        for (String line : lines) {
            if (!line.trim().isEmpty()) return line;
        }
        return "";
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') count++;
        return count;
    }

    static String quote(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "''") + "'";
    }
}
