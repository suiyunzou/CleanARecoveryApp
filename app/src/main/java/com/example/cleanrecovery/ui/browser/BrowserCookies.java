package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import androidx.webkit.CookieManagerCompat;
import androidx.webkit.WebViewFeature;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.IDN;
import java.nio.charset.StandardCharsets;

/** Clear the captured page's visible cookies and Via's persisted ancestor-domain scope. */
public final class BrowserCookies {
    public static void clear(Context context, String url, ValueCallback<Boolean> complete) {
        CookieManager manager = CookieManager.getInstance();
        Uri origin = url == null ? Uri.EMPTY : Uri.parse(url);
        if (origin.getHost() == null || !("https".equalsIgnoreCase(origin.getScheme())
                || "http".equalsIgnoreCase(origin.getScheme()))) { complete.onReceiveValue(false); return; }
        List<String> headers;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            headers = CookieManagerCompat.getCookieInfo(manager, url);
        } else {
            String raw = manager.getCookie(url);
            headers = raw == null ? java.util.Collections.emptyList() : java.util.Arrays.asList(raw.split(";"));
        }
        List<String> deletions = new ArrayList<>();
        for (String header : headers) {
            String[] parts = header.split(";");
            int equals = parts[0].indexOf('=');
            if (equals <= 0) continue;
            String cookieName = parts[0].substring(0, equals).trim();
            StringBuilder deletion = new StringBuilder(cookieName).append("=; Max-Age=0");
            StringBuilder hostDeletion = new StringBuilder(deletion);
            for (int i = 1; i < parts.length; i++) {
                String attribute = parts[i].trim();
                String name = attribute.split("=", 2)[0];
                if (!"Expires".equalsIgnoreCase(name) && !"Max-Age".equalsIgnoreCase(name)) {
                    deletion.append("; ").append(attribute);
                    if (!"Domain".equalsIgnoreCase(name)) hostDeletion.append("; ").append(attribute);
                }
            }
            if (!hostDeletion.toString().equals(deletion.toString())) deletions.add(hostDeletion.toString());
            // __Host- cookies reject any Domain attribute, including when being expired.
            if (!cookieName.startsWith("__Host-") || hostDeletion.toString().equals(deletion.toString())) {
                deletions.add(deletion.toString());
            }
        }
        if (deletions.isEmpty()) { complete.onReceiveValue(clearStored(context, url) && headers.isEmpty()); return; }
        int[] pending = {deletions.size()};
        boolean[] accepted = {true};
        for (String deletion : deletions) manager.setCookie(url, deletion, success -> {
            accepted[0] &= success;
            if (--pending[0] == 0) {
                manager.flush();
                boolean storedCleared = clearStored(context, url);
                String remaining = manager.getCookie(url);
                complete.onReceiveValue(storedCleared && accepted[0] && (remaining == null || remaining.isEmpty()));
            }
        });
    }

    private static boolean clearStored(Context context, String url) {
        File file = new File(context.getApplicationInfo().dataDir, "app_webview/Default/Cookies");
        if (!file.isFile()) return true;
        try {
            List<String> hosts = hostScope(context, url);
            try (SQLiteDatabase database = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                database.beginTransaction();
                try {
                    for (String host : hosts) database.delete("cookies", "host_key=?", new String[]{host});
                    database.setTransactionSuccessful();
                } finally { database.endTransaction(); }
            }
            return true;
        } catch (IOException | IllegalArgumentException | SQLiteException error) {
            Log.w("BrowserCookies", "Stored cookie deletion failed", error);
            return false;
        }
    }

    /** Via b9.b0.r stops at the registrable domain, including PSL wildcard/private exceptions. */
    static List<String> hostScope(Context context, String url) throws IOException {
        String host = Uri.parse(url).getHost();
        if (host == null) return java.util.Collections.emptyList();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        boolean ip = host.indexOf(':') >= 0 || host.matches("[0-9]+(?:\\.[0-9]+){3}");
        if (!ip) host = IDN.toASCII(host).toLowerCase(java.util.Locale.ROOT);
        int keepLabels = ip ? Integer.MAX_VALUE : registrableLabels(context, IDN.toUnicode(host));
        List<String> result = new ArrayList<>();
        while (true) {
            result.add(host); result.add("." + host);
            if (host.split("\\.").length <= keepLabels) break;
            int dot = host.indexOf('.');
            if (dot < 0) break;
            host = host.substring(dot + 1);
        }
        return result;
    }

    private static Set<String> suffixRules;
    private static Set<String> suffixExceptions;

    private static synchronized int registrableLabels(Context context, String host) throws IOException {
        if (suffixRules == null) {
            Set<String> rules = new HashSet<>(), exceptions = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    context.getAssets().open("browser_publicsuffixes.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("//")) continue;
                    if (line.startsWith("!")) exceptions.add(line.substring(1)); else rules.add(line);
                }
            }
            suffixRules = rules; suffixExceptions = exceptions;
        }
        String[] labels = host.split("\\.");
        int publicLabels = 1;
        for (int start = 0; start < labels.length; start++) {
            String candidate = android.text.TextUtils.join(".", java.util.Arrays.copyOfRange(labels, start, labels.length));
            if (suffixExceptions.contains(candidate)) return labels.length - start;
            if (suffixRules.contains(candidate)) publicLabels = Math.max(publicLabels, labels.length - start);
            if (start > 0 && suffixRules.contains("*." + candidate)) publicLabels = Math.max(publicLabels, labels.length - start + 1);
        }
        return publicLabels + 1;
    }
}
