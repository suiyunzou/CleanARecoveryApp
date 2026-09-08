package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.Dialog;
import android.content.Intent;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.BrowserBackupManager;
import com.example.cleanrecovery.ui.browser.BrowserPasswordStore;
import com.example.cleanrecovery.ui.browser.BrowserAiClient;
import com.example.cleanrecovery.ui.browser.BrowserCloudAccount;
import com.example.cleanrecovery.ui.browser.BrowserCloudPayload;
import com.example.cleanrecovery.ui.browser.BrowserDatabaseHelper;
import com.example.cleanrecovery.ui.browser.TabManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercises saved settings against real browser/UI consumers in the isolated test app. */
@RunWith(AndroidJUnit4.class)
public class BrowserSettingsParityTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private BrowserActivity browser;
    private Activity settings;
    private BrowserPrefs prefs;
    private WebView web;

    @Test public void scriptValuesSurviveOriginAndCodeChangesButNotDeletion() throws Exception {
        String name = "gm-storage-parity", other = "gm-storage-other";
        com.example.cleanrecovery.ui.browser.BrowserWebView view =
                (com.example.cleanrecovery.ui.browser.BrowserWebView) web;
        try {
            main(() -> { prefs.saveScript(name, "*", ""); prefs.saveScript(other, "*", "");
                web.loadDataWithBaseURL("https://first.storage.test/", "<html><body>first</body></html>", "text/html", "UTF-8", null); return null; });
            instrumentation.waitForIdleSync();
            Thread.sleep(300);
            js(main(() -> view.wrapUserScript(name,
                    "// @grant GM_setValue\nGM_setValue('data',{count:7,items:[true,null]});GM_setValue('empty',undefined);", "*")));
            main(() -> { prefs.saveScript(name, "*", "// edited");
                web.loadDataWithBaseURL("https://second.storage.test/", "<html><body>second</body></html>", "text/html", "UTF-8", null); return null; });
            instrumentation.waitForIdleSync();
            Thread.sleep(300);
            js(main(() -> view.wrapUserScript(name,
                    "// @grant GM_getValue\n// @grant GM.getValue\nwindow.saved=GM_getValue('data');window.missing=GM_getValue('missing',42);window.emptyIsUndefined=GM_getValue('empty',42)===undefined;GM.getValue('data').then(function(v){window.asyncCount=v.count});", "*")));
            assertEquals("7", js("saved.count"));
            assertEquals("[true,null]", js("saved.items"));
            assertEquals("42", js("missing"));
            assertEquals("true", js("emptyIsUndefined"));
            assertEquals("7", js("asyncCount"));
            js(main(() -> view.wrapUserScript(other, "// @grant GM_getValue\nwindow.isolated=GM_getValue('data','none');", "*")));
            assertEquals("\"none\"", js("isolated"));
            assertEquals("\"__via_missing__\"", js("ViaScriptValues.get('untrusted','data')"));
            assertEquals("0", js("localStorage.length"));
            com.example.cleanrecovery.ui.browser.BrowserScriptValues recreated =
                    new com.example.cleanrecovery.ui.browser.BrowserScriptValues(browser);
            main(() -> { web.addJavascriptInterface(recreated, "RecreatedScriptValues"); return null; });
            // A new bridge instance must read the app store, independent of the page and old bridge.
            String prelude = recreated.script(name).replace("window.ViaScriptValues", "window.RecreatedScriptValues");
            main(() -> { web.reload(); return null; });
            instrumentation.waitForIdleSync(); Thread.sleep(300);
            js(com.example.cleanrecovery.ui.browser.BrowserUserScripts.wrap("// @grant GM_getValue\n// @grant GM_deleteValue\n// @grant GM_listValues\nwindow.persisted=GM_getValue('data').count;GM_deleteValue('data');window.keys=GM_listValues();", "*", prelude));
            assertEquals("7", js("persisted"));
            assertEquals("[\"empty\"]", js("keys"));
            main(() -> { prefs.removeScript(name); prefs.saveScript(name, "*", ""); return null; });
            js(main(() -> view.wrapUserScript(name, "// @grant GM_getValue\nwindow.deleted=GM_getValue('empty','gone');", "*")));
            assertEquals("\"gone\"", js("deleted"));
        } finally {
            main(() -> { prefs.removeScript(name); prefs.removeScript(other); web.removeJavascriptInterface("RecreatedScriptValues"); return null; });
        }
    }

    @Test public void scriptExecutesOnUserRequestedXiaoyaSite() throws Exception {
        String name = "xiaoya-live-parity";
        try {
            main(() -> { prefs.saveScript(name, "https://xiaoyakankan.com/*",
                    "// ==UserScript==\n// @name xiaoya-live-parity\n// @grant GM_getValue\n// @grant GM_setValue\n// @match https://xiaoyakankan.com/*\n// @run-at document-end\n// ==/UserScript==\nGM_setValue('visits',GM_getValue('visits',0)+1);window.__xiaoyaScript=GM_getValue('visits');");
                prefs.setScriptEnabled(name, true);
                ((com.example.cleanrecovery.ui.browser.BrowserWebView)web).applyUserScripts();
                web.loadUrl("https://xiaoyakankan.com/"); return null; });
            long until = System.currentTimeMillis() + 45000;
            while (System.currentTimeMillis() < until && !"1".equals(js("window.__xiaoyaScript"))) Thread.sleep(250);
            assertEquals("Script must run in the actual requested HTTPS document", "1", js("window.__xiaoyaScript"));
            assertEquals("\"https://xiaoyakankan.com\"", js("location.origin"));
            assertTrue("Real site content must load, not an empty test document", Integer.parseInt(js("document.body.innerText.length")) > 100);
            main(() -> { prefs.setScriptEnabled(name, false);
                ((com.example.cleanrecovery.ui.browser.BrowserWebView)web).applyUserScripts(); web.reload(); return null; });
            Thread.sleep(5000);
            assertEquals("Disabling must remove the script from the next document", "null", js("window.__xiaoyaScript"));
        } finally { main(() -> { prefs.removeScript(name); return null; }); }
    }

    @Test public void grantsControlSyncAndPromiseApiExposureIndependently() throws Exception {
        main(() -> { web.loadDataWithBaseURL("https://grant.parity.test/", "<html><body>grant</body></html>", "text/html", "UTF-8", null); return null; });
        instrumentation.waitForIdleSync(); Thread.sleep(300);
        String probe = "window.apiTypes=[typeof GM_getValue,typeof GM_getValues,typeof GM.setValue,typeof GM_setValue,typeof GM.info];";
        js(com.example.cleanrecovery.ui.browser.BrowserUserScripts.wrap("// @grant GM_getValue\n// @grant GM.setValue\n" + probe, "*"));
        assertEquals("[\"function\",\"function\",\"function\",\"undefined\",\"object\"]", js("apiTypes"));
        js(com.example.cleanrecovery.ui.browser.BrowserUserScripts.wrap("// @grant none\n" + probe, "*"));
        assertEquals("[\"undefined\",\"undefined\",\"undefined\",\"undefined\",\"object\"]", js("apiTypes"));
        js(com.example.cleanrecovery.ui.browser.BrowserUserScripts.wrap(probe, "*"));
        assertEquals("[\"undefined\",\"undefined\",\"undefined\",\"undefined\",\"object\"]", js("apiTypes"));
    }

    @Test public void scriptMenuCommandsAppearInvokeAndUnregister() throws Exception {
        String name = "menu-command-parity";
        try {
            main(() -> { prefs.saveScript(name, "https://menu.parity.test/*", "");
                web.loadDataWithBaseURL("https://menu.parity.test/", "<html><body>menus</body></html>", "text/html", "UTF-8", null); return null; });
            instrumentation.waitForIdleSync(); Thread.sleep(300);
            js(main(() -> ((com.example.cleanrecovery.ui.browser.BrowserWebView)web).wrapUserScript(name,
                    "// @grant GM_registerMenuCommand\n// @grant GM_unregisterMenuCommand\n// @grant GM.registerMenuCommand\n"
                    + "GM_registerMenuCommand('执行回调',function(){window.menuResult='old'});"
                    + "window.menuId=GM_registerMenuCommand('执行回调',function(){window.menuResult='clicked';GM_unregisterMenuCommand('执行回调')});"
                    + "GM.registerMenuCommand('另一个命令',function(){window.menuResult='async'});"
                    + "GM_registerMenuCommand('已移除',function(){});GM_unregisterMenuCommand('已移除');", "*")));
            assertEquals("\"执行回调\"", js("menuId"));
            Dialog dialog = main(() -> (Dialog)invoke(browser, "showPageScripts", new Class<?>[]{}));
            long until = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < until && main(() -> findText(dialog.getWindow().getDecorView(), "执行回调")) == null) Thread.sleep(50);
            main(() -> { assertNull(findText(dialog.getWindow().getDecorView(), "已移除"));
                assertNotNull(findText(dialog.getWindow().getDecorView(), "另一个命令"));
                View command = findText(dialog.getWindow().getDecorView(), "执行回调"); assertNotNull(command); command.performClick(); return null; });
            instrumentation.waitForIdleSync();
            assertEquals("\"clicked\"", js("menuResult"));
            Dialog reopened = main(() -> (Dialog)invoke(browser, "showPageScripts", new Class<?>[]{}));
            Thread.sleep(300);
            main(() -> { assertNull(findText(reopened.getWindow().getDecorView(), "执行回调")); reopened.dismiss(); return null; });
            main(() -> { web.loadDataWithBaseURL("https://menu.parity.test/next", "<html><body>next</body></html>", "text/html", "UTF-8", null); return null; });
            Thread.sleep(300);
            String snapshot = js(main(() -> ((com.example.cleanrecovery.ui.browser.BrowserWebView)web).scriptMenus().list(name)));
            assertEquals("Navigation must discard callbacks from the previous document", 0, new org.json.JSONObject(snapshot).getJSONArray("names").length());
        } finally { main(() -> { prefs.removeScript(name); return null; }); }
    }

    @Before public void start() throws Exception {
        assertTrue(instrumentation.getTargetContext().getPackageName().endsWith(".p1test"));
        browser = (BrowserActivity) launch(BrowserActivity.class);
        prefs = new BrowserPrefs(browser);
        web = main(() -> ((TabManager) field(browser, "tabs")).current().webView);
        main(() -> { web.stopLoading(); return null; });
    }

    @After public void stop() throws Exception {
        main(() -> {
            prefs.setUaSelectedId(0);
            prefs.setDesktopMode(false);
            prefs.setUaMode(1);
            prefs.setCookiesEnabled(true);
            prefs.setReaderFont(17);
            prefs.setReaderTheme(0);
            prefs.setReaderCss("");
            prefs.setHomeMode(0);
            prefs.setHomeCustomUrl("https://www.baidu.com");
            prefs.setHomeCustomCss("");
            prefs.dbHelper().removeBookmarkByUrl("https://home-bookmark.parity.test/");
            prefs.dbHelper().removeBookmarkByUrl("https://bookmark.parity.test/");
            prefs.dbHelper().removeBookmarkByUrl("https://cloud-bookmark.parity.test/");
            for (BrowserDatabaseHelper.Entry entry : prefs.dbHelper().listQuickLinks()) {
                if (entry.url.contains("cloud-favorite.parity.test")
                        || entry.url.contains("home-title.parity.test")) {
                    prefs.dbHelper().removeQuickLink(entry.id);
                }
            }
            prefs.resetSiteSettings("site-runtime.parity.test");
            prefs.setRestoreTabs(0);
            prefs.setSessionTabs("");
            prefs.setDoNotTrack(false);
            prefs.setDataSaver(false);
            prefs.setDoNotSell(false);
            prefs.setDisableWebRtc(false);
            prefs.setToolbarMode(1);
            prefs.setTabBarEnabled(false);
            prefs.setToolbarAutoHide(0);
            prefs.setAdaptiveColor(true);
            prefs.setNightMode(false);
            prefs.setForceDarkPages(false);
            prefs.setAdBlockExpand(true);
            prefs.setAutoSnifferButton(true);
            prefs.setReaderConfirm(false);
            prefs.setBackNoReload(true);
            prefs.setDownloadManager(0);
            prefs.setDisableCustomTabs(false);
            prefs.setAddressContent(0);
            java.util.List<BrowserPrefs.CustomSearchItem> customSearches = prefs.customSearchList();
            for (int i = customSearches.size() - 1; i >= 0; i--) {
                if (customSearches.get(i).title.startsWith("Parity engine")) prefs.removeCustomSearch(i);
            }
            prefs.setJsEnabled(true);
            prefs.setVolumeKeyScroll(false);
            prefs.setGestureAction("home", 4);
            prefs.setPermission("camera", "ask");
            prefs.setPermissionException("camera", "camera.parity.test", null);
            prefs.removeScript("Parity userscript");
            prefs.removeScript("Parity document start");
            prefs.removeScript("Parity cloud script");
            prefs.setAiProviderName("");
            prefs.setAiEndpoint("");
            prefs.setAiApiKey("");
            prefs.setAiModels("");
            prefs.setAiModel("");
            prefs.clearCloudAccount();
            BrowserPasswordStore store = new BrowserPasswordStore(browser);
            for (BrowserPasswordStore.Entry entry : store.list()) {
                if (entry.origin.contains("parity.test")) store.remove(entry);
            }
            CookieManager.getInstance().setAcceptCookie(true);
            if (settings != null) settings.finish();
            browser.finish();
            return null;
        });
    }

    @Test public void selectedUaReachesActualWebView() throws Exception {
        main(() -> {
            prefs.setDesktopMode(false);
            prefs.setUaMode(1);
            prefs.setUaSelectedId(prefs.addCustomUa("Parity agent", "ParityBrowser/1.0"));
            invoke(browser, "applySettings", new Class<?>[]{WebView.class}, web);
            assertEquals("The selected UA must affect outgoing browser requests", "ParityBrowser/1.0", web.getSettings().getUserAgentString());
            return null;
        });
    }

    @Test public void desktopSettingControlsWebViewLayout() throws Exception {
        main(() -> {
            prefs.setUaMode(1);
            prefs.setDesktopMode(true);
            invoke(browser, "applySettings", new Class<?>[]{WebView.class}, web);
            assertTrue("Desktop selection must change page layout", web.getSettings().getUseWideViewPort());
            return null;
        });
    }

    @Test public void cookieBlockReachesCookieManager() throws Exception {
        main(() -> {
            prefs.setCookiesEnabled(false);
            invoke(browser, "applySettings", new Class<?>[]{WebView.class}, web);
            assertFalse("Blocking Cookies must block cookie acceptance", CookieManager.getInstance().acceptCookie());
            return null;
        });
    }

    @Test public void readerUsesSavedFontAndCss() throws Exception {
        main(() -> {
            prefs.setReaderFont(24);
            prefs.setReaderCss(".via-reader-body { color: rgb(12, 34, 56) !important; }");
            web.loadDataWithBaseURL("https://reader.parity.test/", "<html><body><article>Reading text</article></body></html>", "text/html", "UTF-8", null);
            return null;
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"true".equals(js("!!document.querySelector('article')")) && System.nanoTime() < until) Thread.sleep(50);
        assertEquals("The intended reader document must finish replacing the previous page", "true",
                js("!!document.querySelector('article')"));
        main(() -> { invoke(browser, "enterReaderMode", new Class<?>[0]); return null; });
        awaitReaderJs("!!document.querySelector('.via-reader-body')", "true");
        assertEquals("Reader font preference must be rendered", "\"24px\"", js("getComputedStyle(document.querySelector('.via-reader-body')).fontSize"));
        assertEquals("Custom CSS must be applied after default reader styling", "\"rgb(12, 34, 56)\"", js("getComputedStyle(document.querySelector('.via-reader-body')).color"));
    }

    @Test public void scriptEditorAcceptsFocusAndTypedContent() throws Exception {
        settings = launch(BrowserSettingsActivity.class);
        main(() -> {
            invoke(settings, "renderScriptEdit", new Class<?>[0]);
            EditText input = findInput(settings.getWindow().getDecorView());
            assertNotNull(input);
            assertTrue("A script editor must allow users to focus and type", input.requestFocus());
            input.setText("Parity script");
            assertEquals("Parity script", input.getText().toString());
            return null;
        });
    }

    @Test public void customHomepageControlsHomeButtonDestination() throws Exception {
        main(() -> {
            prefs.setHomeMode(3);
            prefs.setHomeCustomUrl("https://home.parity.test/");
            invoke(browser, "showHome", new Class<?>[0]);
            TabManager tabs = (TabManager) field(browser, "tabs");
            assertEquals("Home action must consume the user's selected destination", "https://home.parity.test/", tabs.current().url);
            return null;
        });
    }

    @Test public void sessionPersistenceExcludesIncognitoTabs() throws Exception {
        main(() -> {
            prefs.setRestoreTabs(1);
            TabManager tabs = (TabManager) field(browser, "tabs");
            tabs.current().url = "https://public.parity.test/";
            TabManager.Tab privateTab = (TabManager.Tab) invoke(browser, "newTab", new Class<?>[]{String.class}, "");
            privateTab.url = "https://private.parity.test/";
            Field incognito = privateTab.tag.getClass().getDeclaredField("incognito");
            incognito.setAccessible(true);
            incognito.setBoolean(privateTab.tag, true);
            invoke(browser, "onStop", new Class<?>[0]);
            assertTrue("Public tabs must survive a stopped process", prefs.sessionTabs().contains("public.parity.test"));
            assertFalse("Incognito navigation must never be persisted", prefs.sessionTabs().contains("private.parity.test"));
            return null;
        });
    }

    @Test public void privacySettingsReachHttpHeadersAndEarliestPageScript() throws Exception {
        java.util.concurrent.ExecutorService serverThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            server.setSoTimeout(10000);
            java.util.concurrent.Future<String> captured = serverThread.submit(() -> {
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    StringBuilder request = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) request.append(line).append('\n');
                    byte[] body = "<html><script>window.early=[typeof RTCPeerConnection,navigator.doNotTrack,navigator.globalPrivacyControl]</script><body>Privacy</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                    return request.toString().toLowerCase(java.util.Locale.ROOT);
                }
            });
            main(() -> {
                prefs.setDoNotTrack(true);
                prefs.setDataSaver(true);
                prefs.setDoNotSell(true);
                prefs.setDisableWebRtc(true);
                invoke(browser, "applySettings", new Class<?>[]{WebView.class}, web);
                web.loadUrl("http://127.0.0.1:" + server.getLocalPort() + "/");
                return null;
            });
            String request = captured.get(12, TimeUnit.SECONDS);
            assertTrue("DNT must reach the HTTP server", request.contains("dnt: 1"));
            assertTrue("Data saver must reach the HTTP server", request.contains("save-data: on"));
            assertTrue("GPC must reach the HTTP server", request.contains("sec-gpc: 1"));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!"true".equals(js("Array.isArray(window.early)")) && System.nanoTime() < until) Thread.sleep(50);
            assertEquals("Privacy choices must precede the page's own scripts", "[\"undefined\",\"1\",true]", js("window.early"));
        } finally { serverThread.shutdownNow(); }
    }

    @Test public void cameraPolicyBlocksGloballyAndHonorsOriginException() throws Exception {
        instrumentation.getUiAutomation().grantRuntimePermission(instrumentation.getTargetContext().getPackageName(), android.Manifest.permission.CAMERA);
        final boolean[] denied = {false};
        final boolean[] granted = {false};
        android.webkit.PermissionRequest request = new android.webkit.PermissionRequest() {
            @Override public android.net.Uri getOrigin() { return android.net.Uri.parse("https://camera.parity.test/"); }
            @Override public String[] getResources() { return new String[]{RESOURCE_VIDEO_CAPTURE}; }
            @Override public void grant(String[] resources) { granted[0] = java.util.Arrays.asList(resources).contains(RESOURCE_VIDEO_CAPTURE); }
            @Override public void deny() { denied[0] = true; }
        };
        main(() -> {
            prefs.setPermission("camera", "block");
            web.getWebChromeClient().onPermissionRequest(request);
            assertTrue("Blocked websites must not get camera access", denied[0]);
            assertFalse(granted[0]);
            prefs.setPermissionException("camera", "camera.parity.test", "allow");
            web.getWebChromeClient().onPermissionRequest(request);
            assertTrue("An explicitly allowed origin must reach the WebView grant callback", granted[0]);
            return null;
        });
    }

    @Test public void realMicrophoneRequestHonorsSiteExceptionAndStopsItsTrack() throws Exception {
        String host = "microphone-live.parity.test", original = prefs.permission("microphone");
        instrumentation.getUiAutomation().grantRuntimePermission(instrumentation.getTargetContext().getPackageName(), android.Manifest.permission.RECORD_AUDIO);
        try {
            for (int step = 0; step < 3; step++) {
                boolean allow = step == 1;
                String pageUrl = "https://" + host + "/case-" + step;
                main(() -> {
                    prefs.setPermission("microphone", "block");
                    prefs.setPermissionException("microphone", host, allow ? "allow" : null);
                    web.loadDataWithBaseURL(pageUrl, "<html><head><title>Microphone request</title></head><body>Microphone request</body></html>", "text/html", "UTF-8", null);
                    return null;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                String ready = "\"" + pageUrl + "|complete\"";
                while (!ready.equals(js("location.href+'|'+document.readyState")) && System.nanoTime() < deadline) Thread.sleep(30);
                assertEquals("Each permission change must be exercised in the newly loaded document", ready, js("location.href+'|'+document.readyState"));
                js("window.micResult='pending';navigator.mediaDevices.getUserMedia({audio:true}).then(function(s){window.micStream=s;var t=s.getAudioTracks()[0];var live=t&&t.readyState==='live';s.getTracks().forEach(function(x){x.stop()});window.micResult=live&&t.readyState==='ended'?'captured-and-stopped':'invalid-track'},function(e){window.micResult=e.name})");
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                String result;
                do { result = js("window.micResult"); if (!"\"pending\"".equals(result)) break; Thread.sleep(30); }
                while (System.nanoTime() < deadline);
                assertEquals("Site policy must control real capture even when Android permission is granted, including after removing the exception",
                        allow ? "\"captured-and-stopped\"" : "\"NotAllowedError\"", result);
            }
        } finally {
            js("if(window.micStream)window.micStream.getTracks().forEach(function(t){t.stop()})");
            prefs.setPermission("microphone", original); prefs.setPermissionException("microphone", host, null);
        }
    }

    @Test public void permissionExceptionRequiresConfirmationAndSupportsDeletion() throws Exception {
        String host = "permission-dialog.parity.test";
        settings = launch(BrowserSettingsActivity.class);
        try {
            main(() -> {
                prefs.setPermissionException("camera", host, "block");
                java.lang.reflect.Field key = BrowserSettingsActivity.class.getDeclaredField("permissionKey"); key.setAccessible(true); key.set(settings, "camera");
                java.lang.reflect.Field title = BrowserSettingsActivity.class.getDeclaredField("permissionTitle"); title.setAccessible(true); title.set(settings, "摄像头");
                Class page = Class.forName(BrowserSettingsActivity.class.getName() + "$Page");
                invoke(settings, "open", new Class<?>[]{page}, Enum.valueOf(page, "PERMISSION"));
                java.lang.reflect.Method show = BrowserSettingsActivity.class.getDeclaredMethod("showPermissionException", String.class); show.setAccessible(true);
                android.app.AlertDialog dialog = (android.app.AlertDialog) show.invoke(settings, host);
                dialog.getListView().performItemClick(null, 0, 0);
                assertEquals("Selecting a radio option must not save before confirmation", "block", prefs.permission("camera", host));
                dialog.cancel();
                assertEquals("Back or outside dismissal must discard the pending choice", "block", prefs.permission("camera", host));
                dialog = (android.app.AlertDialog) show.invoke(settings, host);
                dialog.getListView().performItemClick(null, 0, 0);
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
                return null;
            });
            instrumentation.waitForIdleSync();
            main(() -> {
                assertEquals("Confirm must persist the selected exception", "allow", prefs.permission("camera", host));
                java.lang.reflect.Method show = BrowserSettingsActivity.class.getDeclaredMethod("showPermissionException", String.class); show.setAccessible(true);
                android.app.AlertDialog dialog = (android.app.AlertDialog) show.invoke(settings, host);
                dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
                return null;
            });
            instrumentation.waitForIdleSync();
            assertFalse("Delete removes the exception rather than changing the global policy", prefs.permissionExceptionHosts("camera").contains(host));
        } finally { prefs.setPermissionException("camera", host, null); }
    }

    @Test public void blockedCameraExceptionCanAllowDirectlyOrAskFirst() throws Exception {
        verifyBlockedPermissionException("camera", "摄像头");
    }

    @Test public void blockedMicrophoneExceptionCanAllowDirectlyOrAskFirst() throws Exception {
        verifyBlockedPermissionException("microphone", "麦克风");
    }

    @Test public void blockedLocationExceptionCanAllowDirectlyOrAskFirst() throws Exception {
        verifyBlockedPermissionException("location", "位置信息");
    }

    @Test public void blockedClipboardExceptionCanAllowDirectlyOrAskFirst() throws Exception {
        verifyBlockedPermissionException("clipboard", "剪贴板");
    }

    @Test public void clipboardPolicyControlsCopyAndWriteWithoutReplacingReads() throws Exception {
        String original = prefs.permission("clipboard");
        String host = "127.0.0.1";
        String originalException = prefs.permissionExceptionHosts("clipboard").contains(host)
                ? prefs.permission("clipboard", host) : null;
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                browser.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        android.content.ClipData originalClip = main(clipboard::getPrimaryClip);
        java.util.concurrent.ExecutorService serverThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            serverThread.submit(() -> {
                while (!server.isClosed()) {
                    try (java.net.Socket socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                        byte[] body = "<html><body>Clipboard policy</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(body);
                        socket.getOutputStream().flush();
                    } catch (java.io.IOException error) {
                        if (!server.isClosed()) throw new RuntimeException(error);
                    }
                }
            });
            String[] modes = {"block", "allow", "ask"};
            for (String mode : modes) {
                main(() -> {
                    prefs.setPermission("clipboard", "block");
                    prefs.setPermissionException("clipboard", host, mode);
                    invoke(browser, "applySettings", new Class<?>[]{WebView.class}, web);
                    web.loadUrl("http://" + host + ":" + server.getLocalPort() + "/" + mode);
                    return null;
                });
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                String ready = "\"http://" + host + ":" + server.getLocalPort() + "/" + mode + "|complete\"";
                while (!ready.equals(js("location.href+'|'+document.readyState")) && System.nanoTime() < until) Thread.sleep(50);
                assertEquals(ready, js("location.href+'|'+document.readyState"));
                main(() -> {
                    web.requestFocus();
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("parity", "clipboard-sentinel"));
                    return null;
                });
                assertEquals("Clipboard writes require the loaded document to have focus", "true", js("document.hasFocus()"));
                assertEquals("Copy policy must not replace native read permission behavior", "false",
                        js("Object.prototype.hasOwnProperty.call(navigator.clipboard,'readText')"));
                if ("ask".equals(mode)) {
                    // Exercise the real JS policy with a controlled confirmation result; native dialog tested separately.
                    js("window.nativeConfirm=window.confirm;window.confirmMessages=[];window.confirm=function(m){confirmMessages.push(m);return false}");
                }
                assertEquals("Default block must honor the site's chosen copy policy: " + mode,
                        "allow".equals(mode) ? "true" : "false",
                        js("document.dispatchEvent(new Event('copy',{bubbles:true,cancelable:true}))"));
                if (!"allow".equals(mode)) {
                    js("window.writeResult='pending';navigator.clipboard.writeText('parity-fixture').then(function(){writeResult='written'},function(e){writeResult=e.name})");
                    until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while ("\"pending\"".equals(js("writeResult")) && System.nanoTime() < until) Thread.sleep(25);
                    assertEquals("Denied writes must reject before changing the clipboard", "\"NotAllowedError\"", js("writeResult"));
                    assertEquals("Rejected writes must preserve existing clipboard data", "clipboard-sentinel",
                            main(() -> clipboard.getPrimaryClip().getItemAt(0).getText().toString()));
                }
                if ("ask".equals(mode)) {
                    assertEquals("Copy event and API write must both ask", "2", js("confirmMessages.length"));
                    assertEquals("The confirmation must identify the requesting host", "true", js("confirmMessages.every(function(m){return m.indexOf('" + host + "')>=0})"));
                    js("window.confirm=function(){return true}");
                    assertEquals("Accepting the prompt must permit the copy event", "true",
                            js("document.dispatchEvent(new Event('copy',{cancelable:true}))"));
                    js("window.confirm=window.nativeConfirm");
                    for (boolean accept : new boolean[]{false, true}) {
                        AtomicReference<String> result = new AtomicReference<>();
                        main(() -> {
                            web.evaluateJavascript("document.dispatchEvent(new Event('copy',{cancelable:true}))", result::set);
                            return null;
                        });
                        answerClipboardConfirmation(host, accept);
                        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (result.get() == null && System.nanoTime() < until) Thread.sleep(25);
                        assertEquals("Native dialog choice must resume the waiting copy operation", String.valueOf(accept), result.get());
                    }
                }
                if (!"block".equals(mode)) {
                    for (boolean item : new boolean[]{false, true}) {
                        String value = "parity-" + mode + (item ? "-item" : "-text");
                        String operation = item
                                ? "navigator.clipboard.write([new ClipboardItem({'text/plain':new Blob(['" + value + "'],{type:'text/plain'})})])"
                                : "navigator.clipboard.writeText('" + value + "')";
                        js("window.writeResult='pending';document.body.innerHTML='<button style=\"position:fixed;inset:0;width:100%;height:100%\">Copy fixture</button>';document.querySelector('button').onclick=function(){"
                                + operation + ".then(function(){writeResult='written'},function(e){writeResult=e.name})}");
                        int[] point = main(() -> {
                            int[] xy = new int[2];
                            web.getLocationOnScreen(xy);
                            xy[0] += web.getWidth() / 2;
                            xy[1] += web.getHeight() / 2;
                            return xy;
                        });
                        long down = android.os.SystemClock.uptimeMillis();
                        android.view.MotionEvent press = android.view.MotionEvent.obtain(down, down,
                                android.view.MotionEvent.ACTION_DOWN, point[0], point[1], 0);
                        android.view.MotionEvent release = android.view.MotionEvent.obtain(down, down + 80,
                                android.view.MotionEvent.ACTION_UP, point[0], point[1], 0);
                        try {
                            instrumentation.sendPointerSync(press);
                            instrumentation.sendPointerSync(release);
                        } finally { press.recycle(); release.recycle(); }
                        if ("ask".equals(mode)) answerClipboardConfirmation(host, true);
                        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while ("\"pending\"".equals(js("writeResult")) && System.nanoTime() < until) Thread.sleep(25);
                        assertEquals("Permitted " + mode + " writes must complete: " + item, "\"written\"", js("writeResult"));
                        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        String copied;
                        do {
                            copied = main(() -> {
                                android.content.ClipData clip = clipboard.getPrimaryClip();
                                return clip == null || clip.getItemCount() == 0 ? "" : String.valueOf(clip.getItemAt(0).getText());
                            });
                            if (value.equals(copied)) break;
                            Thread.sleep(25);
                        } while (System.nanoTime() < until);
                        assertEquals("A resolved write must put the fixture text in Android's clipboard", value,
                                copied);
                    }
                } else {
                    assertEquals("Context-menu copy gets exactly one exemption from the block policy", "[true,false]",
                            js("document.dispatchEvent(new Event('contextmenu'));[document.dispatchEvent(new Event('copy',{cancelable:true})),document.dispatchEvent(new Event('copy',{cancelable:true}))]"));
                    assertEquals("A new mouse press clears an unused context-menu exemption", "false",
                            js("document.dispatchEvent(new Event('contextmenu'));document.dispatchEvent(new Event('mousedown'));document.dispatchEvent(new Event('copy',{cancelable:true}))"));
                }
            }
        } finally {
            serverThread.shutdownNow();
            prefs.setPermission("clipboard", original);
            prefs.setPermissionException("clipboard", host, originalException);
            main(() -> {
                if (originalClip == null) clipboard.clearPrimaryClip();
                else clipboard.setPrimaryClip(originalClip);
                return null;
            });
        }
    }

    private void answerClipboardConfirmation(String host, boolean accept) throws Exception {
        String message = browser.getString(R.string.via_clipboard_copy_confirm).replace("%DOMAIN%", host);
        android.view.accessibility.AccessibilityNodeInfo root = null;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null && !root.findAccessibilityNodeInfosByText(message).isEmpty()) break;
            Thread.sleep(50);
        }
        assertNotNull("Clipboard confirmation must open a native dialog", root);
        assertFalse("Native confirmation must show the requesting host", root.findAccessibilityNodeInfosByText(message).isEmpty());
        String label = browser.getString(accept ? android.R.string.ok : android.R.string.cancel);
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByText(label);
        assertFalse("Native confirmation must offer " + label, buttons.isEmpty());
        assertTrue(buttons.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
    }

    @Test public void repeatedConfirmCanBeSuppressedFromItsRealDialog() throws Exception {
        String host = "localhost";
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        server.setSoTimeout(10000);
        String url = "http://" + host + ":" + server.getLocalPort() + "/";
        Thread fixture = new Thread(() -> {
            try (java.net.ServerSocket owned = server; java.net.Socket socket = owned.accept()) {
                socket.setSoTimeout(5000);
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                byte[] body = "<html><body>Confirm</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
        });
        fixture.setDaemon(true);
        fixture.start();
        main(() -> { web.loadUrl(url); return null; });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String ready = "\"" + url + "|complete\"";
        while (!ready.equals(js("location.href+'|'+document.readyState")) && System.nanoTime() < until) Thread.sleep(30);
        assertEquals(ready, js("location.href+'|'+document.readyState"));
        for (int attempt = 0; attempt < 3; attempt++) {
            AtomicReference<String> result = new AtomicReference<>();
            main(() -> { web.evaluateJavascript("confirm('Repeated confirmation fixture')", result::set); return null; });
            String title = browser.getString(R.string.via_js_message_from, host);
            android.view.accessibility.AccessibilityNodeInfo root = null;
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < until) {
                root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root != null && !root.findAccessibilityNodeInfosByText(title).isEmpty()) break;
                Thread.sleep(30);
            }
            assertNotNull(root);
            assertFalse("Message title must show its source host", root.findAccessibilityNodeInfosByText(title).isEmpty());
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> suppress = root.findAccessibilityNodeInfosByText(browser.getString(R.string.via_js_ignore_minute));
            assertEquals("Only repeated messages offer suppression", attempt < 2, suppress.isEmpty());
            if (attempt == 2) {
                android.view.accessibility.AccessibilityNodeInfo row = suppress.get(0);
                while (!row.isClickable()) row = row.getParent();
                assertTrue(row.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            }
            assertTrue(root.findAccessibilityNodeInfosByText("确定").get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (result.get() == null && System.nanoTime() < until) Thread.sleep(25);
            assertEquals("Suppression applies to later messages, not the current accepted one", "true", result.get());
        }
        assertEquals("Suppressed confirm must resume JavaScript with false", "false", js("confirm('Must not open')"));
    }

    @Test public void promptReturnsEditedEmptyAndCancelledValues() throws Exception {
        main(() -> { web.loadDataWithBaseURL("https://prompt.parity.test/", "<html><body>Prompt fixture</body></html>", "text/html", "UTF-8", null); return null; });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!"\"https://prompt.parity.test/|complete\"".equals(js("location.href+'|'+document.readyState")) && System.nanoTime() < until) Thread.sleep(30);
        assertEquals("\"https://prompt.parity.test/|complete\"", js("location.href+'|'+document.readyState"));
        String[] values = {"initial", "edited fixture", "", null};
        for (String value : values) {
            AtomicReference<String> result = new AtomicReference<>();
            main(() -> { web.evaluateJavascript("prompt('Prompt input fixture','initial')", result::set); return null; });
            android.view.accessibility.AccessibilityNodeInfo root = null;
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < until) {
                root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root != null && !root.findAccessibilityNodeInfosByText("Prompt input fixture").isEmpty()) break;
                Thread.sleep(30);
            }
            assertNotNull(root);
            assertFalse(root.findAccessibilityNodeInfosByText("Prompt input fixture").isEmpty());
            java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes = new java.util.ArrayDeque<>();
            nodes.add(root);
            android.view.accessibility.AccessibilityNodeInfo input = null;
            while (!nodes.isEmpty()) {
                android.view.accessibility.AccessibilityNodeInfo node = nodes.remove();
                if ("android.widget.EditText".contentEquals(node.getClassName())) { input = node; break; }
                for (int i = 0; i < node.getChildCount(); i++) { android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i); if (child != null) nodes.add(child); }
            }
            assertNotNull("Prompt must expose an editable initial value", input);
            assertEquals("initial", input.getText().toString());
            if (value != null && !"initial".equals(value)) {
                android.os.Bundle text = new android.os.Bundle();
                text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                assertTrue(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, text));
            }
            assertTrue(root.findAccessibilityNodeInfosByText(value == null ? "取消" : "确定").get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (result.get() == null && System.nanoTime() < until) Thread.sleep(25);
            assertEquals("Empty input is a successful empty string; cancel is JavaScript null",
                    value == null ? "null" : org.json.JSONObject.quote(value), result.get());
        }
        assertEquals("Via handles the Bdbox bridge prompt silently with an empty result", "\"\"",
                js("prompt('BdboxApp:{\"obj\":\"fixture\"}','initial')"));
        AtomicReference<String> detachedResult = new AtomicReference<>();
        main(() -> { web.evaluateJavascript("prompt('Detach prompt fixture','initial')", detachedResult::set); return null; });
        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean visible = false;
        while (System.nanoTime() < until) {
            android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            visible = root != null && !root.findAccessibilityNodeInfosByText("Detach prompt fixture").isEmpty();
            if (visible) break;
            Thread.sleep(30);
        }
        assertTrue("Detach must be tested while the prompt is still pending", visible);
        ViewGroup parent = main(() -> (ViewGroup) web.getParent());
        int index = main(() -> parent.indexOfChild(web));
        ViewGroup.LayoutParams layout = main(web::getLayoutParams);
        try {
            main(() -> { parent.removeView(web); return null; });
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (detachedResult.get() == null && System.nanoTime() < until) Thread.sleep(25);
            assertEquals("Removing the page must cancel its pending prompt", "null", detachedResult.get());
        } finally { main(() -> { parent.addView(web, index, layout); return null; }); }
    }

    @Test public void beforeUnloadStayPreservesPageAndLeaveNavigates() throws Exception {
        main(() -> {
            web.loadDataWithBaseURL("https://leave.parity.test/", "<html><body><button style='position:fixed;inset:0;width:100%;height:100%' onclick='window.activated=true'>Activate</button><script>window.onbeforeunload=function(e){e.preventDefault();e.returnValue='Unsaved fixture';return 'Unsaved fixture'}</script></body></html>", "text/html", "UTF-8", null);
            return null;
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String ready = "\"https://leave.parity.test/|complete\"";
        while (!ready.equals(js("location.href+'|'+document.readyState")) && System.nanoTime() < until) Thread.sleep(30);
        assertEquals(ready, js("location.href+'|'+document.readyState"));
        int[] point = main(() -> {
            int[] xy = new int[2]; web.getLocationOnScreen(xy);
            xy[0] += web.getWidth() / 2; xy[1] += web.getHeight() / 2;
            return xy;
        });
        long down = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent press = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, point[0], point[1], 0);
        android.view.MotionEvent release = android.view.MotionEvent.obtain(down, down + 80, android.view.MotionEvent.ACTION_UP, point[0], point[1], 0);
        try { instrumentation.sendPointerSync(press); instrumentation.sendPointerSync(release); }
        finally { press.recycle(); release.recycle(); }
        assertEquals("A real user gesture must activate beforeunload", "true", js("window.activated===true"));
        try {
            for (boolean leave : new boolean[]{false, true}) {
                main(() -> { web.loadUrl("about:blank#left"); return null; });
                android.view.accessibility.AccessibilityNodeInfo root = null;
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < until) {
                    root = instrumentation.getUiAutomation().getRootInActiveWindow();
                    if (root != null && !root.findAccessibilityNodeInfosByText("确定离开").isEmpty()) break;
                    Thread.sleep(30);
                }
                assertNotNull(root);
                assertFalse("Navigation must wait for the leave decision", root.findAccessibilityNodeInfosByText("确定离开").isEmpty());
                assertTrue(root.findAccessibilityNodeInfosByText(leave ? "离开此页" : "留在此页").get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
                String expected = leave ? "\"about:blank#left|complete\"" : ready;
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!expected.equals(js("location.href+'|'+document.readyState")) && System.nanoTime() < until) Thread.sleep(30);
                assertEquals("The button must control the actual navigation", expected, js("location.href+'|'+document.readyState"));
                if (!leave) assertEquals("Staying must preserve the existing document", "true", js("window.activated===true"));
            }
        } finally { main(() -> { web.evaluateJavascript("window.onbeforeunload=null", null); return null; }); }
    }

    @Test public void certificatePanelShowsDerAndPublicKeyFingerprintsInScrollableContent() throws Exception {
        String alias = "panel-cert-" + System.currentTimeMillis();
        java.security.KeyStore keys = java.security.KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        AlertDialog dialog = null;
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new android.security.keystore.KeyGenParameterSpec.Builder(alias,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN | android.security.keystore.KeyProperties.PURPOSE_VERIFY)
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new javax.security.auth.x500.X500Principal("CN=panel-cert.parity.test,O=Panel Fixture,OU=Browser"))
                    .setCertificateSerialNumber(java.math.BigInteger.ONE)
                    .setKeySize(2048).build());
            generator.generateKeyPair();
            java.security.cert.X509Certificate certificate = (java.security.cert.X509Certificate) keys.getCertificate(alias);
            dialog = main(() -> {
                return (AlertDialog) invoke(browser, "showCertificateDetails", new Class<?>[]{android.net.http.SslCertificate.class},
                        new android.net.http.SslCertificate(certificate));
            });
            AlertDialog shown = dialog;
            for (byte[] data : new byte[][]{certificate.getEncoded(), certificate.getPublicKey().getEncoded()}) {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
                String expected = new java.math.BigInteger(1, digest).toString(16).toUpperCase(java.util.Locale.ROOT);
                while (expected.length() < 64) expected = "0" + expected;
                String fingerprint = expected;
                main(() -> {
                    View field = findText(shown.getWindow().getDecorView(), fingerprint);
                    assertNotNull("Both DER certificate and public key hashes must be shown", field);
                    android.view.ViewParent ancestor = field.getParent();
                    while (ancestor != null && !(ancestor instanceof android.widget.ScrollView)) ancestor = ancestor.getParent();
                    assertNotNull("Long certificate details must be scrollable", ancestor);
                    assertNotNull(findText(shown.getWindow().getDecorView(), "panel-cert.parity.test"));
                    return null;
                });
            }
        } finally {
            AlertDialog last = dialog;
            main(() -> { if (last != null) last.dismiss(); return null; });
            keys.deleteEntry(alias);
        }
    }

    @Test public void cookiePanelCopiesRawValuesAndClearsItsCapturedPage() throws Exception {
        String url = "https://cookie-panel.parity.test/nested/page";
        String other = "https://other-cookie-panel.parity.test/";
        CookieManager manager = CookieManager.getInstance();
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) browser.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        android.content.ClipData original = main(clipboard::getPrimaryClip);
        AlertDialog dialog = null;
        try {
            main(() -> {
                manager.setAcceptCookie(true);
                manager.setCookie(url, "panel_root=root; Path=/; Secure; HttpOnly");
                manager.setCookie(url, "panel_path=nested; Domain=cookie-panel.parity.test; Path=/nested; Secure");
                manager.setCookie(other, "panel_other=keep; Path=/; Secure");
                manager.flush(); return null;
            });
            String raw = manager.getCookie(url);
            assertTrue(raw.contains("panel_root=root"));
            assertTrue(raw.contains("panel_path=nested"));
            dialog = main(() -> (AlertDialog) invoke(browser, "showCookiesDialog", new Class<?>[]{String.class}, url));
            AlertDialog copyDialog = dialog;
            main(() -> { copyDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); return null; });
            instrumentation.waitForIdleSync();
            assertEquals("Copy must retain the raw semicolon-separated cookie header", raw,
                    main(() -> clipboard.getPrimaryClip().getItemAt(0).getText().toString()));
            dialog = main(() -> (AlertDialog) invoke(browser, "showCookiesDialog", new Class<?>[]{String.class}, url));
            AlertDialog clearDialog = dialog;
            main(() -> {
                ((TabManager) field(browser, "tabs")).current().url = other;
                clearDialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); return null;
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (manager.getCookie(url) != null && !manager.getCookie(url).isEmpty() && System.nanoTime() < until) Thread.sleep(30);
            assertTrue("Root/path/HttpOnly cookies for the dialog's page must be cleared", manager.getCookie(url) == null || manager.getCookie(url).isEmpty());
            assertTrue("Switching the current tab must not redirect the clear operation", manager.getCookie(other).contains("panel_other=keep"));
            dialog = main(() -> (AlertDialog) invoke(browser, "showCookiesDialog", new Class<?>[]{String.class}, url));
            AlertDialog empty = dialog;
            main(() -> {
                assertNotNull(findText(empty.getWindow().getDecorView(), "cookie-panel.parity.test 的 Cookies"));
                assertNotNull(findText(empty.getWindow().getDecorView(), "无 Cookies。"));
                assertEquals(View.GONE, empty.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());
                return null;
            });
        } finally {
            AlertDialog last = dialog;
            main(() -> {
                if (last != null) last.dismiss();
                manager.setCookie(url, "panel_root=; Path=/; Secure; HttpOnly; Max-Age=0");
                manager.setCookie(url, "panel_path=; Domain=cookie-panel.parity.test; Path=/nested; Secure; Max-Age=0");
                manager.setCookie(other, "panel_other=; Path=/; Secure; Max-Age=0");
                if (original == null) clipboard.clearPrimaryClip(); else clipboard.setPrimaryClip(original);
                return null;
            });
        }
    }

    @Test public void sitePanelHistoryFiltersByCurrentHost() throws Exception {
        BrowserDatabaseHelper db = prefs.dbHelper();
        String url = "https://panel-history.parity.test/watch";
        db.recordHistory("Panel matching fixture", url);
        db.recordHistory("Panel unrelated fixture", "https://panel-other.parity.test/watch");
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(HistoryActivity.class.getName(), null, false);
        try {
            main(() -> {
                ((TabManager) field(browser, "tabs")).current().url = url;
                browser.findViewById(R.id.browser_site_card_history).performClick();
                return null;
            });
            settings = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull("Site panel must open the history library", settings);
            instrumentation.waitForIdleSync();
            main(() -> {
                View root = settings.getWindow().getDecorView();
                assertEquals("Current host must populate history search", "panel-history.parity.test", findInput(root).getText().toString());
                assertNotNull(findText(root, "Panel matching fixture"));
                assertNull("Other sites must not appear in the initial filtered list", findText(root, "Panel unrelated fixture"));
                return null;
            });
        } finally {
            instrumentation.removeMonitor(monitor);
            for (BrowserDatabaseHelper.Entry entry : db.listHistory()) {
                if (entry.url.equals(url) || entry.url.equals("https://panel-other.parity.test/watch")) db.removeHistory(entry.id);
            }
        }
    }

    @Test public void pageQrRoundTripsCurrentUrlAndSavedImage() throws Exception {
        String url = "https://qr.parity.test/watch?id=123&name=电影";
        Dialog dialog = main(() -> {
            ((TabManager) field(browser, "tabs")).current().url = url;
            return (Dialog) invoke(browser, "showPageQrDialog", new Class<?>[0]);
        });
        assertNotNull(dialog);
        android.net.Uri saved = null;
        try {
            android.graphics.Bitmap bitmap = main(() -> {
                java.util.ArrayList<View> images = new java.util.ArrayList<>();
                dialog.getWindow().getDecorView().findViewsWithText(images, "网页二维码", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                assertEquals("Page QR must display an image rather than open the scanner", 1, images.size());
                assertNotNull(findText(dialog.getWindow().getDecorView(), "保存图片"));
                return ((android.graphics.drawable.BitmapDrawable) ((android.widget.ImageView) images.get(0)).getDrawable()).getBitmap();
            });
            saved = com.example.cleanrecovery.ui.browser.BrowserPageQr.save(browser, bitmap);
            try (java.io.InputStream input = browser.getContentResolver().openInputStream(saved)) {
                android.graphics.Bitmap read = android.graphics.BitmapFactory.decodeStream(input);
                assertNotNull("Saved image must be readable through MediaStore", read);
                int[] pixels = new int[read.getWidth() * read.getHeight()];
                read.getPixels(pixels, 0, read.getWidth(), 0, 0, read.getWidth(), read.getHeight());
                com.google.zxing.BinaryBitmap encoded = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(
                        new com.google.zxing.RGBLuminanceSource(read.getWidth(), read.getHeight(), pixels)));
                assertEquals("Saved QR must retain the full URL, query and Unicode text", url,
                        new com.google.zxing.MultiFormatReader().decode(encoded).getText());
            }
        } finally {
            if (saved != null) browser.getContentResolver().delete(saved, null, null);
            main(() -> { dialog.dismiss(); return null; });
        }
    }

    @Test public void clipboardDefaultPolicyOffersAllThreeModes() throws Exception {
        String original = prefs.permission("clipboard");
        settings = launch(BrowserSettingsActivity.class);
        try {
            prefs.setPermission("clipboard", "allow");
            String[] modes = {"ask", "block", "allow"}, labels = {"优先询问", "禁止", "允许"};
            for (int i = 0; i < modes.length; i++) {
                main(() -> {
                    java.lang.reflect.Field key = BrowserSettingsActivity.class.getDeclaredField("permissionKey"); key.setAccessible(true); key.set(settings, "clipboard");
                    java.lang.reflect.Field title = BrowserSettingsActivity.class.getDeclaredField("permissionTitle"); title.setAccessible(true); title.set(settings, "剪贴板");
                    Class page = Class.forName(BrowserSettingsActivity.class.getName() + "$Page");
                    invoke(settings, "open", new Class<?>[]{page}, Enum.valueOf(page, "PERMISSION"));
                    String mode = prefs.permission("clipboard");
                    String subtitle = "ask".equals(mode) ? "网站需先询问并得到许可才能使用剪贴板" : "block".equals(mode) ? "禁止网站使用剪贴板" : "允许网站使用剪贴板";
                    View row = findText(settings.getWindow().getDecorView(), subtitle); assertNotNull(row);
                    while (!row.isClickable()) row = (View) row.getParent(); row.performClick();
                    return null;
                });
                long deadline = android.os.SystemClock.uptimeMillis() + 5000;
                android.view.accessibility.AccessibilityNodeInfo root;
                do {
                    root = instrumentation.getUiAutomation().getRootInActiveWindow();
                    if (root != null && !root.findAccessibilityNodeInfosByText("优先询问").isEmpty()) break;
                    android.os.SystemClock.sleep(30);
                } while (android.os.SystemClock.uptimeMillis() < deadline);
                assertNotNull(root);
                for (String label : labels) assertFalse("All three policies must be available in the selector", root.findAccessibilityNodeInfosByText(label).isEmpty());
                android.view.accessibility.AccessibilityNodeInfo choice = root.findAccessibilityNodeInfosByText(labels[i]).get(0);
                while (!choice.isClickable()) choice = choice.getParent();
                assertTrue(choice.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
                instrumentation.waitForIdleSync();
                assertEquals("Selecting the default policy must persist it", modes[i], prefs.permission("clipboard"));
            }
        } finally { prefs.setPermission("clipboard", original); }
    }

    private void verifyBlockedPermissionException(String permission, String label) throws Exception {
        String original = prefs.permission(permission), host = permission + "-add.parity.test";
        settings = launch(BrowserSettingsActivity.class);
        try {
            for (boolean ask : new boolean[]{false, true}) {
                main(() -> {
                    prefs.setPermission(permission, "block");
                    java.lang.reflect.Field key = BrowserSettingsActivity.class.getDeclaredField("permissionKey"); key.setAccessible(true); key.set(settings, permission);
                    java.lang.reflect.Field title = BrowserSettingsActivity.class.getDeclaredField("permissionTitle"); title.setAccessible(true); title.set(settings, label);
                    Class page = Class.forName(BrowserSettingsActivity.class.getName() + "$Page");
                    invoke(settings, "open", new Class<?>[]{page}, Enum.valueOf(page, "PERMISSION"));
                    assertNotNull("Disabled wording must match the permission's actual Via page",
                            findText(settings.getWindow().getDecorView(), "clipboard".equals(permission) ? "禁止网站使用剪贴板"
                                    : "location".equals(permission) ? "已禁止" : "已禁用"));
                    View add = findText(settings.getWindow().getDecorView(), "＋  添加例外网站");
                    while (!add.isClickable()) add = (View) add.getParent(); add.performClick();
                    return null;
                });
                instrumentation.waitForIdleSync();
                android.view.accessibility.AccessibilityNodeInfo root;
                long windowDeadline = android.os.SystemClock.uptimeMillis() + 5000;
                do {
                    root = instrumentation.getUiAutomation().getRootInActiveWindow();
                    if (root != null && !root.findAccessibilityNodeInfosByText("允许特定网站使用" + label + "。").isEmpty()) break;
                    android.os.SystemClock.sleep(30);
                } while (android.os.SystemClock.uptimeMillis() < windowDeadline);
                assertNotNull(root);
                assertFalse("The dialog must explain the inverse exception under a blocked default", root.findAccessibilityNodeInfosByText("允许特定网站使用" + label + "。").isEmpty());
                android.os.Bundle text = new android.os.Bundle();
                text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, host);
                java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> nodes = new java.util.ArrayDeque<>(); nodes.add(root);
                android.view.accessibility.AccessibilityNodeInfo input = null;
                while (!nodes.isEmpty()) {
                    android.view.accessibility.AccessibilityNodeInfo node = nodes.remove();
                    if ("android.widget.EditText".contentEquals(node.getClassName())) { input = node; break; }
                    for (int i = 0; i < node.getChildCount(); i++) if (node.getChild(i) != null) nodes.add(node.getChild(i));
                }
                assertNotNull("The exception dialog must expose an editable website field", input);
                assertTrue(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, text));
                if (ask) assertTrue(root.findAccessibilityNodeInfosByText("优先询问").get(0).getParent().getChild(0)
                        .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
                assertTrue(root.findAccessibilityNodeInfosByText("确定").get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
                instrumentation.waitForIdleSync();
                assertEquals("An unchecked exception allows directly; checking it must require permission first", ask ? "ask" : "allow", prefs.permission(permission, host));
                prefs.setPermissionException(permission, host, null);
            }
        } finally { prefs.setPermission(permission, original); prefs.setPermissionException(permission, host, null); }
    }

    @Test public void settingsCleanupClearsLiveSessionStorageBeforeCompletion() throws Exception {
        main(() -> { web.loadDataWithBaseURL("https://storage.parity.test/", "<html><body>Storage</body></html>", "text/html", "UTF-8", null); return null; });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"\"complete\"".equals(js("document.readyState")) && System.nanoTime() < until) Thread.sleep(50);
        js("sessionStorage.setItem('parity','session');localStorage.setItem('parity','local')");
        CountDownLatch cleaned = new CountDownLatch(1);
        main(() -> {
            com.example.cleanrecovery.ui.browser.BrowserDataCleaner.clear(browser,
                    java.util.Collections.singleton("form"), java.util.Collections.emptyList(), () -> { });
            return null;
        });
        assertEquals("Clearing form suggestions must preserve website storage", "\"local\"", js("localStorage.getItem('parity')"));
        main(() -> {
            com.example.cleanrecovery.ui.browser.BrowserDataCleaner.clear(browser,
                    java.util.Collections.singleton("web_storage"), java.util.Collections.emptyList(), cleaned::countDown);
            return null;
        });
        assertTrue("Cleanup completion must wait for live document callbacks", cleaned.await(5, TimeUnit.SECONDS));
        assertEquals("Hidden browser tabs must also lose session storage", "null", js("sessionStorage.getItem('parity')"));
        assertEquals("Requested website storage must be cleared", "null", js("localStorage.getItem('parity')"));
    }

    @Test public void toolbarLayoutMovesRealNavigationControls() throws Exception {
        for (int mode : new int[]{1, 0, 2, 3}) {
            final int selected = mode;
            main(() -> {
                prefs.setToolbarMode(selected);
                invoke(browser, "applyToolbarMode", new Class<?>[0]);
                return null;
            });
            instrumentation.waitForIdleSync();
            main(() -> {
                View address = browser.findViewById(com.example.cleanrecovery.R.id.browser_toolbar);
                View navigation = browser.findViewById(com.example.cleanrecovery.R.id.browser_bottom_bar);
                View content = browser.findViewById(com.example.cleanrecovery.R.id.browser_web_container);
                int[] a = new int[2], n = new int[2], c = new int[2];
                address.getLocationOnScreen(a); navigation.getLocationOnScreen(n); content.getLocationOnScreen(c);
                if (selected == 1 || selected == 0) assertTrue("Top address must precede the page", a[1] < c[1]);
                else assertTrue("Bottom address must follow the page", a[1] >= c[1] + content.getHeight());
                if (selected == 0 || selected == 2) assertEquals("Compact navigation shares the address row", a[1], n[1]);
                else assertTrue("Traditional and double-row navigation have a separate row", n[1] > a[1]);
                assertTrue("Home remains clickable in every layout", browser.findViewById(com.example.cleanrecovery.R.id.browser_home).isShown());
                return null;
            });
        }
    }

    @Test public void clickToRevealRequiresButtonWhileSwipeModeFollowsScrollDirection() throws Exception {
        main(() -> {
            browser.findViewById(com.example.cleanrecovery.R.id.browser_home_scroll).setVisibility(View.GONE);
            web.loadDataWithBaseURL("https://scroll.parity.test/", "<body style='height:20000px'>Scroll</body>", "text/html", "UTF-8", null);
            return null;
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"\"complete\"".equals(js("document.readyState")) && System.nanoTime() < until) Thread.sleep(50);
        for (int mode : new int[]{1, 2}) {
            final int selected = mode;
            main(() -> {
                prefs.setToolbarAutoHide(selected);
                web.scrollTo(0, 1000);
                return null;
            });
            instrumentation.waitForIdleSync();
            main(() -> {
                assertFalse("Scrolling down must hide navigation", browser.findViewById(com.example.cleanrecovery.R.id.browser_toolbar).isShown());
                web.scrollTo(0, 400);
                return null;
            });
            instrumentation.waitForIdleSync();
            main(() -> {
                View toolbar = browser.findViewById(com.example.cleanrecovery.R.id.browser_toolbar);
                assertEquals("Only swipe mode reveals controls when scrolling up", selected == 1, toolbar.isShown());
                if (selected == 2) {
                    View reveal = (View) field(browser, "toolbarReveal");
                    assertTrue("Click mode must offer an accessible restore button", reveal.isShown());
                    reveal.performClick();
                    assertTrue("Restore button must reveal navigation", toolbar.isShown());
                }
                return null;
            });
        }
    }

    @Test public void adaptiveToolbarColorAndDomainFollowTheSelectedPage() throws Exception {
        main(() -> {
            prefs.setAdaptiveColor(true);
            prefs.setAddressContent(2);
            TabManager.Tab tab = ((TabManager) field(browser, "tabs")).current();
            tab.url = "https://colors.parity.test/path";
            tab.title = "Color page";
            invoke(browser, "showTab", new Class<?>[]{TabManager.Tab.class}, tab);
            web.loadDataWithBaseURL(tab.url, "<body style='margin:0;background:#123456;height:10000px'>Color</body>", "text/html", "UTF-8", null);
            return null;
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int[] color = {0};
        do {
            main(() -> {
                color[0] = ((android.graphics.drawable.ColorDrawable) browser.findViewById(com.example.cleanrecovery.R.id.browser_toolbar).getBackground()).getColor();
                return null;
            });
            if (color[0] == 0xff123456) break;
            Thread.sleep(50);
        } while (System.nanoTime() < until);
        assertEquals("Adaptive toolbar must use the rendered page color", 0xff123456, color[0]);
        main(() -> {
            assertEquals("Domain mode must omit protocol and path", "colors.parity.test",
                    ((android.widget.TextView) browser.findViewById(com.example.cleanrecovery.R.id.browser_page_title)).getText().toString());
            prefs.setAdaptiveColor(false);
            invoke(browser, "applyToolbarMode", new Class<?>[0]);
            assertEquals("Disabling adaptive color must restore the default toolbar", android.graphics.Color.WHITE,
                    ((android.graphics.drawable.ColorDrawable) browser.findViewById(com.example.cleanrecovery.R.id.browser_toolbar).getBackground()).getColor());
            return null;
        });
    }

    @Test public void slowScrollStillHidesToolbar() throws Exception {
        main(() -> {
            prefs.setToolbarAutoHide(1);
            browser.findViewById(com.example.cleanrecovery.R.id.browser_home_scroll).setVisibility(View.GONE);
            web.loadDataWithBaseURL("https://scroll.parity.test/", "<body style='height:20000px'>Scroll</body>", "text/html", "UTF-8", null);
            return null;
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"\"complete\"".equals(js("document.readyState")) && System.nanoTime() < until) Thread.sleep(50);
        for (int i = 0; i < 10; i++) {
            main(() -> { web.scrollBy(0, 8); return null; });
            instrumentation.waitForIdleSync();
        }
        main(() -> {
            assertFalse("Small scroll events must accumulate to hide the toolbar", browser.findViewById(com.example.cleanrecovery.R.id.browser_toolbar).isShown());
            return null;
        });
    }

    @Test public void configuredLongPressActionIsUsedByBrowserButton() throws Exception {
        settings = launch(BrowserSettingsActivity.class);
        main(() -> {
            openSettingsPage("GESTURES");
            settings.findViewById(com.example.cleanrecovery.R.id.browser_home).performClick();
            View label = findText(settings.getWindow().getDecorView(), "新建标签");
            assertNotNull(label);
            ((View) label.getParent().getParent()).performClick();
            assertEquals("The editor must persist the selected button action", 5, prefs.gestureAction("home"));
            settings.finish();
            int count = ((TabManager) field(browser, "tabs")).size();
            browser.findViewById(com.example.cleanrecovery.R.id.browser_home).performLongClick();
            assertEquals("The configured long press must create a real tab", count + 1, ((TabManager) field(browser, "tabs")).size());
            return null;
        });
    }

    @Test public void nestedLongPressOptionsReachTheirRuntimeActions() throws Exception {
        main(() -> {
            web.loadDataWithBaseURL("https://long-press.parity.test/",
                    "<html><body><a href='https://target.parity.test/'>Visible label</a><div id='ad'>Ad</div></body></html>",
                    "text/html", "UTF-8", null);
            return null;
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"true".equals(js("!!document.getElementById('ad')")) && System.nanoTime() < until) Thread.sleep(50);
        assertEquals("The long-press fixture must be the active page", "true",
                js("!!document.getElementById('ad')"));

        main(() -> {
            invoke(browser, "runLongPressAction",
                    new Class<?>[]{String.class, String.class, String.class, String.class},
                    "copy_link_text", "https://target.parity.test/", null, "Visible label");
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    browser.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            assertNotNull(clipboard);
            assertNotNull(clipboard.getPrimaryClip());
            assertEquals("Copy link text must use the visible anchor label", "Visible label",
                    clipboard.getPrimaryClip().getItemAt(0).coerceToText(browser).toString());

            invoke(browser, "runLongPressAction",
                    new Class<?>[]{String.class, String.class, String.class, String.class},
                    "mark_ad", "https://target.parity.test/", null, "Visible label");
            return null;
        });
        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!"true".equals(js("window.__viaAdMarkerActive===true")) && System.nanoTime() < until) Thread.sleep(50);
        assertEquals("Mark ad must arm the live page selector", "true",
                js("window.__viaAdMarkerActive===true"));
        assertTrue("Mark ad must enable the browser's filtering backend", prefs.adBlockEnabled());
    }

    @Test public void ctrlTShortcutCreatesATabThroughTheWindow() throws Exception {
        int count = main(() -> ((TabManager) field(browser, "tabs")).size());
        long now = android.os.SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_T, 0, android.view.KeyEvent.META_CTRL_ON));
        instrumentation.sendKeySync(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_T, 0, android.view.KeyEvent.META_CTRL_ON));
        instrumentation.waitForIdleSync();
        assertEquals("Keyboard shortcut must reach the browser through Android dispatch", count + 1,
                (int) main(() -> ((TabManager) field(browser, "tabs")).size()));
    }

    @Test public void customTabsSettingChangesExternalSessionHandling() throws Exception {
        main(() -> {
            Intent custom = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://custom-tab.parity.test/"));
            custom.putExtra("android.support.customtabs.extra.SESSION", true);
            prefs.setDisableCustomTabs(false);
            invoke(browser, "onNewIntent", new Class<?>[]{Intent.class}, custom);
            assertTrue("An external Custom Tabs session must use the temporary browser mode",
                    (Boolean) field(browser, "customTabMode"));
            assertEquals("Temporary sessions must not expose tab management", View.GONE,
                    ((View) field(browser, "tabsButtonContainer")).getVisibility());
            assertEquals("Temporary sessions must provide Via's top-right close control", "关闭",
                    ((View) field(browser, "reloadIcon")).getContentDescription().toString());

            prefs.setDisableCustomTabs(true);
            invoke(browser, "onNewIntent", new Class<?>[]{Intent.class}, custom);
            assertFalse("Disabling Custom Tabs must route the same link into the full browser",
                    (Boolean) field(browser, "customTabMode"));
            assertEquals(View.VISIBLE, ((View) field(browser, "tabsButtonContainer")).getVisibility());
            assertEquals(browser.getString(R.string.via_menu_reload),
                    ((View) field(browser, "reloadIcon")).getContentDescription().toString());
            return null;
        });
    }

    @Test public void siteInformationPanelUsesTheTopRightCloseControl() throws Exception {
        main(() -> {
            invoke(browser, "toggleSiteCard", new Class<?>[0]);
            assertEquals(View.VISIBLE, ((View) field(browser, "siteCard")).getVisibility());
            assertEquals("关闭", ((View) field(browser, "reloadIcon")).getContentDescription().toString());
            ((View) field(browser, "reloadIcon")).performClick();
            assertEquals("The top-right close control must collapse the information panel", View.GONE,
                    ((View) field(browser, "siteCard")).getVisibility());
            assertEquals(browser.getString(R.string.via_menu_reload),
                    ((View) field(browser, "reloadIcon")).getContentDescription().toString());
            return null;
        });
    }

    @Test public void returningWithoutResultDataUpdatesBackgroundTabs() throws Exception {
        main(() -> {
            invoke(browser, "newTab", new Class<?>[]{String.class, boolean.class}, "about:blank", false);
            prefs.setJsEnabled(false);
            invoke(browser, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, 1004, Activity.RESULT_OK, null);
            for (TabManager.Tab tab : ((TabManager) field(browser, "tabs")).all()) {
                assertFalse("Returning from settings must apply preferences to hidden tabs too", tab.webView.getSettings().getJavaScriptEnabled());
            }
            return null;
        });
    }

    @Test public void settingsRecreationPreservesEditorDraftAndBackStack() throws Exception {
        try (androidx.test.core.app.ActivityScenario<BrowserSettingsActivity> scenario = androidx.test.core.app.ActivityScenario.launch(BrowserSettingsActivity.class)) {
            scenario.onActivity(activity -> settings = activity);
            main(() -> {
                openSettingsPage("READER_CSS");
                findInput(settings.getWindow().getDecorView()).setText("body { color: #123456; }");
                return null;
            });
            scenario.recreate();
            scenario.onActivity(activity -> settings = activity);
            main(() -> {
                assertEquals("A configuration change must retain unsaved editor text", "body { color: #123456; }",
                        findInput(settings.getWindow().getDecorView()).getText().toString());
                settings.onBackPressed();
                assertNotNull("Back must return to the previous settings page", findText(settings.getWindow().getDecorView(), "通用"));
                return null;
            });
        } finally { settings = null; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void openSettingsPage(String name) throws Exception {
        Class type = Class.forName(BrowserSettingsActivity.class.getName() + "$Page");
        invoke(settings, "open", new Class<?>[]{type}, Enum.valueOf(type, name));
    }

    private static View findText(View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void videoGestureLayerRoutesLeftRightAndHorizontalMotion() throws Exception {
        main(() -> {
            java.util.List<String> calls = new java.util.ArrayList<>();
            com.example.cleanrecovery.ui.browser.BrowserVideoGestureLayer layer =
                    new com.example.cleanrecovery.ui.browser.BrowserVideoGestureLayer(browser, new View(browser), true,
                            new com.example.cleanrecovery.ui.browser.BrowserVideoGestureLayer.Callbacks() {
                                @Override public void seek(int seconds) { calls.add("seek:" + seconds); }
                                @Override public int changeBrightness(float delta) { calls.add("brightness"); return 50; }
                                @Override public int changeVolume(float delta) { calls.add("volume"); return 50; }
                            });
            int width = 1800, height = 900;
            layer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            layer.layout(0, 0, width, height);
            swipe(layer, 300, 500, 300, 200);
            swipe(layer, 1500, 500, 1500, 200);
            swipe(layer, 500, 450, 1400, 450);
            assertTrue("Left vertical gesture must adjust brightness", calls.contains("brightness"));
            assertTrue("Right vertical gesture must adjust media volume", calls.contains("volume"));
            assertTrue("Horizontal gesture must seek video", calls.stream().anyMatch(value -> value.startsWith("seek:")));
            return null;
        });
    }

    @Test public void backupArchiveRestoresSelectedBrowserData() throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        main(() -> {
            prefs.setHomeMode(3);
            prefs.setHomeCustomUrl("https://backup.parity.test/");
            prefs.dbHelper().addBookmark("Backup parity", "https://bookmark.parity.test/", "根目录");
            new BrowserPasswordStore(browser).save("https://password.parity.test", "person", "secret");
            return null;
        });
        BrowserBackupManager.exportBackup(browser, output, 63, "portable-backup-password");
        java.util.Set<String> names = new java.util.HashSet<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(output.toByteArray()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) names.add(entry.getName());
        }
        assertTrue("Via backup must contain its settings payload", names.contains("settings.txt"));
        assertTrue("Via backup must contain Netscape bookmarks", names.contains("bookmarks.html"));
        assertTrue("Selecting passwords must add the encrypted password payload", names.contains("pass.enc"));
        assertTrue("Portable password backups must carry Via's encoded salt", names.contains("info.enc"));
        main(() -> {
            prefs.setHomeCustomUrl("https://mutated.parity.test/");
            prefs.dbHelper().removeBookmarkByUrl("https://bookmark.parity.test/");
            BrowserPasswordStore store = new BrowserPasswordStore(browser);
            BrowserPasswordStore.Entry saved = store.find("https://password.parity.test");
            if (saved != null) store.remove(saved);
            return null;
        });
        try {
            BrowserBackupManager.importBackup(browser, new java.io.ByteArrayInputStream(output.toByteArray()),
                    "wrong-password");
            fail("An incorrect backup password must fail before importing any section");
        } catch (Exception expected) {
            assertEquals("A failed encrypted import must not change settings", "https://mutated.parity.test/",
                    prefs.homeCustomUrl());
            assertFalse("A failed encrypted import must not add bookmarks",
                    prefs.dbHelper().isBookmarked("https://bookmark.parity.test/"));
        }
        BrowserBackupManager.importBackup(browser, new java.io.ByteArrayInputStream(output.toByteArray()),
                "portable-backup-password");
        assertEquals("Import must restore the setting captured in the archive", "https://backup.parity.test/", prefs.homeCustomUrl());
        assertTrue("Import must restore a selected bookmark", prefs.dbHelper().isBookmarked("https://bookmark.parity.test/"));
        assertNotNull("Import must restore the encrypted password selection", new BrowserPasswordStore(browser).find("https://password.parity.test"));
    }

    @Test public void savedPasswordIsFilledIntoARealWebForm() throws Exception {
        new BrowserPasswordStore(browser).save("https://password.parity.test", "alice", "correct horse");
        main(() -> {
            web.loadDataWithBaseURL("https://password.parity.test/login",
                    "<html><body><form><input id='u' type='text'><input id='p' type='password'></form></body></html>",
                    "text/html", "UTF-8", null);
            return null;
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"\"correct horse\"".equals(js("document.querySelector('#p')&&document.querySelector('#p').value"))
                && System.nanoTime() < until) Thread.sleep(50);
        assertEquals("Stored username must reach the page form", "\"alice\"", js("document.querySelector('#u').value"));
        assertEquals("Stored password must reach the page form", "\"correct horse\"", js("document.querySelector('#p').value"));
    }

    @Test public void stalePasswordCallbackCannotFillCredentialsIntoAnotherOrigin() throws Exception {
        new BrowserPasswordStore(browser).save("https://password.parity.test", "alice", "correct horse");
        main(() -> {
            web.loadDataWithBaseURL("https://other-password.parity.test/",
                    "<html><body><form><input id='u'><input id='p' type='password'></form></body></html>",
                    "text/html", "UTF-8", null);
            return null;
        });
        awaitReaderJs("location.origin==='https://other-password.parity.test'&&!!document.querySelector('#p')", "true");
        js("delete window.__viaPasswordManager");
        main(() -> {
            invoke(browser, "injectPasswordManager", new Class<?>[]{WebView.class, String.class, boolean.class},
                    web, "https://password.parity.test/login", false);
            return null;
        });
        assertEquals("A callback from the previous origin must never expose its saved password", "\"\"",
                js("document.querySelector('#p').value"));
    }

    @Test public void syncNavigationMatchesViaHierarchy() throws Exception {
        settings = launch(BrowserSettingsActivity.class);
        main(() -> {
            openSettingsPage("SYNC");
            assertNotNull(findText(settings.getWindow().getDecorView(), "登录/注册"));
            assertNull("WebDAV account operations belong on the separate toolbar page", findText(settings.getWindow().getDecorView(), "WEBDAV 账号"));
            View webdav = findText(settings.getWindow().getDecorView(), "WEBDAV");
            assertNotNull(webdav);
            webdav.performClick();
            assertNotNull("The WebDAV action must open the actual sync controls", findText(settings.getWindow().getDecorView(), "手动同步"));
            return null;
        });
    }

    @Test public void cloudLoginDialogMatchesViaContract() throws Exception {
        settings = launch(BrowserSettingsActivity.class);
        main(() -> {
            openSettingsPage("SYNC");
            View login = findText(settings.getWindow().getDecorView(), "登录/注册");
            assertNotNull(login);
            Dialog dialog = com.example.cleanrecovery.ui.browser.ViaUi.loginDialog(
                    settings, "", () -> { }, () -> { }, (values, checked) -> { });
            View dialogRoot = dialog.getWindow().getDecorView();
            assertNotNull("Via login asks for an auto-registering username",
                    findInputByHint(dialogRoot, "用户名 (账号不存在将自动创建)"));
            assertNotNull("Via login links its terms before account creation",
                    findText(dialogRoot, "请阅读并同意 使用协议 与 隐私政策"));
            dialog.dismiss();
            assertEquals("Via hashes the account password with lower-case MD5",
                    "5f4dcc3b5aa765d61d8327deb882cf99", BrowserCloudAccount.md5("password"));
            return null;
        });
    }

    @Test public void cloudPayloadUsesViaFieldsAndRestoresEveryBrowserDataType() throws Exception {
        prefs.dbHelper().addBookmark("Cloud bookmark", "https://cloud-bookmark.parity.test/", "云端");
        prefs.dbHelper().addQuickLink("Cloud favorite", "https://cloud-favorite.parity.test/");
        prefs.saveScript("Parity cloud script", "https://cloud-script.parity.test/*",
                "// ==UserScript==\n// @name Parity cloud script\n// @match https://cloud-script.parity.test/*\n// ==/UserScript==\nwindow.cloudScript=true;");
        prefs.setScriptEnabled("Parity cloud script", false);
        java.util.Map<String, String> fields = BrowserCloudPayload.create(browser);
        assertTrue("Via cloud upload must include settings", fields.containsKey("settings"));
        assertTrue("Via cloud upload must include bookmarks", fields.containsKey("bookmark"));
        assertTrue("Via cloud upload must include ad-block rules", fields.containsKey("adrules"));
        assertFalse("Via's account endpoint does not carry WebDAV-only homepage favorites", fields.containsKey("favorite"));
        assertTrue("Via cloud upload must include userscripts", fields.containsKey("other"));

        org.json.JSONObject response = new org.json.JSONObject(fields);
        int restored = BrowserCloudPayload.restore(browser, response.toString());
        assertTrue("Cloud restore must apply the complete payload", restored >= 4);
        assertTrue("Cloud restore must preserve bookmarks", prefs.dbHelper().isBookmarked("https://cloud-bookmark.parity.test/"));
        assertTrue("Cloud restore must preserve userscript enabled state", !prefs.isScriptEnabled("Parity cloud script"));
        boolean favorite = false;
        for (BrowserDatabaseHelper.Entry entry : prefs.dbHelper().listQuickLinks()) {
            if ("https://cloud-favorite.parity.test/".equals(entry.url)) favorite = true;
        }
        assertTrue("Via account sync must leave local homepage favorites unchanged when no remote favorite field exists", favorite);
    }

    @Test public void multipleCustomSearchEnginesRemainIndependentlySelectable() {
        prefs.addCustomSearch("Parity engine A", "https://a.parity.test/?q=");
        prefs.addCustomSearch("Parity engine B", "https://b.parity.test/?q=");
        java.util.List<BrowserPrefs.CustomSearchItem> items = prefs.customSearchList();
        int a = -1;
        int b = -1;
        for (int i = 0; i < items.size(); i++) {
            if ("Parity engine A".equals(items.get(i).title)) a = i;
            if ("Parity engine B".equals(items.get(i).title)) b = i;
        }
        assertTrue("Each custom engine must have its own list entry", a >= 0 && b >= 0 && a != b);
        prefs.selectCustomSearch(a);
        assertEquals("Selecting the first custom engine must activate its own URL", "https://a.parity.test/?q=", prefs.searchPrefix());
        prefs.selectCustomSearch(b);
        assertEquals("Selecting the second custom engine must activate its own URL", "https://b.parity.test/?q=", prefs.searchPrefix());
    }

    @Test public void malformedSessionRestoreIsAtomic() throws Exception {
        main(() -> {
            Object tabs = field(browser, "tabs");
            int before = (Integer) invoke(tabs, "size", new Class<?>[0]);
            boolean restored = (Boolean) invoke(browser, "restoreSession", new Class<?>[]{String.class},
                    "[{\"u\":\"https://valid-before-error.test\"},42]");
            int after = (Integer) invoke(tabs, "size", new Class<?>[0]);
            assertFalse("A malformed session must be rejected", restored);
            assertEquals("Rejected session data must not leave partially-created tabs", before, after);
            return null;
        });
    }

    @Test public void bookmarkHomeConsumesTheBookmarkDatabase() throws Exception {
        main(() -> {
            prefs.dbHelper().addBookmark("Home bookmark parity", "https://home-bookmark.parity.test/");
            prefs.setHomeMode(2);
            invoke(browser, "showHome", new Class<?>[0]);
            assertNotNull("Bookmark home mode must render saved bookmarks", findText(
                    browser.findViewById(com.example.cleanrecovery.R.id.browser_home_grid), "Home bookmark parity"));
            return null;
        });
    }

    @Test public void customizeTabsRevealInlineViaPropertyPanels() throws Exception {
        settings = launch(BrowserHomeCustomizeActivity.class);
        main(() -> {
            View logo = findText(settings.getWindow().getDecorView(), "Logo");
            assertNotNull(logo);
            logo.performClick();
            assertNotNull("Logo tab must reveal the inline width property", findText(settings.getWindow().getDecorView(), "宽度"));
            assertNotNull("Default Via logo width is adaptive", findText(settings.getWindow().getDecorView(), "自适应"));
            View search = findText(settings.getWindow().getDecorView(), "搜索框");
            assertNotNull(search);
            search.performClick();
            assertNotNull("Search tab must expose stroke opacity inline", findText(settings.getWindow().getDecorView(), "描边不透明度"));
            return null;
        });
    }

    @Test public void enabledUserScriptExecutesOnItsMatchedPage() throws Exception {
        main(() -> {
            prefs.saveScript("Parity userscript", "https://script.parity.test/*",
                    "// ==UserScript==\n// @name Parity userscript\n// @grant GM_getValue\n// @grant GM_setValue\n// @grant GM_xmlhttpRequest\n// @grant GM.addElement\n// @match https://script.parity.test/*\n// ==/UserScript==\n"
                            + "GM_setValues({answer:42});window.parityUserscript=GM_getValues(['answer']).answer===42"
                            + "&&typeof GM_xmlhttpRequest==='function'&&typeof GM.addElement==='function'?'executed':'missing-api';");
            web.loadDataWithBaseURL("https://script.parity.test/page", "<html><body>Script</body></html>", "text/html", "UTF-8", null);
            return null;
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"\"executed\"".equals(js("window.parityUserscript")) && System.nanoTime() < until) Thread.sleep(50);
        assertEquals("Saving a userscript must have a browser execution consumer", "\"executed\"", js("window.parityUserscript"));
    }

    @Test public void documentStartUserScriptRunsBeforeThePageScript() throws Exception {
        main(() -> {
            prefs.saveScript("Parity document start", "https://start-script.parity.test/*",
                    "// ==UserScript==\n// @name Parity document start\n// @match https://start-script.parity.test/*\n// @run-at document-start\n// ==/UserScript==\nwindow.parityAtStart='ready';");
            invoke(browser, "applySettings", new Class<?>[]{WebView.class}, web);
            web.loadDataWithBaseURL("https://start-script.parity.test/page",
                    "<html><head><script>window.paritySeenInline=window.parityAtStart||'missing';</script></head><body>Start</body></html>",
                    "text/html", "UTF-8", null);
            return null;
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String loaded = "\"https://start-script.parity.test/page|complete\"";
        while (!loaded.equals(js("location.href + '|' + document.readyState")) && System.nanoTime() < until) Thread.sleep(50);
        assertEquals("The previous document's complete state must not satisfy the navigation wait", loaded,
                js("location.href + '|' + document.readyState"));
        assertEquals("A document-start userscript must run before the document's own scripts",
                "\"ready\"", js("window.paritySeenInline"));
    }

    @Test public void customHomeCssChangesTheRenderedViaDom() throws Exception {
        WebView custom = main(() -> {
            prefs.setHomeCustomCss("#bookmark_part{display:none!important}");
            invoke(browser, "showHome", new Class<?>[0]);
            return (WebView) field(browser, "homeCustomWeb");
        });
        assertNotNull("Saving homepage CSS must create the Via-compatible homepage renderer", custom);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"true".equals(js(custom, "!!document.querySelector('#bookmark_part')")) && System.nanoTime() < until) Thread.sleep(50);
        assertEquals("Custom CSS must target Via's documented homepage DOM", "\"none\"",
                js(custom, "getComputedStyle(document.querySelector('#bookmark_part')).display"));
    }

    @Test public void nightMaskAndForcedDarkRemainIndependent() throws Exception {
        main(() -> {
            prefs.setNightMode(true);
            prefs.setNightMask(true);
            prefs.setForceDarkPages(false);
            web.loadDataWithBaseURL("https://night.parity.test/", "<html><body style='background:white'>Night</body></html>", "text/html", "UTF-8", null);
            return null;
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"\"complete\"".equals(js("document.readyState")) && System.nanoTime() < until) Thread.sleep(50);
        main(() -> {
            invoke(browser, "applyToolbarMode", new Class<?>[0]);
            assertTrue("Night mode with the mask option must overlay a web page", ((View) field(browser, "nightMask")).isShown());
            prefs.setForceDarkPages(true);
            invoke(browser, "applyDarkMode", new Class<?>[]{WebView.class, boolean.class}, web, true);
            return null;
        });
        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!"true".equals(js("!!document.querySelector('style[data-via-night]')")) && System.nanoTime() < until) Thread.sleep(50);
        assertEquals("Enabling forced dark must inject the page treatment", "true", js("!!document.querySelector('style[data-via-night]')"));
    }

    @Test public void aiProviderUsesConfiguredBackendAndParsesReply() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            java.util.concurrent.Future<String> request = java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
                try (java.net.Socket socket = server.accept()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    StringBuilder headers = new StringBuilder(); String line; int length = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        headers.append(line).append('\n');
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                    }
                    char[] body = new char[length]; int at = 0, n;
                    while (at < length && (n = reader.read(body, at, length - at)) > 0) at += n;
                    byte[] response = "{\"choices\":[{\"message\":{\"content\":\"backend reply\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    java.io.OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + response.length + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    output.write(response); output.flush();
                    return headers + new String(body, 0, at);
                }
            });
            String reply = BrowserAiClient.chat("http://127.0.0.1:" + server.getLocalPort(), "test-key", "test-model", "system", "hello");
            assertEquals("The configured AI service reply must reach the chat consumer", "backend reply", reply);
            String sent = request.get(5, TimeUnit.SECONDS);
            assertTrue("AI request must use the configured bearer key", sent.contains("Authorization: Bearer test-key"));
            assertTrue("AI request must use the selected model", sent.contains("\"model\":\"test-model\""));
        }
    }

    @Test public void aiSettingsMatchProviderAndPromptHierarchy() throws Exception {
        settings = launch(BrowserSettingsActivity.class);
        main(() -> {
            openSettingsPage("AI");
            assertNotNull(findText(settings.getWindow().getDecorView(), "AI 服务提供商"));
            assertNotNull(findText(settings.getWindow().getDecorView(), "AI 提示词"));
            assertNull("Via AI root does not expose a fabricated built-in conversation row", findText(settings.getWindow().getDecorView(), "对话服务"));
            return null;
        });
    }

    @Test public void adBlockSubscriptionScreenMatchesViaHierarchy() throws Exception {
        java.util.List<com.example.cleanrecovery.ui.browser.AdSubscriptionManager.Subscription> original =
                com.example.cleanrecovery.ui.browser.AdSubscriptionManager.load(browser);
        java.util.List<com.example.cleanrecovery.ui.browser.AdSubscriptionManager.Subscription> fixture =
                new java.util.ArrayList<>();
        for (com.example.cleanrecovery.ui.browser.AdSubscriptionManager.CatalogItem item :
                com.example.cleanrecovery.ui.browser.AdSubscriptionManager.catalog()) {
            com.example.cleanrecovery.ui.browser.AdSubscriptionManager.Subscription sub =
                    new com.example.cleanrecovery.ui.browser.AdSubscriptionManager.Subscription();
            sub.title = item.title;
            sub.url = item.url;
            sub.enabled = true;
            sub.lineCount = 100;
            sub.lastUpdateMs = System.currentTimeMillis();
            fixture.add(sub);
        }
        com.example.cleanrecovery.ui.browser.AdSubscriptionManager.save(browser, fixture);
        long previousInterval = prefs.adBlockSubUpdateInterval();
        try {
            settings = launch(BrowserSettingsActivity.class);
            main(() -> {
                openSettingsPage("ADBLOCK_SUBS");
                View root = settings.getWindow().getDecorView();
                assertNotNull(findText(root, "＋"));
                assertNotNull(findText(root, "更新"));
                assertNotNull(findText(root, "自动更新"));
                assertNotNull(findText(root, "规则订阅"));
                assertNotNull(findText(root, "EasyList"));
                assertNotNull(findText(root, "EasyList China"));
                assertNotNull(findText(root, "CJX's Annoyance List"));
                assertNotNull(findText(root, "EasyPrivacy"));
                assertNotNull(findText(root, "Adblock Warning Removal List"));
                assertNull("The obsolete recommendation section is not part of Via's screen",
                        findText(root, "推荐订阅"));
                Dialog intervalDialog = (Dialog) invoke(settings, "showAdBlockUpdateIntervalDialog", new Class<?>[0]);
                assertTrue("Automatic update picker must be attached to a visible window", intervalDialog.isShowing());
                View threeDays = findText(intervalDialog.getWindow().getDecorView(), "每 3 天");
                assertNotNull("Automatic update must open Via's interval picker", threeDays);
                ((View) threeDays.getParent()).performClick();
                assertEquals("The selected interval must be persisted for the updater",
                        3L * DateUtils.DAY_IN_MILLIS, prefs.adBlockSubUpdateInterval());
                prefs.setAdBlockSubUpdateInterval(previousInterval);
                return null;
            });
        } finally {
            com.example.cleanrecovery.ui.browser.AdSubscriptionManager.save(browser, original);
        }
    }

    @Test public void downloadedSubscriptionBlocksARealWebViewRequest() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicBoolean adRequested = new java.util.concurrent.atomic.AtomicBoolean(false);
        final boolean oldEnabled = prefs.adBlockEnabled();
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            server.setSoTimeout(500);
            int port = server.getLocalPort();
            String base = "http://127.0.0.1:" + port;
            String subscriptionUrl = base + "/rules.txt";
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.Future<?> serving = executor.submit(() -> {
                while (running.get()) {
                    try (java.net.Socket socket = server.accept()) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(socket.getInputStream()));
                        String first = reader.readLine();
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                        String path = first == null ? "" : first.split(" ")[1];
                        String body;
                        String type;
                        if (path.startsWith("/rules.txt")) {
                            StringBuilder rules = new StringBuilder("[Adblock Plus 2.0]\n! Title: Parity Probe\n");
                            rules.append("/ads/banner.js$script\n");
                            for (int i = 0; i < 30; i++) rules.append("||unused").append(i).append(".parity.test^\n");
                            body = rules.toString();
                            type = "text/plain";
                        } else if (path.startsWith("/ads/banner.js")) {
                            adRequested.set(true);
                            body = "window.adExecuted=true;";
                            type = "application/javascript";
                        } else {
                            body = "<!doctype html><html><head><title>PROBE</title></head><body>"
                                    + "<script>window.adExecuted=false</script>"
                                    + "<script src='/ads/banner.js'></script>"
                                    + "<script>setTimeout(function(){document.title=window.adExecuted?'AD-RAN':'AD-BLOCKED'},300)</script>"
                                    + "</body></html>";
                            type = "text/html";
                        }
                        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        java.io.OutputStream out = socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type
                                + "\r\nContent-Length: " + bytes.length
                                + "\r\nConnection: close\r\n\r\n")
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        out.write(bytes);
                        out.flush();
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception e) {
                        if (running.get()) throw new RuntimeException(e);
                    }
                }
            });
            try {
                prefs.setAdBlockEnabled(true);
                com.example.cleanrecovery.ui.browser.AdSubscriptionManager.DownloadResult result =
                        com.example.cleanrecovery.ui.browser.AdSubscriptionManager.download(browser, subscriptionUrl, null);
                assertTrue("A valid ABP subscription must be downloaded before testing its effect: " + result.error,
                        result.ok);
                com.example.cleanrecovery.ui.browser.AdBlockRuleLoader.reloadAsync(browser);
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (com.example.cleanrecovery.ui.browser.BrowserAdBlocker.engine().current().matchNetwork(
                        base + "/ads/banner.js", "127.0.0.1", "127.0.0.1",
                        com.example.cleanrecovery.ui.browser.AdFilterEngine.T_SCRIPT, false) == null
                        && System.nanoTime() < until) Thread.sleep(25);
                assertNotNull("The downloaded subscription must enter the live shared filter index",
                        com.example.cleanrecovery.ui.browser.BrowserAdBlocker.engine().current().matchNetwork(
                                base + "/ads/banner.js", "127.0.0.1", "127.0.0.1",
                                com.example.cleanrecovery.ui.browser.AdFilterEngine.T_SCRIPT, false));

                main(() -> { web.loadUrl(base + "/probe"); return null; });
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!"\"AD-BLOCKED\"".equals(js("document.title")) && System.nanoTime() < until) Thread.sleep(50);
                assertEquals("The real WebView client must replace the matching script response",
                        "\"AD-BLOCKED\"", js("document.title"));
                assertFalse("A blocked resource must never reach the HTTP server", adRequested.get());
            } finally {
                com.example.cleanrecovery.ui.browser.AdSubscriptionManager.remove(browser, subscriptionUrl);
                prefs.setAdBlockEnabled(oldEnabled);
                com.example.cleanrecovery.ui.browser.AdBlockRuleLoader.reloadAsync(browser);
                running.set(false);
                server.close();
                serving.get(5, TimeUnit.SECONDS);
                executor.shutdownNow();
            }
        }
    }

    @Test public void automaticSubscriptionIntervalDrivesRealNetworkUpdates() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            server.setSoTimeout(300);
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/auto-rules.txt";
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.Future<?> serving = executor.submit(() -> {
                while (running.get()) {
                    try (java.net.Socket socket = server.accept()) {
                        requests.incrementAndGet();
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(socket.getInputStream()));
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                        StringBuilder rules = new StringBuilder("[Adblock Plus 2.0]\n! Title: Auto Update Probe\n");
                        for (int i = 0; i < 30; i++) rules.append("||auto").append(i).append(".parity.test^\n");
                        byte[] body = rules.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                                + body.length + "\r\nConnection: close\r\n\r\n")
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(body);
                        socket.getOutputStream().flush();
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception e) {
                        if (running.get()) throw new RuntimeException(e);
                    }
                }
            });
            try {
                com.example.cleanrecovery.ui.browser.AdSubscriptionManager.DownloadResult first =
                        com.example.cleanrecovery.ui.browser.AdSubscriptionManager.download(browser, url, null);
                assertTrue(first.error, first.ok);
                java.util.List<com.example.cleanrecovery.ui.browser.AdSubscriptionManager.Subscription> subs =
                        com.example.cleanrecovery.ui.browser.AdSubscriptionManager.load(browser);
                com.example.cleanrecovery.ui.browser.AdSubscriptionManager.Subscription sub =
                        com.example.cleanrecovery.ui.browser.AdSubscriptionManager.find(subs, url);
                assertNotNull(sub);
                sub.lastUpdateMs = System.currentTimeMillis() - 2L * DateUtils.DAY_IN_MILLIS;
                com.example.cleanrecovery.ui.browser.AdSubscriptionManager.save(browser, subs);
                assertFalse("'Never' must not make every subscription immediately expired",
                        com.example.cleanrecovery.ui.browser.AdSubscriptionManager.updateOutdated(browser, 0));
                Thread.sleep(250);
                assertEquals("Disabled automatic updates must not access the network", 1, requests.get());
                assertTrue("A subscription older than the selected day interval must update",
                        com.example.cleanrecovery.ui.browser.AdSubscriptionManager.updateOutdated(
                                browser, DateUtils.DAY_IN_MILLIS));
                assertEquals("The updater must perform a second HTTP request", 2, requests.get());
            } finally {
                com.example.cleanrecovery.ui.browser.AdSubscriptionManager.remove(browser, url);
                running.set(false);
                server.close();
                serving.get(5, TimeUnit.SECONDS);
                executor.shutdownNow();
            }
        }
    }

    @Test public void expandAndReaderConfirmationSettingsReachTheLiveWebView() throws Exception {
        main(() -> {
            prefs.setAdBlockExpand(true);
            web.loadDataWithBaseURL("https://expand.parity.test/",
                    "<html><body><details><summary>More</summary><p id='hidden'>Hidden</p></details></body></html>",
                    "text/html", "UTF-8", null);
            return null;
        });
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"true".equals(js("document.querySelector('details').open")) && System.nanoTime() < until) Thread.sleep(50);
        assertEquals("Automatic expansion must open collapsed page content", "true",
                js("document.querySelector('details').open"));

        loadReaderFixture();
        main(() -> {
            prefs.setReaderConfirm(true);
            browser.findViewById(R.id.browser_site_info).performClick();
            assertEquals("Via confirmation means showing the site menu", View.VISIBLE,
                    browser.findViewById(R.id.browser_site_card).getVisibility());
            assertNotNull("The site menu must offer the reader action",
                    findText(browser.findViewById(R.id.browser_reader_controls), "打开阅读模式"));
            return null;
        });
        assertEquals("Opening the menu must leave the document outside reader mode", "false",
                js("!!document.querySelector('.via-reader-body')"));
        main(() -> {
            invoke(browser, "hideSiteCard", new Class<?>[0]);
            prefs.setReaderConfirm(false);
            browser.findViewById(R.id.browser_site_info).performClick();
            return null;
        });
        awaitReaderJs("!!document.querySelector('.via-reader-body')", "true");
    }

    @Test public void readerExitPreservesOriginalDocumentAndEventHandlers() throws Exception {
        loadReaderFixture();
        js("window.originalBody=document.body;window.originalInput=document.getElementById('reader-input');"
                + "originalInput.value='unsaved edit';window.clicks=0;"
                + "document.getElementById('reader-button').addEventListener('click',function(){window.clicks++});"
                + "window.scrollTo(0,200);window.originalScroll=window.scrollY;");
        main(() -> { prefs.setReaderConfirm(true); invoke(browser, "enterReaderMode", new Class<?>[0]); return null; });
        awaitReaderJs("!!document.querySelector('.via-reader-body')", "true");
        saveParityScreenshot("reader-page.png");
        assertEquals("Menu reader action must not ask again; the original DOM must remain alive", "true",
                js("document.body===originalBody&&originalInput.isConnected&&originalInput.value==='unsaved edit'"));
        assertEquals("Repeated entry must not nest reader overlays", "1", js("document.querySelectorAll('.via-reader-body').length"));
        main(() -> { invoke(browser, "enterReaderMode", new Class<?>[0]); return null; });
        awaitReaderJs("!!document.querySelector('.via-reader-body')", "false");
        assertEquals("Leaving reading mode must restore viewport, overflow and scroll without a reload", "true",
                js("document.body.style.overflow==='auto'&&document.querySelector('meta[name=viewport]').content"
                        + "==='width=device-width,initial-scale=1'&&window.scrollY===originalScroll"));
        assertEquals("Unsaved input and attached listeners must survive reader exit", "true",
                js("document.getElementById('reader-button').click();window.clicks===1&&originalInput.value==='unsaved edit'"));
    }

    @Test public void readerAppearanceUpdatesCurrentDocumentWithoutReextracting() throws Exception {
        loadReaderFixture();
        main(() -> { invoke(browser, "enterReaderMode", new Class<?>[0]); return null; });
        awaitReaderJs("!!document.querySelector('.via-reader-body')", "true");
        js("window.readerNode=document.querySelector('.via-reader-body');readerNode.scrollTop=140;");
        main(() -> {
            prefs.setReaderFont(30);
            prefs.setReaderCss(".via-reader-content{letter-spacing:3px!important}");
            invoke(browser, "applyReturnedSettings", new Class<?>[]{Intent.class}, (Object) null);
            return null;
        });
        awaitReaderJs("getComputedStyle(document.querySelector('.via-reader-body')).fontSize", "\"30px\"");
        assertEquals("Changing appearance must retain the reading position and extracted DOM", "true",
                js("readerNode===document.querySelector('.via-reader-body')&&readerNode.scrollTop===140"));
        assertEquals("Saved CSS uses Via's reader content classes", "\"3px\"",
                js("getComputedStyle(document.querySelector('.via-reader-content')).letterSpacing"));
    }

    @Test public void cosmeticStylesheetResolvesLocallyWithoutDelayingPageCompletion() throws Exception {
        main(() -> {
            prefs.setAdBlockEnabled(true);
            invoke(browser, "applySettings", new Class<?>[]{WebView.class}, web);
            web.loadDataWithBaseURL("https://css-local.parity.test/", "<html><head><title>Local CSS</title></head><body>Local CSS</body></html>",
                    "text/html", "UTF-8", null);
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String state = "";
        do {
            state = js("(function(){var l=document.getElementById('via_inject_css_blocker');return location.href+'|'+document.readyState+'|'+(l?(l.sheet?'loaded':'unloaded:'+l.href):'missing')})()");
            if ("\"https://css-local.parity.test/|complete|loaded\"".equals(state)) break;
            Thread.sleep(30);
        } while (System.nanoTime() < deadline);
        assertEquals("A browser-injected stylesheet must resolve without contacting the nonexistent test host",
                "\"https://css-local.parity.test/|complete|loaded\"", state);
        assertEquals("Via's stable virtual resource URL must match the local CSS interceptor",
                "\"https://css-local.parity.test/via_inject_blocker.css\"", js("document.getElementById('via_inject_css_blocker').href"));
    }

    @Test public void virtualStylesheetUsesItsRequestOriginInsteadOfTheTopLevelPage() throws Exception {
        java.util.Set<String> previous = prefs.cosmeticRuleEntries();
        try {
            prefs.addCosmeticRule("parent-css.parity.test", "#parent-only");
            prefs.addCosmeticRule("frame-css.parity.test", "#frame-only");
            com.example.cleanrecovery.ui.browser.BrowserAdBlocker blocker = new com.example.cleanrecovery.ui.browser.BrowserAdBlocker(prefs);
            blocker.setPageUrl("https://parent-css.parity.test/");
            android.webkit.WebResourceRequest request = new android.webkit.WebResourceRequest() {
                @Override public android.net.Uri getUrl() { return android.net.Uri.parse("https://frame-css.parity.test/via_inject_blocker.css"); }
                @Override public boolean isForMainFrame() { return false; }
                @Override public boolean isRedirect() { return false; }
                @Override public boolean hasGesture() { return false; }
                @Override public String getMethod() { return "GET"; }
                @Override public java.util.Map<String,String> getRequestHeaders() { return java.util.Collections.emptyMap(); }
            };
            android.webkit.WebResourceResponse response = blocker.shouldInterceptRequest(web, request);
            assertNotNull(response);
            String css;
            try (java.io.InputStream input = response.getData(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                css = output.toString("UTF-8");
            }
            assertTrue("The frame must receive its own hiding rules", css.contains("#frame-only"));
            assertFalse("A top-level page's selectors must not leak into another origin", css.contains("#parent-only"));
            assertEquals("Rule edits must not be hidden behind cached virtual CSS", "no-cache", response.getResponseHeaders().get("Cache-Control"));
        } finally { prefs.setCosmeticRuleEntries(previous); }
    }

    @Test public void crossOriginFrameRendersOnlyItsOwnCosmeticRules() throws Exception {
        java.util.Set<String> originalRules = prefs.cosmeticRuleEntries();
        boolean enabled = prefs.adBlockEnabled();
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
            server.setSoTimeout(300);
            String parent = "http://127.0.0.1:" + server.getLocalPort();
            String frame = "http://localhost:" + server.getLocalPort();
            java.util.concurrent.Future<?> serving = worker.submit(() -> {
                while (running.get()) {
                    try (java.net.Socket socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                        String first = reader.readLine(), line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                        boolean child = first != null && first.startsWith("GET /frame ");
                        String elements = "<div id='parent-only'>Parent selector</div><div id='frame-only'>Frame selector</div>";
                        String body = "<html><head><title>Cross-origin CSS</title></head><body>" + elements
                                + (child ? "<script>addEventListener('load',function(){parent.postMessage(getComputedStyle(document.getElementById('parent-only')).display+'|'+getComputedStyle(document.getElementById('frame-only')).display,'" + parent + "')})</script>"
                                : "<script>addEventListener('message',function(e){if(e.origin==='" + frame + "')window.frameCss=e.data})</script><iframe src='" + frame + "/frame'></iframe>")
                                + "</body></html>";
                        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        java.io.OutputStream out = socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        out.write(bytes); out.flush();
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception error) { if (running.get()) throw new RuntimeException(error); }
                }
            });
            try {
                prefs.addCosmeticRule("127.0.0.1", "#parent-only");
                prefs.addCosmeticRule("localhost", "#frame-only");
                main(() -> { prefs.setAdBlockEnabled(true); invoke(browser, "applySettings", new Class<?>[]{WebView.class}, web); web.loadUrl(parent + "/parent"); return null; });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                String rendered;
                do {
                    rendered = js("(function(){if(!window.frameCss)return null;return getComputedStyle(document.getElementById('parent-only')).display+'|'+getComputedStyle(document.getElementById('frame-only')).display+'|'+window.frameCss})()");
                    if (!"null".equals(rendered)) break;
                    Thread.sleep(30);
                } while (System.nanoTime() < deadline);
                assertEquals("Each real document must hide its own selector and leave the other origin's element visible",
                        "\"none|block|block|none\"", rendered);
            } finally { running.set(false); serving.get(5, TimeUnit.SECONDS); }
        } finally {
            running.set(false); worker.shutdownNow(); prefs.setCosmeticRuleEntries(originalRules); prefs.setAdBlockEnabled(enabled);
        }
    }

    private void loadReaderFixture() throws Exception {
        StringBuilder article = new StringBuilder("<html><head><title>Browser reading parity</title>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'></head><body style='overflow:auto'>"
                + "<nav>Navigation</nav><input id='reader-input'><button id='reader-button'>Count</button>"
                + "<article><h1>Browser reading parity</h1>");
        for (int i = 0; i < 16; i++) article.append("<p>This article verifies a browser's reading experience. "
                + "Opening and closing the reader must preserve the original document, its form values, "
                + "scroll position and event listeners. Extracted text should exclude navigation and other "
                + "unrelated interface controls, while leaving them alive in the original page.</p>");
        article.append("</article></body></html>");
        main(() -> {
            web.loadDataWithBaseURL("https://reader.parity.test/", article.toString(), "text/html", "UTF-8", null);
            return null;
        });
        awaitReaderJs("!!document.getElementById('reader-input')", "true");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (main(() -> "阅读模式".contentEquals(browser.findViewById(R.id.browser_site_info).getContentDescription()))) return;
            Thread.sleep(50);
        }
        fail("A readable article must expose Via's address-bar reader entry; document="
                + js("JSON.stringify({url:location.href,ready:document.readyState,text:document.body.innerText.length})")
                + "; icon=" + main(() -> browser.findViewById(R.id.browser_site_info).getContentDescription())
                + "; status=" + main(() -> field(((TabManager) field(browser, "tabs")).current().tag, "readerStatus")));
    }

    @Test public void readerSettingsMatchRangeAndUpdateConfirmationSubtitle() throws Exception {
        prefs.setReaderFont(17);
        prefs.setReaderConfirm(false);
        settings = launch(BrowserSettingsActivity.class);
        main(() -> {
            openSettingsPage("READER");
            SeekBar seek = findViewOfType(settings.getWindow().getDecorView(), SeekBar.class);
            assertNotNull(seek);
            assertEquals("Via's reader slider spans 10 to 30 pixels", 20, seek.getMax());
            assertEquals(7, seek.getProgress());
            return null;
        });
        instrumentation.waitForIdleSync();
        saveParityScreenshot("reader-settings-app.png");
        main(() -> {
            View toggle = findText(settings.getWindow().getDecorView(), "开启阅读模式需要二次确认");
            while (!toggle.isClickable()) toggle = (View) toggle.getParent();
            assertTrue(toggle.performClick());
            assertTrue(prefs.readerConfirm());
            assertNotNull("The subtitle must describe the current click behavior immediately",
                    findText(settings.getWindow().getDecorView(), "当点击地址栏阅读模式图标时，先展示菜单"));
            return null;
        });
    }

    @Test public void readerHintExpiresButSiteMenuKeepsReaderAvailable() throws Exception {
        loadReaderFixture();
        Thread.sleep(3200);
        main(() -> {
            prefs.setReaderConfirm(false);
            View icon = browser.findViewById(R.id.browser_site_info);
            assertEquals("The temporary reading hint must restore the site information action",
                    browser.getString(R.string.via_site_information), icon.getContentDescription());
            icon.performClick();
            assertEquals(View.VISIBLE, browser.findViewById(R.id.browser_site_card).getVisibility());
            assertNotNull("The article remains readable after the temporary hint expires",
                    findText(browser.findViewById(R.id.browser_reader_controls), "打开阅读模式"));
            return null;
        });
        assertEquals("A normal site information click must not unexpectedly enter reading mode", "false",
                js("!!document.querySelector('.via-reader-body')"));
    }

    private void saveParityScreenshot(String name) throws Exception {
        CountDownLatch rendered = new CountDownLatch(1);
        main(() -> {
            View decor = (settings == null ? browser : settings).getWindow().getDecorView();
            Runnable frame = () -> {
                decor.getViewTreeObserver().registerFrameCommitCallback(rendered::countDown);
                decor.invalidate();
            };
            if (settings == null) {
                web.postVisualStateCallback(0, new WebView.VisualStateCallback() {
                    @Override public void onComplete(long requestId) { frame.run(); }
                });
            } else frame.run();
            return null;
        });
        assertTrue("Screenshot must capture a committed frame", rendered.await(10, TimeUnit.SECONDS));
        // The compositor presents a committed app buffer on the following display frame.
        Thread.sleep(200);
        android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(
                instrumentation.getTargetContext().getExternalFilesDir(null), name))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }

    private void awaitReaderJs(String expression, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String actual;
        do {
            actual = js(expression);
            if (expected.equals(actual)) return;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(expression, expected, actual);
    }

    @Test public void automaticSnifferButtonSettingControlsTheRuntimeOverlay() throws Exception {
        main(() -> {
            View button = (View) field(browser, "snifferButton");
            assertNotNull(button);
            button.setVisibility(View.VISIBLE);
            prefs.setAutoSnifferButton(false);
            invoke(browser, "updateSnifferButton", new Class<?>[0]);
            assertEquals("Disabled automatic detection must hide the floating sniffer button",
                    View.GONE, button.getVisibility());
            return null;
        });
    }

    @Test public void returningToHomeHidesTheSnifferAction() throws Exception {
        main(() -> {
            prefs.setAutoSnifferButton(true);
            View button = (View) field(browser, "snifferButton");
            button.setVisibility(View.VISIBLE);
            invoke(browser, "showHome", new Class<?>[0]);
            assertEquals("The home page must never offer resource sniffing", View.GONE,
                    button.getVisibility());
            return null;
        });
    }

    @Test public void sitePanelSettingReturnsToPageAndDoesNotLeakToOtherHost() throws Exception {
        String host = "localhost";
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        String page = "http://localhost:" + server.getLocalPort() + "/";
        String otherPage = "http://127.0.0.1:" + server.getLocalPort() + "/";
        Thread fixture = new Thread(() -> {
            while (!server.isClosed()) {
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    byte[] body = "<html><body>Site scope fixture</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } catch (java.io.IOException error) { if (!server.isClosed()) throw new RuntimeException(error); }
            }
        });
        fixture.setDaemon(true); fixture.start();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(BrowserSiteSettingsActivity.class.getName(), null, false);
        boolean globalJs = prefs.jsEnabled();
        try {
            main(() -> {
                prefs.setJsEnabled(true);
                prefs.resetSiteSettings(host);
                web.loadUrl(page);
                return null;
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!("\"" + page + "|complete\"").equals(js("location.href+'|'+document.readyState")) && System.nanoTime() < until) Thread.sleep(30);
            assertEquals(("\"" + page + "|complete\""), js("location.href+'|'+document.readyState"));
            main(() -> { browser.findViewById(R.id.browser_site_card_settings).performClick(); return null; });
            settings = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull(settings);
            main(() -> {
                View label = findText(settings.getWindow().getDecorView(), "启用 \"" + host + "\" 的网站设定");
                assertNotNull(label);
                ((android.widget.Switch) ((ViewGroup) label.getParent()).getChildAt(1)).performClick();
                View row = findText(settings.getWindow().getDecorView(), "JavaScript");
                while (!row.isClickable()) row = (View) row.getParent();
                row.performClick(); return null;
            });
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            android.view.accessibility.AccessibilityNodeInfo root = null;
            while (System.nanoTime() < until) {
                root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root != null && !root.findAccessibilityNodeInfosByText("禁止").isEmpty()) break;
                Thread.sleep(30);
            }
            assertNotNull(root);
            android.view.accessibility.AccessibilityNodeInfo choice = root.findAccessibilityNodeInfosByText("禁止").get(0);
            while (!choice.isClickable()) choice = choice.getParent();
            assertTrue(choice.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
            instrumentation.waitForIdleSync();
            assertEquals(0, prefs.siteJsMode(host));
            main(() -> { settings.onBackPressed(); return null; });
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (main(() -> web.getSettings().getJavaScriptEnabled()) && System.nanoTime() < until) Thread.sleep(30);
            assertFalse("Returning from site settings must apply the chosen policy", main(() -> web.getSettings().getJavaScriptEnabled()));
            main(() -> { web.loadUrl(otherPage); return null; });
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!main(() -> otherPage.equals(web.getUrl())) || !main(() -> web.getSettings().getJavaScriptEnabled())) && System.nanoTime() < until) Thread.sleep(30);
            assertTrue("Another host must follow the unchanged global JavaScript setting", main(() -> web.getSettings().getJavaScriptEnabled()));
        } finally {
            server.close();
            instrumentation.removeMonitor(monitor);
            prefs.resetSiteSettings(host);
            prefs.setJsEnabled(globalJs);
        }
    }

    @Test public void editingDisabledScriptNamePreservesItsStateAndDeletionRemovesIt() throws Exception {
        String oldName = "Rename disabled fixture", newName = "Renamed disabled fixture";
        prefs.saveScript(oldName, "*", "window.renameFixture=true;");
        prefs.setScriptEnabled(oldName, false);
        try {
            settings = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS, true)
                    .putExtra(BrowserSettingsActivity.EXTRA_EDIT_SCRIPT, oldName));
            main(() -> {
                View root = settings.getWindow().getDecorView();
                ((EditText) findText(root, oldName)).setText(newName);
                findText(root, "保存").performClick(); return null;
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!prefs.scriptNames().contains(newName) && System.nanoTime() < until) Thread.sleep(30);
            assertTrue(prefs.scriptNames().contains(newName));
            assertFalse("Renaming must not activate a disabled script", prefs.isScriptEnabled(newName));
            assertFalse("Rename must remove the old script identity", prefs.scriptNames().contains(oldName));
            prefs.removeScript(newName);
            prefs.saveScript(newName, "*", "window.newInstall=true;");
            assertTrue("Fresh installation must not inherit a deleted script's disabled flag", prefs.isScriptEnabled(newName));
        } finally { prefs.removeScript(oldName); prefs.removeScript(newName); }
    }

    @Test public void importedRequireExecutesBeforeTheMainScript() throws Exception {
        String name = "Import dependency fixture";
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        String base = "http://127.0.0.1:" + server.getLocalPort();
        Thread fixture = new Thread(() -> {
            while (!server.isClosed()) {
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    String first = reader.readLine(), line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    byte[] body = (first.contains("/dependency.js") ? "window.importDependency='loaded';" : "<html><body>Dependency fixture</body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body); socket.getOutputStream().flush();
                } catch (java.io.IOException error) { if (!server.isClosed()) throw new RuntimeException(error); }
            }
        });
        fixture.setDaemon(true); fixture.start();
        try {
            settings = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS, true));
            String source = "// ==UserScript==\n// @name " + name + "\n// @match " + base + "/*\n// @require " + base + "/dependency.js\n// ==/UserScript==\nwindow.importResult=window.importDependency+' then main';";
            main(() -> { invoke(settings, "importUserScript", new Class<?>[]{String.class}, source); return null; });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!prefs.scriptNames().contains(name) && System.nanoTime() < until) Thread.sleep(30);
            assertTrue("Import must finish resolving and storing the required script", prefs.scriptNames().contains(name));
            assertTrue(prefs.scriptCode(name).contains("window.importDependency='loaded'"));
            main(() -> {
                settings.finish();
                invoke(browser, "applySettings", new Class<?>[]{WebView.class}, web);
                web.loadUrl(base + "/page"); return null;
            });
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"\"loaded then main\"".equals(js("window.importResult")) && System.nanoTime() < until) Thread.sleep(30);
            assertEquals("Saved @require code must execute before the automatically injected main script", "\"loaded then main\"", js("window.importResult"));
        } finally { server.close(); prefs.removeScript(name); }
    }

    @Test public void userscriptPhasesFollowDomReadinessBeforeSlowImageFinishes() throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/";
        CountDownLatch releaseImage = new CountDownLatch(1);
        java.util.concurrent.ExecutorService requests = java.util.concurrent.Executors.newCachedThreadPool();
        requests.submit(() -> {
            while (!server.isClosed()) {
                try {
                    java.net.Socket socket = server.accept();
                    requests.submit(() -> {
                        try (java.net.Socket owned = socket) {
                            owned.setSoTimeout(5000);
                            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(owned.getInputStream()));
                            String first = reader.readLine(), line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                            boolean image = first != null && first.contains("/slow");
                            if (image) releaseImage.await(12, TimeUnit.SECONDS);
                            byte[] body = image ? new byte[0] : ("<html><head><script>window.pageSawStart=window.phaseStart===true</script></head><body><img src='/slow'></body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            owned.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: " + (image ? "image/png" : "text/html") + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                            owned.getOutputStream().write(body); owned.getOutputStream().flush();
                        } catch (Exception error) { throw new RuntimeException(error); }
                    });
                } catch (java.io.IOException error) { if (!server.isClosed()) throw new RuntimeException(error); }
            }
        });
        String[] phases = {"document-start", "document-end", "document-idle"};
        String[] bodies = {"window.phaseStart=true;", "window.phaseEnd=document.readyState;window.phaseEndCount=(window.phaseEndCount||0)+1;", "window.phaseIdle=document.readyState;"};
        try {
            main(() -> {
                for (int i = 0; i < phases.length; i++) {
                    prefs.saveScript("Phase fixture " + phases[i], url + "*", "// ==UserScript==\n// @run-at " + phases[i] + "\n// ==/UserScript==\n" + bodies[i]);
                    prefs.setScriptEnabled("Phase fixture " + phases[i], true);
                }
                invoke(browser, "applySettings", new Class<?>[]{WebView.class}, web);
                web.loadUrl(url); return null;
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"\"interactive\"".equals(js("window.phaseEnd")) && System.nanoTime() < until) Thread.sleep(30);
            assertEquals("document-end must execute while a page resource is still pending", "\"interactive\"", js("window.phaseEnd"));
            assertEquals("document-start must precede the page's own head script", "true", js("window.pageSawStart"));
            assertEquals("document-idle must wait for the load stage", "null", js("window.phaseIdle||null"));
            releaseImage.countDown();
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"\"complete\"".equals(js("window.phaseIdle")) && System.nanoTime() < until) Thread.sleep(30);
            assertEquals("document-idle must run when the pending resource finishes", "\"complete\"", js("window.phaseIdle"));
            assertEquals("onPageFinished must not execute document-end a second time", "1", js("window.phaseEndCount"));
        } finally {
            releaseImage.countDown(); server.close(); requests.shutdownNow();
            for (String phase : phases) prefs.removeScript("Phase fixture " + phase);
        }
    }

    @Test public void scriptPanelToggleActuallyExecutesAndStopsTheScript() throws Exception {
        String name = "Panel runtime fixture";
        java.net.ServerSocket server = new java.net.ServerSocket(0);
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/";
        Thread fixture = new Thread(() -> {
            while (!server.isClosed()) {
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    byte[] body = "<html><body>Script runtime</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body); socket.getOutputStream().flush();
                } catch (java.io.IOException error) { if (!server.isClosed()) throw new RuntimeException(error); }
            }
        });
        fixture.setDaemon(true); fixture.start();
        Dialog panel = null;
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(BrowserSettingsActivity.class.getName(), null, false);
        try {
            main(() -> {
                prefs.saveScript(name, url + "*", "window.panelScriptValue='first';");
                prefs.setScriptEnabled(name, false);
                invoke(browser, "applySettings", new Class<?>[]{WebView.class}, web);
                web.loadUrl(url); return null;
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String ready = "\"" + url + "|complete\"";
            while (!ready.equals(js("location.href+'|'+document.readyState")) && System.nanoTime() < until) Thread.sleep(30);
            assertEquals(ready, js("location.href+'|'+document.readyState"));
            panel = main(() -> (Dialog) invoke(browser, "showPageScripts", new Class<?>[0]));
            Dialog shown = panel;
            for (boolean enabled : new boolean[]{true, false, true}) {
                main(() -> {
                    View label = findText(shown.getWindow().getDecorView(), name);
                    assertNotNull("Disabled matching scripts must remain available to re-enable", label);
                    ((android.widget.Switch) ((ViewGroup) label.getParent()).getChildAt(1)).performClick(); return null;
                });
                String expected = enabled ? "\"first\"" : "null";
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!expected.equals(js("window.panelScriptValue||null")) && System.nanoTime() < until) Thread.sleep(30);
                assertEquals("Panel toggle must change real automatic page execution", expected, js("window.panelScriptValue||null"));
                assertEquals(enabled, new BrowserPrefs(browser).isScriptEnabled(name));
            }
            main(() -> { findText(shown.getWindow().getDecorView(), name).performClick(); return null; });
            settings = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull("Clicking a script must open its editable configuration", settings);
            instrumentation.waitForIdleSync();
            main(() -> {
                View root = settings.getWindow().getDecorView();
                assertNotNull(findText(root, "编辑脚本"));
                EditText code = (EditText) findText(root, "window.panelScriptValue='first';");
                assertNotNull(code); code.setText("window.panelScriptValue='edited';");
                findText(root, "保存").performClick(); return null;
            });
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!prefs.scriptCode(name).contains("edited") && System.nanoTime() < until) Thread.sleep(30);
            assertTrue(prefs.scriptCode(name).contains("edited"));
            instrumentation.waitForIdleSync();
            main(() -> { settings.onBackPressed(); return null; });
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"\"edited\"".equals(js("window.panelScriptValue||null")) && System.nanoTime() < until) Thread.sleep(30);
            assertEquals("Returning after save must automatically execute the updated script", "\"edited\"", js("window.panelScriptValue||null"));
        } finally {
            server.close(); instrumentation.removeMonitor(monitor);
            Dialog last = panel;
            main(() -> { if (last != null) last.dismiss(); prefs.removeScript(name); prefs.setScriptEnabled(name, true); return null; });
        }
    }

    @Test public void valuelessMetadataDoesNotSwallowTheFollowingMatchRule() throws Exception {
        for (String newline : new String[]{"\n", "\r\n"}) {
            String code = String.join(newline, "// ==UserScript==", "// @name Metadata fixture", "// @noframes",
                    "// @match https://metadata.parity.test/*", "// @grant", "// @exclude https://metadata.parity.test/private/*",
                    "// @run-at document-end", "// ==/UserScript==", "window.metadataRan=true;");
            com.example.cleanrecovery.ui.browser.BrowserUserScripts.Metadata metadata = com.example.cleanrecovery.ui.browser.BrowserUserScripts.parse(code);
            assertEquals("A valueless marker must not consume the next URL rule", java.util.Collections.singletonList("https://metadata.parity.test/*"), metadata.matches);
            assertEquals(java.util.Collections.singletonList("https://metadata.parity.test/private/*"), metadata.excludes);
            for (String url : new String[]{"https://metadata.parity.test/page", "https://metadata.parity.test/private/page", "https://other-metadata.parity.test/page"}) {
                boolean expected = url.equals("https://metadata.parity.test/page");
                assertEquals("Missing metadata must never broaden a declared match to every site", expected,
                        com.example.cleanrecovery.ui.browser.BrowserUserScripts.applies(code, "*", url));
                main(() -> { web.loadDataWithBaseURL(url, "<html><body>Metadata</body></html>", "text/html", "UTF-8", null); return null; });
                String ready = "\"" + url + "|complete\"";
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!ready.equals(js("location.href+'|'+document.readyState")) && System.nanoTime() < until) Thread.sleep(30);
                assertEquals(ready, js("location.href+'|'+document.readyState"));
                js(com.example.cleanrecovery.ui.browser.BrowserUserScripts.wrap(code, "*"));
                assertEquals("The runtime URL guard must retain both include and exclude rules", String.valueOf(expected), js("window.metadataRan===true"));
            }
        }
    }

    @Test public void userscriptWildcardIncludesRootDomainInSelectionAndExecution() throws Exception {
        String code = "// ==UserScript==\n// @name Root match fixture\n// @match *://*.script.parity.test/*\n// @exclude *://*.script.parity.test/private/*\n// ==/UserScript==\nwindow.scriptMatchExecuted=true;";
        String[] urls = {"https://script.parity.test/page", "http://sub.script.parity.test/page", "https://script.parity.test/private/page", "https://unrelated.parity.test/page"};
        for (int i = 0; i < urls.length; i++) {
            String url = urls[i];
            boolean expected = i < 2;
            assertEquals("Selection must include the root and subdomains while respecting exclusions", expected,
                    com.example.cleanrecovery.ui.browser.BrowserUserScripts.applies(code, "*", url));
            main(() -> { web.loadDataWithBaseURL(url, "<html><body>Match fixture</body></html>", "text/html", "UTF-8", null); return null; });
            String ready = "\"" + url + "|complete\"";
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!ready.equals(js("location.href+'|'+document.readyState")) && System.nanoTime() < until) Thread.sleep(30);
            assertEquals(ready, js("location.href+'|'+document.readyState"));
            js(com.example.cleanrecovery.ui.browser.BrowserUserScripts.wrap(code, "*"));
            assertEquals("WebView execution guard must agree with Java selection", String.valueOf(expected), js("window.scriptMatchExecuted===true"));
        }
    }

    @Test public void siteSettingsReachTheCurrentWebView() throws Exception {
        main(() -> {
            String host = "site-runtime.parity.test";
            TabManager.Tab tab = ((TabManager) field(browser, "tabs")).current();
            tab.url = "https://" + host + "/page";
            prefs.setSiteSettingsEnabled(host, true);
            prefs.setSiteDesktopMode(host, 1);
            prefs.setSiteJsMode(host, 0);
            prefs.setSiteImagesMode(host, 0);
            prefs.setSiteTextZoom(host, 85);
            invoke(browser, "applyReturnedSettings", new Class<?>[]{Intent.class}, new Intent());
            assertTrue("Per-site desktop mode must enable a wide viewport",
                    web.getSettings().getUseWideViewPort());
            assertTrue("Per-site desktop mode must use a desktop UA",
                    web.getSettings().getUserAgentString().contains("Windows NT"));
            assertFalse("Per-site JavaScript blocking must reach WebSettings",
                    web.getSettings().getJavaScriptEnabled());
            assertTrue("Per-site image blocking must reach WebSettings",
                    web.getSettings().getBlockNetworkImage());
            assertEquals("Per-site font size must reach WebSettings", 85,
                    web.getSettings().getTextZoom());
            prefs.setSiteRedirectMode(host, 1);
            prefs.resetSiteSettings(host);
            assertEquals("Reset must also clear the site's redirect override", -1,
                    prefs.siteRedirectMode(host));
            return null;
        });
    }

    @Test public void fontSizeUsesViasSliderAndSiteHint() throws Exception {
        main(() -> {
            Dialog dialog = (Dialog) invoke(browser, "showSiteFontSizeDialog",
                    new Class<?>[]{String.class}, "site-runtime.parity.test");
            assertNotNull("The font-size action must return its visible Via dialog", dialog);
            assertTrue(dialog.isShowing());
            SeekBar seek = findViewOfType(dialog.getWindow().getDecorView(), SeekBar.class);
            assertNotNull("Via font size uses a continuous slider", seek);
            assertNotNull("Per-site font size must explain its scope",
                    findText(dialog.getWindow().getDecorView(), "仅对当前网站生效"));
            seek.setProgress(7);
            assertEquals("Slider changes must persist for the current site", 85,
                    prefs.siteTextZoom("site-runtime.parity.test", -1));
            assertEquals("Slider changes must immediately reach the current WebView", 85,
                    web.getSettings().getTextZoom());
            dialog.dismiss();
            return null;
        });
    }

    @Test public void homeShortcutTitleHasAViaSizedEllipsisBoundary() throws Exception {
        main(() -> {
            String title = "A deliberately long homepage shortcut title that must not fill the row";
            prefs.dbHelper().addQuickLink(title, "https://home-title.parity.test/");
            invoke(browser, "renderHomeGrid", new Class<?>[0]);
            TextView label = (TextView) findText(
                    browser.findViewById(R.id.browser_home_grid), title);
            assertNotNull(label);
            assertTrue("A homepage shortcut title needs a fixed compact ellipsis boundary",
                    label.getLayoutParams().width > 0
                            && label.getLayoutParams().width <= dp(browser, 96));
            return null;
        });
    }

    private static void swipe(View target, float fromX, float fromY, float toX, float toY) {
        long now = android.os.SystemClock.uptimeMillis();
        target.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, fromX, fromY, 0));
        target.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_MOVE, toX, toY, 0));
        target.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now + 100, android.view.MotionEvent.ACTION_UP, toX, toY, 0));
    }

    private Activity launch(Class<? extends Activity> type) {
        Intent intent = new Intent(instrumentation.getTargetContext(), type);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return instrumentation.startActivitySync(intent);
    }

    private static EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findInput(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> T findViewOfType(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findViewOfType(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static EditText findInputByHint(View view, String hint) {
        if (view instanceof EditText && hint.contentEquals(((EditText) view).getHint())) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findInputByHint(group.getChildAt(i), hint);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> owner = target.getClass();
        Method method;
        while (true) {
            try { method = owner.getDeclaredMethod(name, types); break; }
            catch (NoSuchMethodException e) {
                owner = owner.getSuperclass();
                if (owner == null) throw e;
            }
        }
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private String js(String script) throws Exception {
        return js(web, script);
    }

    private String js(WebView target, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        main(() -> { target.evaluateJavascript(script, value -> { result.set(value); done.countDown(); }); return null; });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    private <T> T main(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        instrumentation.runOnMainSync(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
