package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

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

    static final String PREF = "via_browser_prefs";

    private static final String K_UA = "ua_mode";          // 0=desktop,1=mobile
    private static final String K_NIGHT = "night_mode";
    private static final String K_JS = "js_enabled";
    private static final String K_IMAGES = "images_enabled";
    private static final String K_HOME = "home_url";
    private static final String K_PROXY_ON = "proxy_on";
    private static final String K_PROXY_NODE = "proxy_node_name";
    private static final String K_SEARCH_ENGINE = "search_engine"; // 0=YT,1=Google,2=Bing,3=Baidu,4=DDG,5=custom
    private static final String K_SEARCH_PREFIX = "search_prefix";
    private static final String K_SEARCH_CUSTOM_TITLE = "search_custom_title";
    private static final String K_SEARCH_CUSTOM_LIST = "search_custom_list";
    private static final String K_SEARCH_CUSTOM_SELECTED = "search_custom_selected";
    private static final String K_SEARCH_HISTORY = "search_history_json";
    private static final String K_SEARCH_SUGGESTIONS = "search_suggestions";
    private static final String K_SEARCH_TOOLBAR_ON = "search_toolbar_on";
    private static final String K_SEARCH_TOOLBAR_ENGINES = "search_toolbar_engines";
    private static final String K_TOOLBAR_MODE = "toolbar_mode"; // 0=top,1=traditional,2=bottom,3=two-row
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
    private static final String K_UA_SELECTED_ID = "ua_selected_id";
    private static final String K_DESKTOP_UA_SELECTED_ID = "desktop_ua_selected_id";
    private static final String K_CUSTOM_UA_LIST = "custom_ua_list_json";
    private static final String K_SIMPLE_UA = "simple_ua";
    private static final String K_SITE_UA = "site_ua";
    private static final String K_SITE_UA_SELECTED_ID = "site_ua_selected_id";
    private static final String K_SITE_TEXT_ZOOM = "site_text_zoom";
    private static final String K_SITE_ADBLOCK_OFF = "site_adblock_off";
    private static final String K_SITE_COOKIES_OFF = "site_cookies_off";
    private static final String K_SITE_ENABLED = "site_enabled";
    private static final String K_SITE_JS_OFF = "site_js_off";
    private static final String K_SITE_IMAGES_OFF = "site_images_off";
    private static final String K_SITE_DESKTOP_ON = "site_desktop_on";
    private static final String K_SITE_INCOGNITO_ON = "site_incognito_on";
    private static final String K_SITE_JS_MODE = "site_js_mode";
    private static final String K_SITE_IMAGES_MODE = "site_images_mode";
    private static final String K_SITE_DESKTOP_MODE = "site_desktop_mode";
    private static final String K_SITE_ADBLOCK_MODE = "site_adblock_mode";
    private static final String K_SITE_INCOGNITO_MODE = "site_incognito_mode";
    private static final String K_HOME_LOGO_MODE = "home_logo_mode"; // 0=default,1=image,2=text,3=html,4=hidden
    private static final String K_HOME_LOGO_TEXT = "home_logo_text";
    private static final String K_HOME_LOGO_URI = "home_logo_uri";
    private static final String K_HOME_LOGO_SIZE = "home_logo_size";
    private static final String K_HOME_LOGO_WIDTH = "home_logo_width";
    private static final String K_HOME_LOGO_RADIUS = "home_logo_radius";
    private static final String K_HOME_LOGO_BOLD = "home_logo_bold";
    private static final String K_HOME_LOGO_ITALIC = "home_logo_italic";
    private static final String K_HOME_BG_COLOR = "home_bg_color";
    private static final String K_HOME_BG_URI = "home_bg_uri";
    private static final String K_HOME_BG_OPACITY = "home_bg_opacity";
    private static final String K_HOME_BG_BLUR = "home_bg_blur";
    private static final String K_HOME_SEARCH_VISIBLE = "home_search_visible";
    private static final String K_HOME_SEARCH_RADIUS = "home_search_radius";
    private static final String K_HOME_SEARCH_ALPHA = "home_search_alpha";
    private static final String K_HOME_SEARCH_STROKE = "home_search_stroke";
    private static final String K_HOME_SEARCH_LINE = "home_search_line";
    private static final String K_HOME_SEARCH_STROKE_ALPHA = "home_search_stroke_alpha";
    private static final String K_HOME_SEARCH_STYLE = "home_search_style";
    private static final String K_HOME_SEARCH_EFFECT = "home_search_effect";
    private static final String K_HOME_FAV_WIDTH = "home_fav_width";
    private static final String K_HOME_FAV_HEIGHT = "home_fav_height";
    private static final String K_HOME_FAV_RADIUS = "home_fav_radius";
    private static final String K_HOME_FAV_ICON_DISABLED = "home_fav_icon_disabled";
    private static final String K_HOME_FAV_TITLE_DISABLED = "home_fav_title_disabled";
    private static final String K_HOME_FAV_ICON_STYLE = "home_fav_icon_style";
    private static final String K_HOME_FAV_ICON_COLOR = "home_fav_icon_color";
    private static final String K_HOME_CUSTOM_CSS = "home_custom_css";

    private final SharedPreferences sp;

    private final android.content.Context appContextRef;

    public BrowserPrefs(Context ctx) {
        appContextRef = ctx.getApplicationContext();
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public int uaMode() { return desktopMode() ? 0 : 1; }
    public void setUaMode(int mode) { setDesktopMode(mode == 0); }

    public boolean nightMode() { return sp.getBoolean(K_NIGHT, false); }
    public void setNightMode(boolean v) { sp.edit().putBoolean(K_NIGHT, v).apply(); }

    public boolean jsEnabled() { return sp.getBoolean(K_JS, true); }
    public void setJsEnabled(boolean v) { sp.edit().putBoolean(K_JS, v).apply(); }

    public boolean imagesEnabled() { return sp.getBoolean(K_IMAGES, true); }
    public void setImagesEnabled(boolean v) { sp.edit().putBoolean(K_IMAGES, v).apply(); }

    /** 主页 URL：与 homeMode 合一（默认/书签→about:home 九宫格，空白→about:blank，网页→自定义 URL）。 */
    public String homeUrl() {
        switch (homeMode()) {
            case 1:
                return "about:blank";
            case 3:
                return homeCustomUrl() == null || homeCustomUrl().isEmpty()
                        ? "https://www.baidu.com" : homeCustomUrl();
            default:
                return "about:home";
        }
    }
    public void setHomeUrl(String url) { sp.edit().putString(K_HOME, url).apply(); }

    public boolean proxyOn() { return sp.getBoolean(K_PROXY_ON, false); }
    public void setProxyOn(boolean v) { sp.edit().putBoolean(K_PROXY_ON, v).apply(); }

    public String proxyNodeName() { return sp.getString(K_PROXY_NODE, ""); }
    public void setProxyNodeName(String name) { sp.edit().putString(K_PROXY_NODE, name).apply(); }

    /** 搜索引擎索引：0=YouTube,1=Google,2=Bing,3=Baidu,4=DuckDuckGo,5=自定义。 */
    public int searchEngine() { return sp.getInt(K_SEARCH_ENGINE, SearchEngines.GOOGLE); }
    public void setSearchEngine(int idx) { sp.edit().putInt(K_SEARCH_ENGINE, idx).apply(); }

    public String searchPrefix() {
        List<CustomSearchItem> items = customSearchList();
        int selected = sp.getInt(K_SEARCH_CUSTOM_SELECTED, items.isEmpty() ? -1 : 0);
        if (selected >= 0 && selected < items.size()) return items.get(selected).prefix;
        return sp.getString(K_SEARCH_PREFIX, "");
    }
    public void setSearchPrefix(String p) { sp.edit().putString(K_SEARCH_PREFIX, p).apply(); }

    public String customSearchTitle() {
        List<CustomSearchItem> items = customSearchList();
        int selected = sp.getInt(K_SEARCH_CUSTOM_SELECTED, items.isEmpty() ? -1 : 0);
        if (selected >= 0 && selected < items.size()) return items.get(selected).title;
        return sp.getString(K_SEARCH_CUSTOM_TITLE, "自定义");
    }
    public void setCustomSearch(String title, String prefix) {
        addCustomSearch(title, prefix);
    }

    public List<CustomSearchItem> customSearchList() {
        List<CustomSearchItem> out = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(sp.getString(K_SEARCH_CUSTOM_LIST, "[]"));
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i);
                if (item == null) continue;
                String title = item.optString("title").trim();
                String prefix = item.optString("prefix").trim();
                if (!title.isEmpty() && !prefix.isEmpty()) out.add(new CustomSearchItem(title, prefix));
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) {
            String legacyPrefix = sp.getString(K_SEARCH_PREFIX, "").trim();
            if (!legacyPrefix.isEmpty()) out.add(new CustomSearchItem(
                    sp.getString(K_SEARCH_CUSTOM_TITLE, "自定义"), legacyPrefix));
        }
        return out;
    }

    public void addCustomSearch(String title, String prefix) {
        List<CustomSearchItem> items = customSearchList();
        items.add(new CustomSearchItem(title == null || title.trim().isEmpty() ? "自定义" : title.trim(),
                prefix == null ? "" : prefix.trim()));
        saveCustomSearches(items, items.size() - 1);
    }

    public void selectCustomSearch(int index) {
        List<CustomSearchItem> items = customSearchList();
        if (index < 0 || index >= items.size()) return;
        saveCustomSearches(items, index);
        setSearchEngine(SearchEngines.CUSTOM);
    }

    public void removeCustomSearch(int index) {
        List<CustomSearchItem> items = customSearchList();
        if (index < 0 || index >= items.size()) return;
        items.remove(index);
        int selected = Math.min(index, items.size() - 1);
        saveCustomSearches(items, selected);
        if (items.isEmpty() && searchEngine() == SearchEngines.CUSTOM) setSearchEngine(SearchEngines.GOOGLE);
    }

    public int selectedCustomSearch() {
        List<CustomSearchItem> items = customSearchList();
        int selected = sp.getInt(K_SEARCH_CUSTOM_SELECTED, items.isEmpty() ? -1 : 0);
        return selected >= 0 && selected < items.size() ? selected : (items.isEmpty() ? -1 : 0);
    }

    private void saveCustomSearches(List<CustomSearchItem> items, int selected) {
        JSONArray values = new JSONArray();
        for (CustomSearchItem item : items) {
            try { values.put(new JSONObject().put("title", item.title).put("prefix", item.prefix)); }
            catch (Exception ignored) { }
        }
        SharedPreferences.Editor edit = sp.edit().putString(K_SEARCH_CUSTOM_LIST, values.toString())
                .putInt(K_SEARCH_CUSTOM_SELECTED, selected);
        if (selected >= 0 && selected < items.size()) {
            edit.putString(K_SEARCH_CUSTOM_TITLE, items.get(selected).title)
                    .putString(K_SEARCH_PREFIX, items.get(selected).prefix);
        }
        edit.apply();
    }

    public static final class CustomSearchItem {
        public final String title;
        public final String prefix;
        public CustomSearchItem(String title, String prefix) { this.title = title; this.prefix = prefix; }
    }

    public List<String> searchHistory() {
        ArrayList<String> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(sp.getString(K_SEARCH_HISTORY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty() && !out.contains(value)) out.add(value);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public void rememberSearch(String query) {
        if (query == null || query.trim().isEmpty()) return;
        ArrayList<String> values = new ArrayList<>(searchHistory());
        values.remove(query.trim());
        values.add(0, query.trim());
        while (values.size() > 30) values.remove(values.size() - 1);
        sp.edit().putString(K_SEARCH_HISTORY, new JSONArray(values).toString()).apply();
    }

    /** 搜索建议 bit：1=收藏,2=书签,4=标签页,8=历史,16=搜索引擎,32=搜索历史；Via 默认 31。 */
    public int searchSuggestions() { return sp.getInt(K_SEARCH_SUGGESTIONS, 31); }
    public void setSearchSuggestions(int flags) { sp.edit().putInt(K_SEARCH_SUGGESTIONS, flags).apply(); }

    private static final String K_SEARCH_TOOLBAR_ORDER = "searchtoolbarorder";
    private static final String K_SEARCH_TOOLBAR_DISABLED = "searchtoolbardisabled";

    public static final int[] DEFAULT_TOOLBAR_ENGINES = {
            SearchEngines.GOOGLE,
            SearchEngines.BAIDU,
            SearchEngines.BING,
            SearchEngines.YAHOO,
            SearchEngines.STARTPAGE,
            SearchEngines.DUCKDUCKGO
    };

    public boolean searchToolbarEnabled() { return sp.getBoolean(K_SEARCH_TOOLBAR_ON, true); }
    public void setSearchToolbarEnabled(boolean on) { sp.edit().putBoolean(K_SEARCH_TOOLBAR_ON, on).apply(); }

    public java.util.List<Integer> searchToolbarOrder() {
        String stored = sp.getString(K_SEARCH_TOOLBAR_ORDER, "");
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        if (stored != null && !stored.isEmpty()) {
            for (String part : stored.split(",")) {
                try {
                    int engine = Integer.parseInt(part.trim());
                    if (!out.contains(engine)) out.add(engine);
                } catch (Exception ignored) { }
            }
        }
        for (int engine : DEFAULT_TOOLBAR_ENGINES) {
            if (!out.contains(engine)) out.add(engine);
        }
        return out;
    }

    public void setSearchToolbarOrder(java.util.List<Integer> order) {
        StringBuilder b = new StringBuilder();
        if (order != null) {
            for (Integer engine : order) {
                if (engine == null) continue;
                if (b.length() > 0) b.append(',');
                b.append(engine);
            }
        }
        sp.edit().putString(K_SEARCH_TOOLBAR_ORDER, b.toString()).apply();
    }

    public java.util.Set<Integer> searchToolbarDisabledEngines() {
        String stored = sp.getString(K_SEARCH_TOOLBAR_DISABLED, "");
        java.util.HashSet<Integer> set = new java.util.HashSet<>();
        if (stored != null && !stored.isEmpty()) {
            for (String part : stored.split(",")) {
                try {
                    set.add(Integer.parseInt(part.trim()));
                } catch (Exception ignored) { }
            }
        }
        // Current default engine can never be disabled
        set.remove(searchEngine());
        return set;
    }

    public void setSearchToolbarDisabledEngines(java.util.Set<Integer> disabled) {
        StringBuilder b = new StringBuilder();
        if (disabled != null) {
            for (Integer engine : disabled) {
                if (engine == null || engine == searchEngine()) continue;
                if (b.length() > 0) b.append(',');
                b.append(engine);
            }
        }
        sp.edit().putString(K_SEARCH_TOOLBAR_DISABLED, b.toString()).apply();
    }

    public java.util.List<Integer> searchToolbarEngines() {
        java.util.List<Integer> order = searchToolbarOrder();
        java.util.Set<Integer> disabled = searchToolbarDisabledEngines();
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        for (Integer engine : order) {
            if (!disabled.contains(engine)) {
                out.add(engine);
            }
        }
        if (!out.contains(searchEngine())) {
            out.add(0, searchEngine());
        }
        return out;
    }

    public void setSearchToolbarEngines(java.util.List<Integer> engines) {
        java.util.List<Integer> order = searchToolbarOrder();
        java.util.HashSet<Integer> disabled = new java.util.HashSet<>();
        for (Integer e : order) {
            if (engines == null || !engines.contains(e)) {
                disabled.add(e);
            }
        }
        setSearchToolbarDisabledEngines(disabled);
        if (engines != null && !engines.isEmpty()) {
            java.util.ArrayList<Integer> newOrder = new java.util.ArrayList<>(engines);
            for (Integer e : order) {
                if (!newOrder.contains(e)) newOrder.add(e);
            }
            setSearchToolbarOrder(newOrder);
        }
    }


    // ===== Via 主页定制：Logo / 搜索框 / 背景 =====
    public int homeLogoMode() { return sp.getInt(K_HOME_LOGO_MODE, 0); }
    public void setHomeLogoMode(int mode) { sp.edit().putInt(K_HOME_LOGO_MODE, mode).apply(); }
    public String homeLogoText() { return sp.getString(K_HOME_LOGO_TEXT, "枢"); }
    public void setHomeLogoText(String text) { sp.edit().putString(K_HOME_LOGO_TEXT, text == null ? "" : text).apply(); }
    public String homeLogoUri() { return sp.getString(K_HOME_LOGO_URI, ""); }
    public void setHomeLogoUri(String uri) { sp.edit().putString(K_HOME_LOGO_URI, uri == null ? "" : uri).apply(); }
    public int homeLogoTextSize() { return sp.getInt("home_logo_text_size", 28); }
    public void setHomeLogoTextSize(int value) { sp.edit().putInt("home_logo_text_size", clamp(value, 10, 63)).apply(); }
    public int homeLogoSize() { return sp.getInt(K_HOME_LOGO_SIZE, 72); }
    public void setHomeLogoSize(int size) { sp.edit().putInt(K_HOME_LOGO_SIZE, clamp(size, 0, 127)).apply(); }
    public int homeLogoWidth() { return sp.getInt(K_HOME_LOGO_WIDTH, 0); }
    public void setHomeLogoWidth(int width) { sp.edit().putInt(K_HOME_LOGO_WIDTH, clamp(width, 0, 127)).apply(); }
    public int homeLogoRadius() { return sp.getInt(K_HOME_LOGO_RADIUS, 50); }
    public void setHomeLogoRadius(int radius) { sp.edit().putInt(K_HOME_LOGO_RADIUS, clamp(radius, 0, 100)).apply(); }
    public boolean homeLogoBold() { return sp.getBoolean(K_HOME_LOGO_BOLD, true); }
    public void setHomeLogoBold(boolean on) { sp.edit().putBoolean(K_HOME_LOGO_BOLD, on).apply(); }
    public boolean homeLogoItalic() { return sp.getBoolean(K_HOME_LOGO_ITALIC, false); }
    public void setHomeLogoItalic(boolean on) { sp.edit().putBoolean(K_HOME_LOGO_ITALIC, on).apply(); }

    public int homeBackgroundShade() { return sp.getInt("home_background_shade", 0); }
    public void setHomeBackgroundShade(int value) { sp.edit().putInt("home_background_shade", clamp(value, 0, 80)).apply(); }
    public boolean homeBackgroundDark() {
        if (!homeBackgroundUri().isEmpty() && sp.contains("home_background_dark")) return sp.getBoolean("home_background_dark", false);
        int color = homeBackgroundColor();
        return android.graphics.Color.red(color) * .299 + android.graphics.Color.green(color) * .587
            + android.graphics.Color.blue(color) * .114 < 192;
    }
    public void setHomeBackgroundDark(boolean value) { sp.edit().putBoolean("home_background_dark", value).apply(); }
    public int homeBackgroundColor() { return sp.getInt(K_HOME_BG_COLOR, 0xffffffff); }
    public void setHomeBackgroundColor(int color) { sp.edit().putInt(K_HOME_BG_COLOR, color).apply(); }
    public String homeBackgroundUri() { return sp.getString(K_HOME_BG_URI, ""); }
    public void setHomeBackgroundUri(String uri) { sp.edit().putString(K_HOME_BG_URI, uri == null ? "" : uri).apply(); }
    public int homeBackgroundOpacity() { return sp.getInt(K_HOME_BG_OPACITY, 100); }
    public void setHomeBackgroundOpacity(int opacity) { sp.edit().putInt(K_HOME_BG_OPACITY, clamp(opacity, 0, 100)).apply(); }
    public boolean homeBackgroundBlur() { return sp.getBoolean(K_HOME_BG_BLUR, false); }
    public void setHomeBackgroundBlur(boolean on) { sp.edit().putBoolean(K_HOME_BG_BLUR, on).apply(); }

    public boolean homeSearchVisible() { return sp.getBoolean(K_HOME_SEARCH_VISIBLE, true); }
    public void setHomeSearchVisible(boolean visible) { sp.edit().putBoolean(K_HOME_SEARCH_VISIBLE, visible).apply(); }
    public int homeSearchRadius() { return sp.getInt(K_HOME_SEARCH_RADIUS, 100); }
    public void setHomeSearchRadius(int radius) { sp.edit().putInt(K_HOME_SEARCH_RADIUS, clamp(radius, 0, 100)).apply(); }
    public int homeSearchAlpha() { return sp.getInt(K_HOME_SEARCH_ALPHA, 0); }
    public void setHomeSearchAlpha(int alpha) { sp.edit().putInt(K_HOME_SEARCH_ALPHA, clamp(alpha, 0, 100)).apply(); }
    public int homeSearchStroke() { return sp.getInt(K_HOME_SEARCH_STROKE, 1); }
    public void setHomeSearchStroke(int stroke) { sp.edit().putInt(K_HOME_SEARCH_STROKE, clamp(stroke, 0, 7)).apply(); }
    public boolean homeSearchLine() { return sp.getBoolean(K_HOME_SEARCH_LINE, false); }
    public void setHomeSearchLine(boolean on) { sp.edit().putBoolean(K_HOME_SEARCH_LINE, on).apply(); }
    public int homeSearchStrokeAlpha() { return sp.getInt(K_HOME_SEARCH_STROKE_ALPHA, 24); }
    public void setHomeSearchStrokeAlpha(int value) { sp.edit().putInt(K_HOME_SEARCH_STROKE_ALPHA, clamp(value, 0, 100)).apply(); }
    public int homeSearchStyle() { return sp.getInt(K_HOME_SEARCH_STYLE, 0); }
    public void setHomeSearchStyle(int value) { sp.edit().putInt(K_HOME_SEARCH_STYLE, value).apply(); }
    public int homeSearchEffect() { return sp.getInt(K_HOME_SEARCH_EFFECT, 0); }
    public void setHomeSearchEffect(int value) { sp.edit().putInt(K_HOME_SEARCH_EFFECT, value).apply(); }

    public void resetHomeLogo() {
        sp.edit().remove(K_HOME_LOGO_MODE).remove(K_HOME_LOGO_TEXT).remove(K_HOME_LOGO_URI)
                .remove(K_HOME_LOGO_SIZE).remove(K_HOME_LOGO_WIDTH).remove(K_HOME_LOGO_RADIUS)
                .remove(K_HOME_LOGO_BOLD).remove(K_HOME_LOGO_ITALIC).remove("home_logo_text_size").apply();
    }
    public void resetHomeBackground() {
        sp.edit().remove(K_HOME_BG_COLOR).remove(K_HOME_BG_URI).remove(K_HOME_BG_OPACITY)
                .remove(K_HOME_BG_BLUR).remove("home_background_shade").remove("home_background_dark").apply();
    }
    public void resetHomeSearch() {
        sp.edit().remove(K_HOME_SEARCH_VISIBLE).remove(K_HOME_SEARCH_RADIUS)
                .remove(K_HOME_SEARCH_ALPHA).remove(K_HOME_SEARCH_STROKE).remove(K_HOME_SEARCH_STROKE_ALPHA)
                .remove(K_HOME_SEARCH_STYLE).remove(K_HOME_SEARCH_EFFECT)
                .remove(K_HOME_SEARCH_LINE).apply();
    }

    public int homeFavoriteRawWidth() { return sp.getInt(K_HOME_FAV_WIDTH, 46); }
    public int homeFavoriteRawHeight() { return sp.getInt(K_HOME_FAV_HEIGHT, 0); }
    public int homeFavoriteWidth() { int w = homeFavoriteRawWidth(), h = homeFavoriteRawHeight(); return w != 0 ? w : h != 0 ? h : 54; }
    public void setHomeFavoriteWidth(int width) { sp.edit().putInt(K_HOME_FAV_WIDTH, clamp(width, 0, 80)).apply(); }
    public int homeFavoriteHeight() { int h = homeFavoriteRawHeight(); return h == 0 ? homeFavoriteWidth() : h; }
    public void setHomeFavoriteHeight(int height) { sp.edit().putInt(K_HOME_FAV_HEIGHT, clamp(height, 0, 80)).apply(); }
    public int homeFavoriteRadius() { return sp.getInt(K_HOME_FAV_RADIUS, 100); }
    public void setHomeFavoriteRadius(int radius) { sp.edit().putInt(K_HOME_FAV_RADIUS, clamp(radius, 0, 100)).apply(); }
    public boolean homeFavoriteIconDisabled() { return sp.getBoolean(K_HOME_FAV_ICON_DISABLED, false); }
    public void setHomeFavoriteIconDisabled(boolean disabled) { sp.edit().putBoolean(K_HOME_FAV_ICON_DISABLED, disabled).apply(); }
    public boolean homeFavoriteTitleDisabled() { return sp.getBoolean(K_HOME_FAV_TITLE_DISABLED, false); }
    public void setHomeFavoriteTitleDisabled(boolean disabled) { sp.edit().putBoolean(K_HOME_FAV_TITLE_DISABLED, disabled).apply(); }
    public int homeFavoriteIconStyle() { return sp.getInt(K_HOME_FAV_ICON_STYLE, 0); }
    public void setHomeFavoriteIconStyle(int value) { sp.edit().putInt(K_HOME_FAV_ICON_STYLE, value).apply(); }
    public int homeFavoriteIconColor() { return sp.getInt(K_HOME_FAV_ICON_COLOR, 0); }
    public void setHomeFavoriteIconColor(int value) { sp.edit().putInt(K_HOME_FAV_ICON_COLOR, value).apply(); }
    public String homeCustomCss() { return sp.getString(K_HOME_CUSTOM_CSS, ""); }
    public void setHomeCustomCss(String value) { sp.edit().putString(K_HOME_CUSTOM_CSS, value == null ? "" : value).apply(); }
    public void resetHomeFavorites() {
        sp.edit().remove(K_HOME_FAV_WIDTH).remove(K_HOME_FAV_HEIGHT).remove(K_HOME_FAV_RADIUS)
                .remove(K_HOME_FAV_ICON_DISABLED).remove(K_HOME_FAV_TITLE_DISABLED)
                .remove(K_HOME_FAV_ICON_STYLE).remove(K_HOME_FAV_ICON_COLOR).apply();
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    /** 工具栏模式：0=工具栏在上,1=传统,2=工具栏在下,3=双行。 */
    public int toolbarMode() { return sp.getInt(K_TOOLBAR_MODE, 1); }
    public void setToolbarMode(int mode) { sp.edit().putInt(K_TOOLBAR_MODE, mode).apply(); }

    public int textZoom() { return sp.getInt(K_TEXT_ZOOM, 100); }
    public void setTextZoom(int zoom) { sp.edit().putInt(K_TEXT_ZOOM, zoom).apply(); }

    public String customUserAgent() { return sp.getString(K_CUSTOM_UA, ""); }
    public void setCustomUserAgent(String ua) {
        sp.edit().putString(K_CUSTOM_UA, ua == null ? "" : ua).apply();
    }

    public boolean adBlockEnabled() { return sp.getBoolean(K_ADBLOCK, true); } // VIA 默认开启广告拦截
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


    public void setBlockedHosts(Set<String> hosts) {
        Set<String> clean = new LinkedHashSet<>();
        if (hosts != null) {
            for (String host : hosts) {
                String h = normalizeHost(host);
                if (!h.isEmpty()) clean.add(h);
            }
        }
        sp.edit().putStringSet(K_BLOCKED_HOSTS, clean).apply();
    }

    public void setBlockedUrlRules(Set<String> rules) {
        Set<String> clean = new LinkedHashSet<>();
        if (rules != null) {
            for (String rule : rules) {
                String r = normalizeRuleLine(rule);
                if (!r.isEmpty()) clean.add(r);
            }
        }
        sp.edit().putStringSet(K_BLOCKED_URL_RULES, clean).apply();
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

    public Set<String> cosmeticRuleEntries() {
        return new LinkedHashSet<>(sp.getStringSet(K_COSMETIC_RULES, Collections.emptySet()));
    }

    public void setCosmeticRuleEntries(Set<String> entries) {
        Set<String> clean = new LinkedHashSet<>();
        if (entries != null) {
            for (String entry : entries) {
                String e = entry == null ? "" : entry.trim();
                if (e.isEmpty()) continue;
                int split = e.indexOf('	');
                if (split <= 0 || split >= e.length() - 1) continue;
                String h = normalizeHost(e.substring(0, split));
                String selector = e.substring(split + 1).trim();
                if (!h.isEmpty() && !selector.isEmpty() && selector.length() <= 240
                        && !selector.contains("\n") && selector.indexOf('{') < 0
                        && selector.indexOf('}') < 0) {
                    clean.add(h + "	" + selector);
                }
            }
        }
        sp.edit().putStringSet(K_COSMETIC_RULES, clean).apply();
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


    public int adBlockCustomRuleCount() {
        return blockedHosts().size() + blockedUrlRules().size() + cosmeticRuleEntries().size();
    }

    public String exportAdBlockRules() {
        StringBuilder out = new StringBuilder();
        out.append("! CleanRecovery adblock rules\n");
        for (String host : sorted(blockedHosts())) out.append("||").append(host).append("^\n");
        for (String rule : sorted(blockedUrlRules())) out.append(rule).append('\n');
        for (String entry : sorted(cosmeticRuleEntries())) {
            int split = entry.indexOf('\t');
            if (split > 0) {
                out.append(entry, 0, split).append("##")
                        .append(entry.substring(split + 1)).append('\n');
            }
        }
        return out.toString();
    }

    public int importAdBlockRules(String text, boolean replace) {
        Set<String> hosts = replace ? new LinkedHashSet<>() : new LinkedHashSet<>(blockedHosts());
        Set<String> network = replace ? new LinkedHashSet<>() : new LinkedHashSet<>(blockedUrlRules());
        Set<String> cosmetic = replace ? new LinkedHashSet<>() : new LinkedHashSet<>(cosmeticRuleEntries());
        int added = 0;
        if (text != null) {
            for (String raw : text.split("\r?\n")) {
                String line = raw == null ? "" : raw.trim();
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue;
                int cosmeticIdx = line.indexOf("##");
                if (cosmeticIdx > 0 && cosmeticIdx < line.length() - 2) {
                    String host = normalizeHost(line.substring(0, cosmeticIdx));
                    String selector = line.substring(cosmeticIdx + 2).trim();
                    if (!host.isEmpty() && !selector.isEmpty() && selector.length() <= 240
                            && selector.indexOf('{') < 0 && selector.indexOf('}') < 0) {
                        if (cosmetic.add(host + "	" + selector)) added++;
                    }
                    continue;
                }
                if (line.startsWith("||") && line.endsWith("^")) {
                    String host = normalizeHost(line.substring(2, line.length() - 1));
                    if (!host.isEmpty() && hosts.add(host)) added++;
                    continue;
                }
                String rule = normalizeRuleLine(line);
                if (!rule.isEmpty() && network.add(rule)) added++;
            }
        }
        sp.edit()
                .putStringSet(K_BLOCKED_HOSTS, hosts)
                .putStringSet(K_BLOCKED_URL_RULES, network)
                .putStringSet(K_COSMETIC_RULES, cosmetic)
                .apply();
        return added;
    }

    public void clearAdBlockCustomRules() {
        sp.edit()
                .remove(K_BLOCKED_HOSTS)
                .remove(K_BLOCKED_URL_RULES)
                .remove(K_COSMETIC_RULES)
                .apply();
    }

    private static List<String> sorted(Set<String> values) {
        List<String> out = new ArrayList<>(values == null ? Collections.emptySet() : values);
        Collections.sort(out);
        return out;
    }

    private static String normalizeRuleLine(String rule) {
        String r = rule == null ? "" : rule.trim().toLowerCase(Locale.ROOT);
        if (r.isEmpty() || r.startsWith("!") || r.startsWith("[")) return "";
        return r;
    }

    public boolean doNotTrack() { return sp.getBoolean(K_DO_NOT_TRACK, true); } // VIA 默认开启不跟踪
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

    // ===== VIA 原版 1:1 浏览器标识 (User-Agent) 体系架构 =====

    public static final class CustomUaItem {
        public final long id;
        public final String name;
        public final String ua;

        public CustomUaItem(long id, String name, String ua) {
            this.id = id;
            this.name = name == null ? "" : name.trim();
            this.ua = ua == null ? "" : ua.trim();
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("ua", ua);
            } catch (Exception ignored) {}
            return o;
        }

        public static CustomUaItem fromJson(JSONObject o) {
            if (o == null) return null;
            long id = o.optLong("id", 0);
            String name = o.optString("name", "");
            String ua = o.optString("ua", "");
            return new CustomUaItem(id, name, ua);
        }
    }

    public static final int UA_ID_DEFAULT = 0;
    public static final int SITE_UA_ID_INHERIT = -1000;
    public static final int SITE_UA_ID_CUSTOM = -999;
    public static final int UA_ID_ANDROID_PHONE = -1;
    public static final int UA_ID_ANDROID_TABLET = -2;
    public static final int UA_ID_WINDOWS_CHROME = -3;
    public static final int UA_ID_WINDOWS_IE = -4;
    public static final int UA_ID_MACOS = -5;
    public static final int UA_ID_IPHONE = -6;
    public static final int UA_ID_IPAD = -7;
    public static final int UA_ID_SYMBIAN = -8;

    public static final int[] PRESET_UA_IDS = {
            UA_ID_DEFAULT,
            UA_ID_ANDROID_PHONE,
            UA_ID_ANDROID_TABLET,
            UA_ID_WINDOWS_CHROME,
            UA_ID_WINDOWS_IE,
            UA_ID_MACOS,
            UA_ID_IPHONE,
            UA_ID_IPAD,
            UA_ID_SYMBIAN
    };

    public static final String[] PRESET_UA_NAMES = {
            "默认",
            "Android (手机)",
            "Android (平板)",
            "Windows (Chrome)",
            "Windows (IE 11)",
            "macOS",
            "iPhone",
            "iPad",
            "塞班 (Symbian)"
    };

    /** 真实 Via 官方内置 8 档预设 UA 字符串（对齐 Via 7.2.1 原版 AbstractC0676z3.m3431b） */
    public static String getPresetUaString(int id) {
        switch (id) {
            case UA_ID_ANDROID_PHONE:
                return "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36";
            case UA_ID_ANDROID_TABLET:
                return "Mozilla/5.0 (Linux; Android 8.1.0; SM-T837A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
            case UA_ID_WINDOWS_CHROME:
                return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
            case UA_ID_WINDOWS_IE:
                return "Mozilla/5.0 (Windows NT 10.0; Trident/7.0; rv:11.0) like Gecko";
            case UA_ID_MACOS:
                return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
            case UA_ID_IPHONE:
                return "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1";
            case UA_ID_IPAD:
                return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15";
            case UA_ID_SYMBIAN:
                return "Mozilla/5.0 (Symbian/3; Series60/5.2 NokiaN8-00/012.002; Profile/MIDP-2.1 Configuration/CLDC-1.1 ) AppleWebKit/533.4 (KHTML, like Gecko) NokiaBrowser/7.3.0 Mobile Safari/533.4 3gpp-gba";
            case UA_ID_DEFAULT:
            default:
                return null;
        }
    }

    /** 当前激活的 UA ID：0=默认，-1~-8=内置预设，>0=自定义 UA ID */
    public long uaSelectedId() {
        return sp.getLong(K_UA_SELECTED_ID, -sp.getInt("ua_preset", 0));
    }

    public void setUaSelectedId(long id) {
        sp.edit().putLong(K_UA_SELECTED_ID, id).apply();
    }

    /** 电脑模式下的 UA ID：0=默认（自动采用 Windows Chrome），或 -3, -4, -5 及自定义条目 */
    public long desktopUaSelectedId() {
        return sp.getLong(K_DESKTOP_UA_SELECTED_ID, -sp.getInt("desktop_ua_preset", 0));
    }

    public void setDesktopUaSelectedId(long id) {
        sp.edit().putLong(K_DESKTOP_UA_SELECTED_ID, id).apply();
    }

    /** 兼容旧代码的 uaPreset 映射（0=默认，1=Android手机...8=塞班） */
    public int uaPreset() {
        long sel = uaSelectedId();
        if (sel >= -8 && sel <= -1) {
            return (int) (-sel);
        }
        return 0;
    }

    public void setUaPreset(int preset) {
        if (preset == 0) {
            setUaSelectedId(0);
        } else if (preset >= 1 && preset <= 8) {
            setUaSelectedId(-preset);
        }
    }

    public int desktopUaPreset() {
        long sel = desktopUaSelectedId();
        if (sel >= -8 && sel <= -1) {
            return (int) (-sel);
        }
        return 0;
    }

    public void setDesktopUaPreset(int v) {
        if (v == 0) {
            setDesktopUaSelectedId(0);
        } else if (v >= 1 && v <= 8) {
            setDesktopUaSelectedId(-v);
        }
    }

    public static String presetUserAgent(int preset) {
        if (preset >= 1 && preset <= 8) {
            return getPresetUaString(-preset);
        }
        return null;
    }

    public boolean simpleUserAgent() { return simpleUa(); }
    public void setSimpleUserAgent(boolean v) { setSimpleUa(v); }

    public synchronized List<CustomUaItem> customUaList() {
        List<CustomUaItem> list = new ArrayList<>();
        String json = sp.getString(K_CUSTOM_UA_LIST, "");
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    CustomUaItem item = CustomUaItem.fromJson(arr.getJSONObject(i));
                    if (item != null && item.id > 0) {
                        list.add(item);
                    }
                }
            } catch (Exception ignored) {}
        }
        return list;
    }

    public synchronized void saveCustomUaList(List<CustomUaItem> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (CustomUaItem item : list) {
                if (item != null) arr.put(item.toJson());
            }
        }
        sp.edit().putString(K_CUSTOM_UA_LIST, arr.toString()).apply();
    }

    public synchronized long addCustomUa(String name, String ua) {
        List<CustomUaItem> list = customUaList();
        long nextId = 1;
        for (CustomUaItem item : list) {
            if (item.id >= nextId) nextId = item.id + 1;
        }
        CustomUaItem newItem = new CustomUaItem(nextId, name, ua);
        list.add(newItem);
        saveCustomUaList(list);
        return nextId;
    }

    public synchronized boolean updateCustomUa(long id, String name, String ua) {
        List<CustomUaItem> list = customUaList();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) {
                list.set(i, new CustomUaItem(id, name, ua));
                saveCustomUaList(list);
                return true;
            }
        }
        return false;
    }

    public synchronized boolean deleteCustomUa(long id) {
        List<CustomUaItem> list = customUaList();
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id == id) {
                list.remove(i);
                removed = true;
            }
        }
        if (removed) {
            saveCustomUaList(list);
            if (uaSelectedId() == id) {
                setUaSelectedId(0);
            }
            if (desktopUaSelectedId() == id) {
                setDesktopUaSelectedId(0);
            }
        }
        return removed;
    }

    public CustomUaItem getCustomUa(long id) {
        if (id <= 0) return null;
        for (CustomUaItem item : customUaList()) {
            if (item.id == id) return item;
        }
        return null;
    }

    public String getUaName(long id) {
        if (id == 0) return "默认";
        for (int i = 0; i < PRESET_UA_IDS.length; i++) {
            if (PRESET_UA_IDS[i] == id) return PRESET_UA_NAMES[i];
        }
        CustomUaItem item = getCustomUa(id);
        if (item != null) return item.name;
        return "默认";
    }

    /**
     * 计算并输出最终生效的 User-Agent 字符串。
     * 优先级严格遵循 Via 原版规范：
     * 1. 处于电脑模式时 -> 电脑模式选中的 UA（默认 Windows Chrome）
     * 2. 普通模式 -> 站点选中的 UA ID，未覆盖时使用全局选项
     * 3. 站点默认 ID 0 为原厂 WebView UA，-999 为该站点原始自定义值
     * 4. 若开启简化 UA -> 进行设备与版本信息脱敏过滤
     */
    public String getEffectiveUserAgent(boolean isDesktopMode, String host, String defaultWebViewUa) {
        String finalUa;
        if (isDesktopMode) {
            long dId = desktopUaSelectedId();
            if (dId == 0 || dId == UA_ID_WINDOWS_CHROME) {
                finalUa = getPresetUaString(UA_ID_WINDOWS_CHROME);
            } else if (dId < 0) {
                finalUa = getPresetUaString((int) dId);
                if (finalUa == null) finalUa = getPresetUaString(UA_ID_WINDOWS_CHROME);
            } else {
                CustomUaItem item = getCustomUa(dId);
                finalUa = (item != null && !item.ua.isEmpty()) ? item.ua : getPresetUaString(UA_ID_WINDOWS_CHROME);
            }
        } else {
            long siteId = siteSettingsEnabled(host) ? siteUaSelectedId(host) : SITE_UA_ID_INHERIT;
            long id = siteId == SITE_UA_ID_INHERIT ? uaSelectedId() : siteId;
            if (id == 0) {
                // Preserve the legacy global raw-UA preference, but an explicit site ID 0 is native.
                finalUa = siteId == UA_ID_DEFAULT || customUserAgent().isEmpty() ? defaultWebViewUa : customUserAgent();
            } else if (id == SITE_UA_ID_CUSTOM) {
                finalUa = siteUserAgent(host);
            } else if (id < 0) {
                finalUa = getPresetUaString((int) id);
                if (finalUa == null) finalUa = defaultWebViewUa;
            } else {
                CustomUaItem item = getCustomUa(id);
                finalUa = (item != null && !item.ua.isEmpty()) ? item.ua : defaultWebViewUa;
            }
        }

        if (finalUa == null || finalUa.isEmpty()) {
            finalUa = defaultWebViewUa;
        }

        if (simpleUa()) {
            finalUa = simplifyUa(finalUa);
        }
        return finalUa;
    }

    /** 简化浏览器标识（对齐 Via 原版 AbstractC0676z3.m3437h） */
    public static String simplifyUa(String str) {
        if (str == null || str.length() < 8) return str;
        int iIndexOf = str.indexOf("Android");
        if (iIndexOf < 0) return str;
        int iIndexOf2 = str.indexOf(") AppleWebKit", iIndexOf);
        if (iIndexOf2 < 0) return str;

        StringBuilder sb2 = new StringBuilder();
        sb2.append(str, 0, iIndexOf + 7);
        sb2.append(" 10; K");
        int iIndexOf3 = str.indexOf("Chrome/", iIndexOf2);
        if (iIndexOf3 < 0) {
            sb2.append(str, iIndexOf2, str.length());
        } else {
            int iIndexOf4 = str.indexOf(".", iIndexOf3 + 7);
            if (iIndexOf4 > 0) {
                sb2.append(str, iIndexOf2, iIndexOf4 + 1);
                sb2.append("0.0.0");
                int iIndexOf5 = str.indexOf(" ", iIndexOf4);
                if (iIndexOf5 > 0) {
                    sb2.append(str, iIndexOf5, str.length());
                }
            } else {
                sb2.append(str, iIndexOf2, str.length());
            }
        }
        return sb2.toString();
    }
    // ===== VIA 网站设定：按网址 authority 保存覆盖项（含端口） =====

    public boolean incognitoMode() { return b("incognito_mode",false); }
    public void setIncognitoMode(boolean enabled) { put("incognito_mode",enabled); }
    public boolean effectiveIncognito(String url) {
        String host=siteKey(url);
        int mode=siteSettingsEnabled(host)?siteIncognitoMode(host):-1;
        return mode<0?incognitoMode():mode==1;
    }
    public boolean siteSettingsEnabled(String host) { return setContains(K_SITE_ENABLED, host); }
    public void setSiteSettingsEnabled(String host, boolean on) { setToggle(K_SITE_ENABLED, host, on); }

    public int siteJsMode(String host) { return siteMode(K_SITE_JS_MODE, host); }
    public void setSiteJsMode(String host, int mode) { setSiteMode(K_SITE_JS_MODE, host, mode); }

    public int siteImagesMode(String host) { return siteMode(K_SITE_IMAGES_MODE, host); }
    public void setSiteImagesMode(String host, int mode) { setSiteMode(K_SITE_IMAGES_MODE, host, mode); }

    public int siteDesktopMode(String host) { return siteMode(K_SITE_DESKTOP_MODE, host); }
    public void setSiteDesktopMode(String host, int mode) { setSiteMode(K_SITE_DESKTOP_MODE, host, mode); }

    public int siteAdBlockMode(String host) { return siteMode(K_SITE_ADBLOCK_MODE, host); }
    public void setSiteAdBlockMode(String host, int mode) { setSiteMode(K_SITE_ADBLOCK_MODE, host, mode); }

    public int siteIncognitoMode(String host) { return siteMode(K_SITE_INCOGNITO_MODE, host); }
    public void setSiteIncognitoMode(String host, int mode) { setSiteMode(K_SITE_INCOGNITO_MODE, host, mode); }

    public boolean siteJsOff(String host) { return siteJsMode(host) == 0 || setContains(K_SITE_JS_OFF, host); }
    public void setSiteJsOff(String host, boolean off) { setSiteJsMode(host, off ? 0 : 1); setToggle(K_SITE_JS_OFF, host, off); }

    public boolean siteImagesOff(String host) { return siteImagesMode(host) == 0 || setContains(K_SITE_IMAGES_OFF, host); }
    public void setSiteImagesOff(String host, boolean off) { setSiteImagesMode(host, off ? 0 : 1); setToggle(K_SITE_IMAGES_OFF, host, off); }

    public boolean siteDesktopOn(String host) { return siteDesktopMode(host) == 1 || setContains(K_SITE_DESKTOP_ON, host); }
    public void setSiteDesktopOn(String host, boolean on) { setSiteDesktopMode(host, on ? 1 : 0); setToggle(K_SITE_DESKTOP_ON, host, on); }

    public boolean siteIncognitoOn(String host) { return siteIncognitoMode(host) == 1 || setContains(K_SITE_INCOGNITO_ON, host); }
    public void setSiteIncognitoOn(String host, boolean on) { setSiteIncognitoMode(host, on ? 1 : 0); setToggle(K_SITE_INCOGNITO_ON, host, on); }

    public String siteUserAgent(String host) {
        return mapGet(K_SITE_UA, host, "");
    }

    public void setSiteUserAgent(String host, String ua) {
        mapPut(K_SITE_UA, host, ua == null ? "" : ua);
        setSiteUaSelectedId(host, ua == null || ua.isEmpty() ? SITE_UA_ID_INHERIT : SITE_UA_ID_CUSTOM);
    }

    /** -1000=跟随全局，-999=站点原始自定义值，0=原厂，-1..-8=预设，正数=自定义库 ID。 */
    public long siteUaSelectedId(String host) {
        String stored = mapGet(K_SITE_UA_SELECTED_ID, host, "");
        if (!stored.isEmpty()) try { return Long.parseLong(stored); } catch (NumberFormatException ignored) { }
        // Existing raw strings remain custom; equal UA text does not imply a preset selection.
        return siteUserAgent(host).isEmpty() ? SITE_UA_ID_INHERIT : SITE_UA_ID_CUSTOM;
    }

    public void setSiteUaSelectedId(String host, long id) {
        mapPut(K_SITE_UA_SELECTED_ID, host, String.valueOf(id));
    }

    public int siteTextZoom(String host, int fallback) {
        String v = mapGet(K_SITE_TEXT_ZOOM, host, "");
        if (v.isEmpty()) return fallback;
        try { return Integer.parseInt(v); } catch (Exception ignored) { return fallback; }
    }

    public void setSiteTextZoom(String host, int zoom) {
        mapPut(K_SITE_TEXT_ZOOM, host, zoom > 0 ? String.valueOf(zoom) : "");
    }

    public boolean siteAdBlockOff(String host) {
        return setContains(K_SITE_ADBLOCK_OFF, host);
    }

    public void setSiteAdBlockOff(String host, boolean off) {
        setToggle(K_SITE_ADBLOCK_OFF, host, off);
    }

    public boolean siteCookiesOff(String host) {
        return setContains(K_SITE_COOKIES_OFF, host);
    }

    public void setSiteCookiesOff(String host, boolean off) {
        setToggle(K_SITE_COOKIES_OFF, host, off);
    }

    public void resetSiteSettings(String host) {
        String h = host == null ? "" : host;
        if (h.isEmpty()) return;
        mapPut(K_SITE_UA, h, "");
        mapPut(K_SITE_UA_SELECTED_ID, h, "");
        mapPut(K_SITE_TEXT_ZOOM, h, "");
        setToggle(K_SITE_ADBLOCK_OFF, h, false);
        setToggle(K_SITE_COOKIES_OFF, h, false);
        setToggle(K_SITE_ENABLED, h, false);
        setToggle(K_SITE_JS_OFF, h, false);
        setToggle(K_SITE_IMAGES_OFF, h, false);
        setToggle(K_SITE_DESKTOP_ON, h, false);
        setToggle(K_SITE_INCOGNITO_ON, h, false);
        mapPut(K_SITE_JS_MODE, h, "");
        mapPut(K_SITE_IMAGES_MODE, h, "");
        mapPut(K_SITE_DESKTOP_MODE, h, "");
        mapPut(K_SITE_ADBLOCK_MODE, h, "");
        mapPut(K_SITE_INCOGNITO_MODE, h, "");
        mapPut("site_redirect_mode", h, "");
        for (String permission : new String[]{"microphone", "camera", "clipboard", "open_apps", "location"})
            mapPut("site_permission_" + permission, h, "");
        mapPut("site_back_no_reload", h, "");
    }

    private int siteMode(String key, String host) {
        String v = mapGet(key, host, "");
        if (v.isEmpty()) return -1;
        try {
            int parsed = Integer.parseInt(v);
            return normalizeSiteMode(parsed);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void setSiteMode(String key, String host, int mode) {
        String h = host == null ? "" : host;
        if (mode >= 0 && mode <= 3) mapPut(key, h, String.valueOf(mode));
        else mapPut(key, h, "");
    }

    static int normalizeSiteMode(int mode) { return mode >= 0 && mode <= 3 ? mode : -1; }

    /** 网站设定权限：-1 跟随全局，1 允许，2 禁止，3 询问。 */
    public int sitePermissionMode(String name, String host) { return siteMode("site_permission_" + name, host); }
    public void setSitePermissionMode(String name, String host, int mode) { setSiteMode("site_permission_" + name, host, mode); }
    public int siteBackNoReloadMode(String host) { return siteMode("site_back_no_reload", host); }
    public void setSiteBackNoReloadMode(String host, int mode) { setSiteMode("site_back_no_reload", host, mode); }

    /** Installed Via j6.i0.f: exact authority, shared across schemes but including an explicit port. */
    public static String siteKey(String url) {
        if (url == null) return "";
        int separator = url.indexOf("://");
        if (separator < 0) return "";
        int start = separator + 3;
        int end = url.indexOf('/', start);
        return url.substring(start, end < 0 ? url.length() : end);
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

    // ===== VIA 设置树扩展键（对齐 7.2.1，全部真实持久化） =====

    private String s(String key, String def) { return sp.getString(key, def); }
    private int i(String key, int def) { return sp.getInt(key, def); }
    private boolean b(String key, boolean def) { return sp.getBoolean(key, def); }
    private void put(String key, String v) { sp.edit().putString(key, v).apply(); }
    private void put(String key, int v) { sp.edit().putInt(key, v).apply(); }
    private void put(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }

    /** 通用-同步。 */
    public String syncServer() { return s("sync_server", "global"); }
    public void setSyncServer(String v) { put("sync_server", v); }
    public String webdavUrl() { return s("webdav_url", ""); }
    public void setWebdavUrl(String v) { put("webdav_url", v); }
    public String webdavUser() { return s("webdav_user", ""); }
    public void setWebdavUser(String v) { put("webdav_user", v); }
    public String webdavPass() { return s("webdav_pass", ""); }
    public void setWebdavPass(String v) { put("webdav_pass", v); }
    public boolean webdavAutoSync() { return b("webdav_auto_sync", false); }
    public void setWebdavAutoSync(boolean v) { put("webdav_auto_sync", v); }
    public int webdavSyncData() { return i("webdav_sync_data", 0); }
    public void setWebdavSyncData(int v) { put("webdav_sync_data", v); }

    /** 通用-浏览器标识：简化标识。 */
    public boolean simpleUa() { return b("simple_ua", false); }
    public void setSimpleUa(boolean v) { put("simple_ua", v); }

    /** 广告拦截子项与统计。 */
    public boolean adBlockBuiltin() { return b("adblock_builtin", true); }
    public void setAdBlockBuiltin(boolean v) { put("adblock_builtin", v); }
    public boolean adBlockExpand() { return b("adblock_expand", true); }
    public void setAdBlockExpand(boolean v) { put("adblock_expand", v); }
    /** 订阅更新间隔（毫秒，默认 3 天）。 */
    public long adBlockSubUpdateInterval() { return sp.getLong("adblock_sub_interval", 0L); }
    public void setAdBlockSubUpdateInterval(long ms) {
        sp.edit().putLong("adblock_sub_interval", ms).putLong("adblock_sub_lastcheck", 0L).apply();
    }
    /** 上次订阅更新检查时间（>1h 才真正联网检查）。 */
    public long adBlockSubLastCheck() { return sp.getLong("adblock_sub_lastcheck", 0); }
    public void setAdBlockSubLastCheck(long ms) { sp.edit().putLong("adblock_sub_lastcheck", ms).apply(); }
    /** 页面重定向询问（0=允许,1=询问；跨域非手势跳转防劫持，Via 默认允许=0）。 */
    public int redirectAskMode() { return i("redirect_ask_mode", 0); }
    public void setRedirectAskMode(int v) { put("redirect_ask_mode", v); }
    public int siteRedirectMode(String host) { return siteMode("site_redirect_mode", host); }
    public void setSiteRedirectMode(String host, int mode) { setSiteMode("site_redirect_mode", host, mode); }
    public long adBlockBlockedCount() { return sp.getLong("adblock_count", 0); }
    public void addAdBlockBlocked(long n) { sp.edit().putLong("adblock_count", adBlockBlockedCount() + n).apply(); }
    public long adBlockSavedBytes() { return sp.getLong("adblock_bytes", 0); }
    public void addAdBlockBytes(long n) { sp.edit().putLong("adblock_bytes", adBlockSavedBytes() + n).apply(); }
    public List<String> adBlockRuleSubscriptions() {
        return new ArrayList<>(sp.getStringSet("adblock_subs", new LinkedHashSet<String>()));
    }
    public void addAdBlockSubscription(String url) {
        java.util.Set<String> set = new LinkedHashSet<>(sp.getStringSet("adblock_subs", new LinkedHashSet<String>()));
        set.add(url);
        sp.edit().putStringSet("adblock_subs", set).apply();
    }
    public void removeAdBlockSubscription(String url) {
        java.util.Set<String> set = new LinkedHashSet<>(sp.getStringSet("adblock_subs", new LinkedHashSet<String>()));
        set.remove(url);
        sp.edit().putStringSet("adblock_subs", set).apply();
    }

    /** 网站设定-内容全局项。 */
    public boolean desktopMode() { return b("desktop_mode", sp.getInt(K_UA, 1) == 0); }
    public void setDesktopMode(boolean v) { sp.edit().putBoolean("desktop_mode", v).putInt(K_UA, v ? 0 : 1).apply(); }
    public boolean cookiesEnabled() { return b("cookies_enabled", true); }
    public void setCookiesEnabled(boolean v) { put("cookies_enabled", v); }
    public boolean popupsEnabled() { return b("popups_enabled", true); }
    public void setPopupsEnabled(boolean v) { put("popups_enabled", v); }

    /** 网站设定-权限（allow/ask/block）。 */
    public String permission(String name) { return s("perm_" + name, defaultPerm(name)); }
    public void setPermission(String name, String v) { put("perm_" + name, v); }

    public String permission(String name, String host) {
        if (siteSettingsEnabled(host)) {
            int mode = sitePermissionMode(name, host);
            if (mode == 1) return "allow";
            if (mode == 2) return "block";
            if (mode == 3) return "ask";
        }
        return s("perm_exception_" + name + "_" + normalizeHost(host), permission(name));
    }

    public Set<String> permissionExceptionHosts(String name) {
        return new LinkedHashSet<>(sp.getStringSet("perm_exception_hosts_" + name, Collections.emptySet()));
    }

    public void setPermissionException(String name, String host, String mode) {
        host = normalizeHost(host);
        Set<String> hosts = permissionExceptionHosts(name);
        SharedPreferences.Editor edit = sp.edit();
        if (mode == null) {
            hosts.remove(host);
            edit.remove("perm_exception_" + name + "_" + host);
        } else {
            hosts.add(host);
            edit.putString("perm_exception_" + name + "_" + host, mode);
        }
        edit.putStringSet("perm_exception_hosts_" + name, hosts).apply();
    }

    private static String defaultPerm(String name) {
        switch (name) {
            case "location":
            case "vibration":
                return "block";
            case "open_apps":
            case "camera":
            case "microphone":
                return "ask";
            default:
                return "allow";
        }
    }

    public boolean backNoReload() { return b("back_no_reload", true); }
    public void setBackNoReload(boolean v) { put("back_no_reload", v); }

    /** 密码管理器。 */
    public boolean passwordSaveHint() { return b("password_save_hint", true); }
    public void setPasswordSaveHint(boolean v) { put("password_save_hint", v); }

    /** 夜间模式。 */
    public boolean nightMask() { return b("night_mask", true); }
    public void setNightMask(boolean v) { put("night_mask", v); }
    public int nightMaskStrength() { return i("night_mask_strength", 25); }
    public void setNightMaskStrength(int v) { put("night_mask_strength", v); }
    public boolean forceDarkPages() { return b("force_dark_pages", true); }
    public void setForceDarkPages(boolean v) { put("force_dark_pages", v); }

    /** 阅读模式。 */
    public boolean readerConfirm() { return b("reader_confirm", false); }
    public void setReaderConfirm(boolean v) { put("reader_confirm", v); }
    public int readerTheme() { return i("reader_theme", 0); }
    public void setReaderTheme(int v) { put("reader_theme", v); }
    public int readerFont() { return i("reader_font", 17); }
    public void setReaderFont(int v) { put("reader_font", v); }
    public String readerCss() { return s("reader_css", ""); }
    public void setReaderCss(String v) { put("reader_css", v); }

    /** 工具栏设置。 */
    public int toolbarAutoHide() { return i("toolbar_autohide", 0); }
    public void setToolbarAutoHide(int v) { put("toolbar_autohide", v); }
    public boolean tabBarEnabled() { return b("tab_bar", false); }
    public void setTabBarEnabled(boolean v) { put("tab_bar", v); }
    public int addressContent() { return i("address_content", 0); }
    public void setAddressContent(int v) { put("address_content", v); }
    public boolean adaptiveColor() { return b("adaptive_color", true); }
    public void setAdaptiveColor(boolean v) { put("adaptive_color", v); }

    /** 定制长按菜单（flag 集合）。 */
    public static final String[] LONG_PRESS_FLAGS = {
            "bg_open", "new_tab", "view_image", "save_image", "download_image", "share_image",
            "search_image", "image_mode", "page_info", "mark_ad", "copy_link_text",
            "scan_qr", "copy_link", "share"
    };
    private static final String[] LONG_PRESS_DEFAULT_ON = {
            "bg_open", "new_tab", "view_image", "download_image", "share_image",
            "search_image", "image_mode", "page_info", "mark_ad", "copy_link_text",
            "scan_qr", "copy_link", "share"
    };
    public Set<String> longPressFlags() {
        try {
            return sp.getStringSet("longpress_flags",
                    new LinkedHashSet<>(java.util.Arrays.asList(LONG_PRESS_DEFAULT_ON)));
        } catch (ClassCastException e) {
            // prefs 被外部改写/类型污染时回退默认，不让浏览器崩溃
            return new LinkedHashSet<>(java.util.Arrays.asList(LONG_PRESS_DEFAULT_ON));
        }
    }
    public void setLongPressFlags(Set<String> v) { sp.edit().putStringSet("longpress_flags", v).apply(); }

    /** 语言 / 主页 / AI。 */
    public int language() { return i("language", 0); }
    public void setLanguage(int v) { put("language", v); }
    public int homeMode() { return i("home_mode", 0); }
    public void setHomeMode(int v) { put("home_mode", v); }
    public String homeCustomUrl() { return s("home_custom_url", "https://www.baidu.com"); }
    public void setHomeCustomUrl(String v) { put("home_custom_url", v); }
    public int aiService() { return i("ai_service", 0); }
    public void setAiService(int v) { put("ai_service", v); }
    public String aiEndpoint() { return s("ai_endpoint", ""); }
    public String aiProviderId() { return s("ai_provider_id", ""); }
    public synchronized List<BrowserAiStore.Provider> aiProviders() {
        BrowserAiStore store = aiStore();
        if (!sp.getBoolean("ai_providers_migrated", false)) {
            if (!aiProviderName().isEmpty()) {
                BrowserAiStore.Provider provider = store.saveProvider(null, aiProviderName(), aiEndpoint(), aiApiKey(), aiModels(), aiModel());
                sp.edit().putString("ai_provider_id", provider.id).putBoolean("ai_providers_migrated", true).apply();
            } else sp.edit().putBoolean("ai_providers_migrated", true).apply();
        }
        return store.providers();
    }
    public void selectAiProvider(BrowserAiStore.Provider provider) {
        String preferred = s("ai_preferred_model", s("ai_model", ""));
        String effective = "";
        if (provider != null) {
            for (String model : provider.models.split("\n")) {
                model = model.trim();
                if (model.isEmpty()) continue;
                if (effective.isEmpty()) effective = model;
                if (model.equals(preferred)) { effective = model; break; }
            }
        }
        sp.edit().putString("ai_preferred_model", preferred)
                .putString("ai_provider_id", provider == null ? "" : provider.id)
                .putString("ai_provider_name", provider == null ? "" : provider.name)
                .putString("ai_endpoint", provider == null ? "" : provider.endpoint)
                .putString("ai_api_key", provider == null ? "" : provider.key)
                .putString("ai_models", provider == null ? "" : provider.models)
                .putString("ai_model", effective).apply();
    }
    public void setAiEndpoint(String v) { put("ai_endpoint", v); }
    public String aiModel() { return s("ai_model", ""); }
    public void setAiModel(String v) { sp.edit().putString("ai_model", v).putString("ai_preferred_model", v).apply(); }
    public String aiProviderName() { return s("ai_provider_name", ""); }
    public void setAiProviderName(String v) { put("ai_provider_name", v); }
    public String aiApiKey() { return s("ai_api_key", ""); }
    public void setAiApiKey(String v) { put("ai_api_key", v); }
    public String aiModels() { return s("ai_models", ""); }
    public void setAiModels(String v) { put("ai_models", v); }
    public String aiPrompt() { return s("ai_prompt", ""); }
    public void setAiPrompt(String v) { sp.edit().putString("ai_prompt", v).putBoolean("ai_prompt_migrated", false).apply(); }
    public String aiDefaultPromptId() { return s("ai_default_prompt", ""); }
    public void setAiDefaultPromptId(String id) { put("ai_default_prompt", id); }

    /** Via cloud account. Only Via's one-way password digest is persisted. */
    public String cloudUsername() { return s("cloud_username", ""); }
    public void setCloudUsername(String v) { put("cloud_username", v); }
    public String cloudPasswordHash() { return s("cloud_password_hash", ""); }
    public void setCloudPasswordHash(String v) { put("cloud_password_hash", v); }
    public boolean cloudLoggedIn() { return b("cloud_logged_in", false); }
    public void setCloudLoggedIn(boolean v) { put("cloud_logged_in", v); }
    public void clearCloudAccount() {
        sp.edit().remove("cloud_username").remove("cloud_password_hash")
                .putBoolean("cloud_logged_in", false).apply();
    }

    /** 方向 / 下载 / 退出清除 / 会话恢复。 */
    public int orientationMode() { return i("orientation", 0); }
    public void setOrientationMode(int v) { put("orientation", v); }

    /**
     * 设置→下载目录 解析为可用 File；未配置/不可写返回 null（调用方回退原路径）。
     * P0③ 合一：浏览器四处落盘路径统一由此消费。
     */
    public java.io.File downloadDirFile() {
        try {
            String configured = downloadDir();
            if (configured == null || configured.trim().isEmpty()) return null;
            java.io.File dir = new java.io.File(configured.trim());
            if (!dir.exists()) dir.mkdirs();
            if (dir.isDirectory() && dir.canWrite()) return dir;
        } catch (Exception ignored) {
        }
        return null;
    }
    public String downloadDir() { return s("download_dir", "/sdcard/Download"); }
    public void setDownloadDir(String v) { put("download_dir", v); }
    public int downloadManager() { return i("download_manager", 0); }
    public void setDownloadManager(int v) { put("download_manager", v); }
    public int externalPlayer() { return i("external_player", 0); }
    public void setExternalPlayer(int v) { put("external_player", v); }
    /** 退出时清除数据（默认空＝不清除；用户在设置里勾选后生效）。 */
    public Set<String> exitClearFlags() {
        return sp.getStringSet("exit_clear", new LinkedHashSet<>());
    }
    public void setExitClearFlags(Set<String> v) { sp.edit().putStringSet("exit_clear", v).apply(); }
    public int restoreTabs() { return i("restore_tabs", 0); }
    public void setRestoreTabs(int v) { put("restore_tabs", v); }
    public boolean undoCloseToast() { return b("undo_close_toast", true); }
    public void setUndoCloseToast(boolean v) { put("undo_close_toast", v); }

    /** 操作设定。 */
    public boolean swipeNavigation() { return b("swipe_navigation", true); }
    public void setSwipeNavigation(boolean v) { put("swipe_navigation", v); }
    public boolean videoGestures() { return b("video_gestures", true); }
    public void setVideoGestures(boolean v) { put("video_gestures", v); }
    public int gestureAction(String key) { return i("gesture_action_" + key, BrowserActions.defaultAction(key)); }
    public void setGestureAction(String key, int action) { put("gesture_action_" + key, action); }



    /** 会话持久化（P0③ 启动恢复标签）：JSON 数组 [{u:url,t:title}]。 */
    public String sessionTabs() { return s("session_tabs", ""); }
    public void setSessionTabs(String json) { put("session_tabs", json); }
    public int sessionSelectedTab() { return i("session_selected_tab", 0); }
    public void setSessionSelectedTab(int index) { put("session_selected_tab", index); }
    public String closedTabs() { return s("closed_tabs", ""); }
    public void setClosedTabs(String json) { put("closed_tabs", json); }
    public boolean volumeKeyScroll() { return b("volume_scroll", false); }
    public void setVolumeKeyScroll(boolean v) { put("volume_scroll", v); }

    /** 脚本（名称 → {match, code}）。 */
    public List<String> scriptNames() {
        return new ArrayList<>(sp.getStringSet("script_names", new LinkedHashSet<String>()));
    }
    public String scriptMatch(String name) { return s("script_match_override_" + name, s("script_match_" + name, "*")); }
    public void setScriptMatchOverride(String name, String rules) { put("script_match_override_" + name, rules); }
    public String scriptRunAtOverride(String name) { return s("script_run_override_" + name, ""); }
    public void setScriptRunAtOverride(String name, String value) { put("script_run_override_" + name, value); }
    public String scriptExcludes(String name) {
        return s("script_exclude_override_" + name, android.text.TextUtils.join("\n", BrowserUserScripts.parse(scriptCode(name)).excludes));
    }
    public void setScriptExcludes(String name, String value) { put("script_exclude_override_" + name, value); }
    public void resetScriptOverrides(String name) {
        sp.edit().remove("script_match_override_" + name).remove("script_run_override_" + name)
                .remove("script_exclude_override_" + name)
                .putString("script_match_" + name, BrowserUserScripts.parse(scriptCode(name)).matchText()).apply();
    }
    public String scriptExecutionCode(String name) {
        String code = sp.contains("script_match_override_" + name)
                ? BrowserUserScripts.withMatchRules(scriptCode(name), scriptMatch(name)) : scriptCode(name);
        if (!scriptRunAtOverride(name).isEmpty()) code = BrowserUserScripts.withMetadataRules(code, "run-at", "run-at", scriptRunAtOverride(name));
        if (sp.contains("script_exclude_override_" + name)) code = BrowserUserScripts.withMetadataRules(code, "exclude|exclude-match", "exclude", scriptExcludes(name));
        return code;
    }
    public String scriptCode(String name) { return s("script_code_" + name, ""); }
    public long scriptCreatedAt(String name) { return sp.getLong("script_created_"+name,0); }
    public long scriptUpdatedAt(String name) { return sp.getLong("script_updated_"+name,0); }
    public String scriptStorageId(String name) {
        synchronized (BrowserPrefs.class) {
            String id = s("script_id_" + name, "");
            if (id.isEmpty()) { id = java.util.UUID.randomUUID().toString(); put("script_id_" + name, id); }
            return id;
        }
    }

    public void preserveScriptIdentity(String oldName, String newName) {
        if (oldName != null && !oldName.equals(newName)) {
            put("script_id_" + newName, scriptStorageId(oldName));
            sp.edit().putLong("script_created_"+newName,scriptCreatedAt(oldName)).apply();
            if (sp.contains("script_match_override_" + oldName))
                setScriptMatchOverride(newName, scriptMatch(oldName));
            for (String prefix : new String[]{"script_run_override_", "script_exclude_override_"})
                if (sp.contains(prefix + oldName)) put(prefix + newName, s(prefix + oldName, ""));
            sp.edit().remove("script_id_" + oldName).apply();
        }
    }
    public void saveScript(String name, String match, String code) {
        java.util.Set<String> set = new LinkedHashSet<>(sp.getStringSet("script_names", new LinkedHashSet<String>()));
        long now=System.currentTimeMillis();
        long created=sp.contains("script_created_"+name)?scriptCreatedAt(name):(set.contains(name)?0:now);
        set.add(name);
        sp.edit().putStringSet("script_names", set)
                .putLong("script_created_"+name,created).putLong("script_updated_"+name,now)
                .putString("script_match_" + name, match)
                .putString("script_code_" + name, code).apply();
    }
    public void removeScript(String name) {
        String id = s("script_id_" + name, "");
        if (!id.isEmpty()) appContextRef.getSharedPreferences("via_script_values", Context.MODE_PRIVATE).edit().remove(id).apply();
        java.util.Set<String> set = new LinkedHashSet<>(sp.getStringSet("script_names", new LinkedHashSet<String>()));
        set.remove(name);
        sp.edit().putStringSet("script_names", set).remove("script_match_" + name)
                .remove("script_created_"+name).remove("script_updated_"+name)
                .remove("script_run_override_" + name).remove("script_exclude_override_" + name)
                .remove("script_match_override_" + name).remove("script_code_" + name).remove("script_enabled_" + name).remove("script_id_" + name).apply();
    }

    public boolean scriptsEnabled() { return b("scripts_enabled", true); }
    public void setScriptsEnabled(boolean enabled) { put("scripts_enabled", enabled); }
    public boolean isScriptEnabled(String name) { return b("script_enabled_" + name, true); }
    public void setScriptEnabled(String name, boolean v) { put("script_enabled_" + name, v); }

    /** 隐私-不出售或分享数据。 */
    public boolean doNotSell() { return b("do_not_sell", false); }
    public void setDoNotSell(boolean v) { put("do_not_sell", v); }

    /** 网站设定-所有网站列表（并集）。 */
    public Set<String> siteEnabledHosts() {
        java.util.Set<String> out = new LinkedHashSet<>();
        for (String key : new String[]{K_SITE_ENABLED, K_SITE_ADBLOCK_OFF, K_SITE_COOKIES_OFF,
                K_SITE_JS_OFF, K_SITE_IMAGES_OFF, K_SITE_DESKTOP_ON, K_SITE_INCOGNITO_ON})
            out.addAll(sp.getStringSet(key, java.util.Collections.emptySet()));
        for (String key : new String[]{K_SITE_JS_MODE, K_SITE_IMAGES_MODE, K_SITE_DESKTOP_MODE,
                K_SITE_ADBLOCK_MODE, K_SITE_INCOGNITO_MODE, K_SITE_UA, K_SITE_UA_SELECTED_ID, K_SITE_TEXT_ZOOM,
                "site_permission_microphone", "site_permission_camera", "site_permission_clipboard",
                "site_permission_open_apps", "site_permission_location", "site_back_no_reload",
                "site_redirect_mode"}) {
            String stored = sp.getString(key, "");
            if (stored == null) continue;
            for (String pair : stored.split("\n")) {
                int split = pair.indexOf('=');
                if (split > 0) out.add(pair.substring(0, split));
            }
        }
        return out;
    }

    public synchronized BrowserAiStore aiStore() {
        BrowserAiStore store = BrowserAiStore.getInstance(appContextRef);
        if (!sp.getBoolean("ai_prompt_migrated", false)) {
            String legacy = aiPrompt();
            if (!legacy.trim().isEmpty()) {
                BrowserAiPrompt prompt = store.savePrompt(null, "自定义", legacy, 1);
                sp.edit().putString("ai_default_prompt", prompt.id).remove("ai_prompt")
                        .putBoolean("ai_prompt_migrated", true).apply();
            } else sp.edit().putBoolean("ai_prompt_migrated", true).apply();
        }
        if (sp.contains("ai_themes")) {
            store.importLegacy(sp.getStringSet("ai_themes", new LinkedHashSet<String>()));
            sp.edit().remove("ai_themes").apply();
        }
        return store;
    }

    /** Compatibility access for themes created before message persistence. */
    public List<String> aiThemes() {
        List<String> names = new ArrayList<>();
        for (BrowserAiStore.Topic topic : aiStore().topics()) names.add(topic.name);
        return names;
    }
    public void addAiTheme(String name) {
        if (!aiThemes().contains(name)) aiStore().create(name, aiPrompt());
    }
    public void removeAiTheme(String name) {
        for (BrowserAiStore.Topic topic : aiStore().topics()) {
            if (topic.name.equals(name)) aiStore().delete(topic.id);
        }
    }

    /** 数据库便捷访问（书签/历史/离线页）。 */
    public BrowserDatabaseHelper dbHelper() {
        return BrowserDatabaseHelper.getInstance(appContextRef);
    }
}
