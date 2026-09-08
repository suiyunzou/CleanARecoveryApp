package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Script-only value bridge. Random capabilities are enclosed in injected userscript functions. */
public final class BrowserScriptValues {
    public static final String CHANNEL = "ViaScriptValues";
    private static final Object LOCK = new Object();
    private final SharedPreferences store;
    private final BrowserPrefs prefs;
    private final Map<String, String> capabilities = new ConcurrentHashMap<>();

    public BrowserScriptValues(Context context) {
        prefs = new BrowserPrefs(context);
        store = context.getApplicationContext().getSharedPreferences("via_script_values", Context.MODE_PRIVATE);
    }

    public String script(String name) {
        String id = prefs.scriptStorageId(name);
        String token = capabilities.computeIfAbsent(id, ignored -> UUID.randomUUID().toString());
        String key = JSONObject.quote(token);
        return "var __values=window." + CHANNEL + ",__token=" + key + ";"
                + "var GM_getValue=function(k,d){var v=__values.get(__token,String(k));return v==='__via_missing__'?d:v==='undefined'?undefined:JSON.parse(v)};"
                + "var GM_setValue=function(k,v){var j=JSON.stringify(v);__values.set(__token,String(k),j===undefined?'undefined':j)};"
                + "var GM_deleteValue=function(k){__values.remove(__token,String(k))};"
                + "var GM_listValues=function(){return JSON.parse(__values.keys(__token))};";
    }

    private String identity(String token) {
        for (String name : prefs.scriptNames()) {
            String id = prefs.scriptStorageId(name);
            if (token != null && token.equals(capabilities.get(id))) return id;
        }
        return null;
    }

    boolean allowsRequest(String token) {
        String id = identity(token);
        if (id == null || !prefs.scriptsEnabled()) return false;
        for (String name : prefs.scriptNames()) if (id.equals(prefs.scriptStorageId(name)) && prefs.isScriptEnabled(name)) {
            java.util.List<String> grants = BrowserUserScripts.parse(prefs.scriptCode(name)).grants;
            return grants.contains("GM_xmlhttpRequest") || grants.contains("GM.xmlHttpRequest");
        }
        return false;
    }

    private JSONObject values(String id) {
        try { return new JSONObject(store.getString(id, "{}")); }
        catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    @JavascriptInterface public String get(String token, String key) {
        synchronized (LOCK) {
            String id = identity(token);
            return id == null ? "__via_missing__" : values(id).optString(key, "__via_missing__");
        }
    }

    @JavascriptInterface public void set(String token, String key, String json) {
        synchronized (LOCK) {
            String id = identity(token);
            if (id == null) return;
            try {
                JSONObject values = values(id); values.put(key, json);
                store.edit().putString(id, values.toString()).apply();
            } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
        }
    }

    @JavascriptInterface public void remove(String token, String key) {
        synchronized (LOCK) {
            String id = identity(token);
            if (id == null) return;
            JSONObject values = values(id); values.remove(key);
            store.edit().putString(id, values.toString()).apply();
        }
    }

    @JavascriptInterface public String keys(String token) {
        synchronized (LOCK) {
            String id = identity(token);
            org.json.JSONArray names = id == null ? null : values(id).names();
            return names == null ? "[]" : names.toString();
        }
    }
}
