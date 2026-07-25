package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
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
 * VIA 底部菜单：2×5 分页 + 定制菜单双池（显示区 / 可用区）。
 *
 * <p>默认三页顺序与文案对齐模拟器实测 VIA（2026-07-24）。</p>
 */
public final class BrowserBottomMenu {
    public static final int PAGE_SIZE = 10;

    /** VIA 默认显示于菜单的 30 项（3 页 × 10）。 */
    private static final String[] VIA_DEFAULT_VISIBLE = {
            "menu_night", "menu_bookmarks", "menu_history", "menu_download", "menu_incognito",
            "menu_share", "menu_add_bookmark", "menu_ua", "menu_tools", "menu_settings",
            "menu_find", "menu_save", "menu_offline", "menu_translate", "menu_view_source",
            "menu_fullscreen", "menu_image_mode", "menu_sniff", "menu_useragent", "menu_network_log",
            "menu_scan", "menu_add_to_home", "menu_read_aloud", "menu_ai", "menu_rotation",
            "menu_adblock", "menu_mark_ad", "menu_font_size", "menu_report", "menu_customize_menu",
            // 本工程扩展：默认菜单第 4 页可见，避免代理入口被 VIA 对齐时隐藏
            "menu_proxy"
    };

    /** VIA 定制页底部「可添加」池（默认隐藏）。 */
    private static final String[] VIA_DEFAULT_HIDDEN = {
            "menu_reload", "menu_site_conf", "menu_scripts", "menu_clear_data", "menu_print"
    };

    public interface Listener {
        void onAction(int menuId);
    }

    public static final class Entry {
        public final int id;
        public final String resourceName;
        public final CharSequence title;
        public final Drawable icon;
        public boolean visible = true;

        public Entry(int id, String resourceName, CharSequence title, Drawable icon) {
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
    private GridLayout grid;
    private TextView dots;
    private int page;

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
        // VIA 菜单底部贴边；2×5 网格为 160dp，高度和 dump 中 [1080..1400] 对齐。
        root.setPadding(dp(8), dp(14), dp(8), 0);
        root.setBackground(sheetBackground());

        grid = new GridLayout(activity);
        grid.setColumnCount(5);
        grid.setRowCount(2);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));

