package com.example.cleanrecovery.proxy;

/** Mihomo/Clash 代理节点模型，兼容旧版纯 Java Shadowsocks 字段。 */
public final class ProxyNode {

    /** 默认名称前缀（订阅解析未提供 name 时使用）。 */
    public static final String DEFAULT_NAME = "SS-Node";

    public String name;
    public String server;
    public int port;
    public String cipher;
    public String password;
    /** Clash 协议类型，例如 ss/vmess/vless/trojan/hysteria2/tuic。 */
    public String protocol = "ss";
    /** 单个 Clash {@code proxies:} 项的 YAML；Mihomo 模式直接消费。 */
    public String clashYaml;

    public ProxyNode() {
    }

    public ProxyNode(String name, String server, int port, String cipher, String password) {
        this.name = name;
        this.server = server;
        this.port = port;
        this.cipher = cipher;
        this.password = password;
        this.protocol = "ss";
    }

    /** 是否具备可直连测速的地址（TCP 握手用）。 */
    public boolean isReachableHost() {
        return server != null && !server.isEmpty() && port > 0 && port < 65536;
    }

    /** 是否为可用节点（关键字段非空、端口合法）。 */
    public boolean isValid() {
        if (clashYaml != null && !clashYaml.trim().isEmpty()) {
            return name != null && !name.isEmpty()
                    && protocol != null && !protocol.isEmpty();
        }
        return isLegacySs();
    }

    /** 是否可由旧纯 Java SS 引擎运行。 */
    public boolean isLegacySs() {
        return "ss".equalsIgnoreCase(protocol)
                && server != null && !server.isEmpty()
                && password != null && !password.isEmpty()
                && cipher != null && !cipher.isEmpty()
                && port > 0 && port < 65536;
    }

    /** 简要描述，用于日志与 UI 显示。 */
    public String display() {
        String type = protocol == null || protocol.isEmpty() ? "ss" : protocol;
        return (name == null || name.isEmpty() ? DEFAULT_NAME : name)
                + " (" + type + " · "
                + (server == null || server.isEmpty() ? "配置节点" : server + ":" + port)
                + ")";
    }

    @Override
    public String toString() {
        return display();
    }
}
