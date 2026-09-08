package com.example.cleanrecovery.proxy;

import org.json.JSONObject;

/** ProxyNode 与 JSON 互转工具（用于 Intent 传递）。 */
public final class ProxyServiceExtras {

    private ProxyServiceExtras() {
    }

    public static String toJson(ProxyNode n) {
        if (n == null) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("name", n.name);
            o.put("server", n.server);
            o.put("port", n.port);
            o.put("cipher", n.cipher);
            o.put("password", n.password);
            o.put("protocol", n.protocol);
            o.put("clashYaml", n.clashYaml);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    public static ProxyNode fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            ProxyNode n = new ProxyNode();
            n.name = o.optString("name");
            n.server = o.optString("server");
            n.port = o.optInt("port");
            n.cipher = o.optString("cipher");
            n.password = o.optString("password");
            n.protocol = o.optString("protocol", "ss");
            n.clashYaml = o.optString("clashYaml", null);
            return n;
        } catch (Exception e) {
            return null;
        }
    }
}
