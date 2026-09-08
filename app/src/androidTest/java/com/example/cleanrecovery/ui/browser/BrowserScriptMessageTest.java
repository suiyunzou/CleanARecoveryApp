package com.example.cleanrecovery.ui.browser;

import android.app.Instrumentation;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class BrowserScriptMessageTest {
    private static final String BARRIER = "ViaScriptMessageTestBarrier";
    private Instrumentation instrumentation;
    private BrowserWebView web;

    @Before public void createPageWithoutInstallingScripts() throws Exception {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER));
        instrumentation=InstrumentationRegistry.getInstrumentation();
        CompletableFuture<Boolean> loaded=new CompletableFuture<>();
        instrumentation.runOnMainSync(() -> {
            web=new BrowserWebView(instrumentation.getTargetContext());
            web.getSettings().setJavaScriptEnabled(true);
            WebViewCompat.addWebMessageListener(web,BARRIER,Collections.singleton("*"),
                    (view,message,origin,mainFrame,reply) -> reply.postMessage("processed"));
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view,String url) { loaded.complete(true); }
            });
            web.loadDataWithBaseURL("http://message-fixture.invalid/","<html><body>Request message validation</body></html>","text/html","UTF-8",null);
        });
        assertTrue("Local page must finish before posting bridge messages",loaded.get(5,TimeUnit.SECONDS));
    }

    @After public void destroyPage() {
        if(web!=null)instrumentation.runOnMainSync(() -> {
            WebViewCompat.removeWebMessageListener(web,BARRIER);
            web.destroy();
        });
    }

    @Test public void invalidTokenCannotInvokeAnExistingRequestCallback() throws Exception {
        runMessages("channel.postMessage(JSON.stringify({token:'invalid',callback:'__via_req_denied',details:'{}'}));window.sent++;",1);
    }

    @Test public void malformedAndArrayBufferMessagesLeaveTheChannelUsable() throws Exception {
        boolean arrays=WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER);
        String messages="channel.postMessage('{');window.sent++;"
                +(arrays?"channel.postMessage(new ArrayBuffer(1));window.sent++;":"")
                +"channel.postMessage(JSON.stringify({token:'invalid',callback:'__via_req_denied',details:'{}'}));window.sent++;";
        runMessages(messages,arrays?3:2);
    }

    private void runMessages(String messages,int count) throws Exception {
        String setup="(function(){window.callbackCalls=0;window.sent=0;window.barrier=false;"
                +"window.__via_req_denied=function(){window.callbackCalls++;};"
                +"var channel=window.ViaScriptRequestMessages;"
                +"channel.onmessage=function(e){var m=JSON.parse(e.data),f=window[m.callback];if(typeof f==='function')f(m.event,m.response)};"
                +BARRIER+".onmessage=function(){window.barrier=true;};"
                +messages+BARRIER+".postMessage('after invalid messages');return true;})()";
        assertEquals("The request channel must accept page messages without throwing","true",evaluate(setup));
        // The reply follows the invalid posts through the native message queue and back to this document.
        // It proves the renderer and instrumentation process survived, without starting network traffic.
        long deadline=System.currentTimeMillis()+5000;
        String observed="";
        do {
            observed=evaluate("[window.barrier,window.callbackCalls,window.sent]");
            if(observed.startsWith("[true,"))break;
            Thread.sleep(25);
        } while(System.currentTimeMillis()<deadline);
        assertEquals("Rejected page messages must not borrow an existing GM callback; the channel remains usable",
                "[true,0,"+count+"]",observed);
    }

    private String evaluate(String javascript) throws Exception {
        CompletableFuture<String> result=new CompletableFuture<>();
        instrumentation.runOnMainSync(() -> web.evaluateJavascript(javascript,result::complete));
        return result.get(3,TimeUnit.SECONDS);
    }
}
