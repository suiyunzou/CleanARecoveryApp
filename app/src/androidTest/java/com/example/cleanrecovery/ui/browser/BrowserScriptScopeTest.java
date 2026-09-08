package com.example.cleanrecovery.ui.browser;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserScriptScopeTest {
    @Test public void matchingRulesApplyIndependentlyInsideFrames() throws Exception {
        verifyFrameScopes(false);
    }
    @Test public void matchingRulesApplyIndependentlyInsideCrossOriginFrames() throws Exception {
        verifyFrameScopes(true);
    }
    @Test public void crossOriginFrameReceivesItsNativeRequestCallback() throws Exception {
        verifyFrameScopes(true,true);
    }
    private void verifyFrameScopes(boolean crossOrigin) throws Exception {
        verifyFrameScopes(crossOrigin,false);
    }
    private void verifyFrameScopes(boolean crossOrigin,boolean request) throws Exception {
        BrowserPrefs prefs=new BrowserPrefs(instrumentation.getTargetContext());
        String name="__frame_scope_"+java.util.UUID.randomUUID();
        boolean master=prefs.scriptsEnabled();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        try(java.net.ServerSocket server=new java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))) {
            String childOrigin="http://"+(crossOrigin?"localhost":"127.0.0.1")+":"+server.getLocalPort();
            Thread serving=new Thread(() -> {
                while(!server.isClosed())try(java.net.Socket socket=server.accept()) {
                    socket.setSoTimeout(3000);
                    java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII));
                    String first=reader.readLine(),line;
                    while((line=reader.readLine())!=null&&!line.isEmpty()){}
                    String html=first!=null&&first.contains("/child")?
                            "<html><head><script>window.addEventListener('load',function(){parent.postMessage({count:window.frameScopeCount||0},'*')});</script></head><body>Child</body></html>":
                            "<html><head><script>window.addEventListener('message',function(e){if(e.source===frames[0]){window.childCount=e.data.count;window.childOrigin=e.origin;if(e.data.reply)window.frameReply=e.data.reply}});</script></head><body>Parent<iframe src='"+childOrigin+"/child'></iframe></body></html>";
                    if(first!=null&&first.contains("/api"))html="{\"ok\":true}";
                    byte[] body=html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n"+html).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                }catch(Throwable error){if(!server.isClosed())failure.compareAndSet(null,error);}
            });serving.start();
            String base="http://127.0.0.1:"+server.getLocalPort();
            String source="// ==UserScript==\n// @run-at document-end\n// @grant GM_xmlhttpRequest\n// ==/UserScript==\nwindow.frameScopeCount=(window.frameScopeCount||0)+1;"
                    +(request?"if(window!==top)GM_xmlhttpRequest({url:'"+base+"/api',responseType:'json',onload:function(r){parent.postMessage({count:frameScopeCount,reply:[r.status,r.response.ok]},'*')}});":"");
            try {
                prefs.setScriptsEnabled(true);
                main(() -> {web=new BrowserWebView(instrumentation.getTargetContext());web.getSettings().setJavaScriptEnabled(true);return null;});
                for(boolean all:new boolean[]{false,true}) {
                    prefs.saveScript(name,all?base+"/*\n"+childOrigin+"/*":childOrigin+"/child*",source);
                    main(() -> {web.applyUserScripts();web.loadUrl(base+"/parent?all="+all);return null;});
                    String expected=all?"[1,1]":"[0,1]",observed="";
                    long end=System.currentTimeMillis()+5000;
                    while(System.currentTimeMillis()<end) {
                        observed=js("[window.frameScopeCount||0,window.childCount]");
                        if(expected.equals(observed)&&(!request||"[200,true]".equals(js("window.frameReply"))))break;
                        Thread.sleep(50);
                    }
                    assertEquals("Each frame must use its own URL, and scope changes must not duplicate injection",expected,observed);
                    if(request) assertEquals("Native response must return to the cross-origin frame that owns the callback","[200,true]",js("window.frameReply"));
                    assertEquals(childOrigin,new org.json.JSONTokener(js("window.childOrigin")).nextValue());
                    if(crossOrigin) assertEquals("The fixture must really be cross-origin, not merely use a different path","true",
                            js("(function(){try{void frames[0].document;return false}catch(e){return e.name==='SecurityError'}})()"));
                }
                server.close();serving.join(1000);assertNull(failure.get());
            } finally {
                main(() -> {if(web!=null)web.destroy();return null;});
                prefs.removeScript(name);prefs.setScriptsEnabled(master);
                instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs",0).edit().commit();
            }
        }
    }
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private BrowserWebView web;
    private <T> T main(java.util.concurrent.Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task); instrumentation.runOnMainSync(future); return future.get();
    }
    private String js(String code) throws Exception {
        java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        main(() -> { web.evaluateJavascript(code, result::complete); return null; });
        return result.get(5, TimeUnit.SECONDS);
    }
    private android.view.View find(android.view.View root, String text) {
        if (root instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView)root).getText())) return root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)root;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void editorSaveControlsAutomaticInjectionOnRealHttpPages() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        boolean originallyEnabled = prefs.scriptsEnabled();
        prefs.setScriptsEnabled(true);
        String name = "__scope_editor_" + java.util.UUID.randomUUID();
        java.net.ServerSocket server = new java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
        java.util.concurrent.atomic.AtomicReference<Throwable> serverError = new java.util.concurrent.atomic.AtomicReference<>();
        Thread serving = new Thread(() -> {
            while (!server.isClosed()) try (java.net.Socket socket = server.accept()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                String line; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                byte[] body = "<html><body>Scope navigation verification</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body); socket.getOutputStream().flush();
            } catch (Exception error) { if (!server.isClosed()) serverError.set(error); }
        }, "scope-editor-http");
        serving.start();
        String oldRule = "http://localhost:" + server.getLocalPort() + "/*";
        String newRule = "http://127.0.0.1:" + server.getLocalPort() + "/*";
        String source = "// ==UserScript==\n// @name " + name + "\n// @match " + oldRule
                + "\n// @run-at document-end\n// ==/UserScript==\nwindow.editorScopeCount=(window.editorScopeCount||0)+1;";
        android.app.Activity editor = null;
        try {
            prefs.saveScript(name, oldRule, source);
            android.content.Intent intent = new android.content.Intent(instrumentation.getTargetContext(),
                    com.example.cleanrecovery.ui.activity.BrowserSettingsActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(com.example.cleanrecovery.ui.activity.BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS, true)
                    .putExtra(com.example.cleanrecovery.ui.activity.BrowserSettingsActivity.EXTRA_EDIT_SCRIPT, name);
            editor = instrumentation.startActivitySync(intent);
            android.app.Activity shown = editor;
            main(() -> {
                android.view.View root = shown.getWindow().getDecorView();
                android.view.View sourceAction = find(root, "编辑源代码");
                assertNotNull(sourceAction);
                android.view.View sourceTarget = sourceAction;
                while (!sourceTarget.isClickable()) sourceTarget = (android.view.View)sourceTarget.getParent();
                sourceTarget.performClick();
                android.view.View rule = find(root, source);
                assertTrue("Editor exposes userscript source", rule instanceof android.widget.EditText);
                ((android.widget.EditText)rule).setText(source.replace(oldRule, newRule));
                android.view.View save = find(root, "保存"); assertNotNull(save); save.performClick(); return null;
            });
            long until = System.currentTimeMillis() + 5000;
            while (!newRule.equals(prefs.scriptMatch(name)) && System.currentTimeMillis() < until) Thread.sleep(50);
            assertEquals("Actual Save must persist the override", newRule, new BrowserPrefs(instrumentation.getTargetContext()).scriptMatch(name));
            assertEquals("Source save persists the edited metadata", source.replace(oldRule, newRule), prefs.scriptCode(name));
            instrumentation.waitForIdleSync();
            main(() -> { web = new BrowserWebView(instrumentation.getTargetContext());
                web.getSettings().setJavaScriptEnabled(true); web.applyUserScripts(); return null; });
            for (String rule : new String[]{oldRule, newRule, oldRule}) {
                java.util.concurrent.CountDownLatch loaded = new java.util.concurrent.CountDownLatch(1);
                String url = rule.substring(0, rule.length() - 1) + "page";
                main(() -> { web.setWebViewClient(new android.webkit.WebViewClient() {
                    @Override public void onPageFinished(android.webkit.WebView view, String finished) {
                        if (url.equals(finished)) loaded.countDown();
                    }
                }); web.loadUrl(url); return null; });
                assertTrue("Real HTTP page finishes", loaded.await(10, TimeUnit.SECONDS));
                assertEquals("The loaded HTTP document is the test fixture", "\"Scope navigation verification\"", js("document.body.innerText"));
                assertEquals("Only the scope saved through the editor injects, once per page",
                        newRule.equals(rule) ? "1" : "null", js("window.editorScopeCount"));
            }
            main(() -> {
                shown.onBackPressed();
                android.view.View label = find(shown.getWindow().getDecorView(), "启用脚本");
                assertNotNull("Saved script list includes Via master switch", label);
                android.view.View target = label;
                while (!target.isClickable() && target.getParent() instanceof android.view.View) target = (android.view.View)target.getParent();
                assertTrue(target.performClick()); return null;
            });
            assertFalse("Master toggle must persist", prefs.scriptsEnabled());
            assertTrue("Master toggle must preserve individual script choice", prefs.isScriptEnabled(name));
            java.util.concurrent.CountDownLatch disabledLoad = new java.util.concurrent.CountDownLatch(1);
            main(() -> { web.applyUserScripts(); web.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(android.webkit.WebView view, String url) { disabledLoad.countDown(); }
            }); web.loadUrl(newRule.substring(0, newRule.length()-1)+"disabled"); return null; });
            assertTrue(disabledLoad.await(10, TimeUnit.SECONDS));
            assertEquals("Master disable removes automatic injection even for an individually enabled matching script", "null", js("window.editorScopeCount"));
            assertNull("Local server must not silently fail", serverError.get());
        } finally {
            server.close(); serving.join(2000);
            prefs.setScriptsEnabled(originallyEnabled);
            prefs.removeScript(name);
            android.app.Activity finished = editor;
            main(() -> { if(web!=null)web.destroy(); if(finished!=null)finished.finish(); return null; });
        }
    }

    @Test public void nightStylesDarkenNestedContentAndRestoreOriginalColors() throws Exception {
        try {
            main(() -> { web = new BrowserWebView(instrumentation.getTargetContext());
                web.getSettings().setJavaScriptEnabled(true);
                web.loadDataWithBaseURL("https://night.parity.test/", "<html><body style='background:white;color:black'><div id='card' style='background:white;color:black'>Readable card</div><input id='input' value='input'></body></html>", "text/html", "UTF-8", null); return null; });
            long until = System.currentTimeMillis() + 5000;
            while (!"true".equals(js("!!document.getElementById('card')")) && System.currentTimeMillis()<until) Thread.sleep(50);
            main(() -> { BrowserNightMode.apply(web, true); return null; });
            assertEquals("Native darkening preserves author CSS", "\"rgb(255, 255, 255)\"", js("getComputedStyle(document.getElementById('card')).backgroundColor"));
            assertEquals("Page receives dark color scheme", "true", js("matchMedia('(prefers-color-scheme: dark)').matches"));
            assertTrue(main(() -> androidx.webkit.WebSettingsCompat.isAlgorithmicDarkeningAllowed(web.getSettings())));
            main(() -> { BrowserNightMode.apply(web, true); return null; });
            assertEquals("Native darkening must not stack forced CSS", "0", js("document.querySelectorAll('style[data-via-night]').length"));
            main(() -> { BrowserNightMode.apply(web, false); return null; });
            assertEquals("Day mode restores author styles", "\"rgb(255, 255, 255)\"", js("getComputedStyle(document.getElementById('card')).backgroundColor"));
            assertEquals("0", js("document.querySelectorAll('style[data-via-night]').length"));
        } finally { main(() -> { if(web!=null)web.destroy(); return null; }); }
    }

    @Test public void webViewThemeChangeUpdatesNativeColorScheme() throws Exception {
        try {
            main(() -> { android.view.ContextThemeWrapper themed = new android.view.ContextThemeWrapper(instrumentation.getTargetContext(), com.example.cleanrecovery.R.style.ViaWebLight);
                web = new BrowserWebView(themed); web.getSettings().setJavaScriptEnabled(true);
                web.loadDataWithBaseURL("https://theme.parity.test/", "<html><body>Theme</body></html>", "text/html", "UTF-8", null); return null; });
            Thread.sleep(300);
            assertEquals("false", js("matchMedia('(prefers-color-scheme: dark)').matches"));
            main(() -> { web.getContext().getTheme().applyStyle(com.example.cleanrecovery.R.style.ViaWebDark, true);
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.getSettings(), true); return null; });
            Thread.sleep(200);
            assertEquals("Changing theme before WebSettings must reach Chromium", "true", js("matchMedia('(prefers-color-scheme: dark)').matches"));
            main(() -> { web.getContext().getTheme().applyStyle(com.example.cleanrecovery.R.style.ViaWebLight, true);
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.getSettings(), false); return null; });
            Thread.sleep(200);
            assertEquals("false", js("matchMedia('(prefers-color-scheme: dark)').matches"));
        } finally { main(() -> { if(web!=null)web.destroy(); return null; }); }
    }

    @Test public void sitePanelAndCookieDialogFollowNightAndRestoreDay() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        boolean original = prefs.nightMode();
        android.app.Activity activity = instrumentation.startActivitySync(new android.content.Intent(instrumentation.getTargetContext(),
                com.example.cleanrecovery.ui.activity.BrowserActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            for (boolean night : new boolean[]{true, false}) {
                main(() -> {
                    prefs.setNightMode(night);
                    java.lang.reflect.Method toggle = activity.getClass().getDeclaredMethod("toggleSiteCard"); toggle.setAccessible(true);
                    android.view.View card = activity.findViewById(com.example.cleanrecovery.R.id.browser_site_card);
                    card.setVisibility(android.view.View.GONE); toggle.invoke(activity);
                    android.graphics.Bitmap pixel = android.graphics.Bitmap.createBitmap(40,40,android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.drawable.Drawable background = card.getBackground();
                    android.graphics.Rect oldBounds = new android.graphics.Rect(background.getBounds());
                    background.setBounds(0,0,40,40); background.draw(new android.graphics.Canvas(pixel)); background.setBounds(oldBounds);
                    int surface = pixel.getPixel(20,20); pixel.recycle();
                    double luminance = androidx.core.graphics.ColorUtils.calculateLuminance(surface);
                    assertTrue("Panel surface follows selected mode", night ? luminance < .1 : luminance > .8);
                    android.widget.TextView title = activity.findViewById(com.example.cleanrecovery.R.id.browser_site_card_title);
                    assertTrue("Panel title stays readable", androidx.core.graphics.ColorUtils.calculateContrast(title.getCurrentTextColor(),surface)>4.5);
                    java.lang.reflect.Method cookies = activity.getClass().getDeclaredMethod("showCookiesDialog",String.class); cookies.setAccessible(true);
                    android.app.AlertDialog dialog = (android.app.AlertDialog)cookies.invoke(activity,"https://empty-cookie-theme.test/");
                    try {
                        android.util.TypedValue themedSurface = new android.util.TypedValue();
                        assertTrue(dialog.getContext().getTheme().resolveAttribute(android.R.attr.colorBackground,themedSurface,true));
                        assertTrue("Cookie window follows mode", night ? androidx.core.graphics.ColorUtils.calculateLuminance(themedSurface.data)<.1 : androidx.core.graphics.ColorUtils.calculateLuminance(themedSurface.data)>.8);
                        android.view.View message = find(dialog.getWindow().getDecorView(), activity.getString(com.example.cleanrecovery.R.string.via_cookie_empty));
                        assertTrue(message instanceof android.widget.TextView);
                        assertTrue("Cookie text readable without changing its contents", androidx.core.graphics.ColorUtils.calculateContrast(((android.widget.TextView)message).getCurrentTextColor(),themedSurface.data)>4.5);
                    } finally { dialog.dismiss(); }
                    card.setVisibility(android.view.View.GONE);
                    return null;
                });
            }
        } finally { main(() -> { prefs.setNightMode(original); activity.finish(); return null; }); }
    }

    @Test public void configurationOverridesResetWithoutChangingSourceOrEnabledState() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        String name = "__config_" + java.util.UUID.randomUUID();
        String source = "// ==UserScript==\n// @name config\n// @match https://config.test/*\n// @exclude https://config.test/original/*\n// @run-at document-end\n// ==/UserScript==\nwindow.config=true;";
        try {
            prefs.saveScript(name, "https://config.test/*", source);
            prefs.setScriptEnabled(name,false);
            prefs.setScriptRunAtOverride(name,"document-start");
            prefs.setScriptExcludes(name,"https://config.test/private/*");
            assertEquals("Explicit lifecycle overrides metadata", "document-start", BrowserUserScripts.runAt(prefs.scriptExecutionCode(name)));
            assertFalse(BrowserUserScripts.applies(prefs.scriptExecutionCode(name),prefs.scriptMatch(name),"https://config.test/private/page"));
            assertTrue("Replacing exclusions removes old exclusion",BrowserUserScripts.applies(prefs.scriptExecutionCode(name),prefs.scriptMatch(name),"https://config.test/original/page"));
            prefs.setScriptExcludes(name, "");
            assertTrue("Deleting exclusions must actually unblock the page",BrowserUserScripts.applies(prefs.scriptExecutionCode(name),prefs.scriptMatch(name),"https://config.test/private/page"));
            prefs.resetScriptOverrides(name);
            assertEquals("Reset returns to author lifecycle", "document-end", BrowserUserScripts.runAt(prefs.scriptExecutionCode(name)));
            assertFalse(BrowserUserScripts.applies(prefs.scriptExecutionCode(name),prefs.scriptMatch(name),"https://config.test/original/page"));
            assertEquals(source,prefs.scriptCode(name));
            assertFalse("Reset configuration must not enable a disabled script",prefs.isScriptEnabled(name));
        } finally { prefs.removeScript(name); }
    }

    @Test public void editedScopeOverridesMetadataForSelectionAndInjection() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        String name = "__scope_verification_" + java.util.UUID.randomUUID();
        String renamed = name + "_renamed";
        String original = "// ==UserScript==\n// @name Scope\n// @match https://old.scope.test/*\n// @exclude https://new.scope.test/private/*\n// ==/UserScript==\nwindow.scopeExecuted=true;";
        try {
            prefs.saveScript(name, "https://old.scope.test/*", original);
            prefs.setScriptMatchOverride(name, "https://new.scope.test/*");
            assertEquals("Editing scope must not rewrite installed source", original, prefs.scriptCode(name));
            assertFalse(BrowserUserScripts.applies(prefs.scriptExecutionCode(name), prefs.scriptMatch(name), "https://old.scope.test/page"));
            assertTrue(BrowserUserScripts.applies(prefs.scriptExecutionCode(name), prefs.scriptMatch(name), "https://new.scope.test/page"));
            assertFalse("Exclusions still apply", BrowserUserScripts.applies(prefs.scriptExecutionCode(name), prefs.scriptMatch(name), "https://new.scope.test/private/page"));
            main(() -> { web = new BrowserWebView(instrumentation.getTargetContext()); web.getSettings().setJavaScriptEnabled(true); return null; });
            for (String host : new String[]{"old", "new"}) {
                main(() -> { web.loadDataWithBaseURL("https://" + host + ".scope.test/page", "<html><body>scope</body></html>", "text/html", "UTF-8", null); return null; });
                Thread.sleep(300);
                js(BrowserUserScripts.wrap(prefs.scriptExecutionCode(name), prefs.scriptMatch(name)));
                assertEquals("new".equals(host) ? "true" : "null", js("window.scopeExecuted"));
            }
            prefs.saveScript(renamed, "https://updated.scope.test/*", original);
            prefs.preserveScriptIdentity(name, renamed); prefs.removeScript(name);
            assertEquals("Scope survives update/rename", "https://new.scope.test/*", new BrowserPrefs(instrumentation.getTargetContext()).scriptMatch(renamed));
            prefs.removeScript(renamed); prefs.saveScript(renamed, "*", original);
            assertEquals("Deletion clears user override", "*", prefs.scriptMatch(renamed));
        } finally {
            prefs.removeScript(name); prefs.removeScript(renamed);
            main(() -> { if(web!=null)web.destroy(); return null; });
        }
    }
}
