package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/** Applies browser privacy preferences to explicit navigation and new documents. */
public final class BrowserWebView extends WebView {
    private final BrowserPrefs prefs;
    private final BrowserScriptValues scriptValues;
    private final BrowserScriptRequests scriptRequests;
    private final BrowserScriptMenus scriptMenus = new BrowserScriptMenus();
    private final BrowserClipboardPolicy clipboardPolicy;
    private ScriptHandler privacyScript;
    private String installedScript;
    private String installedUserScripts = "";
    private java.util.function.Consumer<String> beforeNavigation;
    private String navigationReferer;
    private int forwardHistoryBudget = Integer.MAX_VALUE;
    private final List<ScriptHandler> userScriptHandlers = new ArrayList<>();

    public BrowserWebView(Context context) {
        super(context);
        prefs = new BrowserPrefs(context);
        scriptValues = new BrowserScriptValues(context);
        addJavascriptInterface(scriptValues, BrowserScriptValues.CHANNEL);
        scriptRequests = new BrowserScriptRequests(this, scriptValues);
        addJavascriptInterface(scriptRequests, BrowserScriptRequests.CHANNEL);
        clipboardPolicy = new BrowserClipboardPolicy(context);
        BrowserDataCleaner.register(this);
    }

    @Override public void destroy() {
        beforeNavigation = null;
        BrowserDataCleaner.unregister(this);
        if (privacyScript != null) privacyScript.remove();
        for (ScriptHandler handler : userScriptHandlers) handler.remove();
        scriptRequests.close();
        removeJavascriptInterface(BrowserScriptRequests.CHANNEL);
        removeJavascriptInterface(BrowserScriptValues.CHANNEL);
        super.destroy();
    }

    @Override public void loadUrl(String url) {
        loadUrl(url, Collections.emptyMap());
    }

    @Override public void loadUrl(String url, Map<String, String> headers) {
        prepareNavigation(url);
        Map<String, String> requestHeaders = headers == null ? new HashMap<>() : new HashMap<>(headers);
        if (android.webkit.URLUtil.isNetworkUrl(url)) {
            navigationReferer = null;
            for (Map.Entry<String, String> header : requestHeaders.entrySet())
                if ("Referer".equalsIgnoreCase(header.getKey()) && android.webkit.URLUtil.isNetworkUrl(header.getValue()))
                    navigationReferer = header.getValue();
        }
        if (prefs.doNotTrack()) requestHeaders.put("DNT", "1");
        if (prefs.dataSaver()) requestHeaders.put("Save-Data", "on");
        if (prefs.doNotSell()) requestHeaders.put("Sec-GPC", "1");
        super.loadUrl(url, requestHeaders);
    }

    public void setBeforeNavigation(java.util.function.Consumer<String> action) {
        beforeNavigation = action;
    }

    public String getNavigationReferer() { return navigationReferer; }

    private void prepareNavigation(String url) {
        if (beforeNavigation != null && url != null && !url.regionMatches(true, 0, "javascript:", 0, 11)) {
            beforeNavigation.accept(url);
        }
    }

    @Override public void reload() {
        prepareNavigation(getUrl());
        super.reload();
    }

    /** A retained page must not forward into the branch replaced by a new page. */
    public void truncateForwardHistory() { forwardHistoryBudget = 0; }

    public void allowNativeForwardHistory() { forwardHistoryBudget = Integer.MAX_VALUE; }

    @Override public boolean canGoForward() {
        return forwardHistoryBudget > 0 && super.canGoForward();
    }

    @Override public void goBack() {
        if (forwardHistoryBudget != Integer.MAX_VALUE && canGoBack()) forwardHistoryBudget++;
        super.goBack();
    }

    @Override public void goForward() {
        if (!canGoForward()) return;
        if (forwardHistoryBudget != Integer.MAX_VALUE) forwardHistoryBudget--;
        super.goForward();
    }

    public void applyPrivacySettings() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        org.json.JSONObject vibration = new org.json.JSONObject();
        org.json.JSONObject clipboard = new org.json.JSONObject();
        try {
            java.util.Set<String> vibrationHosts = prefs.permissionExceptionHosts("vibration");
            java.util.Set<String> clipboardHosts = prefs.permissionExceptionHosts("clipboard");
            clipboardHosts.addAll(prefs.siteEnabledHosts());
            for (String host : vibrationHosts) vibration.put(host, prefs.permission("vibration", host));
            for (String host : clipboardHosts) clipboard.put(host, prefs.permission("clipboard", host));
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        String script = "(function(){"
                + (prefs.disableWebRtc()
                ? "delete window.RTCPeerConnection;delete window.webkitRTCPeerConnection;delete window.mozRTCPeerConnection;" : "")
                + (prefs.doNotTrack()
                ? "Object.defineProperty(Navigator.prototype,'doNotTrack',{get:function(){return '1'},configurable:true});" : "")
                + (prefs.doNotSell()
                ? "Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:function(){return true},configurable:true});" : "")
                + "var vib=" + vibration + ",clip=" + clipboard + ";"
                + "if((vib[location.host]||" + org.json.JSONObject.quote(prefs.permission("vibration"))
                + ")==='block')Navigator.prototype.vibrate=function(){return true};"
                + clipboardPolicy.script(getContext(), "(clip[location.host]||"
                        + org.json.JSONObject.quote(prefs.permission("clipboard")) + ")")
                + "})();" + (prefs.adBlockEnabled() ? BrowserAdBlocker.cosmeticScript() : "");
        if (script.equals(installedScript)) return;
        if (privacyScript != null) privacyScript.remove();
        privacyScript = WebViewCompat.addDocumentStartJavaScript(this, script, Collections.singleton("*"));
        installedScript = script;
    }

    public BrowserScriptMenus scriptMenus() { return scriptMenus; }

    public String wrapUserScript(String name, String code, String rules) {
        return BrowserUserScripts.wrap(code, rules, scriptValues.script(name) + scriptMenus.script(name));
    }

    public void applyUserScripts() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        StringBuilder signature = new StringBuilder();
        List<String> scripts = new ArrayList<>();
        signature.append(prefs.scriptsEnabled());
        if (prefs.scriptsEnabled()) for (String name : prefs.scriptNames()) {
            String code = prefs.scriptExecutionCode(name);
            if (!prefs.isScriptEnabled(name)) continue;
            signature.append(name).append('\u0000').append(code).append('\u0000').append(prefs.scriptMatch(name));
            scripts.add(BrowserUserScripts.documentScript(code, prefs.scriptMatch(name), scriptValues.script(name) + scriptMenus.script(name)));
        }
        if (signature.toString().equals(installedUserScripts)) return;
        for (ScriptHandler handler : userScriptHandlers) handler.remove();
        userScriptHandlers.clear();
        for (String script : scripts) userScriptHandlers.add(WebViewCompat.addDocumentStartJavaScript(
                this, script, Collections.singleton("*")));
        installedUserScripts = signature.toString();
    }

}
