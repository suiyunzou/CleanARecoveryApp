package com.example.cleanrecovery.ui.browser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Via-compatible account endpoint used by cloud sync settings. */
public final class BrowserCloudAccount {
    private BrowserCloudAccount() { }

    public static String endpoint(String server) {
        return "cn".equals(server)
                ? "https://app.viayoo.com/api/user"
                : "https://us.app.viayoo.com/api/user";
    }

    public static String syncEndpoint(String server) {
        return ("cn".equals(server) ? "https://app.viayoo.com" : "https://us.app.viayoo.com") + "/api/sync";
    }

    public static String updateEndpoint(String server) {
        return ("cn".equals(server) ? "https://app.viayoo.com" : "https://us.app.viayoo.com") + "/api/update";
    }

    public static String terms(String server) {
        return "cn".equals(server)
                ? "https://viayoo.com/zh-cn/docs/terms-of-use.html"
                : "https://viayoo.com/en/docs/terms-of-use.html";
    }

    public static String privacy(String server) {
        return "cn".equals(server)
                ? "https://viayoo.com/zh-cn/docs/privacy-policy.html"
                : "https://viayoo.com/en/docs/privacy-policy.html";
    }

    /** Returns Via's response code: 0 login, 1 bad password, 2 newly registered. */
    public static String login(String endpoint, String username, String password) throws Exception {
        return request(endpoint, username, md5(password));
    }

    public static String request(String endpoint, String username, String passwordHash) throws Exception {
        String query = "?name=" + URLEncoder.encode(username, "UTF-8")
                + "&psw=" + URLEncoder.encode(passwordHash, "UTF-8");
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + query).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("GET");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return body.toString().trim();
        } finally {
            connection.disconnect();
        }
    }

    public static String form(String endpoint, Map<String, String> values) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), "UTF-8"));
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        connection.setFixedLengthStreamingMode(bytes.length);
        try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        int code = connection.getResponseCode();
        java.io.InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder response = new StringBuilder();
        if (stream != null) try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) response.append(line);
        }
        connection.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        return response.toString().trim();
    }

    public static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(32);
        for (byte b : digest) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }
}
