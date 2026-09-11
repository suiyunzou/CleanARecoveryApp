package com.example.cleanrecovery.ui.activity;

import com.example.cleanrecovery.ui.browser.ViaDialogBuilder;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.ViaUi;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;


/**
 * Via 主页定制：
 * - 深色底色（#1E1E1E）
 * - 顶部 < 定制 返回栏
 * - 中间浮动圆角卡片实时主页预览（Logo、搜索框、收藏书签）
 * - 底部 5 Tab（Logo、搜索框、背景、收藏、高级）弹窗调节
 */
public final class BrowserHomeCustomizeActivity extends Activity {
    public static final String EXTRA_PAGE = "page";
    private static final int REQ_LOGO_IMAGE = 3101;
    private static final int REQ_BG_IMAGE = 3102;

    private BrowserPrefs prefs;
    private FrameLayout root;
    private LinearLayout activePanel;
    private LinearLayout panelHost;
    private FrameLayout customizeSheet;
    private FrameLayout sliderHost;
    private String activeTab;
    private boolean chooseBackground;
    private LinearLayout bottomTabs;
    private SeekBar activeSlider;
    private String selectedSlider;
    private boolean sliding;
    private FrameLayout editor;
    private android.webkit.WebView previewWeb;
    private String renderedHtml;
    private boolean previewReady;

    private FrameLayout previewCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        SystemUiHelper.apply(this);
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
            & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setStatusBarColor(0xFF1E1E1E); getWindow().setNavigationBarColor(0xFFEEEEEE);
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        prefs = new BrowserPrefs(this);

