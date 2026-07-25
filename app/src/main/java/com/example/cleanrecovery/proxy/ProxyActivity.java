package com.example.cleanrecovery.proxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 代理 UI：订阅拉取 + 节点单选 + 启停代理 + 状态显示。
 *
 * <p>由 BrowserActivity 菜单「代理」入口打开（{@code REQ_PROXY}）。
 * 本类不修改 BrowserActivity，仅暴露自身供协调者集成。</p>
 */
public final class ProxyActivity extends Activity {

    /** Optional test/integration entry point; value is never logged. */
    public static final String EXTRA_SUBSCRIPTION_URL = "subscription_url";

    @android.annotation.SuppressLint("AuthLeak")
    private static final String SAMPLE_SUB =
            "ss://aes-256-gcm:dGVzdC1wYXNzd29yZA==@127.0.0.1:8388#SampleLocal\n"
            + "ss://chacha20-ietf-poly1305:dGVzdC1wYXNzd29yZA==@198.51.100.7:8388#SampleRemote";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ProxyPrefs prefs;
    private ProxyNodeAdapter adapter;
    private final List<ProxySubscription> subscriptions = new ArrayList<>();
    private Spinner subscriptionSpinner;
    private ArrayAdapter<ProxySubscription> subscriptionAdapter;
    private String currentSubscriptionId;
    private boolean bindingSubscriptions;

