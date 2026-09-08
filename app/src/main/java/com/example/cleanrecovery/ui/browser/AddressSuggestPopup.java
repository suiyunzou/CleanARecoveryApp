package com.example.cleanrecovery.ui.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 地址栏建议下拉（P0②，对齐 Via UrlInputView 搜索建议六源）。
 *
 * <p>本地源（收藏/书签/标签页/历史/剪贴板）由宿主通过 {@link Host#buildLocalRows}
 * 注入并按 设置→搜索建议 6 开关（searchSuggestions 位标志，1&lt;&lt;0 收藏 …
 * 1&lt;&lt;5 剪贴板）过滤；搜索引擎源＝「搜索 X」固定行 + 在线联想（百度/必应/谷歌/
 * DuckDuckGo/YouTube 的 suggest 端点，后台线程 + 代次计数防串台，失败静默）。</p>
 */
public final class AddressSuggestPopup {

    /** 行类型。 */
    public static final int KIND_SEARCH = 0;
    public static final int KIND_URL = 1;
    public static final int KIND_TAB = 2;
    public static final int KIND_CLIP = 3;
    public static final int KIND_SUGGEST = 4;
    public static final int KIND_HISTORY = 5;

    /** 单行高度（dp）。 */
    private static final int ROW_DP = 56;
    /** 弹层最大高度（dp），避免被输入法完全遮住。 */
    private static final int MAX_HEIGHT_DP = 360;
    private static final int ID_FILL = 0x01010104;

    private static final int ID_ICON = 0x01010101;
    private static final int ID_TITLE = 0x01010102;
    private static final int ID_SUBTITLE = 0x01010103;

    /** 行模型。 */
    public static final class Row {
        public final int kind;
        public final String title;
        public final String subtitle;

        public Row(int kind, String title, String subtitle) {
            this.kind = kind;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
        }
    }

    /** 宿主回调：本地行组装 + 点击分发 + 引擎联想数据源。 */
    public interface Host {
        /** 组装本地建议行（主线程同步调用；按六开关过滤由宿主负责）。 */
        void buildLocalRows(String query, List<Row> out);

        /** 引擎在线联想请求端点；返回 null 表示该引擎不支持在线联想。 */
        String engineEndpoint(String query);

        /** 端点响应解析为联想词（后台线程调用，须可重入）。 */
        List<String> parseEngineResponse(String endpoint, String body);

        /** 点击建议行（主线程）。 */
        void onPick(Row row);
        default void onFill(Row row) {}
    }

    private final Activity activity;
    private final Host host;
    private final PopupWindow popup;
    private final ListView list;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final List<Row> rows = new ArrayList<>();
    private final RowAdapter adapter = new RowAdapter();
    private View anchor;
    private String currentQuery = "";

    public AddressSuggestPopup(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        list = new ListView(activity);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setDividerHeight(0);
        boolean night = new BrowserPrefs(activity).nightMode();
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(night ? 0xF2242424 : 0xF2F5F5F5);
        background.setCornerRadii(new float[]{0,0,0,0,dp(18),dp(18),dp(18),dp(18)});
        list.setBackground(background); list.setClipToOutline(true);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= rows.size()) return;
            Row row = rows.get(position);
            dismiss();
            host.onPick(row);
        });
        popup = new PopupWindow(list, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setFocusable(false);
        popup.setElevation(0);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
    }

    /** 设置下拉锚点（一般传 browser_toolbar，出现在其正下方）。 */
    public void setAnchor(View view) {
        anchor = view;
    }

    /** 地址栏文本变化入口（主线程）。 */
    public void onTextChanged(String query) {
        currentQuery = query == null ? "" : query;
        rows.clear();
        try {
            host.buildLocalRows(currentQuery, rows);
        } catch (Exception ignored) {
        }
        generation.incrementAndGet();
        if (!rows.isEmpty()) showOrUpdate();
        else if (popup.isShowing()) popup.dismiss();
        scheduleEngineFetch(currentQuery);
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    public void dismiss() {
        generation.incrementAndGet();
        if (popup.isShowing()) popup.dismiss();
    }

    /** 宿主销毁时调用，阻断异步回填。 */
    public void destroy() {
        dismiss();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void showOrUpdate() {
        if (anchor == null || anchor.getWidth() <= 0) return;
        // 本地行每次重建都要触发重绑，否则已显示状态下 ListView 停留在旧内容
        adapter.notifyDataSetChanged();
        final int width = anchor.getWidth();
        final int height = popupHeight();
        if (!popup.isShowing()) {
            popup.setWidth(width);
            popup.setHeight(height);
            popup.showAsDropDown(anchor, 0, 0);
        } else {
            popup.update(anchor, width, height);
        }
    }

    private int popupHeight() {
        return Math.max(1, Math.min(rows.size() * dp(ROW_DP),
                Math.min(dp(MAX_HEIGHT_DP), popup.getMaxAvailableHeight(anchor))));
    }

    private void scheduleEngineFetch(final String query) {
        final int gen = generation.incrementAndGet();
        if (query.trim().isEmpty()) return;
        mainHandler.postDelayed(() -> fetchEngine(query, gen), 220);
    }

    private void fetchEngine(final String query, final int gen) {
        if (gen != generation.get()) return;
        final String endpoint = host.engineEndpoint(query);
        if (endpoint == null) return;
        new Thread(() -> {
            String body = httpGet(endpoint);
            List<String> words = null;
            if (body != null) {
                try {
                    words = host.parseEngineResponse(endpoint, body);
                } catch (Exception ignored) {
                }
            }
            if (words == null || words.isEmpty()) return;
            final List<Row> fetched = new ArrayList<>();
            for (String w : words) {
                if (fetched.size() >= 8) break;
                if (w == null || w.trim().isEmpty()) continue;
                fetched.add(new Row(KIND_SUGGEST, w.trim(), ""));
            }
            mainHandler.post(() -> {
                if (gen != generation.get() || !query.equals(currentQuery)) return;
                // 替换旧联想行（固定行保留在前）
                List<Row> kept = new ArrayList<>();
                for (Row r : rows) if (r.kind != KIND_SUGGEST) kept.add(r);
                kept.addAll(fetched);
                rows.clear();
                rows.addAll(kept);
                showOrUpdate();
            });
        }, "addr-suggest").start();
    }

    private static String httpGet(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 引擎联想端点（对齐 Via opensug 数据源；不支持在线联想的引擎返回 null）。 */
    public static String defaultEndpoint(int engine, String query) {
        try {
            String q = URLEncoder.encode(query, "UTF-8");
            switch (engine) {
                case SearchEngines.BAIDU:
                    return "https://www.baidu.com/sugrec?prod=pc&wd=" + q;
                case SearchEngines.BING:
                    return "https://api.bing.com/osjson.aspx?query=" + q;
                case SearchEngines.GOOGLE:
                    return "https://suggestqueries.google.com/complete/search?client=firefox&q=" + q;
                case SearchEngines.YOUTUBE:
                    return "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=" + q;
                case SearchEngines.DUCKDUCKGO:
                    return "https://duckduckgo.com/ac/?q=" + q + "&type=list";
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 默认解析：firefox/osjson 数组格式与百度 sugrec 对象格式。 */
    public static List<String> defaultParse(String body) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(body)) return out;
        String t = body.trim();
        try {
            if (t.startsWith("[")) {
                JSONArray arr = new JSONArray(t);
                if (arr.length() >= 2 && arr.optJSONArray(1) != null) {
                    JSONArray words = arr.getJSONArray(1);
                    for (int i = 0; i < words.length(); i++) out.add(words.optString(i));
                }
            } else if (t.startsWith("{")) {
                JSONObject obj = new JSONObject(t);
                JSONArray g = obj.optJSONArray("g");
                if (g != null) {
                    for (int i = 0; i < g.length(); i++) {
                        JSONObject item = g.optJSONObject(i);
                        if (item != null) out.add(item.optString("q"));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private int dp(int v) {
        return (int) (activity.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    @SuppressLint("ViewConstructor")
    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rows.get(position);
            LinearLayout box = convertView instanceof LinearLayout ? (LinearLayout) convertView : null;
            TextView title;
            TextView subtitle;
            ImageView icon;
            if (box == null) {
                box = new LinearLayout(activity);
                box.setOrientation(LinearLayout.HORIZONTAL);
                box.setGravity(Gravity.CENTER_VERTICAL);
                box.setPadding(dp(16), 0, 0, 0);
                box.setMinimumHeight(dp(ROW_DP));
                box.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(ROW_DP)));
                icon = new ImageView(activity);
                icon.setId(ID_ICON);
                icon.setColorFilter(new BrowserPrefs(activity).nightMode() ? 0xffdddddd : 0xff333333);
                box.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
                LinearLayout textCol = new LinearLayout(activity);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setGravity(Gravity.CENTER_VERTICAL);
                textCol.setPadding(dp(14), 0, 0, 0);
                title = new TextView(activity);
                title.setId(ID_TITLE);
                title.setTextColor(0xFF212121);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                title.setMaxLines(1);
                title.setEllipsize(TextUtils.TruncateAt.END);
                textCol.addView(title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                subtitle = new TextView(activity);
                subtitle.setId(ID_SUBTITLE);
                subtitle.setTextColor(0xFF9A9A9A);
                subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                subtitle.setMaxLines(1);
                subtitle.setEllipsize(TextUtils.TruncateAt.END);
                textCol.addView(subtitle, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                box.addView(textCol, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                ImageView fill = new ImageView(activity);fill.setId(ID_FILL);
                fill.setImageResource(com.example.cleanrecovery.R.drawable.ic_suggestion_fill);
                fill.setPadding(dp(12),dp(16),dp(12),dp(16));
                fill.setBackgroundResource(com.example.cleanrecovery.R.drawable.bg_via_toolbar_button);
                fill.setContentDescription("填入地址栏");
                box.addView(fill,new LinearLayout.LayoutParams(dp(48),-1));
            } else {
                icon = box.findViewById(ID_ICON);
                title = box.findViewById(ID_TITLE);
                subtitle = box.findViewById(ID_SUBTITLE);
            }
            boolean searchRow = row.kind == KIND_SEARCH || row.kind == KIND_SUGGEST;
            icon.setImageResource(searchRow
                    ? com.example.cleanrecovery.R.drawable.via_top_search
                    : row.kind == KIND_HISTORY ? com.example.cleanrecovery.R.drawable.via_action_history
                    : row.kind == KIND_TAB ? com.example.cleanrecovery.R.drawable.via_nav_tabs
                    : com.example.cleanrecovery.R.drawable.via_action_bookmarks);
            int foreground = new BrowserPrefs(activity).nightMode() ? 0xffeeeeee : 0xff222222;
            title.setTextColor(foreground); subtitle.setTextColor(new BrowserPrefs(activity).nightMode() ? 0xffaaaaaa : 0xff777777);
            ImageView fill=box.findViewById(ID_FILL);fill.setColorFilter(foreground);fill.setOnClickListener(v -> host.onFill(row));
            icon.setVisibility(row.kind == KIND_CLIP ? View.GONE : View.VISIBLE);
            title.setText(row.title);
            boolean hasSub = !row.subtitle.isEmpty();
            subtitle.setVisibility(hasSub ? View.VISIBLE : View.GONE);
            if (hasSub) subtitle.setText(row.subtitle);
            return box;
        }
    }
}
