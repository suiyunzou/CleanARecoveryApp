package com.example.cleanrecovery.ui.browser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简 WebDAV 客户端：PUT / GET（Basic Auth），用于书签备份与恢复。
 * 纯 Java、无第三方依赖；网络逻辑独立于 UI。
 */
public final class BrowserWebdav {
    private BrowserWebdav() {
    }

    public static final class Result {
        public final boolean ok;
        public final String body;
        public final int code;

        public Result(boolean ok, String body, int code) {
            this.ok = ok;
            this.body = body;
            this.code = code;
        }
    }

    public static Result put(String server, String user, String pass,
                             String remotePath, String content) {
        return request(buildUrl(server, remotePath), "PUT", user, pass, content);
    }

    public static Result get(String server, String user, String pass, String remotePath) {
        return request(buildUrl(server, remotePath), "GET", user, pass, null);
    }

    private static String buildUrl(String server, String remotePath) {
        String base = server == null ? "" : server.trim();
        if (!base.endsWith("/")) base += '/';
        String path = remotePath == null ? "" : remotePath.trim();
        if (path.startsWith("/")) path = path.substring(1);
        return base + path;
    }

    private static Result request(String target, String method, String user,
                                  String pass, String content) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(target);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod(method);
            if (user != null && !user.isEmpty()) {
                String auth = android.util.Base64.encodeToString(
                        (user + ":" + pass).getBytes(StandardCharsets.UTF_8),
                        android.util.Base64.NO_WRAP);
                conn.setRequestProperty("Authorization", "Basic " + auth);
            }
            if ("PUT".equals(method)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                byte[] bytes = content == null ? new byte[0]
                        : content.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            java.io.InputStream in = ok ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder body = new StringBuilder();
            if (in != null) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line).append('\n');
                }
                in.close();
            }
            return new Result(ok, body.toString(), code);
        } catch (Exception e) {
            return new Result(false, String.valueOf(e.getMessage()), -1);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