    private EditText subInput;
    private Button fetchBtn;
    private Button sampleBtn;
    private Button toggleBtn;
    private TextView statusView;
    private ImageView statusIcon;
    private RecyclerView list;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_proxy);

        prefs = new ProxyPrefs(this);

        ImageButton back = findViewById(R.id.proxy_back);
        back.setOnClickListener(v -> finish());
        subInput = findViewById(R.id.proxy_sub_input);
        fetchBtn = findViewById(R.id.proxy_fetch_btn);
        sampleBtn = findViewById(R.id.proxy_sample_btn);
        toggleBtn = findViewById(R.id.proxy_toggle_btn);
        statusView = findViewById(R.id.proxy_status);
        statusIcon = findViewById(R.id.proxy_status_icon);
        list = findViewById(R.id.proxy_node_list);
        subscriptionSpinner = findViewById(R.id.proxy_subscription_spinner);

        bindSubscriptionSelector();
        String suppliedUrl = getIntent().getStringExtra(EXTRA_SUBSCRIPTION_URL);
        subInput.setText(suppliedUrl == null || suppliedUrl.trim().isEmpty()
                ? prefs.subscriptionUrl() : suppliedUrl.trim());

        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProxyNodeAdapter();
        adapter.setListener(p -> prefs.setSelectedIndex(p));
        list.setAdapter(adapter);

        loadActiveNodes();

        fetchBtn.setOnClickListener(v -> doFetch());
        sampleBtn.setOnClickListener(v -> subInput.setText(SAMPLE_SUB));
        toggleBtn.setOnClickListener(v -> doToggle());
        findViewById(R.id.proxy_subscription_add).setOnClickListener(
                v -> showAddSubscriptionDialog());
        findViewById(R.id.proxy_subscription_delete).setOnClickListener(
                v -> deleteCurrentSubscription());

        refreshStatus();
    }

    private void bindSubscriptionSelector() {
        subscriptions.clear();
        subscriptions.addAll(prefs.subscriptions());
        subscriptionAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, subscriptions);
        subscriptionAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        subscriptionSpinner.setAdapter(subscriptionAdapter);

        String activeId = prefs.activeSubscriptionId();
        int activeIndex = indexOfSubscription(activeId);
        if (activeIndex < 0) activeIndex = 0;
        currentSubscriptionId = subscriptions.get(activeIndex).id;
        prefs.setActiveSubscriptionId(currentSubscriptionId);
        bindingSubscriptions = true;
        subscriptionSpinner.setSelection(activeIndex, false);
        bindingSubscriptions = false;
        subscriptionSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        if (bindingSubscriptions || position < 0
                                || position >= subscriptions.size()) return;
                        switchSubscription(subscriptions.get(position));
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });
    }

    private void switchSubscription(ProxySubscription target) {
        if (target == null || target.id.equals(currentSubscriptionId)) return;
        prefs.setSubscriptionUrl(subInput.getText().toString().trim());
        prefs.setActiveSubscriptionId(target.id);
        currentSubscriptionId = target.id;
        subInput.setText(target.url);
        loadActiveNodes();
    }

    private void loadActiveNodes() {
        List<ProxyNode> saved = prefs.loadNodes();
        adapter.setNodes(saved);
        int selected = prefs.selectedIndex();
        if (selected >= 0 && selected < saved.size()) adapter.setSelected(selected);
    }

    private void showAddSubscriptionDialog() {
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
                    String baseName = name.getText().toString().trim();
                    int added = 0;
                    for (String url : SubscriptionImport.parseUrls(
                            urls.getText().toString())) {
                        added++;
                        String itemName = baseName.isEmpty()
                                ? "订阅 " + (subscriptions.size() + added)
                                : added == 1 ? baseName : baseName + " " + added;
                        prefs.addSubscription(itemName, url);
                    }
                    if (added == 0) {
                        urls.setError(getString(R.string.proxy_subscription_hint));
                        return;
                    }
                    reloadSubscriptionSelector();
                    Toast.makeText(this, getString(
                            R.string.proxy_imported_count, added), Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void deleteCurrentSubscription() {
        if (subscriptions.size() <= 1) {
            Toast.makeText(this, R.string.proxy_keep_one_subscription,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ProxySubscription current = prefs.activeSubscription();
        new AlertDialog.Builder(this)
                .setTitle(R.string.proxy_delete_subscription)
                .setMessage(getString(R.string.proxy_delete_subscription_confirm, current.name))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    prefs.removeSubscription(current.id);
                    reloadSubscriptionSelector();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void reloadSubscriptionSelector() {
        subscriptions.clear();
        subscriptions.addAll(prefs.subscriptions());
        subscriptionAdapter.notifyDataSetChanged();
        int active = indexOfSubscription(prefs.activeSubscriptionId());
        if (active < 0) active = 0;
        bindingSubscriptions = true;
        subscriptionSpinner.setSelection(active, false);
        bindingSubscriptions = false;
        ProxySubscription item = subscriptions.get(active);
        currentSubscriptionId = item.id;
        subInput.setText(item.url);
        loadActiveNodes();
    }

    private int indexOfSubscription(String id) {
        for (int i = 0; i < subscriptions.size(); i++) {
            if (subscriptions.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private void doFetch() {
        String url = subInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.proxy_subscription_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setSubscriptionUrl(url);
        String subscriptionId = prefs.activeSubscriptionId();
        fetchBtn.setEnabled(false);
        fetchBtn.setText(R.string.proxy_fetching);
        io.execute(() -> {
            try {
                List<ProxyNode> nodes;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    nodes = SubscriptionManager.fetch(url);
                } else {
                    nodes = SubscriptionManager.parse(url);
                }
                main.post(() -> {
                    if (!subscriptionId.equals(prefs.activeSubscriptionId())) {
                        fetchBtn.setEnabled(true);
                        fetchBtn.setText(R.string.proxy_fetch);
                        Toast.makeText(this, R.string.proxy_fetch_profile_changed,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.saveNodes(nodes);
                    adapter.setNodes(nodes);
                    if (!nodes.isEmpty()) {
                        adapter.setSelected(0);
                        prefs.setSelectedIndex(0);
                    }
                    fetchBtn.setEnabled(true);
                    fetchBtn.setText(R.string.proxy_fetch);
                    Toast.makeText(this,
                            getString(R.string.proxy_fetch_ok, nodes.size()),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                main.post(() -> {
                    fetchBtn.setEnabled(true);
                    fetchBtn.setText(R.string.proxy_fetch);
                    Toast.makeText(this,
                            getString(R.string.proxy_fetch_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void doToggle() {
        ProxyEngine eng = ProxyEngine.current();
        if (eng != null && eng.isRunning()) {
            // 停止
            Intent it = new Intent(this, ProxyService.class);
            it.setAction(ProxyService.ACTION_STOP);
            startService(it);
            main.postDelayed(this::refreshStatus, 300);
            return;
        }
        ProxyNode node = adapter.selectedNode();
        if (node == null) {
            Toast.makeText(this, R.string.proxy_no_node_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!node.isValid()) {
            Toast.makeText(this, R.string.proxy_node_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, ProxyService.class);
        it.setAction(ProxyService.ACTION_START);
        it.putExtra(ProxyService.EXTRA_NODE, ProxyServiceExtras.toJson(node));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it);
            } else {
                startService(it);
            }
            main.postDelayed(this::refreshStatus, 500);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.proxy_start_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void refreshStatus() {
        ProxyEngine eng = ProxyEngine.current();
        if (eng != null && eng.isRunning()) {
            statusView.setText(getString(R.string.proxy_status_format,
                    getString(R.string.proxy_status_on),
                    eng.engineName(),
                    eng.socks5Port()));
            statusIcon.setImageResource(R.drawable.ic_proxy_on);
            toggleBtn.setText(R.string.proxy_stop);
        } else {
            statusView.setText(R.string.proxy_status_off);
            statusIcon.setImageResource(R.drawable.ic_proxy_off);
            toggleBtn.setText(R.string.proxy_start);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
