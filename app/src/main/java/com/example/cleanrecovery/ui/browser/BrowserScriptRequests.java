package com.example.cleanrecovery.ui.browser;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated asynchronous native request callbacks, scoped to a WebView. */
public final class BrowserScriptRequests {
    public static final String CHANNEL = "ViaScriptRequests";
    public static final String FRAME_CHANNEL = "ViaScriptRequestMessages";
    private final WebView web;
    private final BrowserScriptValues values;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private static final class Pending {
        final String token;
        final java.util.function.BiConsumer<String,String> reply;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile HttpURLConnection connection;
        Pending(String token,java.util.function.BiConsumer<String,String> reply) { this.token=token;this.reply=reply; }
    }
    BrowserScriptRequests(WebView web, BrowserScriptValues values) {
        this.web=web; this.values=values;
        if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER))
            androidx.webkit.WebViewCompat.addWebMessageListener(web,FRAME_CHANNEL,java.util.Collections.singleton("*"),
                    (view,message,origin,mainFrame,reply) -> {
                        if(message.getType()!=androidx.webkit.WebMessageCompat.TYPE_STRING || message.getData()==null)return;
                        try {
                            JSONObject request=new JSONObject(message.getData());
                            String token=request.getString("token"),callback=request.getString("callback");
                            if("abort".equals(request.optString("action"))) {abort(token,callback);return;}
                            java.util.function.BiConsumer<String,String> respond=(event,payload) ->
                                    reply.postMessage("{\"callback\":"+JSONObject.quote(callback)+",\"event\":"+JSONObject.quote(event)+",\"response\":"+payload+"}");
                            start(token,callback,request.getString("details"),respond);
                        }catch(org.json.JSONException malformed) { /* Ignore malformed page messages. */ }
                    });
    }

    @JavascriptInterface public synchronized boolean start(String token, String callback, String details) {
        return start(token,callback,details,(event,payload) -> web.evaluateJavascript("(function(){var f=window["+JSONObject.quote(callback)+"];if(typeof f==='function')f("+JSONObject.quote(event)+","+payload+")})()",null));
    }
    private synchronized boolean start(String token,String callback,String details,java.util.function.BiConsumer<String,String> reply) {
        if (closed || !values.allowsRequest(token) || callback==null || !callback.matches("__via_req_[a-zA-Z0-9_]+")) return false;
        Pending job=new Pending(token,reply);
        if(pending.putIfAbsent(callback,job)!=null)return false;
        workers.execute(() -> {
            try {
                JSONObject response=BrowserScriptHttp.request(new JSONObject(details), BrowserScriptHttp.currentProxy(), connection -> {
                    job.connection=connection;
                    if(job.finished.get()) {connection.disconnect();throw new java.util.concurrent.CancellationException();}
                }, (event,state) -> {if(!job.finished.get())dispatch(job,event,state);});
                int status=response.getInt("status");
                finish(callback,job,status>=200 && status<300 ? "load" : "error",response);
            } catch(Exception error) {
                finish(callback,job,error instanceof java.net.SocketTimeoutException ? "timeout" : "error", failure(error.toString()));
            }
        });
        return true;
    }
    @JavascriptInterface public void abort(String token,String callback) {
        Pending job=pending.get(callback);
        if(job==null || !job.token.equals(token))return;
        finish(callback,job,"abort",failure("Aborted"));
        if(job.connection!=null)job.connection.disconnect();
    }
    private static JSONObject failure(String error) {
        try { return new JSONObject().put("readyState",4).put("status",0).put("error",error); }
        catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
    }
    private void finish(String callback,Pending job,String event,JSONObject response) {
        if(!job.finished.compareAndSet(false,true))return;
        pending.remove(callback,job);
        dispatch(job,event,response);
    }
    private void dispatch(Pending job,String event,JSONObject response) {
        String payload=response.toString();
        main.post(() -> {if(!closed)job.reply.accept(event,payload);});
    }
    synchronized void close() {
        closed=true;
        for(Pending job:pending.values()){job.finished.set(true);if(job.connection!=null)job.connection.disconnect();}
        pending.clear();workers.shutdownNow();
        if(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER))
            androidx.webkit.WebViewCompat.removeWebMessageListener(web,FRAME_CHANNEL);
    }
}
