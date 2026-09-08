package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local encrypted credential store used by the browser password manager. */
public final class BrowserPasswordStore {
    private static final String PREF = "via_password_store";
    private static final String DATA = "credentials";
    private static final String IGNORED = "ignored_hosts";
    private static final String KEY_ALIAS = "cleanrecovery.browser.passwords";
    private final Context context;
    private final SharedPreferences prefs;

    public BrowserPasswordStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(decrypt(prefs.getString(DATA, "")));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                out.add(new Entry(item.optString("origin"), item.optString("username"), item.optString("password")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public void save(String origin, String username, String password) {
        if (origin == null || origin.isEmpty() || password == null || password.isEmpty()) return;
        List<Entry> entries = list();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (origin.equals(e.origin) && safe(username).equals(e.username)) entries.remove(i);
        }
        entries.add(new Entry(origin, safe(username), password));
        write(entries);
    }

    public void remove(Entry target) {
        List<Entry> entries = list();
        entries.removeIf(e -> e.origin.equals(target.origin) && e.username.equals(target.username));
        write(entries);
    }

    public Entry find(String origin) {
        List<Entry> entries = list();
        for (int i = entries.size() - 1; i >= 0; i--) if (entries.get(i).origin.equals(origin)) return entries.get(i);
        return null;
    }

    public Set<String> ignoredHosts() {
        return new LinkedHashSet<>(prefs.getStringSet(IGNORED, java.util.Collections.emptySet()));
    }

    public void ignore(String host) {
        Set<String> hosts = ignoredHosts();
        hosts.add(host);
        prefs.edit().putStringSet(IGNORED, hosts).apply();
    }

    public void unignore(String host) {
        Set<String> hosts = ignoredHosts();
        hosts.remove(host);
        prefs.edit().putStringSet(IGNORED, hosts).apply();
    }

    public String csv() {
        StringBuilder out = new StringBuilder("name,url,username,password\n");
        for (Entry e : list()) out.append(csvCell(e.origin)).append(',').append(csvCell(e.origin)).append(',')
                .append(csvCell(e.username)).append(',').append(csvCell(e.password)).append('\n');
        return out.toString();
    }

    public static String exportCsv(Context context) { return new BrowserPasswordStore(context).csv(); }

    public int importCsv(String csv) {
        int count = 0;
        String[] lines = csv.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            List<String> cells = parseCsvLine(lines[i]);
            if (cells.size() < 4 || (i == 0 && "url".equalsIgnoreCase(cells.get(1)))) continue;
            String origin = cells.get(1).trim();
            String password = cells.get(3);
            if (!origin.isEmpty() && !password.isEmpty()) {
                save(origin, cells.get(2), password);
                count++;
            }
        }
        return count;
    }

    public static String exportEncrypted(Context context) { return new BrowserPasswordStore(context).prefs.getString(DATA, ""); }
    public static void importEncrypted(Context context, String value) {
        if (value != null && !value.isEmpty()) new BrowserPasswordStore(context).prefs.edit().putString(DATA, value).apply();
    }

    private void write(List<Entry> entries) {
        try {
            JSONArray array = new JSONArray();
            for (Entry e : entries) array.put(new JSONObject().put("origin", e.origin)
                    .put("username", e.username).put("password", e.password));
            prefs.edit().putString(DATA, encrypt(array.toString())).apply();
        } catch (Exception ignored) { }
    }

    private String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] data = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private String decrypt(String encoded) throws Exception {
        if (encoded == null || encoded.isEmpty()) return "[]";
        String[] parts = encoded.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private static String csvCell(String value) { return "\"" + safe(value).replace("\"", "\"\"") + "\""; }
    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                if (quote && i + 1 < line.length() && line.charAt(i + 1) == '\"') { value.append('\"'); i++; }
                else quote = !quote;
            } else if (c == ',' && !quote) { out.add(value.toString()); value.setLength(0); }
            else value.append(c);
        }
        out.add(value.toString());
        return out;
    }
    private static String safe(String value) { return value == null ? "" : value; }

    public static final class Entry {
        public final String origin;
        public final String username;
        public final String password;
        public Entry(String origin, String username, String password) {
            this.origin = origin; this.username = username; this.password = password;
        }
    }
}
