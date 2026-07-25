package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.proxy.ProxyActivity;
import com.example.cleanrecovery.proxy.ProxyRouter;
import com.example.cleanrecovery.ui.browser.BrowserDatabaseHelper;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.SearchEngines;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * VIA 风格设置：首页分类 → 子页列表（对齐模拟器实测 VIA 7.2.1）。
 *
 * <p>本工程扩展：在「高级」中保留 {@link ProxyActivity} 入口。</p>
 */
public final class BrowserSettingsActivity extends Activity {

    public static final String EXTRA_OPEN_CUSTOMIZER = "open_customizer";

    private enum Page {
        ROOT,
        GENERAL,
        CUSTOMIZE,
        PRIVACY,
        ADVANCED,
        SCRIPTS,
        ABOUT,
        UA,
        TOOLBAR,
        ADBLOCK,
        SEARCH,
        HOME,
        FONT_SIZE
    }

    private enum RowType { NAV, TOGGLE, ACTION, INFO }

    private static final class Row {
        final RowType type;
        final String title;
        final String subtitle;
        final boolean checked;
        final Runnable onClick;
        final Runnable onToggle;

        Row(RowType type, String title, String subtitle, boolean checked,
                Runnable onClick, Runnable onToggle) {
            this.type = type;
            this.title = title;
            this.subtitle = subtitle;
            this.checked = checked;
            this.onClick = onClick;
            this.onToggle = onToggle;
        }

        static Row nav(String title, String subtitle, Runnable onClick) {
            return new Row(RowType.NAV, title, subtitle, false, onClick, null);
        }

        static Row toggle(String title, String subtitle, boolean on, Runnable onToggle) {
            return new Row(RowType.TOGGLE, title, subtitle, on, null, onToggle);
        }

        static Row action(String title, String subtitle, Runnable onClick) {
            return new Row(RowType.ACTION, title, subtitle, false, onClick, null);
        }

        static Row info(String title, String subtitle) {
            return new Row(RowType.INFO, title, subtitle, false, null, null);
        }
    }

