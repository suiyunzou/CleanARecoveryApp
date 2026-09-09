package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.SearchEngines;
import com.example.cleanrecovery.ui.browser.ViaUi;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Via 风格“搜索设置”：搜索引擎 / 搜索建议 / 搜索工具栏。 */
public final class BrowserSearchSettingsActivity extends Activity {
    private enum Page { MAIN, ENGINE, NEW_ENGINE, TOOLBAR }
    private BrowserPrefs prefs;
    private LinearLayout root;
    private FrameLayout contentContainer;
    private ScrollView scroll;
    private LinearLayout list;
    private RecyclerView toolbarRv;
    private TextView title;
    private TextView action;
    private Page page = Page.MAIN;
    private ItemTouchHelper toolbarTouchHelper;

    private static final int[] ENGINES = {
            SearchEngines.GOOGLE, SearchEngines.BAIDU, SearchEngines.BING,
            SearchEngines.YAHOO, SearchEngines.STARTPAGE, SearchEngines.DUCKDUCKGO
    };
    private static final String[] SUGGESTION_NAMES = {"收藏", "书签", "标签页", "历史", "搜索引擎", "搜索历史"};

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        prefs = new BrowserPrefs(this);
        buildShell();
        String pageExtra = getIntent() != null ? getIntent().getStringExtra("page") : null;
        if ("toolbar".equalsIgnoreCase(pageExtra)) {
            render(Page.TOOLBAR);
        } else if ("engine".equalsIgnoreCase(pageExtra)) {
            render(Page.ENGINE);
        } else if ("suggestions".equalsIgnoreCase(pageExtra)) {
            render(Page.MAIN);
            list.post(this::showSuggestionsDialog);
        } else {
            render(Page.MAIN);
        }
    }

    private void buildShell() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xffffffff);
        frame.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xffffffff);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(1), 0, dp(1), 0);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(54) - 1));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setColorFilter(0xFF212121);
        back.setBackgroundResource(R.drawable.bg_via_menu_cell);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setContentDescription("返回");
        back.setOnClickListener(v -> goBack());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        title = new TextView(this);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTextColor(0xFF212121);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -1, 1);
        titleParams.leftMargin = dp(1);
        top.addView(title, titleParams);

        action = new TextView(this);
        action.setGravity(Gravity.CENTER);
        action.setTextColor(0xFF212121);
        action.setBackgroundResource(R.drawable.bg_via_menu_cell);
        action.setVisibility(View.GONE);
        top.addView(action, new LinearLayout.LayoutParams(dp(48), -1));

        View line = new View(this);
        line.setBackgroundColor(0xffe5e5e5);
        root.addView(line, new LinearLayout.LayoutParams(-1, 1));

        contentContainer = new FrameLayout(this);
        root.addView(contentContainer, new LinearLayout.LayoutParams(-1, 0, 1));

        scroll = new ScrollView(this) {
            @Override
            public void requestChildFocus(View child, View focused) {
                // 阻断焦点获取时的非预期滚动跳转
            }
        };
        scroll.setFillViewport(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scroll.setFocusable(false);
        scroll.setFocusableInTouchMode(false);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        contentContainer.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        toolbarRv = new RecyclerView(this);
        toolbarRv.setLayoutManager(new LinearLayoutManager(this));
        contentContainer.addView(toolbarRv, new FrameLayout.LayoutParams(-1, -1));
    }

    private void render(Page p) {
        page = p;
        action.setVisibility(View.GONE);
        action.setOnClickListener(null);
        if (p == Page.TOOLBAR) {
            scroll.setVisibility(View.GONE);
            toolbarRv.setVisibility(View.VISIBLE);
            renderToolbar();
        } else {
            toolbarRv.setVisibility(View.GONE);
            scroll.setVisibility(View.VISIBLE);
            scroll.scrollTo(0, 0);
            list.removeAllViews();
            switch (p) {
                case MAIN:
                    title.setText("搜索设置");
                    addRow("搜索引擎", searchEngineLabel(), () -> render(Page.ENGINE));
                    addRow("搜索建议", suggestionsSummary(), this::showSuggestionsDialog);
                    addRow("搜索工具栏", prefs.searchToolbarEnabled() ? "已启用" : "已禁用", () -> render(Page.TOOLBAR));
                    break;
                case ENGINE:
                    title.setText("搜索引擎");
                    action.setText("＋");
                    action.setTextSize(24);
                    action.setContentDescription("新建");
                    action.getLayoutParams().width = dp(48);
                    action.setVisibility(View.VISIBLE);
                    action.setOnClickListener(v -> render(Page.NEW_ENGINE));
                    for (int engine : ENGINES) addEngineSelectRow(engine);
                    List<BrowserPrefs.CustomSearchItem> customItems = prefs.customSearchList();
                    for (int i = 0; i < customItems.size(); i++) addCustomEngineSelectRow(i, customItems.get(i));
                    break;
                case NEW_ENGINE:
                    renderNewEngine();
                    break;
                default:
                    break;
            }
        }
    }

    private void renderToolbar() {
        title.setText("搜索工具栏");
        List<Integer> engines = new ArrayList<>(prefs.searchToolbarOrder());
        if (prefs.customSearchList().isEmpty()) {
            engines.remove((Integer) SearchEngines.CUSTOM);
        } else if (!engines.contains(SearchEngines.CUSTOM)) {
            engines.add(0, SearchEngines.CUSTOM);
        }
        Set<Integer> disabled = prefs.searchToolbarDisabledEngines();
        int defaultEngine = prefs.searchEngine();

        ToolbarAdapter adapter = new ToolbarAdapter(engines, disabled, defaultEngine);
        toolbarRv.setAdapter(adapter);

        if (toolbarTouchHelper != null) toolbarTouchHelper.attachToRecyclerView(null);
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
                if (viewHolder.getAdapterPosition() < 2) {
                    return makeMovementFlags(0, 0);
                }
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
                int from = viewHolder.getAdapterPosition() - 2;
                int to = target.getAdapterPosition() - 2;
                if (from < 0 || to < 0 || from >= engines.size() || to >= engines.size()) return false;
                engines.add(to, engines.remove(from));
                adapter.notifyItemMoved(viewHolder.getAdapterPosition(), target.getAdapterPosition());
                prefs.setSearchToolbarOrder(engines);
                return true;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }
        });
        touchHelper.attachToRecyclerView(toolbarRv);
        toolbarTouchHelper = touchHelper;
        adapter.setTouchHelper(touchHelper);
    }

    private class ToolbarAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_ENABLE_SWITCH = 0;
        private static final int TYPE_SECTION_HEADER = 1;
        private static final int TYPE_ENGINE_ITEM = 2;

        private final List<Integer> engines;
        private final Set<Integer> disabled;
        private final int defaultEngine;
        private ItemTouchHelper touchHelper;

        ToolbarAdapter(List<Integer> engines, Set<Integer> disabled, int defaultEngine) {
            this.engines = engines;
            this.disabled = disabled;
            this.defaultEngine = defaultEngine;
        }

        void setTouchHelper(ItemTouchHelper touchHelper) {
            this.touchHelper = touchHelper;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return TYPE_ENABLE_SWITCH;
            if (position == 1) return TYPE_SECTION_HEADER;
            return TYPE_ENGINE_ITEM;
        }

        @Override
        public int getItemCount() {
            return 2 + engines.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == TYPE_ENABLE_SWITCH) {
                LinearLayout row = new LinearLayout(BrowserSearchSettingsActivity.this);
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(20), dp(16), dp(20));
                row.setBackgroundResource(R.drawable.bg_via_menu_cell);

                TextView t = new TextView(BrowserSearchSettingsActivity.this);
                t.setText("启用搜索工具栏");
                t.setTextSize(14);
                t.setTextColor(0xFF212121);
                row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

                ImageView sw = ViaUi.switchView(BrowserSearchSettingsActivity.this, prefs.searchToolbarEnabled());
                row.addView(sw, new LinearLayout.LayoutParams(dp(20), dp(20)));

                return new EnableSwitchHolder(row, sw);
            } else if (viewType == TYPE_SECTION_HEADER) {
                TextView section = new TextView(BrowserSearchSettingsActivity.this);
                section.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
                section.setText("搜索引擎");
                section.setTextSize(13);
                section.setTextColor(getColor(R.color.via_accent));
                section.setGravity(Gravity.CENTER_VERTICAL);
                section.setPadding(dp(16), 0, dp(16), 0);

                return new SectionHolder(section);
            } else {
                LinearLayout row = new LinearLayout(BrowserSearchSettingsActivity.this);
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(20), dp(16), dp(20));
                row.setBackgroundResource(R.drawable.bg_via_menu_cell);

                LinearLayout texts = new LinearLayout(BrowserSearchSettingsActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setGravity(Gravity.CENTER_VERTICAL);

                TextView title = new TextView(BrowserSearchSettingsActivity.this);
                title.setTextSize(14);
                title.setTextColor(0xFF212121);
                texts.addView(title);

                TextView subtitle = new TextView(BrowserSearchSettingsActivity.this);
                subtitle.setTextSize(12);
                subtitle.setTextColor(0xFF888888);
                texts.addView(subtitle);

                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

                ImageView dragHandle = new ImageView(BrowserSearchSettingsActivity.this);
                dragHandle.setImageResource(R.drawable.ic_drag_handle);
                dragHandle.setPadding(dp(8), dp(8), dp(8), dp(8));
                dragHandle.setColorFilter(0xFF888888);
                LinearLayout.LayoutParams lpDrag = new LinearLayout.LayoutParams(dp(40), dp(40));
                lpDrag.rightMargin = dp(16);
                row.addView(dragHandle, lpDrag);

                ImageView sw = ViaUi.switchView(BrowserSearchSettingsActivity.this, true);
                row.addView(sw, new LinearLayout.LayoutParams(dp(20), dp(20)));

                return new EngineHolder(row, title, subtitle, dragHandle, sw);
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder rawHolder, int position) {
            if (rawHolder instanceof EnableSwitchHolder) {
                EnableSwitchHolder holder = (EnableSwitchHolder) rawHolder;
                boolean enabled = prefs.searchToolbarEnabled();
                ViaUi.renderSwitch(holder.sw, enabled);
                View.OnClickListener toggleEnable = v -> {
                    boolean next = !prefs.searchToolbarEnabled();
                    prefs.setSearchToolbarEnabled(next);
                    ViaUi.renderSwitch(holder.sw, next);
                };
                holder.itemView.setOnClickListener(toggleEnable);
                holder.sw.setOnClickListener(toggleEnable);
            } else if (rawHolder instanceof EngineHolder) {
                EngineHolder holder = (EngineHolder) rawHolder;
                int engine = engines.get(position - 2);
                holder.title.setText(searchEngineLabel(engine));
                boolean isDefault = (engine == defaultEngine);

                if (isDefault) {
                    holder.subtitle.setText("默认");
                    holder.subtitle.setVisibility(View.VISIBLE);
                    ViaUi.renderSwitch(holder.sw, true);
                    holder.sw.setAlpha(0.6f);
                    holder.itemView.setOnClickListener(null);
                    holder.sw.setOnClickListener(null);
                } else {
                    holder.subtitle.setVisibility(View.GONE);
                    holder.sw.setAlpha(1.0f);
                    boolean isChecked = !disabled.contains(engine);
                    ViaUi.renderSwitch(holder.sw, isChecked);

                    View.OnClickListener toggleEngine = v -> {
                        if (disabled.contains(engine)) {
                            disabled.remove(engine);
                        } else {
                            disabled.add(engine);
                        }
                        boolean isNowOn = !disabled.contains(engine);
                        ViaUi.renderSwitch(holder.sw, isNowOn);
                        prefs.setSearchToolbarDisabledEngines(disabled);
                    };
                    holder.itemView.setOnClickListener(toggleEngine);
                    holder.sw.setOnClickListener(toggleEngine);
                }

                holder.dragHandle.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null) {
                        touchHelper.startDrag(holder);
                    }
                    return false;
                });
            }
        }

        class EnableSwitchHolder extends RecyclerView.ViewHolder {
            final ImageView sw;
            EnableSwitchHolder(View v, ImageView sw) {
                super(v);
                this.sw = sw;
            }
        }

        class SectionHolder extends RecyclerView.ViewHolder {
            SectionHolder(View v) { super(v); }
        }

        class EngineHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView subtitle;
            final ImageView dragHandle;
            final ImageView sw;

            EngineHolder(View v, TextView title, TextView subtitle, ImageView dragHandle, ImageView sw) {
                super(v);
                this.title = title;
                this.subtitle = subtitle;
                this.dragHandle = dragHandle;
                this.sw = sw;
            }
        }
    }

    private void addEngineSelectRow(int engine) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(20), dp(16), dp(20));
        row.setBackgroundResource(R.drawable.bg_via_menu_cell);
        ImageView radio = ViaUi.switchView(this, prefs.searchEngine() == engine);
        LinearLayout.LayoutParams radioParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        radioParams.rightMargin = dp(18);
        row.addView(radio, radioParams);
        TextView label = new TextView(this);
        label.setText(searchEngineLabel(engine));
        label.setTextSize(14);
        label.setTextColor(0xFF212121);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> {
            prefs.setSearchEngine(engine);
            render(Page.ENGINE);
        });
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addCustomEngineSelectRow(int index, BrowserPrefs.CustomSearchItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(20), dp(16), dp(20));
        row.setBackgroundResource(R.drawable.bg_via_menu_cell);
        boolean selected = prefs.searchEngine() == SearchEngines.CUSTOM
                && prefs.selectedCustomSearch() == index;
        ImageView radio = ViaUi.switchView(this, selected);
        LinearLayout.LayoutParams radioParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        radioParams.rightMargin = dp(18);
        row.addView(radio, radioParams);
        TextView label = new TextView(this);
        label.setText(item.title);
        label.setTextSize(14);
        label.setTextColor(0xFF212121);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> {
            prefs.selectCustomSearch(index);
            render(Page.ENGINE);
        });
        row.setOnLongClickListener(v -> {
            ViaUi.listDialog(this, item.title, new String[]{"删除"}, choice -> {
                prefs.removeCustomSearch(index);
                render(Page.ENGINE);
            }).show();
            return true;
        });
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showSuggestionsDialog() {
        int flags = prefs.searchSuggestions();
        boolean[] checked = new boolean[SUGGESTION_NAMES.length];
        for (int i = 0; i < checked.length; i++) checked[i] = (flags & (1 << i)) != 0;
        ViaUi.checkboxDialog(this, "搜索建议", "使用地址栏时，从下列内容获取建议",
                SUGGESTION_NAMES, checked, values -> {
            int next = 0;
            for (int i = 0; i < values.length; i++) if (values[i]) next |= 1 << i;
            prefs.setSearchSuggestions(next);
            render(Page.MAIN);
        });
    }

    private void renderNewEngine() {
        title.setText("新建");
        action.setText("保存");
        action.setTextSize(14);
        action.setContentDescription("保存");
        action.getLayoutParams().width = dp(54);
        action.setVisibility(View.VISIBLE);
        EditText name = new EditText(this);
        name.setHint("标题");
        name.setSingleLine(true);
        name.setTextSize(14);
        name.setPadding(dp(16), dp(10), dp(16), dp(10));
        list.addView(name, new LinearLayout.LayoutParams(-1, dp(61)));
        EditText address = new EditText(this);
        address.setHint("地址");
        address.setGravity(Gravity.TOP);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        address.setTextSize(14);
        address.setPadding(dp(16), dp(10), dp(16), dp(10));
        list.addView(address, new LinearLayout.LayoutParams(-1, dp(102)));
        action.setOnClickListener(v -> {
            String label = name.getText().toString().trim();
            String prefix = address.getText().toString().trim();
            if (label.isEmpty()) { name.requestFocus(); return; }
            if (prefix.isEmpty()) { address.requestFocus(); return; }
            prefs.addCustomSearch(label, prefix);
            prefs.setSearchEngine(SearchEngines.CUSTOM);
            render(Page.ENGINE);
        });
    }

    private void addRow(String name, String sub, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(20), dp(16), dp(20));
        row.setBackgroundResource(R.drawable.bg_via_menu_cell);
        row.setClickable(true);
        row.setFocusable(false);
        row.setFocusableInTouchMode(false);
        row.setOnClickListener(v -> click.run());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(14);
        t.setTextColor(0xFF212121);
        texts.addView(t);

        if (!TextUtils.isEmpty(sub)) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setTextSize(12);
            s.setTextColor(0xFF757575);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        list.addView(row, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addSwitchRow(String name, String sub, boolean checked, SwitchConsumer consumer) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(20), dp(16), dp(20));
        row.setBackgroundResource(R.drawable.bg_via_menu_cell);
        row.setClickable(true);
        row.setFocusable(false);
        row.setFocusableInTouchMode(false);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(14);
        t.setTextColor(0xFF212121);
        texts.addView(t);
        if (!TextUtils.isEmpty(sub)) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(0xFF757575);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        ImageView sw = ViaUi.switchView(this, checked);
        row.addView(sw, new LinearLayout.LayoutParams(dp(20), dp(20)));

        row.setOnClickListener(v -> {
            boolean next = !checked;
            ViaUi.renderSwitch(sw, next);
            consumer.accept(next);
        });

        list.addView(row, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private String suggestionsSummary() {
        int flags = prefs.searchSuggestions();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < SUGGESTION_NAMES.length; i++) {
            if ((flags & (1 << i)) != 0) {
                if (b.length() > 0) b.append('/');
                b.append(SUGGESTION_NAMES[i]);
            }
        }
        return b.length() == 0 ? "已关闭" : b.toString();
    }

    private String searchEngineLabel() { return searchEngineLabel(prefs.searchEngine()); }
    private String searchEngineLabel(int engine) {
        return engine == SearchEngines.CUSTOM ? prefs.customSearchTitle() : SearchEngines.label(engine);
    }

    private void goBack() {
        if (page == Page.MAIN) {
            setResult(RESULT_OK, new Intent());
            finish();
        } else render(Page.MAIN);
    }

    @Override public void onBackPressed() { goBack(); }
    private int statusBarHeight() { int id = getResources().getIdentifier("status_bar_height", "dimen", "android"); return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24); }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private interface SwitchConsumer { void accept(boolean checked); }
}
