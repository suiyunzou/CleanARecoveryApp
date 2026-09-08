package com.example.cleanrecovery.proxy;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 代理引擎状态持有者：优先运行 Mihomo 全协议内核，SS 节点可回退旧纯 Java 引擎。
 *
 * <p>由 {@link ProxyService} 启停；{@link ProxyRouter} 与 UI 通过静态访问器读取
 * 当前本地代理地址，避免跨进程/跨组件直接持有 Service 句柄。</p>
 */
public final class ProxyEngine {

    private static final String TAG = "ProxyEngine";
    private static final AtomicReference<ProxyEngine> INSTANCE = new AtomicReference<>();

    /** 当前生效的引擎实例（可能为 null，表示代理未运行）。 */
    public static ProxyEngine current() {
        return INSTANCE.get();
    }

    private final Context context;
    private final ProxyNode node;
    private final List<ProxyNode> nodes;
    private volatile MihomoProcess mihomo;
    private volatile Socks5Server socks5;
    private volatile HttpProxyServer httpProxy;
    private volatile int socks5Port = -1;
    private volatile int httpPort = -1;
    private volatile boolean httpEnabled = false;

    public ProxyEngine(Context context, ProxyNode node, List<ProxyNode> nodes) {
        this.context = context.getApplicationContext();
        this.node = node;
        this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
    }

    /** 启动代理。Mihomo mixed-port 同时支持 SOCKS5 与 HTTP。 */
    public synchronized int start(boolean alsoHttp) throws Exception {
        if (isRunning()) return socks5Port;
        try {
            mihomo = new MihomoProcess(context);
            int port = mihomo.start(nodes, node);
            socks5Port = port;
            httpPort = port;
            httpEnabled = true;
            INSTANCE.set(this);
            return port;
        } catch (Exception coreError) {
            if (mihomo != null) mihomo.stop();
            mihomo = null;
            if (!node.isLegacySs()) throw coreError;
            Log.w(TAG, "Mihomo start failed, using legacy SS engine", coreError);
        }

        socks5 = new Socks5Server(node);
        socks5Port = socks5.start();
        if (alsoHttp) {
            httpProxy = new HttpProxyServer(node);
            httpPort = httpProxy.start();
            httpEnabled = true;
        }
        INSTANCE.set(this);
        return socks5Port;
    }

    public synchronized void stop() {
        if (mihomo != null) { mihomo.stop(); mihomo = null; }
        if (socks5 != null) { socks5.stop(); socks5 = null; }
        if (httpProxy != null) { httpProxy.stop(); httpProxy = null; }
        socks5Port = -1;
        httpPort = -1;
        httpEnabled = false;
        INSTANCE.compareAndSet(this, null);
    }

    public ProxyNode node() { return node; }
    public int socks5Port() { return socks5Port; }
    public int httpPort() { return httpPort; }
    public boolean isHttpEnabled() { return httpEnabled; }
    public boolean isRunning() {
        return (mihomo != null && mihomo.isRunning()) || socks5 != null;
    }
    public boolean isMihomo() { return mihomo != null && mihomo.isRunning(); }
    public String engineName() { return isMihomo() ? "Mihomo" : "Legacy SS"; }

    /** 本地代理主机（固定 127.0.0.1）。 */
    public static String localHost() { return "127.0.0.1"; }
}