    private BrowserPrefs prefs;
    private TextView titleView;
    private ListView listView;
    private final Deque<Page> stack = new ArrayDeque<>();
    private final List<Row> rows = new ArrayList<>();
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_browser_settings);

        prefs = new BrowserPrefs(this);
        titleView = findViewById(R.id.settings_title);
        listView = findViewById(R.id.settings_list);
        ImageButton back = findViewById(R.id.settings_back);
        back.setOnClickListener(v -> onBackPressed());

        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= rows.size()) return;
            Row row = rows.get(position);
            if (row.type == RowType.TOGGLE) {
                Switch sw = view.findViewById(R.id.settings_row_switch);
                if (sw != null) sw.setChecked(!sw.isChecked());
            } else if (row.onClick != null) {
                row.onClick.run();
            }
        });

        open(Page.ROOT);
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            stack.removeLast();
            render(stack.peekLast());
            setResult(RESULT_OK);
            return;
        }
        setResult(RESULT_OK);
        finish();
    }

    private void open(Page page) {
        stack.addLast(page);
        render(page);
    }

    private void render(Page page) {
        rows.clear();
        switch (page) {
            case ROOT:
                titleView.setText(R.string.via_settings_title);
                rows.add(Row.nav(getString(R.string.via_settings_cat_general), null,
                        () -> open(Page.GENERAL)));
                rows.add(Row.nav(getString(R.string.via_settings_cat_customize), null,
                        () -> open(Page.CUSTOMIZE)));
                rows.add(Row.nav(getString(R.string.via_settings_cat_privacy), null,
                        () -> open(Page.PRIVACY)));
                rows.add(Row.nav(getString(R.string.via_settings_cat_advanced), null,
                        () -> open(Page.ADVANCED)));
                rows.add(Row.nav(getString(R.string.via_settings_cat_scripts), null,
                        () -> open(Page.SCRIPTS)));
                rows.add(Row.nav(getString(R.string.via_settings_cat_about), null,
                        () -> open(Page.ABOUT)));
                break;
            case GENERAL:
                titleView.setText(R.string.via_settings_cat_general);
                rows.add(Row.action(getString(R.string.via_settings_sync), null, this::toastDeveloping));
                rows.add(Row.nav(getString(R.string.via_settings_browser_id), uaPresetLabel(),
                        () -> open(Page.UA)));
                rows.add(Row.action(getString(R.string.via_settings_clear_data), null,
                        this::showClearDataDialog));
                rows.add(Row.nav(getString(R.string.via_settings_adblock),
                        prefs.adBlockEnabled()
                                ? getString(R.string.via_settings_adblock_on)
                                : getString(R.string.via_settings_adblock_off),
                        () -> open(Page.ADBLOCK)));
                rows.add(Row.action(getString(R.string.via_settings_site_conf), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_password), null,
                        this::toastDeveloping));
                rows.add(Row.toggle(getString(R.string.via_settings_night), null,
                        prefs.nightMode(), () -> {
                            prefs.setNightMode(!prefs.nightMode());
                            render(Page.GENERAL);
                        }));
                rows.add(Row.action(getString(R.string.via_settings_reader), null,
                        this::toastDeveloping));
                rows.add(Row.nav(getString(R.string.via_settings_toolbar), toolbarModeLabel(),
                        () -> open(Page.TOOLBAR)));
                rows.add(Row.action(getString(R.string.via_settings_customize_menu), null,
                        this::openCustomizer));
                rows.add(Row.action(getString(R.string.via_settings_customize_context), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_ai), null,
                        this::toastDeveloping));
                rows.add(Row.info(getString(R.string.via_settings_orientation),
                        getString(R.string.via_settings_orientation_system)));
                rows.add(Row.nav(getString(R.string.via_settings_search_engine),
                        searchEngineLabel(), () -> open(Page.SEARCH)));
                rows.add(Row.nav(getString(R.string.via_settings_home), prefs.homeUrl(),
                        () -> open(Page.HOME)));
                rows.add(Row.nav(getString(R.string.via_settings_font_size),
                        prefs.textZoom() + "%", () -> open(Page.FONT_SIZE)));
                rows.add(Row.info(getString(R.string.via_settings_download_dir),
                        "/sdcard/Download"));
                rows.add(Row.info(getString(R.string.via_settings_download_mgr),
                        getString(R.string.via_settings_download_builtin)));
                rows.add(Row.action(getString(R.string.via_settings_ext_player), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_system_share), null,
                        this::toastDeveloping));
                rows.add(Row.toggle(getString(R.string.via_settings_clear_on_exit), null,
                        prefs.clearDataOnExit(), () -> {
                            prefs.setClearDataOnExit(!prefs.clearDataOnExit());
                            render(Page.GENERAL);
                        }));
                rows.add(Row.action(getString(R.string.via_settings_font), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_gestures),
                        getString(R.string.via_settings_gesture_keys), this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_import_bookmarks), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_import_data), null,
                        this::toastDeveloping));
                break;
            case CUSTOMIZE:
                titleView.setText(R.string.via_settings_cat_customize);
                rows.add(Row.action(getString(R.string.via_settings_customize_logo), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_customize_search), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_customize_bg), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_customize_fav), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_customize_menu), null,
                        this::openCustomizer));
                rows.add(Row.nav(getString(R.string.via_settings_toolbar), toolbarModeLabel(),
                        () -> open(Page.TOOLBAR)));
                break;
            case PRIVACY:
                titleView.setText(R.string.via_settings_cat_privacy);
                rows.add(Row.toggle(getString(R.string.via_settings_dnt), null,
                        prefs.doNotTrack(), () -> {
                            prefs.setDoNotTrack(!prefs.doNotTrack());
                            render(Page.PRIVACY);
                        }));
                rows.add(Row.toggle(getString(R.string.via_settings_disable_webrtc), null,
                        prefs.disableWebRtc(), () -> {
                            prefs.setDisableWebRtc(!prefs.disableWebRtc());
                            render(Page.PRIVACY);
                        }));
                rows.add(Row.toggle(getString(R.string.via_settings_gpc),
                        getString(R.string.via_settings_gpc_desc),
                        prefs.doNotTrack(), () -> {
                            prefs.setDoNotTrack(!prefs.doNotTrack());
                            render(Page.PRIVACY);
                        }));
                rows.add(Row.toggle(getString(R.string.via_settings_js), null,
                        prefs.jsEnabled(), () -> {
                            prefs.setJsEnabled(!prefs.jsEnabled());
                            render(Page.PRIVACY);
                        }));
                rows.add(Row.toggle(getString(R.string.via_settings_images), null,
                        prefs.imagesEnabled(), () -> {
                            prefs.setImagesEnabled(!prefs.imagesEnabled());
                            render(Page.PRIVACY);
                        }));
                break;
            case ADVANCED:
                titleView.setText(R.string.via_settings_cat_advanced);
                // 本工程自有代理模块：必须保留且置于高级页显眼位置
                rows.add(Row.nav(getString(R.string.via_settings_proxy_entry),
                        proxySubtitle(),
                        () -> startActivity(new Intent(this, ProxyActivity.class))));
                rows.add(Row.toggle(getString(R.string.via_settings_data_saver),
                        getString(R.string.via_settings_data_saver_desc),
                        prefs.dataSaver(), () -> {
                            prefs.setDataSaver(!prefs.dataSaver());
                            render(Page.ADVANCED);
                        }));
                rows.add(Row.toggle(getString(R.string.via_settings_auto_sniffer), null,
                        prefs.autoSnifferButton(), () -> {
                            prefs.setAutoSnifferButton(!prefs.autoSnifferButton());
                            render(Page.ADVANCED);
                        }));
                rows.add(Row.toggle(getString(R.string.via_settings_web_debug), null,
                        prefs.webDebug(), () -> {
                            prefs.setWebDebug(!prefs.webDebug());
                            WebView.setWebContentsDebuggingEnabled(prefs.webDebug());
                            render(Page.ADVANCED);
                        }));
                rows.add(Row.toggle(getString(R.string.via_settings_disable_custom_tabs),
                        getString(R.string.via_settings_disable_custom_tabs_desc),
                        prefs.disableCustomTabs(), () -> {
                            prefs.setDisableCustomTabs(!prefs.disableCustomTabs());
                            render(Page.ADVANCED);
                        }));
                rows.add(Row.toggle(getString(R.string.via_settings_disable_safe_browsing),
                        getString(R.string.via_settings_disable_safe_browsing_desc),
                        prefs.disableSafeBrowsing(), () -> {
                            prefs.setDisableSafeBrowsing(!prefs.disableSafeBrowsing());
                            render(Page.ADVANCED);
                        }));
                rows.add(Row.toggle(getString(R.string.via_settings_ignore_ssl),
                        getString(R.string.via_settings_ignore_ssl_desc),
                        prefs.ignoreSslWarnings(), () -> {
                            prefs.setIgnoreSslWarnings(!prefs.ignoreSslWarnings());
                            render(Page.ADVANCED);
                        }));
                break;
            case SCRIPTS:
                titleView.setText(R.string.via_settings_cat_scripts);
                rows.add(Row.info(getString(R.string.via_settings_scripts_empty), null));
                break;
            case ABOUT:
                titleView.setText(R.string.via_settings_cat_about);
                rows.add(Row.info(getString(R.string.via_settings_about_name), "0.1.0"));
                rows.add(Row.action(getString(R.string.via_settings_check_update), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_contact), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_terms), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_privacy_policy), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_licenses), null,
                        this::toastDeveloping));
                break;
            case UA:
                titleView.setText(R.string.via_settings_browser_id);
                addUaRow(0, R.string.via_settings_ua_default);
                addUaRow(1, R.string.via_settings_ua_android_phone);
                addUaRow(2, R.string.via_settings_ua_android_tablet);
                addUaRow(3, R.string.via_settings_ua_chrome);
                addUaRow(4, R.string.via_settings_ua_ie);
                addUaRow(5, R.string.via_settings_ua_macos);
                addUaRow(6, R.string.via_settings_ua_iphone);
                addUaRow(7, R.string.via_settings_ua_ipad);
                addUaRow(8, R.string.via_settings_ua_symbian);
                rows.add(Row.info(getString(R.string.via_settings_ua_desktop_mode),
                        getString(R.string.via_settings_ua_chrome)));
                rows.add(Row.toggle(getString(R.string.via_settings_ua_simple),
                        getString(R.string.via_settings_ua_simple_desc),
                        prefs.simpleUserAgent(), () -> {
                            prefs.setSimpleUserAgent(!prefs.simpleUserAgent());
                            render(Page.UA);
                        }));
                break;
            case TOOLBAR:
                titleView.setText(R.string.via_settings_toolbar);
                addToolbarRow(0, R.string.via_toolbar_top);
                addToolbarRow(1, R.string.via_toolbar_bottom);
                addToolbarRow(2, R.string.via_toolbar_sandwich);
                addToolbarRow(3, R.string.via_toolbar_two_row);
                rows.add(Row.info(getString(R.string.via_settings_auto_hide_bar),
                        getString(R.string.via_settings_always_show)));
                rows.add(Row.info(getString(R.string.via_settings_enable_tab_bar), null));
                rows.add(Row.info(getString(R.string.via_settings_addr_content),
                        getString(R.string.via_settings_addr_title)));
                rows.add(Row.info(getString(R.string.via_settings_color_mode),
                        getString(R.string.via_settings_color_mode_desc)));
                break;
            case ADBLOCK:
                titleView.setText(R.string.via_settings_adblock);
                rows.add(Row.toggle(getString(R.string.via_settings_adblock), null,
                        prefs.adBlockEnabled(), () -> {
                            prefs.setAdBlockEnabled(!prefs.adBlockEnabled());
                            render(Page.ADBLOCK);
                        }));
                rows.add(Row.toggle(getString(R.string.via_settings_adblock_builtin),
                        getString(R.string.via_settings_adblock_builtin_desc),
                        prefs.adBlockEnabled(), () -> {
                            prefs.setAdBlockEnabled(!prefs.adBlockEnabled());
                            render(Page.ADBLOCK);
                        }));
                rows.add(Row.action(getString(R.string.via_settings_adblock_custom), null,
                        this::toastDeveloping));
                rows.add(Row.action(getString(R.string.via_settings_adblock_subscribe), null,
                        this::toastDeveloping));
                break;
            case SEARCH:
                titleView.setText(R.string.via_settings_search_engine);
                addSearchRow(0, R.string.via_search_engine_youtube);
                addSearchRow(1, R.string.via_search_engine_google);
                addSearchRow(2, R.string.via_search_engine_bing);
                addSearchRow(3, R.string.via_search_engine_baidu);
                addSearchRow(4, R.string.via_search_engine_duckduckgo);
                addSearchRow(5, R.string.via_search_engine_custom);
                if (prefs.searchEngine() == SearchEngines.CUSTOM
                        || prefs.searchEngine() == 5) {
                    rows.add(Row.action(getString(R.string.via_settings_search_prefix),
                            prefs.searchPrefix(), this::editSearchPrefix));
                }
                break;
            case HOME:
                titleView.setText(R.string.via_settings_home);
                rows.add(Row.action(getString(R.string.via_settings_home),
                        prefs.homeUrl(), this::editHomeUrl));
                break;
            case FONT_SIZE:
                titleView.setText(R.string.via_settings_font_size);
                for (int zoom : new int[]{75, 90, 100, 110, 125, 150, 200}) {
                    final int z = zoom;
                    String mark = prefs.textZoom() == z ? " ✓" : "";
                    rows.add(Row.action(z + "%" + mark, null, () -> {
                        prefs.setTextZoom(z);
                        render(Page.FONT_SIZE);
                    }));
                }
                break;
            default:
                break;
        }
        adapter.notifyDataSetChanged();
    }

    private void addUaRow(int preset, int titleRes) {
        String mark = prefs.uaPreset() == preset ? " ✓" : "";
        rows.add(Row.action(getString(titleRes) + mark, null, () -> {
            prefs.setUaPreset(preset);
            // 电脑类预设 → uaMode=0；移动类 → 1
            boolean desktop = preset == 3 || preset == 4 || preset == 5;
            prefs.setUaMode(desktop ? 0 : 1);
            prefs.setCustomUserAgent(uaStringForPreset(preset));
            render(Page.UA);
        }));
    }

    private void addToolbarRow(int mode, int titleRes) {
        String mark = prefs.toolbarMode() == mode ? " ✓" : "";
        rows.add(Row.action(getString(titleRes) + mark, null, () -> {
            prefs.setToolbarMode(mode);
            render(Page.TOOLBAR);
        }));
    }

    private void addSearchRow(int engine, int titleRes) {
        String mark = prefs.searchEngine() == engine ? " ✓" : "";
        rows.add(Row.action(getString(titleRes) + mark, null, () -> {
            prefs.setSearchEngine(engine);
            render(Page.SEARCH);
        }));
    }

    private String uaPresetLabel() {
        int p = prefs.uaPreset();
        int[] titles = {
                R.string.via_settings_ua_default,
                R.string.via_settings_ua_android_phone,
                R.string.via_settings_ua_android_tablet,
                R.string.via_settings_ua_chrome,
                R.string.via_settings_ua_ie,
                R.string.via_settings_ua_macos,
                R.string.via_settings_ua_iphone,
                R.string.via_settings_ua_ipad,
                R.string.via_settings_ua_symbian
        };
        if (p < 0 || p >= titles.length) p = 0;
        return getString(titles[p]);
    }

    private String toolbarModeLabel() {
        switch (prefs.toolbarMode()) {
            case 1: return getString(R.string.via_toolbar_bottom);
            case 2: return getString(R.string.via_toolbar_sandwich);
            case 3: return getString(R.string.via_toolbar_two_row);
            default: return getString(R.string.via_toolbar_top);
        }
    }

    private String searchEngineLabel() {
        switch (prefs.searchEngine()) {
            case 1: return getString(R.string.via_search_engine_google);
            case 2: return getString(R.string.via_search_engine_bing);
            case 3: return getString(R.string.via_search_engine_baidu);
            case 4: return getString(R.string.via_search_engine_duckduckgo);
            case 5: return getString(R.string.via_search_engine_custom);
            default: return getString(R.string.via_search_engine_youtube);
        }
    }

    private String proxySubtitle() {
        if (ProxyRouter.isProxyActive()) {
            String node = prefs.proxyNodeName();
            if (node == null || node.isEmpty()) {
                return getString(R.string.via_proxy_on_short);
            }
            return getString(R.string.via_proxy_on_short) + " · " + node;
        }
        return getString(R.string.via_settings_proxy_desc);
    }

    private static String uaStringForPreset(int preset) {
        switch (preset) {
            case 2:
                return "Mozilla/5.0 (Linux; Android 14; Pixel Tablet) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
            case 3:
                return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
            case 4:
                return "Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko";
            case 5:
                return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                        + "(KHTML, like Gecko) Version/17.0 Safari/605.1.15";
            case 6:
                return "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                        + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
            case 7:
                return "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) "
                        + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
            case 8:
                return "Mozilla/5.0 (SymbianOS/9.4; Series60/5.0 Nokia808PureView/20.0.011) "
                        + "AppleWebKit/533.4 (KHTML, like Gecko) NokiaBrowser/7.3.1.33 Mobile Safari/533.4";
            case 0:
            case 1:
            default:
                return "";
        }
    }

    private void openCustomizer() {
        Intent data = new Intent();
        data.putExtra(EXTRA_OPEN_CUSTOMIZER, true);
        setResult(RESULT_OK, data);
        finish();
    }

    private void toastDeveloping() {
        Toast.makeText(this, R.string.via_settings_developing, Toast.LENGTH_SHORT).show();
    }

    private void editHomeUrl() {
        EditText input = new EditText(this);
        input.setText(prefs.homeUrl());
        input.setHint(R.string.via_settings_home_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_settings_home)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String home = input.getText().toString().trim();
                    if (home.isEmpty()) home = "about:home";
                    prefs.setHomeUrl(home);
                    render(Page.HOME);
                })
                .show();
    }

    private void editSearchPrefix() {
        EditText input = new EditText(this);
        input.setText(prefs.searchPrefix());
        input.setHint(R.string.via_settings_search_prefix_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_settings_search_prefix)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    prefs.setSearchPrefix(input.getText().toString().trim());
                    render(Page.SEARCH);
                })
                .show();
    }

    private void showClearDataDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        CheckBox cache = addClearCheck(box, R.string.via_settings_clear_cache, true);
        CheckBox form = addClearCheck(box, R.string.via_settings_clear_form, true);
        CheckBox history = addClearCheck(box, R.string.via_settings_clear_history, true);
        CheckBox closed = addClearCheck(box, R.string.via_settings_clear_closed_tabs, false);
        CheckBox storage = addClearCheck(box, R.string.via_settings_clear_web_storage, true);
        CheckBox cookies = addClearCheck(box, R.string.via_settings_clear_cookies, false);
        CheckBox appCache = addClearCheck(box, R.string.via_settings_clear_app_cache, true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_settings_clear_data)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (cache.isChecked() || appCache.isChecked()) {
                        WebView webView = new WebView(this);
                        webView.clearCache(true);
                        webView.destroy();
                    }
                    if (form.isChecked()) {
                        WebView webView = new WebView(this);
                        webView.clearFormData();
                        webView.destroy();
                    }
                    if (history.isChecked()) {
                        BrowserDatabaseHelper.getInstance(this).clearHistory();
                    }
                    if (storage.isChecked()) {
                        WebStorage.getInstance().deleteAllData();
                    }
                    if (cookies.isChecked()) {
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                    }
                    // closed tabs: no persistent store yet
                    if (closed.isChecked()) {
                        // reserved
                    }
                    Toast.makeText(this, R.string.via_settings_clear_done,
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private CheckBox addClearCheck(LinearLayout parent, int titleRes, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(titleRes);
        cb.setChecked(checked);
        parent.addView(cb);
        return cb;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() { return rows.size(); }

        @Override
        public Row getItem(int position) { return rows.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(BrowserSettingsActivity.this)
                        .inflate(R.layout.item_via_settings_row, parent, false);
            }
            Row row = getItem(position);
            TextView title = view.findViewById(R.id.settings_row_title);
            TextView subtitle = view.findViewById(R.id.settings_row_subtitle);
            Switch sw = view.findViewById(R.id.settings_row_switch);
            View chevron = view.findViewById(R.id.settings_row_chevron);

            title.setText(row.title);
            if (row.subtitle == null || row.subtitle.isEmpty()) {
                subtitle.setVisibility(View.GONE);
            } else {
                subtitle.setVisibility(View.VISIBLE);
                subtitle.setText(row.subtitle);
            }

            sw.setOnCheckedChangeListener(null);
            if (row.type == RowType.TOGGLE) {
                sw.setVisibility(View.VISIBLE);
                chevron.setVisibility(View.GONE);
                sw.setChecked(row.checked);
                sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (row.onToggle != null) row.onToggle.run();
                });
            } else if (row.type == RowType.NAV) {
                sw.setVisibility(View.GONE);
                chevron.setVisibility(View.VISIBLE);
            } else {
                sw.setVisibility(View.GONE);
                chevron.setVisibility(View.GONE);
            }
            return view;
        }
    }
}
