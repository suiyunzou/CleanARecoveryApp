package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VIA 底部菜单：
 * RecyclerView+PagerSnapHelper 平滑翻页；每页显式两行、每行五列等宽布局；
 * 圆点为真实 View（4dp，选中 #202020 / 未选 #D9D9D9）；
 * 图标为 VIA 原版资源（via_menu_*.png），两行各 86dp，文字适应系统字号。
 * 代理为本工程自有功能：强制可见、定制菜单中不可移除。
 */
public final class BrowserBottomMenu {
    public static final int PAGE_SIZE = 10;

    /** VIA 默认显示于菜单的 31 项（含本工程代理扩展，4 页）。 */
    private static final String[] VIA_DEFAULT_VISIBLE = {
            "menu_night", "menu_bookmarks", "menu_history", "menu_download", "menu_incognito",
            "menu_share", "menu_add_bookmark", "menu_ua", "menu_tools", "menu_settings",
            "menu_find", "menu_save", "menu_offline", "menu_translate", "menu_view_source",
            "menu_fullscreen", "menu_image_mode", "menu_sniff", "menu_useragent", "menu_network_log",
            "menu_scan", "menu_add_to_home", "menu_read_aloud", "menu_ai", "menu_rotation",
            "menu_adblock", "menu_mark_ad", "menu_font_size", "menu_clear_data", "menu_customize_menu",
            "menu_proxy"
    };

    /** VIA 定制页底部「可添加」池（默认隐藏）。 */
    private static final String[] VIA_DEFAULT_HIDDEN = {
            "menu_reload", "menu_site_conf", "menu_scripts", "menu_print", "menu_reader",
            "menu_open_with", "menu_game_mode", "menu_add_favorite", "menu_report"
    };

    public interface Listener {
        void onAction(int menuId);
    }

    public static final class Entry {
        public final int id;
        public final String resourceName;
        public CharSequence title;
        public final android.graphics.drawable.Drawable icon;
        public boolean visible = true;
        /** VIA 状态化：置灰禁用（如主页态的朗读/标记/字号），点击不触发动作 */
        public boolean disabled = false;
        /** VIA 状态化：高亮（如广告拦截已开启），图标与文字染主题蓝 */
        public boolean highlighted = false;

        public Entry(int id, String resourceName, CharSequence title,
                     android.graphics.drawable.Drawable icon) {
            this.id = id;
            this.resourceName = resourceName;
            this.title = title;
            this.icon = icon;
        }
    }

    private final Activity activity;
    private final BrowserPrefs prefs;
    private final List<Entry> allEntries;
    private final Listener listener;
    private final List<Entry> visibleEntries = new ArrayList<>();
    private Dialog dialog;
    private RecyclerView pager;
    private PageAdapter pageAdapter;
    private LinearLayout dotsRow;
    private int pageCount;

    public BrowserBottomMenu(
            Activity activity,
            BrowserPrefs prefs,
            List<Entry> entries,
            Listener listener) {
        this.activity = activity;
        this.prefs = prefs;
        this.allEntries = applySavedOrder(entries, prefs);
        this.listener = listener;
        for (Entry entry : allEntries) {
            if (entry.visible) visibleEntries.add(entry);
        }
    }

    /** 工具箱二级页：直接展示给定条目，不读偏好。 */
    public BrowserBottomMenu(
            Activity activity,
            List<Entry> entries,
            Listener listener) {
        this.activity = activity;
        this.prefs = null;
        this.allEntries = new ArrayList<>(entries);
        this.listener = listener;
        for (Entry entry : allEntries) {
            entry.visible = true;
            visibleEntries.add(entry);
        }
    }

    public void show() {
        dialog = new Dialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(20), 0, 0);
        root.setBackground(sheetBackground());

        pageCount = Math.max(1, (visibleEntries.size() + PAGE_SIZE - 1) / PAGE_SIZE);

