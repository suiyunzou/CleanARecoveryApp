package com.example.cleanrecovery.ui.browser;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.cleanrecovery.R;

/** The same toolbar arrangement is used by the browser and its settings preview. */
public final class BrowserToolbarLayout {
    private final LinearLayout root, top, bottom, compactRow, navigation, tabItems;
    private final FrameLayout address;
    private final HorizontalScrollView tabStrip;
    private int mode = -1;
    private boolean editing;
    private boolean tabsEnabled;
    private final boolean preview;

    public BrowserToolbarLayout(LinearLayout root, boolean preview) {
        this.root = root;
        this.preview = preview;
        address = root.findViewById(R.id.browser_toolbar);
        navigation = root.findViewById(R.id.browser_bottom_bar);
        root.removeView(address);
        root.removeView(navigation);
        top = column();
        bottom = column();
        compactRow = new LinearLayout(root.getContext());
        compactRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top, 0, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        tabStrip = new HorizontalScrollView(root.getContext());
        tabStrip.setHorizontalScrollBarEnabled(false);
        tabStrip.setContentDescription("标签栏");
        tabItems = new LinearLayout(root.getContext());
        tabStrip.addView(tabItems, new ViewGroup.LayoutParams(-2, -1));
        apply(1, false, false);
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(root.getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private int dp(float value) { return ViaUi.dp(root.getContext(), value); }

    private static void detach(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    public void apply(int mode, boolean tabsEnabled, boolean editing) {
        boolean compact = mode == 0 || mode == 2;
        tabsEnabled &= compact;
        if (this.mode == mode && this.editing == editing && this.tabsEnabled == tabsEnabled) return;
        this.mode = mode;
        this.editing = editing;
        this.tabsEnabled = tabsEnabled;
        detach(address); detach(navigation); detach(tabStrip); detach(compactRow);
        top.removeAllViews(); bottom.removeAllViews();
        navigation.findViewById(R.id.browser_nav_back).setVisibility(compact ? View.GONE : View.VISIBLE);
        navigation.findViewById(R.id.browser_nav_forward).setVisibility(compact ? View.GONE : View.VISIBLE);
        navigation.setVisibility(editing && compact ? View.GONE : View.VISIBLE);
        LinearLayout addressHost = mode == 2 || mode == 3 ? bottom : top;
        if (compact) {
            compactRow.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1));
            compactRow.addView(navigation, new LinearLayout.LayoutParams(dp(176), dp(48)));
            if (tabsEnabled && mode == 2) addressHost.addView(tabStrip, new LinearLayout.LayoutParams(-1, dp(36)));
            addressHost.addView(compactRow, new LinearLayout.LayoutParams(-1, dp(48)));
            if (tabsEnabled && mode == 0) addressHost.addView(tabStrip, new LinearLayout.LayoutParams(-1, dp(36)));
        } else {
            addressHost.addView(address, new LinearLayout.LayoutParams(-1, dp(48)));
            bottom.addView(navigation, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        for (int id : new int[]{R.id.browser_bar_home, R.id.browser_bar_page}) {
            View bar = root.findViewById(id);
            boolean rounded = preview || (compact && id == R.id.browser_bar_page);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(rounded ? 40 : 48), Gravity.CENTER);
            params.leftMargin = rounded ? dp(compact ? 12 : mode == 3 ? 16 : 12) : 0;
            params.rightMargin = rounded ? dp(compact ? 0 : mode == 3 ? 16 : 12) : 0;
            bar.setLayoutParams(params);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(rounded ? 0xffeeeeee : Color.TRANSPARENT);
            pill.setCornerRadius(dp(24));
            bar.setBackground(pill);
        }
    }

    public void setHidden(boolean hidden) {
        top.setVisibility(hidden ? View.GONE : View.VISIBLE);
        bottom.setVisibility(hidden ? View.GONE : View.VISIBLE);
    }

    public void updateTabs(java.util.List<String> titles, java.util.List<android.graphics.Bitmap> icons, int selected,
                           java.util.function.IntConsumer select, java.util.function.IntConsumer close, Runnable add) {
        tabItems.removeAllViews();
        for (int i = 0; i < titles.size(); i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(root.getContext());
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackgroundColor(i == selected ? 0xffeeeeee : Color.WHITE);
            android.widget.ImageView icon = new android.widget.ImageView(root.getContext());
            if (icons.get(i) != null) icon.setImageBitmap(icons.get(i));
            else icon.setImageResource(R.drawable.ic_via_globe);
            icon.setPadding(dp(12), dp(8), dp(8), dp(8));
            item.addView(icon, new LinearLayout.LayoutParams(dp(48), -1));
            TextView title = new TextView(root.getContext());
            title.setText(titles.get(i));
            title.setTextSize(14);
            title.setTextColor(ViaUi.TEXT);
            title.setSingleLine();
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setOnClickListener(v -> select.accept(index));
            icon.setOnClickListener(v -> select.accept(index));
            item.addView(title, new LinearLayout.LayoutParams(dp(80), -1));
            TextView remove = new TextView(root.getContext());
            remove.setText("×");
            remove.setTextColor(ViaUi.TEXT);
            remove.setTextSize(20);
            remove.setGravity(Gravity.CENTER);
            remove.setContentDescription("关闭标签");
            remove.setOnClickListener(v -> close.accept(index));
            item.addView(remove, new LinearLayout.LayoutParams(dp(48), -1));
            tabItems.addView(item, new LinearLayout.LayoutParams(-2, -1));
        }
        TextView plus = new TextView(root.getContext());
        plus.setText("+");
        plus.setTextColor(ViaUi.TEXT);
        plus.setTextSize(24);
        plus.setGravity(Gravity.CENTER);
        plus.setContentDescription("新建标签");
        plus.setOnClickListener(v -> add.run());
        tabItems.addView(plus, new LinearLayout.LayoutParams(dp(48), -1));
        if (selected >= 0 && selected < titles.size()) {
            View active = tabItems.getChildAt(selected);
            tabStrip.post(() -> {
                int left = active.getLeft(), right = active.getRight();
                if (left < tabStrip.getScrollX()) tabStrip.scrollTo(left, 0);
                else if (right > tabStrip.getScrollX() + tabStrip.getWidth()) tabStrip.scrollTo(right - tabStrip.getWidth(), 0);
            });
        }
    }

    public void setColors(int background, int foreground) {
        address.setBackgroundColor(background);
        navigation.setBackgroundColor(background);
        tabStrip.setBackgroundColor(background);
        for (int id : new int[]{R.id.browser_nav_back, R.id.browser_nav_forward, R.id.browser_home,
                R.id.browser_tabs, R.id.browser_menu, R.id.browser_site_info, R.id.browser_reload,
                R.id.browser_search_toggle, R.id.browser_hide_bar, R.id.browser_edit_scan, R.id.browser_edit_search, R.id.browser_url_go,
                R.id.browser_app_home, R.id.browser_edit_clear}) {
            ((android.widget.ImageView) root.findViewById(id)).setColorFilter(foreground);
        }
        ((TextView)root.findViewById(R.id.browser_url_input)).setTextColor(foreground);
        ((TextView)root.findViewById(R.id.browser_url_input)).setHintTextColor(foreground);
        for (int id : new int[]{R.id.browser_page_title, R.id.browser_home_title, R.id.browser_tab_badge}) {
            ((TextView) root.findViewById(id)).setTextColor(foreground);
        }
    }
}
