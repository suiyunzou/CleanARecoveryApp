package com.example.cleanrecovery.ui.browser;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserScriptHttpTest {
    @org.junit.Before public void removeStaleRequestFixtures() {
        android.content.Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        BrowserPrefs prefs=new BrowserPrefs(context);
        for(String name:new java.util.ArrayList<>(prefs.scriptNames()))
            if((name.startsWith("__native_request_") || name.startsWith("__request_lifecycle_"))
                    && prefs.scriptCode(name).contains("http://127.0.0.1:")) prefs.removeScript(name);
        context.getSharedPreferences("via_browser_prefs",0).edit().commit();
    }
    @org.junit.After public void flushFixtureCleanup() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("via_browser_prefs",0).edit().commit();
    }

    @Test public void successfulResponseReportsStatesBeforeReturningBody() throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            CompletableFuture<Throwable> failure=new CompletableFuture<>();
            Thread serving=new Thread(() -> {
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();failure.complete(null);
                }catch(Exception error){failure.complete(error);}
            });serving.start();
            java.util.List<String> events=new java.util.ArrayList<>();
            java.util.List<JSONObject> progress=new java.util.ArrayList<>();
            JSONObject result=BrowserScriptHttp.request(new JSONObject().put("url","http://127.0.0.1:"+server.getLocalPort()+"/success"),Proxy.NO_PROXY,c->{},
                    (event,state)->{if(event.equals("progress"))progress.add(state);else events.add(event+":"+state.optInt("readyState"));});
            assertEquals(java.util.Arrays.asList("loadstart:1","readystatechange:2","readystatechange:3"),events);
            assertEquals("Successful response reaches DONE with its body",4,result.getInt("readyState"));
            assertEquals("ok",result.getString("responseText"));
            assertFalse("A consumed body reports download progress",progress.isEmpty());
            JSONObject last=progress.get(progress.size()-1);
            assertEquals(2,last.getLong("loaded"));assertEquals(2,last.getLong("total"));assertTrue(last.getBoolean("lengthComputable"));
            assertNull(failure.get(2,TimeUnit.SECONDS));serving.join(1000);
        }
    }
    @Test public void nativeRequestPreservesHttpErrorsAndUsesExplicitProxy() throws Exception {
        try (ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<String> request=new CompletableFuture<>();
            Thread serving=new Thread(() -> {
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    String first=input.readLine(); String line; int length=0;
                    while((line=input.readLine())!=null&&!line.isEmpty())
                        if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(15).trim());
                    char[] body=new char[length]; int offset=0;
                    while(offset<length) {int n=input.read(body,offset,length-offset);if(n<0)throw new EOFException();offset+=n;}
                    request.complete(first+"|"+new String(body));
                    byte[] response="permission denied".getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(response);socket.getOutputStream().flush();
                } catch(Exception error){request.completeExceptionally(error);}
            }); serving.start();
            Proxy proxy=new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",server.getLocalPort()));
            JSONObject result=BrowserScriptHttp.request(new JSONObject().put("url","http://does-not-resolve.invalid/api")
                    .put("method","POST").put("data","key=value").put("timeout",5000),proxy);
            assertEquals("Proxy receives target URL without local DNS lookup","POST http://does-not-resolve.invalid/api HTTP/1.1|key=value",request.get(5,TimeUnit.SECONDS));
            assertEquals("HTTP error is a response, not discarded as transport failure",403,result.getInt("status"));
            assertEquals("Via stops HTTP failures at headers received",2,result.getInt("readyState"));
            assertFalse("Via does not consume HTTP error bodies",result.has("responseText"));
            serving.join(1000);
        }
    }

    @Test public void pageReceivesProgressThenSuccessfulJsonAndCompletion() throws Exception {
        android.app.Instrumentation instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        BrowserPrefs prefs=new BrowserPrefs(instrumentation.getTargetContext());
        String name="__native_request_"+java.util.UUID.randomUUID();
        boolean master=prefs.scriptsEnabled();
        BrowserWebView[] web=new BrowserWebView[1];
        CompletableFuture<Throwable> failure=new CompletableFuture<>();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Thread serving=new Thread(() -> {
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    assertEquals("POST /progress HTTP/1.1",reader.readLine());
                    String line;int length=0;String cookie="";
                    while((line=reader.readLine())!=null&&!line.isEmpty()) {
                        if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                        if(line.toLowerCase(java.util.Locale.ROOT).startsWith("cookie:"))cookie=line.substring(7).trim();
                    }
                    assertEquals("header=one;explicit=two",cookie);
                    assertEquals("Upload reports UTF-8 bytes, not JavaScript character count",6,length);
                    char[] posted=new char[2];int offset=0;
                    while(offset<posted.length){int count=reader.read(posted,offset,posted.length-offset);if(count<0)throw new EOFException();offset+=count;}
                    assertEquals("中文",new String(posted));
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\nb\r\n{\"ok\":true}\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();failure.complete(null);
                }catch(Throwable error){failure.complete(error);}
            });serving.start();
            String code="// ==UserScript==\n// @grant GM_xmlhttpRequest\n// ==/UserScript==\n"
                    +"window.events=[];window.contexts=[];GM_xmlhttpRequest({url:'http://127.0.0.1:"+server.getLocalPort()+"/progress',method:'POST',data:'中文',headers:{cOoKiE:'header=one'},cookie:'explicit=two',context:{episode:7,nested:{name:'中文'}},responseType:'json',upload:{onprogress:function(r){window.upload=[r.loaded,r.total,r.lengthComputable];events.push('upload')}},"
                    +"onreadystatechange:function(r){contexts.push([r.context.episode,r.context.nested.name]);events.push('state'+r.readyState)},onloadstart:function(){events.push('start')},"
                    +"onprogress:function(r){window.progress=[r.loaded,r.total,r.lengthComputable];events.push('progress')},"
                    +"onload:function(r){window.result=[r.status,r.response.ok];events.push('load')},"
                    +"onerror:function(){events.push('error')},onloadend:function(){events.push('end');window.done=true}});";
            String origin="http://"+java.util.UUID.randomUUID()+".test/";
            prefs.saveScript(name,origin+"*",code);prefs.setScriptsEnabled(true);
            instrumentation.runOnMainSync(() -> {
                web[0]=new BrowserWebView(instrumentation.getTargetContext());web[0].getSettings().setJavaScriptEnabled(true);
                web[0].applyUserScripts();web[0].loadDataWithBaseURL(origin,"<html><body>Download progress</body></html>","text/html","UTF-8",null);
            });
            String observed="";long end=System.currentTimeMillis()+5000;
            while(System.currentTimeMillis()<end) {
                CompletableFuture<String> value=new CompletableFuture<>();
                instrumentation.runOnMainSync(() -> web[0].evaluateJavascript("[window.done,window.result,window.progress,window.events,window.upload,window.contexts]",value::complete));
                observed=value.get(3,TimeUnit.SECONDS);if(observed.startsWith("[true,"))break;Thread.sleep(50);
            }
            org.json.JSONArray snapshot=new org.json.JSONArray(observed);
            assertTrue("Progress must not remove the terminal callback: "+observed,snapshot.getBoolean(0));
            assertEquals("[200,true]",snapshot.getJSONArray(1).toString());
            assertEquals("Chunked response reports bytes without inventing a total","[11,-1,false]",snapshot.getJSONArray(2).toString());
            assertEquals("Upload size matches the bytes received by server","[6,6,true]",snapshot.getJSONArray(4).toString());
            assertEquals("Request context follows all state callbacks","[[7,\"中文\"],[7,\"中文\"],[7,\"中文\"],[7,\"中文\"]]",snapshot.getJSONArray(5).toString());
            java.util.List<String> events=new java.util.ArrayList<>();
            org.json.JSONArray sequence=snapshot.getJSONArray(3);
            for(int i=0;i<sequence.length();i++)if(!sequence.getString(i).equals("progress"))events.add(sequence.getString(i));
            assertEquals(java.util.Arrays.asList("state1","start","upload","state2","state3","state4","load","end"),events);
            assertTrue("Progress arrives while the body is loading",sequence.toString().indexOf("progress")<sequence.toString().indexOf("state4"));
            assertNull(failure.get(2,TimeUnit.SECONDS));serving.join(1000);
        } finally {
            prefs.removeScript(name);prefs.setScriptsEnabled(master);
            instrumentation.runOnMainSync(() -> {if(web[0]!=null)web[0].destroy();});
        }
    }
    @Test public void stalledServerHonorsConfiguredTimeout() throws Exception {
        CountDownLatch release=new CountDownLatch(1);
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<Boolean> accepted=new CompletableFuture<>();
            Thread serving=new Thread(() -> {try(Socket socket=server.accept()) {accepted.complete(true);release.await(5,TimeUnit.SECONDS);}
                catch(Exception error){accepted.completeExceptionally(error);}});
            serving.start();
            try {
                BrowserScriptHttp.request(new JSONObject().put("url","http://127.0.0.1:"+server.getLocalPort()+"/stall").put("timeout",200),Proxy.NO_PROXY);
                fail("Read must time out instead of hanging a worker");
            } catch(SocketTimeoutException expected) {assertTrue(accepted.get(1,TimeUnit.SECONDS));}
            finally {release.countDown();serving.join(1000);}
        }
    }

    @Test public void anonymousRequestsSuppressBothExplicitCookieSources() throws Exception {
        try(ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            CompletableFuture<java.util.List<String>> received=new CompletableFuture<>();
            Thread serving=new Thread(() -> {
                java.util.List<String> cookies=new java.util.ArrayList<>();
                try {
                    for(int i=0;i<4;i++)try(Socket socket=server.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                        String line,cookie="";while((line=reader.readLine())!=null&&!line.isEmpty())
                            if(line.toLowerCase(java.util.Locale.ROOT).startsWith("cookie:"))cookie=line.substring(7).trim();
                        cookies.add(cookie);
                        socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    received.complete(cookies);
                }catch(Throwable error){received.completeExceptionally(error);}
            });serving.start();
            String target="http://127.0.0.1:"+server.getLocalPort()+"/cookie";
            JSONObject details=new JSONObject().put("url",target).put("timeout",3000)
                    .put("headers",new JSONObject().put("cOoKiE","header=one")).put("cookie","explicit=two");
            BrowserScriptHttp.request(details.put("anonymous",true),Proxy.NO_PROXY);
            BrowserScriptHttp.request(details.put("anonymous",false),Proxy.NO_PROXY);
            details.remove("headers");
            BrowserScriptHttp.request(details,Proxy.NO_PROXY);
            details.remove("cookie");
            BrowserScriptHttp.request(details,Proxy.NO_PROXY);
            assertEquals("Anonymous removes both sources; normal requests combine them without leaking to subsequent calls",
                    java.util.Arrays.asList("","header=one;explicit=two","explicit=two",""),received.get(5,TimeUnit.SECONDS));
            serving.join(1000);
        }
    }
    @Test public void userscriptNativeBridgeReadsCrossOriginResponseRejectedByFetch() throws Exception {
        android.app.Instrumentation instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        BrowserPrefs prefs=new BrowserPrefs(instrumentation.getTargetContext());
        String name="__native_request_"+java.util.UUID.randomUUID();
        BrowserWebView[] web=new BrowserWebView[1];
        boolean master=prefs.scriptsEnabled();
        CompletableFuture<Throwable> failure=new CompletableFuture<>();
        try(ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(10000);
            Thread serving=new Thread(() -> {
                try {
                    while(!server.isClosed())try(Socket socket=server.accept()) {
                        socket.setSoTimeout(5000);
                        BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                        String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                        byte[] body="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n"+new String(body,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
                    }
                    failure.complete(null);
                }catch(Exception error){failure.complete(server.isClosed()?null:error);}
            });serving.start();
            String target="http://127.0.0.1:"+server.getLocalPort()+"/cors";
            String code="// ==UserScript==\n// @name native request\n// @grant GM_xmlhttpRequest\n// @grant GM.xmlHttpRequest\n// ==/UserScript==\n"
                    +"fetch('"+target+"').then(function(){window.normalFetch='allowed'}).catch(function(){window.normalFetch='blocked'});"
                    +"window.states=[];GM_xmlhttpRequest({url:'"+target+"',responseType:GM_xmlhttpRequest.RESPONSE_TYPE_JSON,onreadystatechange:function(r){states.push(r.readyState)},onload:function(){window.nativeStatus='unexpected load'},onloadend:function(){window.nativeEnded=true},onerror:function(r){window.nativeError=r.error;window.nativeStatus=r.status;window.nativeBody=r.readyState===2&&!('responseText' in r)}});"
                    +"GM.xmlHttpRequest({url:'"+target+"',responseType:'json'}).then(function(){window.promiseStatus='unexpected resolve'},function(r){window.promiseStatus=r.status});"
                    +"window.requestConstants=[GM_xmlhttpRequest.UNSENT,GM_xmlhttpRequest.OPENED,GM_xmlhttpRequest.HEADERS_RECEIVED,GM_xmlhttpRequest.LOADING,GM_xmlhttpRequest.DONE,GM_xmlhttpRequest.RESPONSE_TYPE_ARRAYBUFFER,GM_xmlhttpRequest.RESPONSE_TYPE_BLOB,GM_xmlhttpRequest.RESPONSE_TYPE_DOCUMENT,GM_xmlhttpRequest.RESPONSE_TYPE_JSON,GM_xmlhttpRequest.RESPONSE_TYPE_STREAM,GM_xmlhttpRequest.RESPONSE_TYPE_TEXT];";
            prefs.saveScript(name,"http://origin.test/*",code);prefs.setScriptsEnabled(true);
            instrumentation.runOnMainSync(() -> {web[0]=new BrowserWebView(instrumentation.getTargetContext());web[0].getSettings().setJavaScriptEnabled(true);
                web[0].applyUserScripts();web[0].loadDataWithBaseURL("http://origin.test/","<html><body>Cross origin script</body></html>","text/html","UTF-8",null);});
            String observed="";long end=System.currentTimeMillis()+10000;
            while(System.currentTimeMillis()<end) {
                CompletableFuture<String> value=new CompletableFuture<>();
                instrumentation.runOnMainSync(() -> web[0].evaluateJavascript("[window.normalFetch,window.nativeStatus,window.nativeBody,window.promiseStatus,window.nativeEnded]",value::complete));
                observed=value.get(3,TimeUnit.SECONDS);
                if("[\"blocked\",403,true,403,true]".equals(observed))break;
                Thread.sleep(50);
            }
            CompletableFuture<String> diagnostic=new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> web[0].evaluateJavascript("window.nativeError",diagnostic::complete));
            assertEquals("diagnostic="+diagnostic.get(3,TimeUnit.SECONDS)+" fixture="+(failure.isDone()?failure.get():"pending")+" Via routes HTTP failures to onerror and Promise rejection, while page fetch is denied","[\"blocked\",403,true,403,true]",observed);
            CompletableFuture<String> constants=new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> web[0].evaluateJavascript("requestConstants",constants::complete));
            assertEquals("Scripts can use Via's named request states and response types instead of hardcoded literals",
                    "[0,1,2,3,4,\"arraybuffer\",\"blob\",\"document\",\"json\",\"stream\",\"text\"]",constants.get(3,TimeUnit.SECONDS));
            server.close();
            assertNull("HTTP fixture completed without hidden failures",failure.get(2,TimeUnit.SECONDS));
            CompletableFuture<String> states=new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> web[0].evaluateJavascript("states",states::complete));
            assertEquals("Via emits opened, headers, then headers again with the error callback","[1,2,2]",states.get(3,TimeUnit.SECONDS));
            CompletableFuture<String> denied=new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> web[0].evaluateJavascript("ViaScriptRequests.start('invalid','__via_req_untrusted','{}')",denied::complete));
            assertEquals("Untrusted page cannot start a native request","false",denied.get(3,TimeUnit.SECONDS));
            serving.join(1000);
        } finally {
            prefs.removeScript(name);prefs.setScriptsEnabled(master);
            instrumentation.runOnMainSync(() -> {if(web[0]!=null)web[0].destroy();});
        }
    }
    @Test public void unsupportedSchemesCannotReadLocalFiles() throws Exception {
        try { BrowserScriptHttp.request(new JSONObject().put("url","file:///data/local/tmp/script.txt"),Proxy.NO_PROXY);fail("file URL must be rejected"); }
        catch(IOException expected){assertTrue(expected.getMessage().contains("HTTP(S)"));}
    }

    @Test public void abortAndTimeoutDeliverExactlyOneTerminalCallback() throws Exception {
        android.app.Instrumentation instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        BrowserPrefs prefs=new BrowserPrefs(instrumentation.getTargetContext());
        String name="__request_lifecycle_"+java.util.UUID.randomUUID();
        boolean master=prefs.scriptsEnabled();
        BrowserWebView[] web=new BrowserWebView[1];
        CountDownLatch release=new CountDownLatch(1);
        CompletableFuture<Boolean> accepted=new CompletableFuture<>();
        try(ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Thread serving=new Thread(() -> {
                try(Socket socket=server.accept()) {
                    accepted.complete(true);
                    release.await(5,TimeUnit.SECONDS);
                }catch(Exception error){accepted.completeExceptionally(error);}
            });
            serving.start();
            String target="http://127.0.0.1:"+server.getLocalPort()+"/stall";
            String code="// ==UserScript==\n// @grant GM_xmlhttpRequest\n// ==/UserScript==\n"
                    +"window.events=[];window.req=GM_xmlhttpRequest({url:'"+target+"',timeout:2000,"
                    +"onabort:function(){events.push('abort')},onload:function(){events.push('load')},"
                    +"onerror:function(){events.push('error')},ontimeout:function(){events.push('timeout')},"
                    +"onloadend:function(){events.push('end')}});";
            prefs.saveScript(name,"http://lifecycle.test/*",code);prefs.setScriptsEnabled(true);
            instrumentation.runOnMainSync(() -> {
                web[0]=new BrowserWebView(instrumentation.getTargetContext());
                web[0].getSettings().setJavaScriptEnabled(true);web[0].applyUserScripts();
                web[0].loadDataWithBaseURL("http://lifecycle.test/","<html><body>Request cancellation</body></html>","text/html","UTF-8",null);
            });
            assertTrue("Cancel a request that actually reached the server",accepted.get(5,TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> web[0].evaluateJavascript("req.abort();req.abort()",null));
            // Wait beyond the configured timeout: neither the worker failure nor a timer may deliver a second terminal event.
            Thread.sleep(2300);
            CompletableFuture<String> result=new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> web[0].evaluateJavascript("events",result::complete));
            assertEquals("Repeated cancellation must settle once without load/error/timeout afterward","[\"abort\",\"end\"]",result.get(3,TimeUnit.SECONDS));
            release.countDown();serving.join(1000);
        }finally {
            release.countDown();prefs.removeScript(name);prefs.setScriptsEnabled(master);
            instrumentation.runOnMainSync(() -> {if(web[0]!=null)web[0].destroy();});
        }
    }
}
