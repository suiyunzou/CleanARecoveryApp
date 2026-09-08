package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializes the opaque data fields used by Via's /api/update and /api/sync endpoints. */
public final class BrowserCloudPayload {
    private BrowserCloudPayload() { }

    public static Map<String, String> create(Context context) throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(context);
        BrowserDatabaseHelper db = prefs.dbHelper();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("adrules", encode(new JSONObject().put("custom", prefs.exportAdBlockRules()).toString()));
        result.put("settings", encode(BrowserBackupManager.settingsJson(context)));
        result.put("bookmark", encode(entries(db.listBookmarks(), true)));
        result.put("other", encode(scripts(prefs)));
        return result;
    }

    public static int restore(Context context, String response) throws Exception {
        JSONObject root = new JSONObject(response);
        BrowserPrefs prefs = new BrowserPrefs(context);
        List<BrowserDatabaseHelper.Entry> bookmarks = root.has("bookmark")
                ? parseEntries(decode(root.optString("bookmark")), true) : null;
        List<BrowserDatabaseHelper.Entry> favorites = root.has("favorite")
                ? parseEntries(decode(root.optString("favorite")), false) : null;
        prefs.dbHelper().replaceCloudEntries(bookmarks, favorites);

        int changed = (bookmarks == null ? 0 : bookmarks.size()) + (favorites == null ? 0 : favorites.size());
        if (root.has("adrules") && !root.optString("adrules").isEmpty()) {
            String rules = new JSONObject(decode(root.optString("adrules"))).optString("custom");
            prefs.importAdBlockRules(rules, true);
            changed++;
        }
        if (root.has("settings") && !root.optString("settings").isEmpty()) {
            String username = prefs.cloudUsername();
            String password = prefs.cloudPasswordHash();
            String server = prefs.syncServer();
            BrowserBackupManager.restoreSettings(context, decode(root.optString("settings")));
            prefs.setCloudUsername(username);
            prefs.setCloudPasswordHash(password);
            prefs.setCloudLoggedIn(true);
            prefs.setSyncServer(server);
            changed++;
        }
        if (root.has("other") && !root.optString("other").isEmpty()) {
            changed += restoreScripts(prefs, decode(root.optString("other")));
        }
        if (changed == 0) throw new IllegalStateException("云端没有可同步的数据");
        return changed;
    }

    private static String entries(List<BrowserDatabaseHelper.Entry> values, boolean bookmark) throws Exception {
        StringBuilder out = new StringBuilder();
        int order = 0;
        for (BrowserDatabaseHelper.Entry entry : values) {
            JSONObject item = new JSONObject().put("title", entry.title).put("url", entry.url)
                    .put("order", order++);
            if (bookmark) {
                item.put("folder", "根目录".equals(entry.folder) ? "" : entry.folder)
                        .put("updated_at", entry.time / 1000).put("created_at", entry.time / 1000);
            }
            if (out.length() > 0) out.append('\n');
            out.append(item);
        }
        return out.toString();
    }

    private static List<BrowserDatabaseHelper.Entry> parseEntries(String body, boolean bookmark) throws Exception {
        List<BrowserDatabaseHelper.Entry> out = new ArrayList<>();
        long fallback = System.currentTimeMillis();
        for (String line : body.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            JSONObject item = new JSONObject(line);
            String url = item.optString("url").trim();
            if (url.isEmpty()) continue;
            long time = item.optLong("updated_at", 0) * 1000;
            if (time <= 0) time = fallback++;
            String folder = bookmark ? item.optString("folder", "") : "根目录";
            if (folder.isEmpty()) folder = "根目录";
            out.add(new BrowserDatabaseHelper.Entry(0, item.optString("title", url), url, time, folder));
        }
        return out;
    }

    private static String scripts(BrowserPrefs prefs) throws Exception {
        List<String> names = new ArrayList<>(prefs.scriptNames());
        Collections.sort(names);
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            String code = prefs.scriptCode(name);
            BrowserUserScripts.Metadata metadata = BrowserUserScripts.parse(code);
            JSONObject item = new JSONObject().put("scriptId", name)
                    .put("enabled", prefs.isScriptEnabled(name))
                    .put("url", metadata.updateUrl == null ? "" : metadata.updateUrl)
                    .put("codeV2", code);
            if (out.length() > 0) out.append('\n');
            out.append(item);
        }
        return out.toString();
    }

    private static int restoreScripts(BrowserPrefs prefs, String body) throws Exception {
        List<String> old = new ArrayList<>(prefs.scriptNames());
        for (String name : old) prefs.removeScript(name);
        int count = 0;
        for (String line : body.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            JSONObject item = new JSONObject(line);
            String code = item.optString("codeV2");
            if (code.isEmpty()) continue;
            BrowserUserScripts.Metadata metadata = BrowserUserScripts.parse(code);
            String name = metadata.name == null || metadata.name.trim().isEmpty()
                    ? item.optString("scriptId", "用户脚本") : metadata.name.trim();
            String match = metadata.matches.isEmpty() ? "*" : metadata.matches.get(0);
            prefs.saveScript(name, match, code);
            prefs.setScriptEnabled(name, item.optBoolean("enabled", true));
            count++;
        }
        return count;
    }

    private static String encode(String value) {
        return Base64.encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
    }

    private static String decode(String value) {
        return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8);
    }
}
