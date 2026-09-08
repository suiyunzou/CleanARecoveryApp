package com.example.cleanrecovery.ui.browser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small OpenAI-compatible chat client used by Via-style AI topics. */
public final class BrowserAiClient {
    private BrowserAiClient() { }

    public static String chat(String endpoint, String apiKey, String model,
                              String systemPrompt, String message) throws Exception {
        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        }
        messages.put(new JSONObject().put("role", "user").put("content", message));
        HttpURLConnection connection = connect(endpoint, apiKey, model, messages, false, null);
        try {
            int code = connection.getResponseCode();
            String response = read(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) throw httpError(code, response);
            return reply(new JSONObject(response));
        } finally { connection.disconnect(); }
    }

    private static HttpURLConnection connect(String endpoint, String apiKey, String model,
                                              JSONArray messages, boolean stream, Request request) throws Exception {
        String base = endpoint == null ? "" : endpoint.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.endsWith("/chat/completions")) base += "/chat/completions";
        HttpURLConnection connection = (HttpURLConnection) new URL(base).openConnection();
        if (request != null) request.connection = connection;
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        byte[] body = new JSONObject().put("model", model).put("messages", messages).put("stream", stream)
                .put("temperature", 0.5)
                .toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            if (request != null && request.cancelled) throw new java.io.InterruptedIOException("已停止");
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            return connection;
        } catch (Exception error) { connection.disconnect(); throw error; }
    }

    private static String reply(JSONObject json) throws Exception {
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IOException("响应中没有回答");
        return choices.getJSONObject(0).getJSONObject("message").optString("content");
    }

    public interface StreamListener { void onUpdate(String content, String reasoning); }

    private static IOException httpError(int code, String response) {
        return new IOException("HTTP 错误：" + code + "\n\n" + (response.isEmpty() ? "Empty" : response));
    }

    /** A single cancellable reply. Updates contain the accumulated text, not transport fragments. */
    public static final class Request {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        public boolean isCancelled() { return cancelled; }

        public void cancel() {
            cancelled = true;
            HttpURLConnection active = connection;
            if (active != null) active.disconnect();
        }

        public void run(String endpoint, String apiKey, String model, JSONArray messages,
                        StreamListener listener) throws Exception {
            if (cancelled) return;
            HttpURLConnection active = connect(endpoint, apiKey, model, messages, true, this);
            connection = active;
            try {
                if (cancelled) return;
                int code = active.getResponseCode();
                if (code < 200 || code >= 300) throw httpError(code, read(active.getErrorStream()));
                String type = active.getContentType();
                if (type != null && type.contains("application/json")) {
                    String content = reply(new JSONObject(read(active.getInputStream())));
                    if (!cancelled) listener.onUpdate(content, "");
                    return;
                }
                StringBuilder content = new StringBuilder();
                StringBuilder reasoning = new StringBuilder();
                StringBuilder data = new StringBuilder();
                boolean received = false;
                boolean finished = false;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(active.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!cancelled && (line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            if (data.length() > 0) data.append('\n');
                            String fragment = line.substring(5);
                            data.append(fragment.startsWith(" ") ? fragment.substring(1) : fragment);
                        } else if (line.isEmpty() && data.length() > 0) {
                            String event = data.toString();
                            data.setLength(0);
                            if ("[DONE]".equals(event)) { finished = true; break; }
                            JSONObject json = new JSONObject(event);
                            if (json.has("error")) throw new IOException(json.get("error").toString());
                            JSONArray choices = json.optJSONArray("choices");
                            if (choices == null || choices.length() == 0) continue;
                            if (!choices.getJSONObject(0).isNull("finish_reason")) finished = true;
                            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                            if (delta == null) continue;
                            if (!delta.isNull("content")) content.append(delta.optString("content"));
                            if (!delta.isNull("reasoning_content")) reasoning.append(delta.optString("reasoning_content"));
                            if (content.length() > 0 || reasoning.length() > 0) {
                                received = true;
                                if (!cancelled) listener.onUpdate(content.toString(), reasoning.toString());
                            }
                        }
                    }
                }
                if (!cancelled && !received) throw new IOException("响应中没有回答");
                if (!cancelled && !finished) throw new IOException("回复连接意外中断");
            } finally { active.disconnect(); connection = null; }
        }
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) output.write(buffer, 0, n);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
