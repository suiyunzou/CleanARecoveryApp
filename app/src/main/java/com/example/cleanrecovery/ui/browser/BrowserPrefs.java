package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 浏览器设置（UA / 夜间模式 / JS / 图片 / 首页 / 代理状态）。
 *
 * <p>基于 SharedPreferences，纯 Java，无新依赖。</p>
 */
public final class BrowserPrefs {

    private static final String PREF = "via_browser_prefs";

    private static final String K_UA = "ua_mode";          // 0=desktop,1=mobile
    private static final String K_NIGHT = "night_mode";
    private static final String K_JS = "js_enabled";
    private static final String K_IMAGES = "images_enabled";
    private static final String K_HOME = "home_url";
    private static final String K_PROXY_ON = "proxy_on";
    private static final String K_PROXY_NODE = "proxy_node_name";
    private static final String K_SEARCH_ENGINE = "search_engine"; // 0=YT,1=Google,2=Bing,3=Baidu,4=DDG,5=custom
    private static final String K_SEARCH_PREFIX = "search_prefix";
    private static final String K_TOOLBAR_MODE = "toolbar_mode"; // 0=top,1=bottom,2=sandwich,3=two-row
    private static final String K_TEXT_ZOOM = "text_zoom";
    private static final String K_CUSTOM_UA = "custom_ua";
    private static final String K_ADBLOCK = "adblock";
    private static final String K_BLOCKED_HOSTS = "blocked_hosts";
    private static final String K_BLOCKED_URL_RULES = "blocked_url_rules";
    private static final String K_COSMETIC_RULES = "cosmetic_rules";
    private static final String K_MENU_ORDER = "menu_order_v2";
    private static final String K_MENU_HIDDEN = "menu_hidden_v2";
    private static final String K_DO_NOT_TRACK = "do_not_track";
    private static final String K_DISABLE_WEBRTC = "disable_webrtc";
    private static final String K_DATA_SAVER = "data_saver";
    private static final String K_AUTO_SNIFFER = "auto_sniffer_button";
    private static final String K_WEB_DEBUG = "web_debug";
    private static final String K_DISABLE_CUSTOM_TABS = "disable_custom_tabs";
    private static final String K_DISABLE_SAFE_BROWSING = "disable_safe_browsing";
    private static final String K_IGNORE_SSL = "ignore_ssl_warnings";
    private static final String K_CLEAR_ON_EXIT = "clear_data_on_exit";
    private static final String K_UA_PRESET = "ua_preset"; // 0=default mobile ... see BrowserSettingsActivity
    private static final String K_SIMPLE_UA = "simple_ua";
    private static final String K_SITE_UA = "site_ua";
    private static final String K_SITE_TEXT_ZOOM = "site_text_zoom";
    private static final String K_SITE_ADBLOCK_OFF = "site_adblock_off";
    private static final String K_SITE_COOKIES_OFF = "site_cookies_off";

    private final SharedPreferences sp;

    public BrowserPrefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public int uaMode() { return sp.getInt(K_UA, 1); }
    public void setUaMode(int mode) { sp.edit().putInt(K_UA, mode).apply(); }

    public boolean nightMode() { return sp.getBoolean(K_NIGHT, false); }
    public void setNightMode(boolean v) { sp.edit().putBoolean(K_NIGHT, v).apply(); }

    public boolean jsEnabled() { return sp.getBoolean(K_JS, true); }
    public void setJsEnabled(boolean v) { sp.edit().putBoolean(K_JS, v).apply(); }

    public boolean imagesEnabled() { return sp.getBoolean(K_IMAGES, true); }
    public void setImagesEnabled(boolean v) { sp.edit().putBoolean(K_IMAGES, v).apply(); }

    public String homeUrl() { return sp.getString(K_HOME, "https://www.youtube.com"); }
    public void setHomeUrl(String url) { sp.edit().putString(K_HOME, url).apply(); }

    public boolean proxyOn() { return sp.getBoolean(K_PROXY_ON, false); }
    public void setProxyOn(boolean v) { sp.edit().putBoolean(K_PROXY_ON, v).apply(); }

    public String proxyNodeName() { return sp.getString(K_PROXY_NODE, ""); }
    public void setProxyNodeName(String name) { sp.edit().putString(K_PROXY_NODE, name).apply(); }

    /** 搜索引擎索引：0=YouTube,1=Google,2=Bing,3=Baidu,4=DuckDuckGo,5=自定义。 */
    public int searchEngine() { return sp.getInt(K_SEARCH_ENGINE, 0); }
    public void setSearchEngine(int idx) { sp.edit().putInt(K_SEARCH_ENGINE, idx).apply(); }

    public String searchPrefix() { return sp.getString(K_SEARCH_PREFIX, ""); }
    public void setSearchPrefix(String p) { sp.edit().putString(K_SEARCH_PREFIX, p).apply(); }

    /** 工具栏模式：0=顶部,1=底部,2=三明治,3=双行。 */
    public int toolbarMode() { return sp.getInt(K_TOOLBAR_MODE, 1); }
    public void setToolbarMode(int mode) { sp.edit().putInt(K_TOOLBAR_MODE, mode).apply(); }

