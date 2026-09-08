package com.example.cleanrecovery.ui.browser;

import android.text.TextUtils;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Userscript metadata parsing, URL matching and update retrieval. */
public final class BrowserUserScripts {
    private static final Pattern META = Pattern.compile("(?m)^[ \\t]*//[ \\t]*@([\\w-]+)[ \\t]+([^\\r\\n]+?)[ \\t]*\\r?$");
    private BrowserUserScripts() { }

    public static Metadata parse(String code) {
        String name = "";
        String version = "";
        List<String> matches = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        List<String> requires = new ArrayList<>();
        List<String> grants = new ArrayList<>();
        String update = "";
        String runAt = "document-end";
        String source = code == null ? "" : code;
        int header = source.indexOf("// ==UserScript==");
        int footer = source.indexOf("// ==/UserScript==", Math.max(0, header));
        if (header >= 0 && footer > header) source = source.substring(header, footer);
        Matcher matcher = META.matcher(source);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2).trim();
            if ("name".equalsIgnoreCase(key) && name.isEmpty()) name = value;
            else if ("version".equalsIgnoreCase(key) && version.isEmpty()) version = value;
            else if ("match".equalsIgnoreCase(key) || "include".equalsIgnoreCase(key)) matches.add(value);
            else if ("exclude-match".equalsIgnoreCase(key) || "exclude".equalsIgnoreCase(key)) excludes.add(value);
            else if ("require".equalsIgnoreCase(key)) requires.add(value);
            else if ("grant".equalsIgnoreCase(key)) grants.add(value);
            else if ("run-at".equalsIgnoreCase(key)) runAt = value;
            else if (("updateURL".equalsIgnoreCase(key) || "downloadURL".equalsIgnoreCase(key)) && update.isEmpty()) update = value;
        }
        return new Metadata(name, version, matches, excludes, requires, update, runAt, grants);
    }

    /** Apply user scope only to the execution copy, leaving installed source untouched. */
    public static String withMatchRules(String code, String rules) {
        return withMetadataRules(code, "match|include", "match", TextUtils.isEmpty(rules) ? "*" : rules);
    }

    public static String withMetadataRules(String code, String keys, String key, String rules) {
        String source = code == null ? "" : code;
        int header = source.indexOf("// ==UserScript==");
        int footer = source.indexOf("// ==/UserScript==", Math.max(0, header));
        StringBuilder lines = new StringBuilder();
        for (String rule : (rules == null ? "" : rules).split("[\\n,]+"))
            if (!rule.trim().isEmpty()) lines.append("// @").append(key).append(" ").append(rule.trim()).append('\n');
        String pattern = "(?m)^[ \\t]*//[ \\t]*@(" + keys + ")[ \\t]+[^\\r\\n]*(?:\\r?\\n|$)";
        if (header >= 0 && footer > header) return source.substring(0, header)
                + source.substring(header, footer).replaceAll(pattern, "") + lines + source.substring(footer);
        return lines + source.replaceAll(pattern, "");
    }

    public static boolean matches(String rules, String url) {
        if (TextUtils.isEmpty(url)) return false;
        if (TextUtils.isEmpty(rules) || "*".equals(rules.trim()) || "<all_urls>".equals(rules.trim())) return true;
        for (String rule : rules.split("[\\n,]+")) {
            String trimmed = rule.trim();
            if (trimmed.isEmpty()) continue;
            if (url.matches(wildcardRegex(trimmed))) return true;
        }
        return false;
    }

    public static boolean applies(String code, String fallbackRules, String url) {
        Metadata metadata = parse(code);
        for (String exclude : metadata.excludes) if (matches(exclude, url)) return false;
        return matches(metadata.matches.isEmpty() ? fallbackRules : TextUtils.join("\n", metadata.matches), url);
    }

    public static String download(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", "Via");
        try (java.io.InputStream input = connection.getInputStream()) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) >= 0) if (n > 0) output.write(buffer, 0, n);
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }

    /** Resolves @require dependencies once when a script is installed or updated. */
    public static String resolveRequires(String code) throws Exception {
        String source = code == null ? "" : code.replaceAll(
                "(?s)// Via @require begin\\n.*?// Via @require end\\n", "");
        Metadata metadata = parse(source);
        if (metadata.requires.isEmpty()) return source;
        StringBuilder resolved = new StringBuilder("// Via @require begin\n");
        for (String require : metadata.requires) {
            resolved.append("// Via resolved @require ").append(require).append('\n')
                    .append(download(require)).append("\n;");
        }
        resolved.append("// Via @require end\n");
        int footer = source.indexOf("// ==/UserScript==");
        if (footer < 0) return resolved.append(source).toString();
        int insert = source.indexOf('\n', footer);
        if (insert < 0) insert = source.length(); else insert++;
        return source.substring(0, insert) + resolved + source.substring(insert);
    }

    public static String runAt(String code) { return parse(code).runAt; }

    /** Register lifecycle callbacks before page scripts; Via j6.r uses head, DOMContentLoaded, load. */
    public static String documentScript(String code, String fallbackRules) {
        return documentScript(code, fallbackRules, null);
    }

    public static String documentScript(String code, String fallbackRules, String storage) {
        String run = "function run(){" + wrap(code, fallbackRules, storage) + "}";
        switch (runAt(code).toLowerCase(java.util.Locale.ROOT)) {
            case "document-start":
                return "(function(){" + run + "if(document.head)run();else{var observer=new MutationObserver(function(){if(document.head){observer.disconnect();run()}});observer.observe(document.documentElement||document,{childList:true,subtree:true})}})();";
            case "document-end":
                return "(function(){" + run + "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run,{once:true});else run()})();";
            case "document-idle":
                return "(function(){" + run + "if(document.readyState!=='complete')window.addEventListener('load',run,{once:true});else run()})();";
            default: return "";
        }
    }

    /** Isolated userscript wrapper with Via/Greasemonkey-compatible value/style helpers and URL guards. */
    public static String wrap(String code, String fallbackRules) {
        return wrap(code, fallbackRules, null);
    }

    public static String wrap(String code, String fallbackRules, String storage) {
        Metadata metadata = parse(code);
        String includes = metadata.matches.isEmpty() ? fallbackRules : TextUtils.join("\n", metadata.matches);
        StringBuilder allow = new StringBuilder();
        for (String rule : (TextUtils.isEmpty(includes) ? "*" : includes).split("[\\n,]+")) {
            if (rule.trim().isEmpty()) continue;
            if (allow.length() > 0) allow.append("||");
            allow.append("new RegExp(").append(org.json.JSONObject.quote(wildcardRegex(rule.trim())))
                    .append(").test(location.href)");
        }
        StringBuilder deny = new StringBuilder();
        for (String rule : metadata.excludes) {
            if (deny.length() > 0) deny.append("||");
            deny.append("new RegExp(").append(org.json.JSONObject.quote(wildcardRegex(rule)))
                    .append(").test(location.href)");
        }
        String namespace = "__via_gm_" + Integer.toHexString((metadata.name + code).hashCode()) + "_";
        return "(function(){try{if(!(" + (allow.length() == 0 ? "true" : allow) + "))return;"
                + (deny.length() == 0 ? "" : "if(" + deny + ")return;")
                + "var __p=" + org.json.JSONObject.quote(namespace) + ";"
                + "var GM_registerMenuCommand,GM_unregisterMenuCommand;"
                + (storage != null ? storage : "var GM_getValue=function(k,d){try{var v=localStorage.getItem(__p+k);return v===null?d:JSON.parse(v)}catch(e){return d}};"
                + "var GM_setValue=function(k,v){localStorage.setItem(__p+k,JSON.stringify(v))};"
                + "var GM_deleteValue=function(k){localStorage.removeItem(__p+k)};"
                + "var GM_listValues=function(){var a=[];for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k&&k.indexOf(__p)===0)a.push(k.slice(__p.length))}return a};")
                + "var GM_getValues=function(a){var o={};if(Array.isArray(a))a.forEach(function(k){o[k]=GM_getValue(k)});else if(a)Object.keys(a).forEach(function(k){o[k]=GM_getValue(k,a[k])});return o};"
                + "var GM_setValues=function(o){if(o)Object.keys(o).forEach(function(k){GM_setValue(k,o[k])})};"
                + "var GM_deleteValues=function(a){if(a)a.forEach(GM_deleteValue)};"
                + "var GM_addStyle=function(c){var s=document.createElement('style');s.textContent=c;(document.head||document.documentElement).appendChild(s);return s};"
                + "var GM_addElement=function(p,t,a){if(typeof p==='string'){a=t;t=p;p=document.head||document.documentElement}var e=document.createElement(t);Object.keys(a||{}).forEach(function(k){if(k==='textContent')e.textContent=a[k];else e.setAttribute(k,a[k])});(p||document.head||document.documentElement).appendChild(e);return e};"
                + "var GM_openInTab=function(u){return window.open(u,'_blank')};"
                + "var GM_setClipboard=function(v){return navigator.clipboard&&navigator.clipboard.writeText(String(v))};"
                + "var GM_download=function(d,n){var u=typeof d==='string'?d:d.url,nm=n||(d&&d.name);var a=document.createElement('a');a.href=u;a.download=nm||'';a.click()};"
                + "var GM_notification=function(d){if(typeof d==='string')d={text:d};if(window.Notification&&Notification.permission==='granted')return new Notification(d.title||'',{body:d.text||d.body||''})};"
                + "var GM_xmlhttpRequest=function(d){var c=new AbortController(),m=d.method||'GET';fetch(d.url,{method:m,headers:d.headers||{},body:/^(GET|HEAD)$/i.test(m)?undefined:d.data,signal:c.signal,credentials:d.anonymous?'omit':'include'}).then(function(r){return r.text().then(function(t){var x={readyState:4,status:r.status,statusText:r.statusText,responseHeaders:Array.from(r.headers.entries()).map(function(h){return h[0]+': '+h[1]}).join('\\r\\n'),responseText:t,response:t,finalUrl:r.url};if(d.onload)d.onload(x);if(d.onloadend)d.onloadend(x)})}).catch(function(e){if(d.onerror)d.onerror({readyState:4,status:0,error:String(e)});if(d.onloadend)d.onloadend({readyState:4,status:0,error:String(e)})});return{abort:function(){c.abort()}}};"
                + "if(typeof __token!=='undefined'&&window.ViaScriptRequests)GM_xmlhttpRequest=function(d){var key='__via_req_'+Date.now()+'_'+Math.random().toString(36).slice(2),opts=Object.assign({},d);opts.url=new URL(d.url,location.href).href;opts.v_ua=navigator.userAgent;if(d.data instanceof FormData||d.data instanceof URLSearchParams)opts.data=new URLSearchParams(d.data).toString();else if(Array.isArray(d.data))opts.data=d.data.join(',');"
                + "window[key]=function(event,r){if(event==='uploadprogress'){if(d.upload&&d.upload.onprogress)d.upload.onprogress(r);return;}if(event==='progress'){if(d.onprogress)d.onprogress(r);return;}if(event==='readystatechange'||event==='loadstart'){if(d.onreadystatechange)d.onreadystatechange(r);if(event==='loadstart'&&d.onloadstart)d.onloadstart(r);return;}delete window[key];try{if(event==='load'){if(d.responseType==='json'){try{r.response=JSON.parse(r.responseText)}catch(e){r.response=null}}else if(d.responseType==='arraybuffer'||d.responseType==='blob'){var b=Uint8Array.from(atob(r.responseBase64),function(c){return c.charCodeAt(0)});r.response=d.responseType==='blob'?new Blob([b],{type:r.contentType}):b.buffer}else if(d.responseType==='document')r.response=new DOMParser().parseFromString(r.responseText,'text/html');else r.response=r.responseText;}delete r.responseBase64;if(d.onreadystatechange)d.onreadystatechange(r);if(d['on'+event])d['on'+event](r)}finally{if(d.onloadend)d.onloadend(r)}};"
                + "var channel=window.ViaScriptRequestMessages;if(channel){channel.onmessage=function(e){var m=JSON.parse(e.data),f=window[m.callback];if(typeof f==='function')f(m.event,m.response)};channel.postMessage(JSON.stringify({token:__token,callback:key,details:JSON.stringify(opts)}));}else if(!window.ViaScriptRequests.start(__token,key,JSON.stringify(opts)))window[key]('error',{readyState:4,status:0,error:'Request permission denied'});return{abort:function(){if(channel)channel.postMessage(JSON.stringify({action:'abort',token:__token,callback:key}));else window.ViaScriptRequests.abort(__token,key)}}};"
                + "Object.assign(GM_xmlhttpRequest,{UNSENT:0,OPENED:1,HEADERS_RECEIVED:2,LOADING:3,DONE:4,RESPONSE_TYPE_ARRAYBUFFER:'arraybuffer',RESPONSE_TYPE_BLOB:'blob',RESPONSE_TYPE_DOCUMENT:'document',RESPONSE_TYPE_JSON:'json',RESPONSE_TYPE_STREAM:'stream',RESPONSE_TYPE_TEXT:'text'});"
                + "var GM_log=function(){console.log.apply(console,arguments)};var unsafeWindow=window;"
                + "var GM_info={script:{name:" + org.json.JSONObject.quote(metadata.name) + "},scriptHandler:'Via'};"
                + "var GM={info:GM_info,registerMenuCommand:function(n,f,k){return GM_registerMenuCommand(n,f,k)},unregisterMenuCommand:function(n){return GM_unregisterMenuCommand(n)},getValue:function(k,d){return Promise.resolve(GM_getValue(k,d))},getValues:function(a){return Promise.resolve(GM_getValues(a))},setValue:function(k,v){GM_setValue(k,v);return Promise.resolve()},setValues:function(o){GM_setValues(o);return Promise.resolve()},deleteValue:function(k){GM_deleteValue(k);return Promise.resolve()},deleteValues:function(a){GM_deleteValues(a);return Promise.resolve()},listValues:function(){return Promise.resolve(GM_listValues())},addStyle:function(c){return Promise.resolve(GM_addStyle(c))},addElement:function(p,t,a){return Promise.resolve(GM_addElement(p,t,a))},openInTab:function(u){return Promise.resolve(GM_openInTab(u))},setClipboard:function(v){return Promise.resolve(GM_setClipboard(v))},download:function(d,n){return Promise.resolve(GM_download(d,n))},notification:function(d){return Promise.resolve(GM_notification(d))},xmlHttpRequest:function(d){return new Promise(function(ok,fail){var x=Object.assign({},d,{onload:ok,onerror:fail,ontimeout:fail,onabort:fail});GM_xmlhttpRequest(x)})}};\n"
                + grantedBody(code, metadata.grants) + "\n}catch(e){console.error('Via userscript',e)}})();";
    }

    /** Via s5.a/j6.j0 exposes declared synchronous and Promise APIs independently. */
    private static String grantedBody(String code, List<String> grants) {
        String[] methods = {"getValue", "getValues", "setValue", "setValues", "deleteValue", "deleteValues",
                "listValues", "addStyle", "addElement", "openInTab", "setClipboard", "download",
                "notification", "xmlhttpRequest", "log", "registerMenuCommand", "unregisterMenuCommand"};
        StringBuilder parameters = new StringBuilder("GM");
        StringBuilder arguments = new StringBuilder("{info:GM_info");
        for (String method : methods) {
            String async = "xmlhttpRequest".equals(method) ? "xmlHttpRequest" : method;
            String base = method.endsWith("Values") && !"listValues".equals(method)
                    ? method.substring(0, method.length() - 1) : method;
            String asyncBase = base.equals("xmlhttpRequest") ? "xmlHttpRequest" : base;
            if (grants.contains("GM." + asyncBase)) arguments.append(',').append(async).append(":GM.").append(async);
        }
        arguments.append('}');
        for (String method : methods) {
            String base = method.endsWith("Values") && !"listValues".equals(method)
                    ? method.substring(0, method.length() - 1) : method;
            parameters.append(",GM_").append(method);
            arguments.append(',').append(grants.contains("GM_" + base) ? "GM_" + method : "undefined");
        }
        return "(function(" + parameters + "){\n" + (code == null ? "" : code)
                + "\n}).call(window," + arguments + ");";
    }

    private static String wildcardRegex(String rule) {
        if ("*".equals(rule) || "<all_urls>".equals(rule)) return "^.*$";
        Matcher match = Pattern.compile("^(\\*|https?|ftp|file)://([^/]*)(/.*)$").matcher(rule);
        if (match.matches()) {
            String scheme = "*".equals(match.group(1)) ? "https?" : match.group(1);
            String host = match.group(2);
            String hostRegex = "*".equals(host) ? "[^/]+" : host.startsWith("*.")
                    ? "(?:[^/]+\\.)?" + wildcardBody(host.substring(2)) : wildcardBody(host);
            return "^" + scheme + "://" + hostRegex + wildcardBody(match.group(3)) + "$";
        }
        return "^" + wildcardBody(rule) + "$";
    }

    private static String wildcardBody(String rule) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rule.length(); i++) {
            char c = rule.charAt(i);
            if (c == '*') out.append(".*");
            else {
                if ("\\^$.[]|?+(){}".indexOf(c) >= 0) out.append('\\');
                out.append(c);
            }
        }
        return out.toString();
    }

    public static final class Metadata {
        public final String name;
        public final String version;
        public final List<String> matches;
        public final List<String> excludes;
        public final List<String> requires;
        public final String updateUrl;
        public final String runAt;
        public final List<String> grants;
        Metadata(String name, String version, List<String> matches, List<String> excludes, List<String> requires,
                 String updateUrl, String runAt, List<String> grants) {
            this.name = name; this.version = version; this.matches = matches; this.excludes = excludes; this.requires = requires;
            this.updateUrl = updateUrl; this.runAt = runAt; this.grants = grants;
        }
        public String matchText() { return matches.isEmpty() ? "*" : TextUtils.join("\n", matches); }
    }
}
