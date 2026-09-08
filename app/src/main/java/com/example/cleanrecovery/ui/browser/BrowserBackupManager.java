package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Via-compatible document workflow for bookmark and settings backups. */
public final class BrowserBackupManager {
    public static final int BOOKMARKS = 1;
    public static final int FAVORITES = 2;
    public static final int HISTORY = 4;
    public static final int CLOSED_TABS = 8;
    public static final int SETTINGS = 16;
    public static final int PASSWORDS = 32;

    private BrowserBackupManager() { }

    public static void exportBackup(Context context, OutputStream output, int mask) throws Exception {
        exportBackup(context, output, mask, null);
    }

    public static void exportBackup(Context context, OutputStream output, int mask, String password) throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(context);
        BrowserDatabaseHelper db = prefs.dbHelper();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            if ((mask & BOOKMARKS) != 0) put(zip, "bookmarks.html", bookmarksHtml(db.listBookmarks()));
            if ((mask & FAVORITES) != 0) put(zip, "favorites.txt", entriesJson(db.listQuickLinks()));
            if ((mask & HISTORY) != 0) put(zip, "history.txt", entriesJson(db.listHistory()));
            if ((mask & CLOSED_TABS) != 0) put(zip, "tabs.txt", prefs.closedTabs());
            if ((mask & SETTINGS) != 0) put(zip, "settings.txt", settingsJson(context));
            if ((mask & PASSWORDS) != 0) {
                if (password == null || password.isEmpty()) throw new IllegalArgumentException("密码备份需要加密口令");
                String salt = UUID.randomUUID().toString().replace("-", "");
                putBytes(zip, "info.enc", xorSalt(salt.getBytes(StandardCharsets.UTF_8)));
                putBytes(zip, "pass.enc", encryptBackup(
                        BrowserPasswordStore.exportCsv(context).getBytes(StandardCharsets.UTF_8), password, salt));
            }
            if ((mask & 31) == 31) put(zip, "data-for-older-versions.txt", "via-backup-v1");
        }
    }

    public static int importBackup(Context context, InputStream input) throws Exception {
        return importBackup(context, input, null);
    }

    public static int importBackup(Context context, InputStream input, String password) throws Exception {
        byte[] bytes = readAll(input);
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            int imported = 0;
            Map<String, byte[]> files = new LinkedHashMap<>();
            try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    files.put(entry.getName(), readAll(zip));
                    zip.closeEntry();
                }
            }
            if (files.containsKey("pass.enc")
                    && (password == null || password.isEmpty() || !files.containsKey("info.enc"))) {
                throw new IllegalArgumentException("请输入备份密码");
            }
            String passwordCsv = null;
            if (files.containsKey("pass.enc")) {
                String salt = new String(xorSalt(files.get("info.enc")), StandardCharsets.UTF_8);
                passwordCsv = new String(decryptBackup(files.get("pass.enc"), password, salt), StandardCharsets.UTF_8);
            }
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                    String body = new String(file.getValue(), StandardCharsets.UTF_8);
                    switch (file.getKey()) {
                        case "bookmarks.html": imported += importBookmarks(context, body); break;
                        case "favorites.txt": imported += importEntries(context, body, BrowserDatabaseHelper.TABLE_QUICKLINKS); break;
                        case "history.txt": imported += importEntries(context, body, BrowserDatabaseHelper.TABLE_HISTORY); break;
                        case "tabs.txt": new BrowserPrefs(context).setClosedTabs(body); break;
                        case "settings.txt": restoreSettings(context, body); break;
                        default: break;
                    }
            }
            if (passwordCsv != null) imported += new BrowserPasswordStore(context).importCsv(passwordCsv);
            return imported;
        }
        return importBookmarks(context, new String(bytes, StandardCharsets.UTF_8));
    }

    public static String bookmarksHtml(java.util.List<BrowserDatabaseHelper.Entry> entries) {
        StringBuilder html = new StringBuilder("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
                .append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
                .append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n");
        for (BrowserDatabaseHelper.Entry entry : entries) {
            html.append("<DT><A HREF=\"").append(escape(entry.url)).append("\"")
                    .append(" ADD_DATE=\"").append(entry.time / 1000).append("\"")
                    .append(" DATA-FOLDER=\"").append(escape(entry.folder)).append("\">")
                    .append(escape(entry.title)).append("</A>\n");
        }
        return html.append("</DL><p>\n").toString();
    }

    public static int importBookmarks(Context context, String htmlOrText) {
        BrowserDatabaseHelper db = BrowserDatabaseHelper.getInstance(context);
        Pattern html = Pattern.compile("<A\\s+[^>]*HREF=\"([^\"]+)\"[^>]*>(.*?)</A>", Pattern.CASE_INSENSITIVE);
        Pattern folder = Pattern.compile("DATA-FOLDER=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
        int count = 0;
        for (String line : htmlOrText.split("\\r?\\n")) {
            Matcher match = html.matcher(line);
            if (match.find()) {
                Matcher fm = folder.matcher(line);
                String f = fm.find() ? unescape(fm.group(1)) : "根目录";
                if (!"根目录".equals(f)) db.addFolder(f);
                if (db.addBookmark(unescape(match.group(2)), unescape(match.group(1)), f)) count++;
            } else {
                String[] parts = line.split("\\t", 3);
                if (parts.length >= 2 && db.addBookmark(parts[0], parts[1], parts.length == 3 ? parts[2] : "根目录")) count++;
            }
        }
        return count;
    }

    public static void exportBookmarks(Context context, OutputStream output) throws Exception {
        output.write(bookmarksHtml(BrowserDatabaseHelper.getInstance(context).listBookmarks())
                .getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String entriesJson(java.util.List<BrowserDatabaseHelper.Entry> entries) throws Exception {
        JSONArray array = new JSONArray();
        for (BrowserDatabaseHelper.Entry e : entries) {
            array.put(new JSONObject().put("title", e.title).put("url", e.url).put("time", e.time));
        }
        return array.toString();
    }

    private static int importEntries(Context context, String body, String table) throws Exception {
        JSONArray array = new JSONArray(body);
        BrowserDatabaseHelper db = BrowserDatabaseHelper.getInstance(context);
        int count = 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (BrowserDatabaseHelper.TABLE_QUICKLINKS.equals(table)) {
                if (db.addQuickLink(item.optString("title"), item.optString("url"))) count++;
            } else {
                db.recordHistory(item.optString("title"), item.optString("url"));
                count++;
            }
        }
        return count;
    }

    public static String settingsJson(Context context) throws Exception {
        SharedPreferences sp = context.getSharedPreferences(BrowserPrefs.PREF, Context.MODE_PRIVATE);
        JSONArray values = new JSONArray();
        for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
            Object value = entry.getValue();
            JSONObject item = new JSONObject().put("key", entry.getKey());
            if (value instanceof Boolean) item.put("type", "b").put("value", value);
            else if (value instanceof Integer) item.put("type", "i").put("value", value);
            else if (value instanceof Long) item.put("type", "l").put("value", value);
            else if (value instanceof Float) item.put("type", "f").put("value", value);
            else if (value instanceof Set) item.put("type", "s").put("value", new JSONArray((Set<?>) value));
            else item.put("type", "t").put("value", String.valueOf(value));
            values.put(item);
        }
        return new JSONObject().put("version", 1).put("settings", values).toString();
    }

    public static void restoreSettings(Context context, String body) throws Exception {
        JSONArray values = new JSONObject(body).getJSONArray("settings");
        SharedPreferences.Editor edit = context.getSharedPreferences(BrowserPrefs.PREF, Context.MODE_PRIVATE).edit();
        for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.getJSONObject(i);
            String key = item.getString("key");
            switch (item.getString("type")) {
                case "b": edit.putBoolean(key, item.getBoolean("value")); break;
                case "i": edit.putInt(key, item.getInt("value")); break;
                case "l": edit.putLong(key, item.getLong("value")); break;
                case "f": edit.putFloat(key, (float) item.getDouble("value")); break;
                case "s":
                    JSONArray array = item.getJSONArray("value");
                    Set<String> set = new HashSet<>();
                    for (int j = 0; j < array.length(); j++) set.add(array.getString(j));
                    edit.putStringSet(key, set);
                    break;
                default: edit.putString(key, item.optString("value")); break;
            }
        }
        edit.apply();
    }

    private static void put(ZipOutputStream zip, String name, String value) throws Exception {
        putBytes(zip, name, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value == null ? new byte[0] : value);
        zip.closeEntry();
    }

    private static byte[] xorSalt(byte[] value) {
        byte[] copy = value.clone();
        byte[] key = {90, 60, 127, (byte) 0xA7};
        for (int i = 0; i < copy.length; i++) copy[i] ^= key[i % key.length];
        return copy;
    }

    private static byte[] backupKey(String password, String salt) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8),
                        10000, 256)).getEncoded();
    }

    private static byte[] encryptBackup(byte[] plain, String password, String salt) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(backupKey(password, salt), "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
        return out;
    }

    private static byte[] decryptBackup(byte[] encoded, String password, String salt) throws Exception {
        if (encoded == null || encoded.length <= 16) throw new IllegalArgumentException("密码备份已损坏");
        byte[] iv = java.util.Arrays.copyOfRange(encoded, 0, 16);
        byte[] encrypted = java.util.Arrays.copyOfRange(encoded, 16, encoded.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(backupKey(password, salt), "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(encrypted);
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static String escape(String s) {
        return (s == null ? "" : s).replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescape(String s) {
        return (s == null ? "" : s).replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }
}
