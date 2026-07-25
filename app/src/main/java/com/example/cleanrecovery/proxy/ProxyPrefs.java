package com.example.cleanrecovery.proxy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 代理偏好持久化：订阅 URL、节点列表、当前选中节点。
 *
 * <p>用 SharedPreferences + Android Keystore 加密 JSON 存储，避免代理凭据明文落盘。</p>
 */
public final class ProxyPrefs {

    private static final String PREF = "cleanrecovery_proxy";
    private static final String K_SUB_URL = "sub_url";
    private static final String K_NODES = "nodes_json";
    private static final String K_SELECTED = "selected_index";
    private static final String K_SUBSCRIPTIONS = "subscriptions_json";
    private static final String K_ACTIVE_SUBSCRIPTION = "active_subscription";
    private static final String DEFAULT_ID = "default";

    private final SharedPreferences sp;
    private final ProxySecretCipher secretCipher = new ProxySecretCipher();

    public ProxyPrefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String subscriptionUrl() {
        ProxySubscription active = activeSubscription();
        return active == null
                ? secretCipher.decrypt(sp.getString(K_SUB_URL, "")) : active.url;
    }

    public void setSubscriptionUrl(String url) {
        String safe = url == null ? "" : url;
        List<ProxySubscription> all = subscriptions();
        ProxySubscription active = find(all, activeSubscriptionId());
        if (active == null) {
            active = new ProxySubscription(DEFAULT_ID, "默认订阅", safe);
            all.add(active);
        } else {
            active.url = safe;
        }
        saveSubscriptions(all);
        sp.edit().remove(K_SUB_URL).apply();
    }

    public int selectedIndex() {
        int legacy = DEFAULT_ID.equals(activeSubscriptionId())
                ? sp.getInt(K_SELECTED, -1) : -1;
        return sp.getInt(profileKey(K_SELECTED), legacy);
    }

    public void setSelectedIndex(int index) {
        sp.edit().putInt(profileKey(K_SELECTED), index).apply();
    }

    public void saveNodes(List<ProxyNode> nodes) {
        JSONArray arr = new JSONArray();
        for (ProxyNode n : nodes) {
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
            arr.put(o);
        }
        SharedPreferences.Editor editor = sp.edit().putString(
                profileKey(K_NODES), secretCipher.encrypt(arr.toString()));
        if (DEFAULT_ID.equals(activeSubscriptionId())) editor.remove(K_NODES);
        editor.apply();
    }

    public List<ProxyNode> loadNodes() {
        List<ProxyNode> out = new ArrayList<>();
        String stored = sp.getString(profileKey(K_NODES), null);
        if (stored == null) {
            stored = DEFAULT_ID.equals(activeSubscriptionId())
                    ? sp.getString(K_NODES, "") : "";
        }
        boolean needsMigration = !stored.isEmpty() && !secretCipher.isEncrypted(stored);
        String json = secretCipher.decrypt(stored);
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ProxyNode n = new ProxyNode();
                n.name = o.optString("name");
                n.server = o.optString("server");
                n.port = o.optInt("port");
                n.cipher = o.optString("cipher");
                n.password = o.optString("password");
                n.protocol = o.optString("protocol", "ss");
                n.clashYaml = o.optString("clashYaml", null);
                out.add(n);
            }
        } catch (Exception ignored) {
        }
        if (needsMigration && !out.isEmpty()) saveNodes(out);
        return out;
    }

    public List<ProxySubscription> subscriptions() {
        List<ProxySubscription> out = new ArrayList<>();
        String stored = sp.getString(K_SUBSCRIPTIONS, "");
        boolean needsMigration = !stored.isEmpty() && !secretCipher.isEncrypted(stored);
        String json = secretCipher.decrypt(stored);
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    String id = item.optString("id");
                    if (id.isEmpty()) continue;
                    out.add(new ProxySubscription(
                            id,
                            item.optString("name", "订阅 " + (i + 1)),
                            item.optString("url")));
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) {
            out.add(new ProxySubscription(
                    DEFAULT_ID, "默认订阅",
                    secretCipher.decrypt(sp.getString(K_SUB_URL, ""))));
            saveSubscriptions(out);
        } else if (needsMigration) {
            saveSubscriptions(out);
        }
        return out;
    }

    public void saveSubscriptions(List<ProxySubscription> subscriptions) {
        JSONArray array = new JSONArray();
        if (subscriptions != null) {
            for (ProxySubscription item : subscriptions) {
                if (item == null || item.id == null || item.id.isEmpty()) continue;
                JSONObject json = new JSONObject();
                try {
                    json.put("id", item.id);
                    json.put("name", item.name);
                    json.put("url", item.url);
                    array.put(json);
                } catch (Exception ignored) {
                }
            }
        }
        sp.edit()
                .putString(K_SUBSCRIPTIONS, secretCipher.encrypt(array.toString()))
                .remove(K_SUB_URL)
                .apply();
    }

    public ProxySubscription addSubscription(String name, String url) {
        List<ProxySubscription> all = subscriptions();
        ProxySubscription item = new ProxySubscription(
                java.util.UUID.randomUUID().toString(),
                name == null || name.trim().isEmpty()
                        ? "订阅 " + (all.size() + 1) : name.trim(),
                url == null ? "" : url.trim());
        all.add(item);
        saveSubscriptions(all);
        setActiveSubscriptionId(item.id);
        return item;
    }

    public void removeSubscription(String id) {
        List<ProxySubscription> all = subscriptions();
        if (all.size() <= 1) return;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) all.remove(i);
        }
        sp.edit()
                .remove(K_NODES + "_" + id)
                .remove(K_SELECTED + "_" + id)
                .apply();
        saveSubscriptions(all);
        if (id != null && id.equals(activeSubscriptionId())) {
            setActiveSubscriptionId(all.get(0).id);
        }
    }

    public String activeSubscriptionId() {
        return sp.getString(K_ACTIVE_SUBSCRIPTION, DEFAULT_ID);
    }

    public void setActiveSubscriptionId(String id) {
        sp.edit().putString(K_ACTIVE_SUBSCRIPTION,
                id == null || id.isEmpty() ? DEFAULT_ID : id).apply();
    }

    public ProxySubscription activeSubscription() {
        List<ProxySubscription> all = subscriptions();
        ProxySubscription active = find(all, activeSubscriptionId());
        return active == null ? all.get(0) : active;
    }

    private String profileKey(String base) {
        return base + "_" + activeSubscriptionId();
    }

    private static ProxySubscription find(List<ProxySubscription> all, String id) {
        if (id == null) return null;
        for (ProxySubscription item : all) {
            if (id.equals(item.id)) return item;
        }
        return null;
    }
}
