package com.example.cleanrecovery.proxy;

import com.example.cleanrecovery.extractor.ExtractorHttp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订阅管理器：拉取订阅 URL 并解析为 {@link ProxyNode} 列表。
 *
 * <p>支持两种格式：</p>
 * <ol>
 *   <li>Base64 编码的 {@code ss://} 列表（整段 base64 解码后按行解析）。</li>
 *   <li>Clash YAML：保留 {@code proxies:} 下的完整节点 YAML，交给 Mihomo 解析。</li>
 * </ol>
 *
 * <p>HTTP 拉取复用 {@link ExtractorHttp}（{@code java.net.HttpURLConnection}），
 * 不引入 OkHttp。</p>
 */
public final class SubscriptionManager {

    private SubscriptionManager() {
    }

    /** 拉取并解析订阅，返回节点列表（可能为空，不抛业务异常）。 */
    public static List<ProxyNode> fetch(String subscriptionUrl) throws Exception {
        String body = ExtractorHttp.downloadWebpage(subscriptionUrl, ExtractorHttp.defaultHeaders());
        return parse(body);
    }

    /** 解析订阅文本（自动识别 Base64 ss 列表 / Clash YAML / 明文 ss 列表）。 */
    public static List<ProxyNode> parse(String body) {
        if (body == null) return new ArrayList<>();
        String trimmed = body.trim();
        // 1) Clash YAML：含 "proxies:" 关键字
        if (trimmed.contains("proxies:") && trimmed.contains(":") && trimmed.contains("\n")) {
            List<ProxyNode> clash = parseClashYaml(body);
            if (!clash.isEmpty()) return clash;
        }
        // 2) 整段 Base64（常见机场订阅会编码 URI 列表）
        if (!containsProxyUri(trimmed)) {
            String decoded = tryBase64(trimmed);
            if (decoded != null && (containsProxyUri(decoded) || decoded.contains("@"))) {
                return parseProxyList(decoded);
            }
        }
        // 3) 明文 URI 列表
        if (containsProxyUri(trimmed)) {
            return parseProxyList(body);
        }
        // 4) 兜底：尝试 base64 解码后再按 URI 列表解析
        String decoded = tryBase64(trimmed);
        if (decoded != null) return parseProxyList(decoded);
        return new ArrayList<>();
    }

    /** 解析明文 ss:// 列表（按行）。 */
    public static List<ProxyNode> parseSsList(String text) {
        List<ProxyNode> all = parseProxyList(text);
        List<ProxyNode> out = new ArrayList<>();
        for (ProxyNode node : all) {
            if ("ss".equalsIgnoreCase(node.protocol)) out.add(node);
        }
        return out;
    }

    /** 解析 SS/VMess/VLESS/Trojan/Hysteria2/TUIC URI 列表。 */
    public static List<ProxyNode> parseProxyList(String text) {
        List<ProxyNode> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            ProxyNode node = parseProxyUri(s);
            if (node != null && node.isValid()) out.add(node);
        }
        return out;
    }

    public static ProxyNode parseProxyUri(String uri) {
        if (uri == null) return null;
        String value = uri.trim();
        int schemeAt = value.indexOf("://");
        if (schemeAt <= 0) return null;
        String scheme = value.substring(0, schemeAt).toLowerCase(java.util.Locale.ROOT);
        if ("ss".equals(scheme)) return parseSsUri(value);
        if ("vmess".equals(scheme)) return parseVmessUri(value);
        if ("vless".equals(scheme) || "trojan".equals(scheme)
                || "hysteria2".equals(scheme) || "hy2".equals(scheme)
                || "tuic".equals(scheme)) {
            return parseStructuredUri(value, scheme);
        }
        return null;
    }

    /**
     * 解析单个 ss:// URI。
     * 支持：
     * - ss://base64(method:password)@host:port#name
     * - ss://method:password@host:port#name
     * - ss://base64(method:password@host:port)#name
     * - ss://base64url(...)#name（SIP002）
     */
    public static ProxyNode parseSsUri(String uri) {
        if (uri == null) return null;
        String s = uri.trim();
        if (!s.startsWith("ss://")) return null;
        s = s.substring("ss://".length());

        String name = null;
        int hash = s.indexOf('#');
        if (hash >= 0) {
            name = urlDecode(s.substring(hash + 1));
            s = s.substring(0, hash);
        }
        // 去掉查询串
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);

        String method;
        String password;
        String host;
        int port;

        int at = s.lastIndexOf('@');
        if (at >= 0) {
            String userinfo = s.substring(0, at);
            String hostport = s.substring(at + 1);
            String[] mp = decodeUserinfo(userinfo);
            method = mp[0];
            password = mp[1];
            int colon = hostport.lastIndexOf(':');
            if (colon < 0) return null;
            host = hostport.substring(0, colon);
            port = parseInt(hostport.substring(colon + 1), -1);
        } else {
            // 整段 base64 = method:password@host:port
            String decoded = tryBase64(s);
            if (decoded == null || !decoded.contains("@")) return null;
            int a2 = decoded.lastIndexOf('@');
            String userinfo = decoded.substring(0, a2);
            String hostport = decoded.substring(a2 + 1);
            int c = userinfo.indexOf(':');
            if (c < 0) return null;
            method = userinfo.substring(0, c);
            password = userinfo.substring(c + 1);
            int colon = hostport.lastIndexOf(':');
            if (colon < 0) return null;
            host = hostport.substring(0, colon);
            port = parseInt(hostport.substring(colon + 1), -1);
        }

        if (name == null || name.isEmpty()) name = host + ":" + port;
        return new ProxyNode(name, host, port, method, password);
    }

    private static ProxyNode parseVmessUri(String uri) {
        String json = tryBase64(uri.substring("vmess://".length()).trim());
        if (json == null) return null;
        try {
            org.json.JSONObject data = new org.json.JSONObject(json);
            String name = data.optString("ps", "VMess");
            String server = data.optString("add", "");
            int port = parseInt(data.optString("port"), -1);
            String uuid = data.optString("id", "");
            if (server.isEmpty() || port <= 0 || uuid.isEmpty()) return null;

            String network = data.optString("net", "tcp");
            String tls = data.optString("tls", "");
            StringBuilder raw = new StringBuilder();
            appendYaml(raw, 0, "name", name);
            appendYaml(raw, 0, "type", "vmess");
            appendYaml(raw, 0, "server", server);
            appendYamlNumber(raw, 0, "port", port);
            appendYaml(raw, 0, "uuid", uuid);
            appendYamlNumber(raw, 0, "alterId",
                    parseInt(data.optString("aid"), 0));
            appendYaml(raw, 0, "cipher", data.optString("scy", "auto"));
            appendYaml(raw, 0, "network", network);
            if (!tls.isEmpty() && !"none".equalsIgnoreCase(tls)) {
                appendYamlBoolean(raw, 0, "tls", true);
                appendYaml(raw, 0, "servername",
                        firstNonEmpty(data.optString("sni"), data.optString("host")));
            }
            appendTransportOptions(raw, network,
                    data.optString("path"), data.optString("host"),
                    data.optString("type"));
            return rawNode(name, "vmess", server, port, raw.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ProxyNode parseStructuredUri(String value, String inputScheme) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            String protocol = "hy2".equals(inputScheme) ? "hysteria2" : inputScheme;
            String credential = urlDecode(uri.getRawUserInfo());
            String server = uri.getHost();
            int port = uri.getPort();
            if (server == null || server.isEmpty() || port <= 0
                    || credential == null || credential.isEmpty()) {
                return null;
            }
            Map<String, String> query = parseQuery(uri.getRawQuery());
            String name = urlDecode(uri.getRawFragment());
            if (name == null || name.isEmpty()) name = server + ":" + port;

            StringBuilder raw = new StringBuilder();
            appendYaml(raw, 0, "name", name);
            appendYaml(raw, 0, "type", protocol);
            appendYaml(raw, 0, "server", server);
            appendYamlNumber(raw, 0, "port", port);
            if ("vless".equals(protocol)) {
                appendYaml(raw, 0, "uuid", credential);
                appendYamlBoolean(raw, 0, "udp", true);
                appendYaml(raw, 0, "network", query.get("type"));
                appendYaml(raw, 0, "flow", query.get("flow"));
                String security = query.get("security");
                if ("tls".equalsIgnoreCase(security) || "reality".equalsIgnoreCase(security)) {
                    appendYamlBoolean(raw, 0, "tls", true);
                    appendYaml(raw, 0, "servername", query.get("sni"));
                    appendYaml(raw, 0, "client-fingerprint", query.get("fp"));
                }
                if ("reality".equalsIgnoreCase(security)) {
                    raw.append("  reality-opts:\n");
                    appendYaml(raw, 2, "public-key", query.get("pbk"));
                    appendYaml(raw, 2, "short-id", query.get("sid"));
                }
                appendTransportOptions(raw, query.get("type"),
                        query.get("path"), query.get("host"),
                        query.get("serviceName"));
            } else if ("trojan".equals(protocol)) {
                appendYaml(raw, 0, "password", credential);
                appendYaml(raw, 0, "sni", firstNonEmpty(query.get("sni"), query.get("peer")));
                appendYamlBoolean(raw, 0, "skip-cert-verify",
                        parseBoolean(query.get("allowInsecure")));
                appendYaml(raw, 0, "network", query.get("type"));
                appendTransportOptions(raw, query.get("type"),
                        query.get("path"), query.get("host"),
                        query.get("serviceName"));
            } else if ("hysteria2".equals(protocol)) {
                appendYaml(raw, 0, "password", credential);
                appendYaml(raw, 0, "sni", query.get("sni"));
                appendYamlBoolean(raw, 0, "skip-cert-verify",
                        parseBoolean(query.get("insecure")));
                appendYaml(raw, 0, "ports", query.get("mport"));
                appendYaml(raw, 0, "fingerprint", query.get("pinSHA256"));
                appendYaml(raw, 0, "obfs", query.get("obfs"));
                appendYaml(raw, 0, "obfs-password", query.get("obfs-password"));
            } else if ("tuic".equals(protocol)) {
                String[] auth = credential.split(":", 2);
                appendYaml(raw, 0, "uuid", auth[0]);
                appendYaml(raw, 0, "password", auth.length > 1 ? auth[1] : "");
                appendYaml(raw, 0, "sni", query.get("sni"));
                appendYaml(raw, 0, "congestion-controller",
                        firstNonEmpty(query.get("congestion_control"), "bbr"));
                appendYaml(raw, 0, "udp-relay-mode",
                        firstNonEmpty(query.get("udp_relay_mode"), "native"));
            }
            return rawNode(name, protocol, server, port, raw.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ProxyNode rawNode(
            String name, String protocol, String server, int port, String raw) {
        ProxyNode node = new ProxyNode(name, server, port, null, null);
        node.protocol = protocol;
        node.clashYaml = raw;
        return node;
    }

    private static void appendTransportOptions(
            StringBuilder raw,
            String network,
            String path,
            String host,
            String serviceName) {
        if ("ws".equalsIgnoreCase(network)) {
            raw.append("  ws-opts:\n");
            appendYaml(raw, 2, "path", path);
            if (host != null && !host.isEmpty()) {
                raw.append("    headers:\n");
                appendYaml(raw, 4, "Host", host);
            }
        } else if ("grpc".equalsIgnoreCase(network)) {
            raw.append("  grpc-opts:\n");
            appendYaml(raw, 2, "grpc-service-name", serviceName);
        }
    }

    private static void appendYaml(StringBuilder out, int indent, String key, String value) {
        if (value == null || value.isEmpty()) return;
        indent(out, indent);
        out.append(key).append(": ").append(MihomoConfigBuilder.quote(value)).append('\n');
    }

    private static void appendYamlNumber(StringBuilder out, int indent, String key, int value) {
        indent(out, indent);
        out.append(key).append(": ").append(value).append('\n');
    }

    private static void appendYamlBoolean(
            StringBuilder out, int indent, String key, boolean value) {
        indent(out, indent);
        out.append(key).append(": ").append(value).append('\n');
    }

    private static void indent(StringBuilder out, int spaces) {
        if (out.length() == 0) {
            out.append("- ");
            return;
        }
        out.append("  ");
        for (int i = 0; i < spaces; i++) out.append(' ');
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            String key = urlDecode(equals < 0 ? part : part.substring(0, equals));
            String value = urlDecode(equals < 0 ? "" : part.substring(equals + 1));
            out.put(key, value);
        }
        return out;
    }

    private static boolean parseBoolean(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : second;
    }

    /** 解码 userinfo：可能是 base64(method:password)，也可能是明文 method:password。 */
    private static String[] decodeUserinfo(String userinfo) {
        if (userinfo.contains(":")) {
            // 可能是明文 method:password
            int c = userinfo.indexOf(':');
            return new String[]{userinfo.substring(0, c), userinfo.substring(c + 1)};
        }
        String decoded = tryBase64(userinfo);
        if (decoded != null && decoded.contains(":")) {
            int c = decoded.indexOf(':');
            return new String[]{decoded.substring(0, c), decoded.substring(c + 1)};
        }
        return new String[]{"", ""};
    }

    /** 解析 Clash YAML 的 proxies 段；节点的嵌套配置会原样保留。 */
    public static List<ProxyNode> parseClashYaml(String yaml) {
        List<ProxyNode> out = new ArrayList<>();
        if (yaml == null) return out;
        String[] lines = yaml.split("\\r?\\n");
        boolean inProxies = false;
        int proxiesIndent = -1;
        int nodeIndent = -1;
        Map<String, String> current = null;
        StringBuilder rawNode = null;
        for (String raw : lines) {
            if (raw.trim().isEmpty() || raw.trim().startsWith("#")) continue;
            int indent = leadingSpaces(raw);
            String trimmed = raw.trim();

            if (!inProxies) {
                if (trimmed.equals("proxies:") || trimmed.startsWith("proxies:")) {
                    inProxies = true;
                    proxiesIndent = indent;
                }
                continue;
            }
            // proxies 段内：遇到同级或更高级的新顶层键则结束
            if (indent <= proxiesIndent && trimmed.endsWith(":") && !trimmed.startsWith("-")) {
                break;
            }
            boolean nodeStart = trimmed.startsWith("- ")
                    && (nodeIndent < 0 || indent == nodeIndent);
            if (nodeStart) {
                // 新节点开始：先把上一个提交
                if (current != null) {
                    addClashNode(out, current, rawNode == null ? null : rawNode.toString());
                }
                if (nodeIndent < 0) nodeIndent = indent;
                current = new HashMap<>();
                rawNode = new StringBuilder();
                rawNode.append(raw).append('\n');
                // "- name: xxx" 形式
                String rest = trimmed.substring(2);
                if (rest.startsWith("{") && rest.endsWith("}")) {
                    applyInlineClashMap(current, rest.substring(1, rest.length() - 1));
                } else {
                    applyClashKv(current, rest);
                }
            } else if (current != null) {
                rawNode.append(raw).append('\n');
                applyClashKv(current, trimmed);
            }
        }
        if (current != null) {
            addClashNode(out, current, rawNode == null ? null : rawNode.toString());
        }
        return out;
    }

    private static void addClashNode(
            List<ProxyNode> out,
            Map<String, String> m,
            String rawYaml) {
        String type = m.get("type");
        if (type == null || type.isEmpty()) return;
        String name = m.get("name");
        String server = m.get("server");
        int port = parseInt(m.get("port"), -1);
        String cipher = m.get("cipher");
        String password = m.get("password");
        if (name == null || name.isEmpty()) {
            name = server == null ? type : server + ":" + port;
        }
        ProxyNode node = new ProxyNode(name, server, port, cipher, password);
        node.protocol = type.toLowerCase(java.util.Locale.ROOT);
        node.clashYaml = rawYaml;
        out.add(node);
    }

    private static void applyClashKv(Map<String, String> m, String kv) {
        int c = kv.indexOf(':');
        if (c < 0) return;
        String k = kv.substring(0, c).trim();
        String v = kv.substring(c + 1).trim();
        // 去引号
        if (v.length() >= 2
                && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1);
        }
        m.put(k, v);
    }

    /** 解析常见的 "- {name: x, type: vmess, ...}" 行内节点。 */
    private static void applyInlineClashMap(Map<String, String> out, String body) {
        StringBuilder token = new StringBuilder();
        char quote = 0;
        int nested = 0;
        for (int i = 0; i <= body.length(); i++) {
            char next = i < body.length() ? body.charAt(i) : ',';
            if (quote != 0) {
                token.append(next);
                if (next == quote && (i == 0 || body.charAt(i - 1) != '\\')) quote = 0;
                continue;
            }
            if (next == '\'' || next == '"') {
                quote = next;
                token.append(next);
            } else if (next == '[' || next == '{') {
                nested++;
                token.append(next);
            } else if (next == ']' || next == '}') {
                nested--;
                token.append(next);
            } else if (next == ',' && nested == 0) {
                applyClashKv(out, token.toString().trim());
                token.setLength(0);
            } else {
                token.append(next);
            }
        }
    }

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') n++;
        return n;
    }

    private static boolean containsProxyUri(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("ss://")
                || lower.contains("vmess://")
                || lower.contains("vless://")
                || lower.contains("trojan://")
                || lower.contains("hysteria2://")
                || lower.contains("hy2://")
                || lower.contains("tuic://");
    }

    private static String tryBase64(String s) {
        if (s == null || s.isEmpty()) return null;
        byte[] b = base64Decode(s);
        if (b == null) return null;
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 极简 Base64 解码器（纯 Java，兼容标准与 URL-safe 字母表，忽略空白与 padding）。
     * 不依赖 {@code java.util.Base64}（API 26）或 {@code android.util.Base64}，
     * 以保证 JVM 单测与低版本设备均可运行。
     */
    private static final String B64_STD =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private static byte[] base64Decode(String s) {
        int[] map = new int[256];
        java.util.Arrays.fill(map, -1);
        for (int i = 0; i < 64; i++) map[B64_STD.charAt(i)] = i;
        map['-'] = 62; // url-safe
        map['_'] = 63; // url-safe
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            if (c >= 256 || map[c] < 0) return null;
            buf = (buf << 6) | map[c];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
