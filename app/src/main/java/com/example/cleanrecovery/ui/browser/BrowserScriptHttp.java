package com.example.cleanrecovery.ui.browser;

import com.example.cleanrecovery.proxy.ProxyEngine;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native userscript HTTP transport. Call from a worker, never a Javascript/UI thread. */
public final class BrowserScriptHttp {
    private BrowserScriptHttp() { }

    public static Proxy currentProxy() throws IOException {
        ProxyEngine engine = ProxyEngine.current();
        if (engine == null || !engine.isRunning()) return Proxy.NO_PROXY;
        if (engine.httpPort() <= 0) throw new IOException("Active proxy has no HTTP endpoint");
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ProxyEngine.localHost(), engine.httpPort()));
    }

    public static JSONObject request(JSONObject details, Proxy proxy) throws Exception {
        return request(details, proxy, connection -> {});
    }

    static JSONObject request(JSONObject details, Proxy proxy, java.util.function.Consumer<HttpURLConnection> opened) throws Exception {
        return request(details, proxy, opened, (event,response) -> {});
    }

    static JSONObject request(JSONObject details, Proxy proxy, java.util.function.Consumer<HttpURLConnection> opened,
                              java.util.function.BiConsumer<String,JSONObject> events) throws Exception {
        URL url = new URL(details.getString("url"));
        if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol()))
            throw new IOException("Only HTTP(S) script requests are supported");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
        opened.accept(connection);
        int timeout = details.optInt("timeout", 30000);
        if (timeout < 0) throw new IllegalArgumentException("Negative request timeout");
        connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
        String method = details.optString("method", "GET").toUpperCase(java.util.Locale.ROOT);
        connection.setRequestMethod(method);
        events.accept("loadstart",new JSONObject().put("readyState",1).put("status",0).put("context",details.optJSONObject("context")));
        JSONObject headers = details.optJSONObject("headers");
        String cookie="";
        if (headers != null) for (java.util.Iterator<String> names = headers.keys(); names.hasNext();) {
            String name = names.next();
            if("Cookie".equalsIgnoreCase(name)) cookie=headers.getString(name);
            else connection.setRequestProperty(name, headers.getString(name));
        }
        if(!details.optBoolean("anonymous",false)) {
            String explicit=details.optString("cookie","");
            if(!explicit.isEmpty()) cookie=cookie.isEmpty()?explicit:cookie+";"+explicit;
            if(!cookie.isEmpty()) connection.setRequestProperty("Cookie",cookie);
        }
        if (connection.getRequestProperty("User-Agent") == null && details.has("v_ua"))
            connection.setRequestProperty("User-Agent", details.getString("v_ua"));
        try {
            if (details.has("data") && !"GET".equals(method) && !"HEAD".equals(method)) {
                byte[] body = details.getString("data").getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
                events.accept("uploadprogress",new JSONObject().put("lengthComputable",true)
                        .put("loaded",body.length).put("total",body.length));
            }
            int status = connection.getResponseCode();
            JSONObject response=new JSONObject().put("readyState",2).put("status",status)
                    .put("statusText",connection.getResponseMessage()).put("finalUrl",connection.getURL().toString())
                    .put("context",details.optJSONObject("context"));
            events.accept("readystatechange",new JSONObject(response.toString()));
            if(status<200 || status>=300) return response;
            StringBuilder responseHeaders = new StringBuilder();
            for (Map.Entry<String,List<String>> entry : connection.getHeaderFields().entrySet())
                if (entry.getKey()!=null) for(String value:entry.getValue()) responseHeaders.append(entry.getKey()).append(": ").append(value).append("\r\n");
            byte[] bytes;
            response.put("readyState",3).put("responseHeaders",responseHeaders.toString());
            events.accept("readystatechange",new JSONObject(response.toString()));
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                long total=connection.getContentLength();
                if (input != null) { byte[] buffer = new byte[8192]; int count;
                    while((count=input.read(buffer))!=-1) {
                        output.write(buffer,0,count);
                        events.accept("progress",new JSONObject().put("readyState",3).put("status",status)
                                .put("statusText",connection.getResponseMessage()).put("finalUrl",connection.getURL().toString())
                                .put("responseHeaders",responseHeaders.toString()).put("loaded",output.size())
                                .put("lengthComputable",total>0).put("total",total>0?total:-1));
                    } }
                bytes = output.toByteArray();
            }
            Charset charset = StandardCharsets.UTF_8;
            String contentType = connection.getContentType();
            if (contentType != null) {
                Matcher match = Pattern.compile("(?i)charset=[\"']?([^;\\s\"']+)").matcher(contentType);
                if (match.find()) try { charset=Charset.forName(match.group(1)); }
                    catch (IllegalArgumentException unsupported) { /* Unknown charset falls back to UTF-8. */ }
            }
            return new JSONObject().put("readyState",4).put("status",status)
                    .put("statusText",connection.getResponseMessage()).put("finalUrl",connection.getURL().toString())
                    .put("responseHeaders",responseHeaders.toString()).put("responseText",new String(bytes,charset))
                    .put("responseBase64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
                    .put("contentType",contentType==null?"":contentType).put("context",details.optJSONObject("context"));
        } finally { connection.disconnect(); }
    }
}