        pager = new RecyclerView(activity);
        pager.setItemAnimator(null);
        pager.setLayoutManager(new LinearLayoutManager(
                activity, LinearLayoutManager.HORIZONTAL, false));
        pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        new PagerSnapHelper().attachToRecyclerView(pager);
        pageAdapter = new PageAdapter();
        pager.setAdapter(pageAdapter);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) refreshDots();
            }
        });
        // 两行完整渲染（2×86dp），圆点紧贴
        root.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(172)));

        dotsRow = new LinearLayout(activity);
        dotsRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < pageCount; i++) {
            View dot = new View(activity);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(4), dp(4));
            p.leftMargin = dp(1);
            p.rightMargin = dp(2);
            dot.setLayoutParams(p);
            dot.setBackgroundResource(R.drawable.via_menu_dot);
            dotsRow.addView(dot);
        }
        root.addView(dotsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        LinearLayout footer = new LinearLayout(activity);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(new Space(activity),
                new LinearLayout.LayoutParams(0, dp(44), 4f));
        footer.addView(footerButton(
                R.drawable.via_menu_power,
                activity.getString(R.string.via_menu_exit),
                v -> {
                    dialog.dismiss();
                    listener.onAction(R.id.menu_exit);
                }), new LinearLayout.LayoutParams(0, dp(44), 1f));
        footer.addView(new Space(activity),
                new LinearLayout.LayoutParams(0, dp(44), 0.5f));
        ImageView collapse = footerButton(
                R.drawable.via_nav_back,
                activity.getString(R.string.via_menu_collapse),
                v -> dialog.dismiss());
        // 「<」旋转 270° 得到向下箭头（VIA 收起键）
        collapse.setRotation(270);
        footer.addView(collapse, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.28f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        pager.scrollToPosition(0);
        refreshDots();
    }

    private void refreshDots() {
        LinearLayoutManager lm = (LinearLayoutManager) pager.getLayoutManager();
        int page = lm == null ? 0 : lm.findFirstCompletelyVisibleItemPosition();
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;
        for (int i = 0; i < dotsRow.getChildCount(); i++) {
            dotsRow.getChildAt(i).setActivated(i == page);
        }
        dotsRow.setContentDescription(activity.getString(
                R.string.via_menu_page_format, page + 1, pageCount));
    }

    private void smoothToPage(int page) {
        if (pager == null || page < 0 || page >= pageCount) return;
        LinearLayoutManager lm = (LinearLayoutManager) pager.getLayoutManager();
        int current = lm == null ? 0 : lm.findFirstCompletelyVisibleItemPosition();
        if (page == current) return;
        if (Math.abs(page - current) > 2) {
            pager.scrollToPosition(page);
        } else {
            pager.smoothScrollToPosition(page);
        }
    }

    private class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout page = new LinearLayout(activity);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setBaselineAligned(false);
            page.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new Holder(page);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(position);
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }

        class Holder extends RecyclerView.ViewHolder {
            final LinearLayout page;
            private int boundPage = -1;

            Holder(@NonNull View itemView) {
                super(itemView);
                page = (LinearLayout) itemView;
            }

            void bind(int position) {
                if (boundPage == position) return;
                boundPage = position;
                page.removeAllViews();
                int startIdx = position * PAGE_SIZE;
                int endIdx = Math.min(startIdx + PAGE_SIZE, visibleEntries.size());
                LinearLayout row = null;
                for (int slot = 0; slot < PAGE_SIZE; slot++) {
                    if (slot % 5 == 0) {
                        row = new LinearLayout(activity);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setBaselineAligned(false);
                        page.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
                    }
                    int index = startIdx + slot;
                    View cell = index < endIdx
                            ? buildCell(visibleEntries.get(index))
                            : new Space(activity);
                    row.addView(cell, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                }
            }
        }
    }

    private View buildCell(final Entry entry) {
        // VIA 度量：内容顶部对齐（图标 24dp + 6dp + 单行文字 15dp）
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(8), 0, 0); // VIA 图标中心距面板顶 39.4dp
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setBackgroundResource(R.drawable.bg_via_menu_cell);

        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(copyIcon(entry.icon));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int stateColor = entry.disabled ? 0xFFB2B2B2
                : entry.highlighted ? 0xFF6F8DE1 : color(R.color.text_primary);
        icon.setColorFilter(stateColor);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView label = new TextView(activity);
        label.setText(entry.title);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setIncludeFontPadding(false);
        label.setTextColor(stateColor);
        label.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(6);
        cell.addView(label, labelParams);
        cell.setContentDescription(entry.title);
        cell.setOnClickListener(v -> {
            // 工具箱：与 VIA 一致平滑翻到工具页，不关闭菜单
            if ("menu_tools".equals(entry.resourceName)) {
                smoothToPage(1);
                return;
            }
            if (entry.disabled) return;
            dialog.dismiss();
            listener.onAction(entry.id);
        });
        return cell;
    }

    private ImageView footerButton(int iconRes, String description, View.OnClickListener click) {
        ImageView button = new ImageView(activity);
        button.setImageResource(iconRes);
        button.setColorFilter(color(R.color.text_primary));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setContentDescription(description);
        button.setBackgroundResource(R.drawable.bg_via_menu_cell);
        button.setOnClickListener(click);
        return button;
    }

    private GradientDrawable sheetBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(R.color.surface_card));
        float radius = dp(14);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return drawable;
    }

    private int color(int res) {
        boolean night = (prefs != null ? prefs : new BrowserPrefs(activity)).nightMode();
        if (res == R.color.surface_card) return night ? 0xFF1C1C1E : 0xFFFFFFFF;
        if (res == R.color.text_primary) return night ? 0xFFB8B8B8 : 0xFF333333;
        return androidx.core.content.ContextCompat.getColor(activity, res);
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** VIA 定制菜单：上方显示池（5 列网格拖拽），下方可用池（点击添加）。 */
    public static Dialog showCustomizer(
            Activity activity,
            BrowserPrefs prefs,
            List<Entry> entries) {
        List<Entry> customizable = new ArrayList<>();
        for (Entry entry : entries) {
            if (java.util.Arrays.asList("menu_new_tab", "menu_close_tab", "menu_screenshot", "menu_exit").contains(entry.resourceName)) continue;
            customizable.add(new Entry(entry.id, entry.resourceName,
                entry.id == R.id.menu_reader ? activity.getString(R.string.via_menu_reader) : entry.title, copyIcon(entry.icon)));
        }
        List<Entry> ordered = applySavedOrder(customizable, prefs);
        List<Entry> active = new ArrayList<>();
        List<Entry> available = new ArrayList<>();
        for (Entry entry : ordered) {
            if (entry.visible) active.add(entry);
            else available.add(entry);
        }

        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
        ImageView back = new ImageView(activity);
        back.setImageResource(R.drawable.via_toolbar_back);
        back.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        back.setOnClickListener(v -> dialog.dismiss());
        TextView title = new TextView(activity);
        title.setText(R.string.via_customize_menu_title);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setTextColor(0xff333333);
        TextView reset = new TextView(activity);
        reset.setText(R.string.via_customize_menu_reset);
        reset.setTextColor(0xff333333);
        reset.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        header.addView(back, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(reset);
        root.addView(header);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(activity, 24));

        TextView activeHint = new TextView(activity);
        activeHint.setText(R.string.via_customize_active_hint);
        activeHint.setTextColor(Color.GRAY);
        activeHint.setTextSize(13);
        activeHint.setGravity(Gravity.CENTER);
        activeHint.setPadding(0, dp(activity, 18), 0, dp(activity, 18));
        body.addView(activeHint);

        RecyclerView activeList = new RecyclerView(activity);
        activeList.setNestedScrollingEnabled(false);
        activeList.setLayoutManager(new GridLayoutManager(activity, 5));
        CustomizeGridAdapter activeAdapter = new CustomizeGridAdapter(activity, active, true);
        activeList.setAdapter(activeAdapter);
        body.addView(activeList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView availableHint = new TextView(activity);
        availableHint.setText(R.string.via_customize_available_hint);
        availableHint.setTextColor(Color.GRAY);
        availableHint.setTextSize(13);
        availableHint.setGravity(Gravity.CENTER);
        availableHint.setPadding(0, dp(activity, 16), 0, dp(activity, 16));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(activity, 20);
        body.addView(availableHint, hintParams);

        RecyclerView availableList = new RecyclerView(activity);
        availableList.setNestedScrollingEnabled(false);
        availableList.setLayoutManager(new GridLayoutManager(activity, 5));
        CustomizeGridAdapter availableAdapter =
                new CustomizeGridAdapter(activity, available, false);
        availableList.setAdapter(availableAdapter);
        body.addView(availableList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        activeAdapter.setOnTap(position -> {
            if (position < 0 || position >= active.size()) return;
            Entry moved = active.get(position);
            if ("menu_proxy".equals(moved.resourceName)) {
                com.example.cleanrecovery.ui.widget.GlassToast.makeText(activity, R.string.via_proxy_locked,
                        com.example.cleanrecovery.ui.widget.GlassToast.LENGTH_SHORT).show();
                return;
            }
            active.remove(position);
            moved.visible = false;
            available.add(0, moved);
            activeAdapter.notifyDataSetChanged();
            availableAdapter.notifyDataSetChanged();
            persist(prefs, active, available);
        });
        availableAdapter.setOnTap(position -> {
            if (position < 0 || position >= available.size()) return;
            Entry moved = available.remove(position);
            moved.visible = true;
            active.add(moved);
            activeAdapter.notifyDataSetChanged();
            availableAdapter.notifyDataSetChanged();
            persist(prefs, active, available);
        });

        ItemTouchHelper helper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(
                        ItemTouchHelper.UP | ItemTouchHelper.DOWN
                                | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0) {
                    @Override
                    public boolean onMove(
                            @NonNull RecyclerView recyclerView,
                            @NonNull RecyclerView.ViewHolder source,
                            @NonNull RecyclerView.ViewHolder target) {
                        int from = source.getBindingAdapterPosition();
                        int to = target.getBindingAdapterPosition();
                        if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                            return false;
                        }
                        active.add(to, active.remove(from));
                        activeAdapter.notifyItemMoved(from, to);
                        persist(prefs, active, available);
                        return true;
                    }

                    @Override
                    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                    }
                });
        helper.attachToRecyclerView(activeList);
        activeAdapter.setTouchHelper(helper);

        android.view.View.OnDragListener addDraggedItem = (target, event) -> {
            if (!(event.getLocalState() instanceof Entry)) return false;
            if (event.getAction() == android.view.DragEvent.ACTION_DROP) {
                Entry moved = (Entry)event.getLocalState();
                if (!available.remove(moved)) return false;
                View child = activeList.findChildViewUnder(event.getX(), event.getY());
                int position = child == null ? active.size() : activeList.getChildAdapterPosition(child);
                active.add(position < 0 ? active.size() : position, moved); moved.visible = true;
                activeAdapter.notifyDataSetChanged(); availableAdapter.notifyDataSetChanged();
                persist(prefs, active, available);
            }
            return true;
        };
        activeList.setOnDragListener(addDraggedItem);
        reset.setOnClickListener(v -> {
            prefs.resetMenuConfiguration();
            active.clear(); available.clear();
            for (Entry entry : applySavedOrder(customizable, prefs)) (entry.visible ? active : available).add(entry);
            activeAdapter.notifyDataSetChanged(); availableAdapter.notifyDataSetChanged();
        });
        back.setContentDescription(activity.getString(android.R.string.cancel));

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
                androidx.core.graphics.Insets bars = insets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            androidx.core.view.ViewCompat.requestApplyInsets(root);
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        return dialog;
    }

    private static android.graphics.drawable.Drawable copyIcon(android.graphics.drawable.Drawable icon) {
        if (icon == null) return null;
        android.graphics.drawable.Drawable copy = icon.getConstantState() == null ? icon.mutate() : icon.getConstantState().newDrawable().mutate();
        copy.clearColorFilter();
        return copy;
    }

    private static void persist(
            BrowserPrefs prefs,
            List<Entry> active,
            List<Entry> available) {
        List<String> order = new ArrayList<>();
        Set<String> hidden = new HashSet<>();
        for (Entry entry : active) {
            order.add(entry.resourceName);
        }
        for (Entry entry : available) {
            order.add(entry.resourceName);
            hidden.add(entry.resourceName);
        }
        prefs.saveMenuConfiguration(order, hidden);
    }

    private static List<Entry> applySavedOrder(List<Entry> entries, BrowserPrefs prefs) {
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Entry entry : entries) byName.put(entry.resourceName, entry);

        List<String> saved = prefs.menuOrder();
        List<String> order = new ArrayList<>();
        if (saved.isEmpty()) {
            Collections.addAll(order, VIA_DEFAULT_VISIBLE);
            Collections.addAll(order, VIA_DEFAULT_HIDDEN);
        } else {
            order.addAll(saved);
        }
        for (Entry entry : entries) {
            if (!order.contains(entry.resourceName)) order.add(entry.resourceName);
        }

        Set<String> hidden = prefs.hiddenMenuItems();
        boolean defaults = saved.isEmpty();
        Set<String> defaultVisible = new HashSet<>();
        Collections.addAll(defaultVisible, VIA_DEFAULT_VISIBLE);
        List<Entry> result = new ArrayList<>();
        for (String name : order) {
            Entry entry = byName.remove(name);
            if (entry == null) continue;
            if ("menu_proxy".equals(name)) {
                // 代理是本工程自有功能：无条件保留可见，任何隐藏记录都无效
                entry.visible = true;
            } else if (defaults) {
                entry.visible = defaultVisible.contains(name);
            } else {
                entry.visible = !hidden.contains(name);
            }
            result.add(entry);
        }
        for (Entry leftover : byName.values()) {
            leftover.visible = "menu_proxy".equals(leftover.resourceName);
            result.add(leftover);
        }
        return result;
    }

    private static final class CustomizeGridAdapter
            extends RecyclerView.Adapter<CustomizeGridAdapter.Holder> {
        interface TapListener {
            void onTap(int position);
        }

        private final Activity activity;
        private final List<Entry> entries;
        private final boolean activePool;
        private ItemTouchHelper touchHelper;
        private TapListener tapListener;

        CustomizeGridAdapter(Activity activity, List<Entry> entries, boolean activePool) {
            this.activity = activity;
            this.entries = entries;
            this.activePool = activePool;
        }

        void setTouchHelper(ItemTouchHelper helper) {
            this.touchHelper = helper;
        }

        void setOnTap(TapListener listener) {
            this.tapListener = listener;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(activity);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
            cell.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(activity, 86)));
            cell.setBackgroundResource(R.drawable.bg_via_menu_cell);
            ImageView icon = new ImageView(activity);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)));
            TextView title = new TextView(activity);
            title.setTextSize(11);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            title.setTextColor(Color.DKGRAY);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 32));
            titleParams.topMargin = dp(activity, 4);
            cell.addView(title, titleParams);
            return new Holder(cell, icon, title);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Entry entry = entries.get(position);
            holder.icon.setImageDrawable(copyIcon(entry.icon));
            holder.title.setText(entry.title);
            holder.itemView.setOnClickListener(v -> {
                if (tapListener != null) tapListener.onTap(holder.getBindingAdapterPosition());
            });
            if (activePool) {
                holder.itemView.setOnLongClickListener(v -> {
                    if (touchHelper != null) touchHelper.startDrag(holder);
                    return true;
                });
            } else {
                holder.itemView.setOnLongClickListener(v -> {
                    android.content.ClipData data = android.content.ClipData.newPlainText("menu-item", entry.resourceName);
                    if (android.os.Build.VERSION.SDK_INT >= 24) v.startDragAndDrop(data, new View.DragShadowBuilder(v), entry, 0);
                    else v.startDrag(data, new View.DragShadowBuilder(v), entry, 0);
                    return true;
                });
            }
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title;

            Holder(@NonNull View itemView, ImageView icon, TextView title) {
                super(itemView);
                this.icon = icon;
                this.title = title;
            }
        }
    }
}
