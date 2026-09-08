package com.example.cleanrecovery.proxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.tabs.TabLayout;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 代理 UI（FlClash 风格改版）：「代理」「配置」双板块。
 *
 * <p>代理页 = 连接状态卡（启停）+ 协议分组标签 + 双列节点卡片网格 + 延迟测试 FAB；
 * 配置页 = 订阅卡片列表（更新/编辑/删除）+ 添加配置 FAB。</p>
 *
 * <p>由 BrowserActivity 菜单「代理」入口打开（{@code REQ_PROXY}）。</p>
 */
public final class ProxyActivity extends Activity {

    /** Optional test/integration entry point; value is never logged. */
    public static final String EXTRA_SUBSCRIPTION_URL = "subscription_url";

    /** 组过滤常量：全部协议。 */
    public static final String GROUP_ALL = "__all__";

    @android.annotation.SuppressLint("AuthLeak")
    private static final String SAMPLE_SUB =
            "ss://aes-256-gcm:dGVzdC1wYXNzd29yZA==@127.0.0.1:8388#SampleLocal\n"
            + "ss://chacha20-ietf-poly1305:dGVzdC1wYXNzd29yZA==@198.51.100.7:8388#SampleRemote";

    private static final int LATENCY_OK_THREADS = 8;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService latencyPool = Executors.newFixedThreadPool(LATENCY_OK_THREADS);

    private ProxyPrefs prefs;
    private final List<ProxyNode> fullNodes = new ArrayList<>();
    private ProxyNodeAdapter nodeAdapter;
    private ProfileAdapter profileAdapter;

    private TabLayout sectionTabs;
    private TabLayout groupTabs;
    private View pageProxies;
    private View pageProfiles;
    private TextView statusTitle;
    private TextView statusDetail;
    private android.widget.Switch proxySwitch;
    private TextView nodeEmptyTitle;
    private TextView nodeEmptyHint;
    private ImageView statusIcon;
    private ExtendedFloatingActionButton latencyFab;
    private ExtendedFloatingActionButton addProfileFab;
    private View nodeEmpty;
    private View profileEmpty;
    private RecyclerView nodeList;
    private RecyclerView profileList;

