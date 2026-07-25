package com.example.cleanrecovery.proxy;

/** A named subscription profile with isolated nodes and selection state. */
public final class ProxySubscription {
    public final String id;
    public String name;
    public String url;

    public ProxySubscription(String id, String name, String url) {
        this.id = id;
        this.name = name;
        this.url = url;
    }

    @Override
    public String toString() {
        return name == null || name.trim().isEmpty() ? "订阅" : name;
    }
}