        dots = new TextView(activity);
        dots.setGravity(Gravity.CENTER);
        dots.setTextSize(14);
        dots.setTextColor(color(R.color.text_muted));
        dots.setOnClickListener(v -> {
            int count = Math.max(1, (visibleEntries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            setPage((page + 1) % count);
        });
        root.addView(dots, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        LinearLayout footer = new LinearLayout(activity);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(new Space(activity),
                new LinearLayout.LayoutParams(0, dp(44), 4f));
        footer.addView(footerButton(
                R.drawable.ic_via_power,
                activity.getString(R.string.via_menu_exit),
                v -> {
                    dialog.dismiss();
                    listener.onAction(R.id.menu_exit);
                }), new LinearLayout.LayoutParams(0, dp(44), 1f));
        footer.addView(new Space(activity),
                new LinearLayout.LayoutParams(0, dp(44), 0.5f));
        footer.addView(footerButton(
                R.drawable.ic_via_collapse,
                activity.getString(R.string.via_menu_collapse),
                v -> dialog.dismiss()), new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

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
        setPage(0);
    }

    /** VIA 定制菜单：上方显示池（5 列网格拖拽），下方可用池（点击添加）。 */
    public static void showCustomizer(
            Activity activity,
            BrowserPrefs prefs,
            List<Entry> entries) {
        List<Entry> ordered = applySavedOrder(entries, prefs);
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
        back.setImageResource(R.drawable.ic_back);
        back.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        back.setOnClickListener(v -> dialog.dismiss());
        TextView title = new TextView(activity);
        title.setText(R.string.via_customize_menu_title);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        TextView reset = new TextView(activity);
        reset.setText(R.string.via_customize_menu_reset);
        reset.setTextColor(Color.parseColor("#D64146"));
        reset.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        header.addView(back, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(reset);
        root.addView(header);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 12), 0, dp(activity, 12), dp(activity, 24));

        TextView activeHint = new TextView(activity);
        activeHint.setText(R.string.via_customize_active_hint);
        activeHint.setTextColor(Color.GRAY);
        activeHint.setTextSize(13);
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
            Entry moved = active.remove(position);
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
                        Collections.swap(active, from, to);
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

        reset.setOnClickListener(v -> {
            prefs.resetMenuConfiguration();
            dialog.dismiss();
        });
        back.setContentDescription(activity.getString(android.R.string.cancel));

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
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

    private void setPage(int requested) {
        int count = Math.max(1, (visibleEntries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(requested, count - 1));
        grid.removeAllViews();
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, visibleEntries.size());
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            View cell = slot + start < end
                    ? menuCell(visibleEntries.get(slot + start))
                    : new Space(activity);
            bindPagingGesture(cell);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(slot / 5, 1, 1f),
                    GridLayout.spec(slot % 5, 1, 1f));
            params.width = 0;
            params.height = dp(80);
            grid.addView(cell, params);
        }
        StringBuilder indicator = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) indicator.append(' ');
            indicator.append(i == page ? '●' : '○');
        }
        dots.setText(indicator);
        dots.setContentDescription(activity.getString(
                R.string.via_menu_page_format, page + 1, count));
    }

    private View menuCell(Entry entry) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setBackgroundResource(R.drawable.bg_via_menu_cell);
        cell.setContentDescription(entry.title);

        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(entry.icon);
        icon.setColorFilter(color(R.color.text_primary));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));

        TextView label = new TextView(activity);
        label.setText(entry.title);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setTextColor(color(R.color.text_primary));
        label.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        labelParams.topMargin = dp(7);
        cell.addView(label, labelParams);
        cell.setOnClickListener(view -> {
            // 工具箱：VIA 实测会翻到第 2 页工具区，不关闭菜单
            if ("menu_tools".equals(entry.resourceName)) {
                setPage(1);
                return;
            }
            dialog.dismiss();
            listener.onAction(entry.id);
        });
        return cell;
    }

    private void bindPagingGesture(View view) {
        final float[] downX = new float[1];
        view.setOnTouchListener((target, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX[0] = event.getX();
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float delta = event.getX() - downX[0];
                if (Math.abs(delta) > dp(48)) {
                    setPage(page + (delta < 0 ? 1 : -1));
                    return true;
                }
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && Math.abs(event.getX() - downX[0]) > dp(24)) {
                return true;
            }
            return false;
        });
    }

    private View footerButton(int iconRes, String description, View.OnClickListener click) {
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
        return androidx.core.content.ContextCompat.getColor(activity, res);
    }

    private int dp(int value) {
        return dp(activity, value);
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
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
            if (defaults) {
                entry.visible = defaultVisible.contains(name);
            } else {
                entry.visible = !hidden.contains(name);
            }
            result.add(entry);
        }
        for (Entry leftover : byName.values()) {
            // 本工程代理入口：未显式隐藏时始终保留可见，避免对齐 VIA 默认菜单后丢失
            leftover.visible = "menu_proxy".equals(leftover.resourceName)
                    && !hidden.contains("menu_proxy");
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
            ImageView icon = new ImageView(activity);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 28)));
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
            holder.icon.setImageDrawable(entry.icon);
            holder.icon.setColorFilter(Color.BLACK);
            holder.title.setText(entry.title);
            holder.itemView.setOnClickListener(v -> {
                if (tapListener != null) tapListener.onTap(holder.getBindingAdapterPosition());
            });
            if (activePool) {
                holder.itemView.setOnLongClickListener(v -> {
                    if (touchHelper != null) touchHelper.startDrag(holder);
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