    public int textZoom() { return sp.getInt(K_TEXT_ZOOM, 100); }
    public void setTextZoom(int zoom) { sp.edit().putInt(K_TEXT_ZOOM, zoom).apply(); }

    public String customUserAgent() { return sp.getString(K_CUSTOM_UA, ""); }
    public void setCustomUserAgent(String ua) {
        sp.edit().putString(K_CUSTOM_UA, ua == null ? "" : ua).apply();
    }

    public boolean adBlockEnabled() { return sp.getBoolean(K_ADBLOCK, false); }
    public void setAdBlockEnabled(boolean enabled) {
        sp.edit().putBoolean(K_ADBLOCK, enabled).apply();
    }

    public Set<String> blockedHosts() {
        return new HashSet<>(sp.getStringSet(K_BLOCKED_HOSTS, Collections.emptySet()));
    }

    public void addBlockedHost(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty()) return;
        Set<String> hosts = blockedHosts();
        hosts.add(h);
        sp.edit().putStringSet(K_BLOCKED_HOSTS, hosts).apply();
    }

    public Set<String> blockedUrlRules() {
        return new LinkedHashSet<>(sp.getStringSet(K_BLOCKED_URL_RULES, Collections.emptySet()));
    }

    public void addBlockedUrlRule(String rule) {
        if (rule == null) return;
        String r = rule.trim().toLowerCase(Locale.ROOT);
        if (r.isEmpty()) return;
        Set<String> rules = blockedUrlRules();
        rules.add(r);
        sp.edit().putStringSet(K_BLOCKED_URL_RULES, rules).apply();
    }

    public List<String> cosmeticRulesForHost(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        Set<String> stored = sp.getStringSet(K_COSMETIC_RULES, Collections.emptySet());
        for (String entry : stored) {
            if (entry == null) continue;
            int split = entry.indexOf('\t');
            if (split <= 0 || split >= entry.length() - 1) continue;
            String ruleHost = entry.substring(0, split);
            if (h.equals(ruleHost) || h.endsWith("." + ruleHost)) {
                out.add(entry.substring(split + 1));
            }
        }
        return out;
    }

    public void addCosmeticRule(String host, String selector) {
        String h = normalizeHost(host);
        if (h.isEmpty() || selector == null) return;
        String clean = selector.trim();
        if (clean.isEmpty() || clean.length() > 240 || clean.indexOf('\n') >= 0) return;
        Set<String> rules = new LinkedHashSet<>(sp.getStringSet(K_COSMETIC_RULES, Collections.emptySet()));
        rules.add(h + "\t" + clean);
        sp.edit().putStringSet(K_COSMETIC_RULES, rules).apply();
    }

    public String cosmeticCssForHost(String host) {
        List<String> rules = cosmeticRulesForHost(host);
        if (rules.isEmpty()) return "";
        StringBuilder css = new StringBuilder();
        for (String selector : rules) {
            if (selector.indexOf('{') >= 0 || selector.indexOf('}') >= 0) continue;
            css.append(selector).append("{display:none!important;visibility:hidden!important;}\n");
        }
        return css.toString();
    }

    public java.util.List<String> menuOrder() {
        String stored = sp.getString(K_MENU_ORDER, "");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (stored == null || stored.isEmpty()) return out;
        for (String name : stored.split(",")) {
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        return out;
    }

    public java.util.Set<String> hiddenMenuItems() {
        return new java.util.HashSet<>(sp.getStringSet(
                K_MENU_HIDDEN, java.util.Collections.emptySet()));
    }

    public void saveMenuConfiguration(
            java.util.List<String> order,
            java.util.Set<String> hidden) {
        sp.edit()
                .putString(K_MENU_ORDER, android.text.TextUtils.join(",", order))
                .putStringSet(K_MENU_HIDDEN, new java.util.HashSet<>(hidden))
                .apply();
    }

    public void resetMenuConfiguration() {
        sp.edit().remove(K_MENU_ORDER).remove(K_MENU_HIDDEN).apply();
    }

    public boolean doNotTrack() { return sp.getBoolean(K_DO_NOT_TRACK, false); }
    public void setDoNotTrack(boolean v) { sp.edit().putBoolean(K_DO_NOT_TRACK, v).apply(); }

    public boolean disableWebRtc() { return sp.getBoolean(K_DISABLE_WEBRTC, false); }
    public void setDisableWebRtc(boolean v) { sp.edit().putBoolean(K_DISABLE_WEBRTC, v).apply(); }

    public boolean dataSaver() { return sp.getBoolean(K_DATA_SAVER, false); }
    public void setDataSaver(boolean v) { sp.edit().putBoolean(K_DATA_SAVER, v).apply(); }

    public boolean autoSnifferButton() { return sp.getBoolean(K_AUTO_SNIFFER, true); }
    public void setAutoSnifferButton(boolean v) { sp.edit().putBoolean(K_AUTO_SNIFFER, v).apply(); }

    public boolean webDebug() { return sp.getBoolean(K_WEB_DEBUG, false); }
    public void setWebDebug(boolean v) { sp.edit().putBoolean(K_WEB_DEBUG, v).apply(); }

    public boolean disableCustomTabs() { return sp.getBoolean(K_DISABLE_CUSTOM_TABS, false); }
    public void setDisableCustomTabs(boolean v) {
        sp.edit().putBoolean(K_DISABLE_CUSTOM_TABS, v).apply();
    }

    public boolean disableSafeBrowsing() { return sp.getBoolean(K_DISABLE_SAFE_BROWSING, false); }
    public void setDisableSafeBrowsing(boolean v) {
        sp.edit().putBoolean(K_DISABLE_SAFE_BROWSING, v).apply();
    }

    public boolean ignoreSslWarnings() { return sp.getBoolean(K_IGNORE_SSL, false); }
    public void setIgnoreSslWarnings(boolean v) { sp.edit().putBoolean(K_IGNORE_SSL, v).apply(); }

    public boolean clearDataOnExit() { return sp.getBoolean(K_CLEAR_ON_EXIT, false); }
    public void setClearDataOnExit(boolean v) { sp.edit().putBoolean(K_CLEAR_ON_EXIT, v).apply(); }

    /** UA 预设：0=默认(手机),1=平板,2=Chrome PC,3=IE11,4=macOS,5=iPhone,6=iPad,7=塞班。 */
    public int uaPreset() { return sp.getInt(K_UA_PRESET, 0); }
    public void setUaPreset(int preset) { sp.edit().putInt(K_UA_PRESET, preset).apply(); }

    public boolean simpleUserAgent() { return sp.getBoolean(K_SIMPLE_UA, false); }
    public void setSimpleUserAgent(boolean v) { sp.edit().putBoolean(K_SIMPLE_UA, v).apply(); }
    // ===== VIA 网站设定：按 host 保存覆盖项 =====

    public String siteUserAgent(String host) {
        return mapGet(K_SITE_UA, normalizeHost(host), "");
    }

    public void setSiteUserAgent(String host, String ua) {
        mapPut(K_SITE_UA, normalizeHost(host), ua == null ? "" : ua);
    }

    public int siteTextZoom(String host, int fallback) {
        String v = mapGet(K_SITE_TEXT_ZOOM, normalizeHost(host), "");
        if (v.isEmpty()) return fallback;
        try { return Integer.parseInt(v); } catch (Exception ignored) { return fallback; }
    }

    public void setSiteTextZoom(String host, int zoom) {
        mapPut(K_SITE_TEXT_ZOOM, normalizeHost(host), String.valueOf(zoom));
    }

    public boolean siteAdBlockOff(String host) {
        return setContains(K_SITE_ADBLOCK_OFF, normalizeHost(host));
    }

    public void setSiteAdBlockOff(String host, boolean off) {
        setToggle(K_SITE_ADBLOCK_OFF, normalizeHost(host), off);
    }

    public boolean siteCookiesOff(String host) {
        return setContains(K_SITE_COOKIES_OFF, normalizeHost(host));
    }

    public void setSiteCookiesOff(String host, boolean off) {
        setToggle(K_SITE_COOKIES_OFF, normalizeHost(host), off);
    }

    public void resetSiteSettings(String host) {
        String h = normalizeHost(host);
        if (h.isEmpty()) return;
        mapPut(K_SITE_UA, h, "");
        mapPut(K_SITE_TEXT_ZOOM, h, "");
        setToggle(K_SITE_ADBLOCK_OFF, h, false);
        setToggle(K_SITE_COOKIES_OFF, h, false);
    }

    public static String normalizeHost(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        while (h.startsWith(".")) h = h.substring(1);
        return h;
    }

    private String mapGet(String key, String host, String fallback) {
        if (host == null || host.isEmpty()) return fallback;
        String stored = sp.getString(key, "");
        if (stored == null || stored.isEmpty()) return fallback;
        for (String pair : stored.split("\n")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) continue;
            if (host.equals(pair.substring(0, idx))) return pair.substring(idx + 1);
        }
        return fallback;
    }

    private void mapPut(String key, String host, String value) {
        if (host == null || host.isEmpty()) return;
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        String stored = sp.getString(key, "");
        if (stored != null && !stored.isEmpty()) {
            for (String pair : stored.split("\n")) {
                int idx = pair.indexOf('=');
                if (idx <= 0) continue;
                map.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        if (value == null || value.isEmpty()) map.remove(host); else map.put(host, value);
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : map.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        sp.edit().putString(key, out.toString()).apply();
    }

    private boolean setContains(String key, String host) {
        return host != null && !host.isEmpty() && sp.getStringSet(
                key, java.util.Collections.emptySet()).contains(host);
    }

    private void setToggle(String key, String host, boolean add) {
        if (host == null || host.isEmpty()) return;
        java.util.Set<String> set = new java.util.HashSet<>(sp.getStringSet(
                key, java.util.Collections.emptySet()));
        if (add) set.add(host); else set.remove(host);
        sp.edit().putStringSet(key, set).apply();
    }

}