        // 根布局：全黑/深灰 #1E1E1E 背景
        root = new FrameLayout(this);
        // Keep the navigation inset on the same grey surface as the tabs (including edge-to-edge).
        root.setBackground(new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint();
            @Override public void draw(android.graphics.Canvas canvas) {
                canvas.drawColor(0xFF1E1E1E);
                paint.setColor(0xFFEEEEEE);
                canvas.drawRect(0, getBounds().bottom - root.getPaddingBottom(), getBounds().right, getBounds().bottom, paint);
            }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        });
        root.setFitsSystemWindows(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏 48dp：< 定制 + 右侧重置
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(1), 0, dp(1), 0);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setColorFilter(0xFFFFFFFF);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setBackgroundResource(R.drawable.bg_via_menu_cell);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> onBackPressed());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText("定制");
        title.setTextSize(17);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(dp(1), 0, 0, 0);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        page.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(53)));
        View topDivider = new View(this);
        topDivider.setBackgroundColor(0xFF333333);
        page.addView(topDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 中间主页实时预览卡片区域
        FrameLayout centerArea = new FrameLayout(this);
        centerArea.setPadding(0, 0, 0, 0);

        previewCard = new FrameLayout(this);
        previewCard.setElevation(dp(8));
        previewCard.setScaleX(.95f); previewCard.setScaleY(.95f);
        previewCard.setClipToOutline(true);

        centerArea.addView(previewCard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        centerParams.bottomMargin = dp(48);
        page.addView(centerArea, centerParams);
        previewWeb = new android.webkit.WebView(this);
        previewWeb.setBackgroundColor(Color.TRANSPARENT);
        previewWeb.setOnTouchListener((view, event) -> true);
        previewWeb.setFocusable(false); previewWeb.setFocusableInTouchMode(false);
        previewWeb.getSettings().setJavaScriptEnabled(true);
        previewWeb.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(android.webkit.WebView view, String url) { previewReady = true; }
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest request) { return true; }
        });
        previewCard.addView(previewWeb, new FrameLayout.LayoutParams(-1, -1));

        // 底部 5-Tab 栏：白/浅灰 #F2F2F2 圆角卡片，Logo / 搜索框 / 背景 / 收藏 / 高级
        bottomTabs = new LinearLayout(this);
        bottomTabs.setOrientation(LinearLayout.HORIZONTAL);

        bottomTabs.setGravity(Gravity.CENTER_VERTICAL);

        // One rounded grey backing surface contains both the white card and the tabs.
        customizeSheet = new FrameLayout(this);
        customizeSheet.setElevation(dp(16));
        GradientDrawable backing = new GradientDrawable();
        backing.setColor(0xFFEEEEEE);
        backing.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        customizeSheet.setBackground(backing);
        bottomTabs.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        bottomTabs.addView(createTab("Logo", this::showLogoSheet), tabLp);
        bottomTabs.addView(createTab("搜索框", this::showSearchSheet), tabLp);
        bottomTabs.addView(createTab("背景", this::showBackgroundSheet), tabLp);
        bottomTabs.addView(createTab("收藏", this::showFavoritesSheet), tabLp);
        bottomTabs.addView(createTab("高级", this::showAdvancedSheet), tabLp);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM);
        customizeSheet.addView(bottomTabs, bottomParams);
        root.addView(customizeSheet, new FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM));
        root.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> updatePanelInsets());

        setContentView(root);
        updatePreview();

        String p = getIntent().getStringExtra(EXTRA_PAGE);
        if ("logo".equals(p)) showLogoSheet();
        else if ("search".equals(p)) showSearchSheet();
        else if ("background".equals(p)) showBackgroundSheet();
        else if ("favorites".equals(p)) showFavoritesSheet();
    }

    private View createTab(String label, Runnable onClick) {
        TextView tab = new TextView(this);
        tab.setText(label);
        tab.setTextColor(0xFF222222);
        tab.setTextSize(14);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setBackgroundResource(R.drawable.bg_via_menu_cell);
        tab.setOnClickListener(v -> {
            if (label.equals(activeTab)) {
                closeSlider(); customizeSheet.removeView(panelHost); panelHost = null; activePanel = null; activeTab = null;
                resizeCustomizeSheet();
                tintTabs();
            } else onClick.run();
        });
        return tab;
    }

    private void resizeCustomizeSheet() {
        ViewGroup.LayoutParams params = customizeSheet.getLayoutParams();
        params.height = dp(panelHost == null ? 48 : 184);
        customizeSheet.setLayoutParams(params);
    }

    private void updatePanelInsets() {
        if (panelHost == null || root.getWidth() == 0) return;
        int width = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int inset = Math.round(width * .025f); // Match the 95% preview, rather than the full window.
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panelHost.getLayoutParams();
        if (params.leftMargin != inset || params.rightMargin != inset) {
            params.leftMargin = inset; params.rightMargin = inset; panelHost.setLayoutParams(params);
        }
        int cellWidth = "palette".equals(activePanel.getTag()) ? dp(56) : (width - inset * 2) / 5;
        for (int i = 0; i < activePanel.getChildCount(); i++) {
            View child = activePanel.getChildAt(i);
            ViewGroup.LayoutParams cell = child.getLayoutParams();
            if (cell.width != cellWidth) { cell.width = cellWidth; child.setLayoutParams(cell); }
        }
    }

    private void tintTabs() {
        if (bottomTabs == null) return;
        for (int i = 0; i < bottomTabs.getChildCount(); i++) {
            TextView tab = (TextView) bottomTabs.getChildAt(i);
            tab.setTextColor(tab.getText().toString().equals(activeTab) ? 0xFF6F8DE1 : 0xFF222222);
        }
    }

    private int dp(float v) {
        return ViaUi.dp(this, v);
    }

    /** 实时驱动主页卡片样式更新。 */
    private void updatePreview() {
        String html = new com.example.cleanrecovery.ui.browser.HomePageRenderer(this).render(prefs.homeCustomCss());
        if (!html.equals(renderedHtml)) {
            renderedHtml = html;
            if (!previewReady) previewWeb.loadDataWithBaseURL("https://local.via/", html, "text/html", "UTF-8", null);
            else previewWeb.evaluateJavascript("(function(){const next=new DOMParser().parseFromString("
                + org.json.JSONObject.quote(html) + ",'text/html');document.querySelector('style').textContent=next.querySelector('style').textContent;"
                + "document.getElementById('content').innerHTML=next.getElementById('content').innerHTML;})()", null);
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(prefs.homeBackgroundColor()); background.setCornerRadius(dp(16));
        previewCard.setBackground(background);
    }

    // ================= 弹窗配置 =================

    private final class SheetDialog {
        final LinearLayout container;
        final String title;
        SheetDialog(LinearLayout container, String title) { this.container = container; this.title = title; }
        void show() {
            if (sliding) return;
            closeSlider();
            if (panelHost != null) customizeSheet.removeView(panelHost);
            activePanel = container;
            activeTab = title; tintTabs();
            panelHost = new LinearLayout(BrowserHomeCustomizeActivity.this);
            panelHost.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable card = new GradientDrawable();
            card.setColor(Color.WHITE);
            // The top joins the preview; the lower corners reveal the surrounding grey sheet.
            card.setCornerRadii(new float[]{0, 0, 0, 0, dp(16), dp(16), dp(16), dp(16)});
            panelHost.setBackground(card);
            panelHost.setClipToOutline(true);
            sliderHost = new FrameLayout(BrowserHomeCustomizeActivity.this);
            panelHost.addView(sliderHost, new LinearLayout.LayoutParams(-1, dp(40)));
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(BrowserHomeCustomizeActivity.this);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setFillViewport(true);
            for (int i = 0; i < container.getChildCount(); i++) {
                container.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(
                    "palette".equals(container.getTag()) ? dp(56) : Math.max(dp(64), getResources().getDisplayMetrics().widthPixels / 5), -1));
            }
            scroll.addView(container, new android.widget.HorizontalScrollView.LayoutParams(-2, -1));
            panelHost.addView(scroll, new LinearLayout.LayoutParams(-1, dp(96)));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            params.bottomMargin = dp(48);
            customizeSheet.addView(panelHost, params);
            resizeCustomizeSheet();
            updatePanelInsets();
        }
        void dismiss() {
            if (activePanel == container) {
                customizeSheet.removeView(panelHost);
                panelHost = null; activePanel = null; activeTab = null;
                resizeCustomizeSheet(); tintTabs();
            }
        }
    }

    private void showLogoSheet() {
        SheetDialog d = createSheetDialog("Logo");
        LinearLayout box = d.container;

        String[] modes = {"默认", "图片", "文字", "HTML", "隐藏"};
        box.addView(dialogRadioRow("Logo", modes[Math.max(0, Math.min(4, prefs.homeLogoMode()))], () -> {
            ViaUi.radioDialog(this, "Logo", modes, prefs.homeLogoMode(), idx -> {
                if (idx == 1) { pickImage(REQ_LOGO_IMAGE); return; }
                if (idx == 2) {
                    ViaUi.inputDialog(this, "文字", new ViaUi.InputField("Logo 文字", prefs.homeLogoText(), false),
                        null, false, null, (values, checked) -> {
                            prefs.setHomeLogoText(values[0]); prefs.setHomeLogoMode(2); updatePreview(); showLogoSheet();
                        });
                    return;
                }
                if (idx == 3) { showEditor("HTML", prefs.homeLogoText(), value -> {
                    prefs.setHomeLogoText(value); prefs.setHomeLogoMode(3); updatePreview(); showLogoSheet();
                }); return; }
                if (idx == 0) prefs.resetHomeLogo();
                prefs.setHomeLogoMode(idx); updatePreview(); showLogoSheet();
            }).show();
        }));

        if (prefs.homeLogoMode() == 2) {
            box.addView(panelSlider("字号", String.valueOf(prefs.homeLogoTextSize()), prefs.homeLogoTextSize(), 10, 63, "px",
                value -> { prefs.setHomeLogoTextSize(value); updatePreview(); }));
            box.addView(dialogNavRow("字体", new String[]{"常规", "粗体", "斜体", "粗斜体"}[(prefs.homeLogoBold() ? 1 : 0) + (prefs.homeLogoItalic() ? 2 : 0)], () -> {
                int next = ((prefs.homeLogoBold() ? 1 : 0) + (prefs.homeLogoItalic() ? 2 : 0) + 1) % 4;
                prefs.setHomeLogoBold((next & 1) != 0); prefs.setHomeLogoItalic((next & 2) != 0); updatePreview(); showLogoSheet();
            }));
            d.show(); return;
        }
        if (prefs.homeLogoMode() == 3 || prefs.homeLogoMode() == 4) { d.show(); return; }
        box.addView(panelSlider("宽度", prefs.homeLogoWidth() == 0 ? "自适应" : prefs.homeLogoWidth() + "px",
                Math.max(10, prefs.homeLogoWidth()), 10, 127, "px", value -> { prefs.setHomeLogoWidth(value == 10 ? 0 : value); updatePreview(); showLogoSheet(); }));
        box.addView(panelSlider("高度", prefs.homeLogoSize() == 0 ? "自适应" : prefs.homeLogoSize() + "px", Math.max(10, prefs.homeLogoSize()), 10, 127, "px",
                value -> { prefs.setHomeLogoSize(value == 10 ? 0 : value); updatePreview(); showLogoSheet(); }));
        box.addView(panelSlider("圆角值", prefs.homeLogoRadius() + "%", prefs.homeLogoRadius(), 0, 100, "%",
                value -> { prefs.setHomeLogoRadius(value); updatePreview(); showLogoSheet(); }));

        d.show();
    }

    private void showSearchSheet() {
        SheetDialog d = createSheetDialog("搜索框");
        LinearLayout box = d.container;

        box.addView(panelSlider("圆角值", prefs.homeSearchRadius() + "%", prefs.homeSearchRadius(), 0, 100, "%",
                value -> { prefs.setHomeSearchRadius(value); updatePreview(); showSearchSheet(); }));
        box.addView(panelSlider("不透明度", prefs.homeSearchAlpha() + "%", prefs.homeSearchAlpha(), 0, 100, "%",
                value -> { prefs.setHomeSearchAlpha(value); updatePreview(); showSearchSheet(); }));
        box.addView(panelSlider("描边粗细", prefs.homeSearchStroke() + "px", prefs.homeSearchStroke(), 0, 7, "px",
                value -> { prefs.setHomeSearchStroke(value); updatePreview(); showSearchSheet(); }));
        box.addView(panelSlider("描边不透明度", prefs.homeSearchStrokeAlpha() + "%", prefs.homeSearchStrokeAlpha(), 0, 100, "%",
                value -> { prefs.setHomeSearchStrokeAlpha(value); updatePreview(); showSearchSheet(); }));
        box.addView(dialogNavRow("搜索框样式", prefs.homeSearchStyle() == 0 ? "矩形" : "下划线", () ->
                { prefs.setHomeSearchStyle(1 - prefs.homeSearchStyle()); updatePreview(); showSearchSheet(); }));

        d.show();
    }

    private void showBackgroundSheet() {
        SheetDialog d = createSheetDialog("背景");
        LinearLayout box = d.container;

        if (!prefs.homeBackgroundUri().isEmpty() && !chooseBackground) {
            box.addView(dialogNavRow("背景", "图片", () -> { chooseBackground = true; showBackgroundSheet(); }));
            box.addView(dialogNavRow("主题色", prefs.homeBackgroundDark() ? "暗色" : "亮色", () -> {
                prefs.setHomeBackgroundDark(!prefs.homeBackgroundDark()); updatePreview(); showBackgroundSheet();
            }));
            box.addView(panelSlider("不透明度", prefs.homeBackgroundShade() + "%", prefs.homeBackgroundShade(), 0, 80, "%",
                value -> { prefs.setHomeBackgroundShade(value); updatePreview(); }));
            d.show(); return;
        }
        box.setTag("palette");
        int[] colors = {Color.WHITE, Color.WHITE, -13928247, -13287859, -7707309, -4162752, -1919870, -11776948, -3848376, -482746, -79799, -4931980, -9780028, -10391622, -6592854, -3432857, -6578261, -9876171, -6801101, -2519416, -2121393, -10790341, -4480632, -10727322};
        String[] labels = {"默认", "图片", "Classic blue", "Indigo", "Leather", "Walnut", "Birch", "Limestone", "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "Brown", "Light grey", "Maroon", "Light Pink", "Carnation", "Hay", "Tawny", "Light brown", "Deep purple"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            FrameLayout cell = new FrameLayout(this); cell.setContentDescription(labels[i]);
            ImageView swatch = new ImageView(this);
            GradientDrawable background = new GradientDrawable(); background.setShape(GradientDrawable.OVAL);
            background.setColor(colors[i]); background.setStroke(dp(1), 0xFFDDDDDD);
            swatch.setBackground(background);
            if (i == 1) swatch.setImageResource(R.drawable.via_customize_background);
            boolean selected = i == 1 ? !prefs.homeBackgroundUri().isEmpty() : prefs.homeBackgroundUri().isEmpty() && prefs.homeBackgroundColor() == colors[i];
            if (selected) background.setStroke(dp(3), 0xFF6F8DE1);
            cell.addView(swatch, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
            cell.setOnClickListener(v -> {
                if (index == 1) { pickImage(REQ_BG_IMAGE); return; }
                chooseBackground = false; prefs.setHomeBackgroundUri(""); prefs.setHomeBackgroundColor(colors[index]); updatePreview(); showBackgroundSheet();
            });
            box.addView(cell);
        }
        d.show();
    }

    private void showFavoritesSheet() {
        SheetDialog d = createSheetDialog("收藏");
        LinearLayout box = d.container;

        box.addView(panelSlider("宽度", prefs.homeFavoriteRawWidth() == 0 ? "自适应" : prefs.homeFavoriteRawWidth() + "px", Math.max(24, prefs.homeFavoriteRawWidth()), 24, 80, "px",
                value -> { prefs.setHomeFavoriteWidth(value == 24 ? 0 : value); updatePreview(); showFavoritesSheet(); }));
        box.addView(panelSlider("高度", prefs.homeFavoriteRawHeight() == 0 ? "自适应" : prefs.homeFavoriteRawHeight() + "px", Math.max(24, prefs.homeFavoriteRawHeight()), 24, 80, "px",
                value -> { prefs.setHomeFavoriteHeight(value == 24 ? 0 : value); updatePreview(); showFavoritesSheet(); }));
        box.addView(panelSlider("圆角值", prefs.homeFavoriteRadius() + "%", prefs.homeFavoriteRadius(), 0, 100, "%",
                value -> { prefs.setHomeFavoriteRadius(value); updatePreview(); showFavoritesSheet(); }));
        box.addView(dialogNavRow("图标样式", prefs.homeFavoriteIconStyle() == 0 ? "图标优先" : "标题优先", () ->
                { prefs.setHomeFavoriteIconStyle(1 - prefs.homeFavoriteIconStyle()); updatePreview(); showFavoritesSheet(); }));
        box.addView(dialogNavRow("图标色彩", prefs.homeFavoriteIconColor() == 0 ? "多彩" : "单色", () ->
                { prefs.setHomeFavoriteIconColor(1 - prefs.homeFavoriteIconColor()); updatePreview(); showFavoritesSheet(); }));

        d.show();
    }

    private void showAdvancedSheet() {
        SheetDialog d = createSheetDialog("高级");
        LinearLayout box = d.container;

        box.addView(dialogNavRow("搜索框效果", prefs.homeSearchEffect() == 0 ? "透明" : "模糊", () ->
                { prefs.setHomeSearchEffect(1 - prefs.homeSearchEffect()); updatePreview(); showAdvancedSheet(); }));
        box.addView(dialogNavRow("搜索区域", prefs.homeSearchVisible() ? "展示" : "隐藏", () -> {
            prefs.setHomeSearchVisible(!prefs.homeSearchVisible()); updatePreview(); showAdvancedSheet();
        }));
        box.addView(dialogNavRow("自定义 CSS", null, () -> showEditor("自定义 CSS", prefs.homeCustomCss(), value -> {
            prefs.setHomeCustomCss(value); updatePreview();
        })));
        box.addView(dialogNavRow("清除图标", null, () -> new ViaDialogBuilder(this)
            .setTitle("清除图标").setMessage("清除缓存的站点图标？")
            .setNegativeButton("取消", null).setPositiveButton("确定", (dialog, which) -> {
                previewWeb.clearCache(true); renderedHtml = null; updatePreview();
            }).show()));
        box.addView(dialogNavRow("重置", null, () -> new ViaDialogBuilder(this)
            .setTitle("重置").setMessage("恢复默认主页定制？")
            .setNegativeButton("取消", null).setPositiveButton("确定", (dialog, which) -> resetAll()).show()));

        d.show();
    }

    private SheetDialog createSheetDialog(String title) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setBackgroundColor(Color.WHITE);
        return new SheetDialog(container, title);
    }

    private View dialogNavRow(String title, String subtitle, Runnable onClick) {
        LinearLayout row = baseDialogRow();
        attachDialogTexts(row, title, subtitle);
        row.setOnClickListener(v -> {
            closeSlider();
            if (onClick != null) onClick.run();
        });
        return row;
    }

    private View dialogRadioRow(String title, String currentVal, Runnable onClick) {
        return dialogNavRow(title, currentVal, onClick);
    }

    private View panelSlider(String title, String value, int initial, int min, int max,
                             String suffix, ViaUi.OnSlider change) {
        final int[] current = {initial};
        LinearLayout row = (LinearLayout) dialogNavRow(title, value, null);
        row.setOnClickListener(v -> {
            if (title.equals(selectedSlider)) { closeSlider(); return; }
            closeSlider(); selectedSlider = title;
            row.setSelected(true);
            tintOption(row, 0xFF6F8DE1);
            activeSlider = new SeekBar(this);
            activeSlider.setContentDescription(title);
            activeSlider.setMax(max - min); activeSlider.setProgress(current[0] - min);
            activeSlider.setPadding(0, dp(9), 0, dp(12));
            activeSlider.setThumbOffset(0);
            GradientDrawable thumb = new GradientDrawable(); thumb.setColor(0xFF6F8DE1);
            thumb.setShape(GradientDrawable.OVAL); thumb.setSize(dp(12), dp(12));
            activeSlider.setThumb(thumb);
            activeSlider.getProgressDrawable().setTint(0xFF6F8DE1);
            activeSlider.getThumb().setTint(0xFF6F8DE1);
            activeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar bar) {}
                public void onStopTrackingTouch(SeekBar bar) {}
                public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                    sliding = true;
                    try {
                        current[0] = min + progress;
                        change.onChange(current[0]);
                        LinearLayout texts = (LinearLayout) row.getChildAt(0);
                        ((TextView) texts.getChildAt(texts.getChildCount() - 1)).setText(("宽度".equals(title) || "高度".equals(title)) && progress == 0 ? "自适应" : (min + progress) + suffix);
                    } finally { sliding = false; }
                }
            });
            FrameLayout.LayoutParams sliderParams = new FrameLayout.LayoutParams(-1, -1);
            sliderParams.leftMargin = dp(24); sliderParams.rightMargin = dp(24);
            sliderHost.addView(activeSlider, sliderParams);
        });
        return row;
    }

    private void closeSlider() {
        if (activeSlider != null && activeSlider.getParent() instanceof ViewGroup)
            ((ViewGroup) activeSlider.getParent()).removeView(activeSlider);
        activeSlider = null; selectedSlider = null;
        if (activePanel != null) for (int i = 0; i < activePanel.getChildCount(); i++) {
            View row = activePanel.getChildAt(i); row.setSelected(false); tintOption(row, 0xFF444444);
        }
    }

    private void tintOption(View view, int color) {
        if (view instanceof ImageView) ((ImageView) view).setColorFilter(color);
        if (view instanceof TextView) ((TextView) view).setTextColor(color);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) tintOption(((ViewGroup) view).getChildAt(i), color);
    }

    private void showEditor(String title, String value, java.util.function.Consumer<String> save) {
        closeSlider();
        editor = new FrameLayout(this); editor.setBackgroundColor(Color.WHITE);
        editor.setElevation(dp(24));
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        TextView cancel = new TextView(this); cancel.setText("返回"); cancel.setGravity(Gravity.CENTER);
        TextView heading = new TextView(this); heading.setText(title); heading.setGravity(Gravity.CENTER);
        TextView done = new TextView(this); done.setText("保存"); done.setGravity(Gravity.CENTER);
        bar.addView(cancel, new LinearLayout.LayoutParams(dp(64), dp(48)));
        bar.addView(heading, new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(done, new LinearLayout.LayoutParams(dp(64), dp(48)));
        EditText source = new EditText(this); source.setText(value); source.setGravity(Gravity.TOP);
        source.setTypeface(Typeface.MONOSPACE); source.setTextSize(14); source.setContentDescription(title + "源码");
        source.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        source.setHorizontallyScrolling(true);
        page.addView(bar); page.addView(source, new LinearLayout.LayoutParams(-1, 0, 1));
        editor.addView(page); root.addView(editor, new FrameLayout.LayoutParams(-1, -1));
        cancel.setOnClickListener(v -> onBackPressed());
        done.setOnClickListener(v -> { save.accept(source.getText().toString()); root.removeView(editor); editor = null; });
    }

    private LinearLayout baseDialogRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(12));
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.bg_via_menu_cell);
        row.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return row;
    }

    private void attachDialogTexts(LinearLayout row, String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(11);
        t.setMaxLines(2); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(4), dp(2), dp(4), 0);
        t.setGravity(Gravity.CENTER);
        int iconId = optionIcon(title);
        if (iconId != 0) {
            ImageView icon = new ImageView(this); icon.setImageResource(iconId); icon.setColorFilter(0xFF444444);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
            iconParams.gravity = Gravity.CENTER; iconParams.bottomMargin = 0;
            box.addView(icon, iconParams);
        }
        t.setTextColor(0xFF212121);
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextSize(11);
            s.setGravity(Gravity.CENTER); s.setMaxLines(1);
            s.setTextColor(0xFF757575);
            s.setPadding(dp(4), dp(2), dp(4), 0);
            box.addView(s, new LinearLayout.LayoutParams(-1, -2));
        }
        box.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL); box.setPadding(0, dp(12), 0, 0);
        row.addView(box, new LinearLayout.LayoutParams(-1, -1));
    }

    private int optionIcon(String title) {
        switch (title) {
            case "Logo": return R.drawable.via_customize_logo;
            case "宽度": return R.drawable.via_customize_width;
            case "高度": return R.drawable.via_customize_height;
            case "圆角值": return R.drawable.via_customize_radius;
            case "字号": return R.drawable.via_customize_font;
            case "字体": return R.drawable.via_customize_typeface;
            case "图标样式": return R.drawable.via_customize_style;
            case "主题色":
            case "图标色彩": return R.drawable.via_customize_color;
            case "不透明度": return R.drawable.via_customize_alpha;
            case "描边不透明度": return R.drawable.via_customize_stroke_alpha;
            case "描边粗细": return R.drawable.via_customize_stroke;
            case "搜索框效果": return R.drawable.via_customize_effect;
            case "搜索区域": case "搜索框样式": return R.drawable.via_customize_search;
            case "自定义 CSS": return R.drawable.via_customize_css;
            case "清除图标": return R.drawable.via_customize_clear;
            case "重置": return R.drawable.via_customize_reset;
            default: return R.drawable.via_customize_background;
        }
    }

    @Override protected void onDestroy() {
        if (previewWeb != null) { previewWeb.stopLoading(); previewWeb.destroy(); }
        super.onDestroy();
    }

    private void pickImage(int request) {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("image/*");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(it, request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            if (requestCode == REQ_LOGO_IMAGE) {
                prefs.setHomeLogoUri(uri.toString());
                prefs.setHomeLogoMode(1);
                prefs.setHomeLogoWidth(80); prefs.setHomeLogoSize(80); prefs.setHomeLogoRadius(100);
            } else if (requestCode == REQ_BG_IMAGE) {
                prefs.setHomeBackgroundUri(uri.toString());
                chooseBackground = false;
            }
            updatePreview();
            if (requestCode == REQ_LOGO_IMAGE) showLogoSheet(); else showBackgroundSheet();
        }
    }

    private void resetAll() {
        prefs.resetHomeLogo();
        prefs.resetHomeSearch();
        prefs.resetHomeBackground();
        prefs.resetHomeFavorites();
        prefs.setHomeCustomCss("");
        showAdvancedSheet();
        updatePreview();
        GlassToast.makeText(this, "已重置主页定制", GlassToast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (editor != null) {
            new ViaDialogBuilder(this).setMessage("放弃未保存的修改？")
                .setNegativeButton("取消", null).setPositiveButton("放弃", (dialog, which) -> {
                    root.removeView(editor); editor = null;
                }).show(); return;
        }
        if (activeSlider != null) { closeSlider(); return; }
        setResult(RESULT_OK); finish();
    }
}