    private boolean bindingGroupTabs;
    private boolean latencyRunning;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_proxy);

        prefs = new ProxyPrefs(this);

        ImageButton back = findViewById(R.id.proxy_back);
        back.setOnClickListener(v -> finish());

        sectionTabs = findViewById(R.id.proxy_section_tabs);
        groupTabs = findViewById(R.id.proxy_group_tabs);
        pageProxies = findViewById(R.id.proxy_page_proxies);
        pageProfiles = findViewById(R.id.proxy_page_profiles);
        statusTitle = findViewById(R.id.proxy_status_title);
        statusDetail = findViewById(R.id.proxy_status_detail);
        statusIcon = findViewById(R.id.proxy_status_icon);
        proxySwitch = findViewById(R.id.proxy_switch);
        nodeEmptyTitle = findViewById(R.id.proxy_node_empty_title);
        nodeEmptyHint = findViewById(R.id.proxy_node_empty_hint);
        latencyFab = findViewById(R.id.proxy_latency_fab);
        addProfileFab = findViewById(R.id.proxy_profile_add_fab);
        nodeEmpty = findViewById(R.id.proxy_node_empty);
        profileEmpty = findViewById(R.id.proxy_profile_empty);
        nodeList = findViewById(R.id.proxy_node_list);
        profileList = findViewById(R.id.proxy_profile_list);

        // 代理页：双列网格；运行中点击节点 = 热切换出口
        nodeAdapter = new ProxyNodeAdapter();
        nodeAdapter.setListener(p -> {
            ProxyNode n = nodeAdapter.selectedNode();
            if (n == null) return;
            prefs.setSelectedIndex(fullNodes.indexOf(n));
            restartWithNodeIfRunning(n);
        });
        nodeList.setLayoutManager(new GridLayoutManager(this, 2));
        nodeList.setAdapter(nodeAdapter);

        // 配置页：单列卡片
        profileAdapter = new ProfileAdapter();
        profileAdapter.setListener(new ProfileAdapter.OnActionsListener() {
            @Override
            public void onOpen(ProxySubscription sub) {
                activateProfile(sub);
            }

            @Override
            public void onMenu(ProxySubscription sub, View anchor) {
                showProfileMenu(sub, anchor);
            }
        });
        profileList.setLayoutManager(new LinearLayoutManager(this));
        profileList.setAdapter(profileAdapter);

        sectionTabs.addTab(sectionTabs.newTab().setText(R.string.proxy_tab_proxies));
        sectionTabs.addTab(sectionTabs.newTab().setText(R.string.proxy_tab_profiles));
        sectionTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showPage(tab.getPosition() == 0);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        groupTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (bindingGroupTabs) return;
                nodeAdapter.setGroupFilter((String) tab.getTag());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        proxySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // 编程式 setChecked 期间由 refreshStatus 摘掉监听，这里只响应用户操作
            if (isChecked) startProxy();
            else stopProxy();
        });
        latencyFab.setOnClickListener(v -> runLatencyTest());
        addProfileFab.setOnClickListener(v -> showAddProfileDialog(""));

        reloadNodes();
        reloadProfiles();

        // 外部传入订阅链接：切到配置页并预填添加弹窗
        String suppliedUrl = getIntent().getStringExtra(EXTRA_SUBSCRIPTION_URL);
        if (suppliedUrl != null && !suppliedUrl.trim().isEmpty()) {
            sectionTabs.getTabAt(1).select();
            showAddProfileDialog(suppliedUrl.trim());
        }

        refreshStatus();
    }

    private void showPage(boolean proxiesPage) {
        pageProxies.setVisibility(proxiesPage ? View.VISIBLE : View.GONE);
        pageProfiles.setVisibility(proxiesPage ? View.GONE : View.VISIBLE);
        updateFabVisibility(proxiesPage);
    }

    /** FAB 跟随页面与运行态：代理页+运行中→延迟测试；配置页→添加配置。 */
    private void updateFabVisibility(boolean proxiesPage) {
        boolean running = isEngineRunning();
        latencyFab.setVisibility(proxiesPage && running ? View.VISIBLE : View.GONE);
        addProfileFab.setVisibility(proxiesPage ? View.GONE : View.VISIBLE);
    }

    private static boolean isEngineRunning() {
        ProxyEngine eng = ProxyEngine.current();
        return eng != null && eng.isRunning();
    }

    // ------------------------------------------------------------------
    // 代理页
    // ------------------------------------------------------------------

    private void reloadNodes() {
        fullNodes.clear();
        fullNodes.addAll(prefs.loadNodes());
        nodeAdapter.setNodes(fullNodes);
        int selected = prefs.selectedIndex();
        if (selected >= 0 && selected < fullNodes.size()) {
            nodeAdapter.setSelectedByNode(fullNodes.get(selected));
        }
        rebuildGroupTabs();
        // 空态/可见性统一由 refreshStatus 依据运行态决定
        refreshStatus();
    }

    /** 分组标签 = 全部 + 出现过的协议（保持首次出现顺序）。 */
    private void rebuildGroupTabs() {
        bindingGroupTabs = true;
        Map<String, String> groups = new LinkedHashMap<>();
        groups.put(GROUP_ALL, getString(R.string.proxy_group_all));
        for (ProxyNode n : fullNodes) {
            String key = n.protocol == null || n.protocol.isEmpty()
                    ? "ss" : n.protocol.toLowerCase(java.util.Locale.ROOT);
            groups.put(key, key.toUpperCase(java.util.Locale.ROOT));
        }
        String current = nodeAdapter.groupFilter();
        groupTabs.removeAllTabs();
        for (Map.Entry<String, String> e : groups.entrySet()) {
            TabLayout.Tab tab = groupTabs.newTab().setText(e.getValue());
            tab.setTag(e.getKey());
            groupTabs.addTab(tab);
            if (e.getKey().equals(current)) tab.select();
        }
        bindingGroupTabs = false;
        if (groups.containsKey(current)) {
            nodeAdapter.setGroupFilter(current);
        } else {
            nodeAdapter.setGroupFilter(GROUP_ALL);
        }
    }

    private void startProxy() {
        ProxyNode node = selectedFromPrefs();
        if (node == null || !node.isValid()) {
            proxySwitch.setChecked(false);
            GlassToast.makeText(this, R.string.proxy_start_no_node, GlassToast.LENGTH_SHORT).show();
            return;
        }
        launchProxyService(node);
        main.postDelayed(this::refreshStatus, 600);
    }

    private void stopProxy() {
        Intent it = new Intent(this, ProxyService.class);
        it.setAction(ProxyService.ACTION_STOP);
        startService(it);
        main.postDelayed(this::refreshStatus, 400);
    }

    /** 运行中点击节点：保存选择并热切换出口（服务先停旧引擎再以新节点启动）。 */
    private void restartWithNodeIfRunning(ProxyNode node) {
        if (!isEngineRunning()) return;
        launchProxyService(node);
        GlassToast.makeText(this, getString(R.string.proxy_node_switched,
                node.name == null ? ProxyNode.DEFAULT_NAME : node.name),
                GlassToast.LENGTH_SHORT).show();
        main.postDelayed(this::refreshStatus, 600);
    }

    private void launchProxyService(ProxyNode node) {
        Intent it = new Intent(this, ProxyService.class);
        it.setAction(ProxyService.ACTION_START);
        it.putExtra(ProxyService.EXTRA_NODE, ProxyServiceExtras.toJson(node));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it);
            } else {
                startService(it);
            }
        } catch (Exception e) {
            proxySwitch.setChecked(false);
            GlassToast.makeText(this, getString(R.string.proxy_start_failed, e.getMessage()),
                    GlassToast.LENGTH_LONG).show();
        }
    }

    private ProxyNode selectedFromPrefs() {
        int index = prefs.selectedIndex();
        return (index >= 0 && index < fullNodes.size()) ? fullNodes.get(index) : null;
    }

    /** 直连 TCP 握手测速（不依赖代理运行，衡量节点可达性）。 */
    private void runLatencyTest() {
        if (latencyRunning) return;
        if (fullNodes.isEmpty()) {
            GlassToast.makeText(this, R.string.proxy_no_node_selected, GlassToast.LENGTH_SHORT).show();
            return;
        }
        latencyRunning = true;
        latencyFab.setText(R.string.proxy_latency_testing);
        latencyFab.setEnabled(false);
        nodeAdapter.clearLatency();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        for (int i = 0; i < fullNodes.size(); i++) {
            ProxyNode node = fullNodes.get(i);
            final int index = i;
            latencyPool.execute(() -> {
                int result = LATENCY_FAILED;
                if (node.isReachableHost()) {
                    result = tcpPing(node.server, node.port);
                }
                final int ms = result;
                main.post(() -> {
                    nodeAdapter.applyLatency(index, ms);
                    if (ms >= 0) ok.incrementAndGet();
                    else fail.incrementAndGet();
                    if (done.incrementAndGet() == fullNodes.size()) {
                        latencyRunning = false;
                        latencyFab.setText(R.string.proxy_latency_test);
                        latencyFab.setEnabled(true);
                        GlassToast.makeText(this, getString(
                                R.string.proxy_latency_done, ok.get(), fail.get()),
                                GlassToast.LENGTH_SHORT).show();
                    }
                });
            });
        }
    }

    private static final int LATENCY_FAILED = -2;

    private static int tcpPing(String host, int port) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 4000);
            return (int) (System.currentTimeMillis() - start);
        } catch (Exception e) {
            return LATENCY_FAILED;
        }
    }

    private void refreshStatus() {
        boolean running = isEngineRunning();
        // 配置页状态卡 + 开关（先摘监听，避免编程式 setChecked 反向触发启停）
        proxySwitch.setOnCheckedChangeListener(null);
        proxySwitch.setChecked(running);
        proxySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) startProxy();
            else stopProxy();
        });
        if (running) {
            ProxyEngine eng = ProxyEngine.current();
            statusTitle.setText(R.string.proxy_status_title_on);
            statusDetail.setText(getString(R.string.proxy_status_detail_on,
                    eng.engineName(), eng.socks5Port()));
            statusIcon.setImageResource(R.drawable.ic_proxy_on);
        } else {
            statusTitle.setText(R.string.proxy_status_title_off);
            statusDetail.setText(R.string.proxy_status_detail_off);
            statusIcon.setImageResource(R.drawable.ic_proxy_off);
        }

        // 代理页：未运行时不显示任何节点信息
        groupTabs.setVisibility(running ? View.VISIBLE : View.GONE);
        nodeList.setVisibility(running ? View.VISIBLE : View.GONE);
        if (!running) {
            nodeEmptyTitle.setText(R.string.proxy_node_requires_running_title);
            nodeEmptyHint.setText(R.string.proxy_node_requires_running_hint);
            nodeEmpty.setVisibility(View.VISIBLE);
        } else if (nodeAdapter.visibleCount() == 0) {
            nodeEmptyTitle.setText(R.string.proxy_node_empty_title);
            nodeEmptyHint.setText(R.string.proxy_node_empty_hint);
            nodeEmpty.setVisibility(View.VISIBLE);
        } else {
            nodeEmpty.setVisibility(View.GONE);
        }
        updateFabVisibility(sectionTabs.getSelectedTabPosition() == 0);
    }

    // ------------------------------------------------------------------
    // 配置页
    // ------------------------------------------------------------------

    private void reloadProfiles() {
        List<ProxySubscription> subs = prefs.subscriptions();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ProxySubscription sub : subs) {
            counts.put(sub.id, prefs.loadNodes(sub.id).size());
        }
        profileAdapter.setItems(subs, prefs.activeSubscriptionId(), counts);
        profileEmpty.setVisibility(subs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void activateProfile(ProxySubscription sub) {
        if (sub.id.equals(prefs.activeSubscriptionId())) return;
        ProxyEngine eng = ProxyEngine.current();
        if (eng != null && eng.isRunning()) {
            GlassToast.makeText(this, R.string.proxy_stop_before_switch, GlassToast.LENGTH_SHORT).show();
            return;
        }
        prefs.setActiveSubscriptionId(sub.id);
        reloadProfiles();
        reloadNodes();
    }

    private void showProfileMenu(ProxySubscription sub, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(getString(R.string.proxy_profile_menu_update)).setOnMenuItemClickListener(i -> {
            updateProfileNow(sub);
            return true;
        });
        menu.getMenu().add(getString(R.string.proxy_profile_menu_edit)).setOnMenuItemClickListener(i -> {
            showEditProfileDialog(sub);
            return true;
        });
        menu.getMenu().add(getString(R.string.proxy_profile_menu_delete)).setOnMenuItemClickListener(i -> {
            confirmDeleteProfile(sub);
            return true;
        });
        menu.show();
    }

    /** 更新单个配置：激活中的直接落盘；非激活的写对应 profile 键，不打扰当前选择。 */
    private void updateProfileNow(ProxySubscription sub) {
        if (sub.url == null || sub.url.trim().isEmpty()) {
            GlassToast.makeText(this, R.string.proxy_subscription_hint, GlassToast.LENGTH_SHORT).show();
            return;
        }
        GlassToast.makeText(this, R.string.proxy_fetching, GlassToast.LENGTH_SHORT).show();
        final String url = sub.url.trim();
        final String subscriptionId = sub.id;
        io.execute(() -> {
            try {
                List<ProxyNode> nodes;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    nodes = SubscriptionManager.fetch(url);
                } else {
                    nodes = SubscriptionManager.parse(url);
                }
                main.post(() -> {
                    if (subscriptionId.equals(prefs.activeSubscriptionId())) {
                        prefs.saveNodes(nodes);
                        if (!nodes.isEmpty()) prefs.setSelectedIndex(0);
                        else prefs.setSelectedIndex(-1);
                        reloadNodes();
                    } else {
                        prefs.saveNodesFor(subscriptionId, nodes);
                    }
                    prefs.touchSubscription(subscriptionId);
                    reloadProfiles();
                    GlassToast.makeText(this, getString(
                            R.string.proxy_update_ok, nodes.size()), GlassToast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                main.post(() -> GlassToast.makeText(this,
                        getString(R.string.proxy_update_failed, e.getMessage()),
                        GlassToast.LENGTH_LONG).show());
            }
        });
    }

    private void showAddProfileDialog(String prefill) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, 0, padding, 0);
        EditText name = new EditText(this);
        name.setHint(R.string.proxy_subscription_name);
        EditText urls = new EditText(this);
        urls.setHint(R.string.proxy_subscription_urls);
        urls.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        urls.setMinLines(3);
        if (prefill != null && !prefill.isEmpty()) urls.setText(prefill);
        form.addView(name);
        form.addView(urls);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.proxy_add_subscription_title)
                .setView(form)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    int added = importProfiles(
                            name.getText().toString().trim(),
                            urls.getText().toString());
                    if (added == 0) {
                        urls.setError(getString(R.string.proxy_subscription_hint));
                        return;
                    }
                    reloadProfiles();
                    reloadNodes();
                    GlassToast.makeText(this, getString(
                            R.string.proxy_imported_count, added), GlassToast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    /**
     * 每行一个配置：http(s) 为订阅链接；其余（ss://vmess:// 等或 base64 文本）
     * 作为本地导入配置（url 存原文，{@link SubscriptionManager#parse} 消费）。
     */
    private int importProfiles(String baseName, String text) {
        int added = 0;
        for (String line : text == null ? new String[0] : text.split("[\\r\\n]+")) {
            String value = line.trim();
            if (value.isEmpty()) continue;
            boolean isHttp = value.startsWith("http://") || value.startsWith("https://");
            boolean isNodeOrContent = value.contains("://")
                    || (!isHttp && SubscriptionImport.looksLikeNodeList(value));
            if (!isHttp && !isNodeOrContent) continue;
            added++;
            String itemName = baseName.isEmpty()
                    ? (isHttp
                        ? getString(R.string.proxy_profile_default_name, added)
                        : getString(R.string.proxy_profile_local_name, added))
                    : (added == 1 ? baseName : baseName + " " + added);
            prefs.addSubscription(itemName, value);
        }
        return added;
    }

    private void showEditProfileDialog(ProxySubscription sub) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, 0, padding, 0);
        EditText name = new EditText(this);
        name.setHint(R.string.proxy_subscription_name);
        name.setText(sub.name);
        EditText url = new EditText(this);
        url.setHint(R.string.proxy_subscription_hint);
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        url.setText(sub.url);
        form.addView(name);
        form.addView(url);
        new AlertDialog.Builder(this)
                .setTitle(R.string.proxy_edit_subscription_title)
                .setView(form)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<ProxySubscription> all = prefs.subscriptions();
                    for (ProxySubscription item : all) {
                        if (!item.id.equals(sub.id)) continue;
                        String newName = name.getText().toString().trim();
                        if (!newName.isEmpty()) item.name = newName;
                        item.url = url.getText().toString().trim();
                    }
                    prefs.saveSubscriptions(all);
                    reloadProfiles();
                    reloadNodes();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteProfile(ProxySubscription sub) {
        if (prefs.subscriptions().size() <= 1) {
            GlassToast.makeText(this, R.string.proxy_keep_one_subscription, GlassToast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.proxy_delete_subscription)
                .setMessage(getString(R.string.proxy_delete_subscription_confirm,
                        sub.name == null ? "" : sub.name))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (sub.id.equals(prefs.activeSubscriptionId())) {
                        ProxyEngine eng = ProxyEngine.current();
                        if (eng != null && eng.isRunning()) {
                            GlassToast.makeText(this, R.string.proxy_stop_before_switch,
                                    GlassToast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    prefs.removeSubscription(sub.id);
                    reloadProfiles();
                    reloadNodes();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadNodes();
        reloadProfiles();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        latencyPool.shutdownNow();
    }
}
