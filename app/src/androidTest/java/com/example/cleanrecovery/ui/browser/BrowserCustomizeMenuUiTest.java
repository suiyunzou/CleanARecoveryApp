package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.activity.BrowserActivity;
import com.example.cleanrecovery.ui.activity.BrowserHomeCustomizeActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserCustomizeMenuUiTest {
    private final android.app.Instrumentation instrument = InstrumentationRegistry.getInstrumentation();
    private android.content.SharedPreferences savedPreferences;
    private java.util.Map<String, ?> savedValues;
    @org.junit.Before public void isolateHomeSettings() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrument.getTargetContext());
        savedPreferences = (android.content.SharedPreferences) field(prefs, "sp");
        savedValues = new java.util.HashMap<>(savedPreferences.getAll());
        prefs.resetHomeLogo(); prefs.resetHomeSearch(); prefs.resetHomeBackground(); prefs.resetHomeFavorites(); prefs.setHomeCustomCss("");
    }
    @org.junit.After public void restoreHomeSettings() {
        android.content.SharedPreferences.Editor edit = savedPreferences.edit();
        for (String key : savedPreferences.getAll().keySet()) if (key.startsWith("home_")) edit.remove(key);
        for (java.util.Map.Entry<String, ?> e : savedValues.entrySet()) if (e.getKey().startsWith("home_")) {
            Object value = e.getValue();
            if (value instanceof String) edit.putString(e.getKey(), (String)value);
            else if (value instanceof Integer) edit.putInt(e.getKey(), (Integer)value);
            else if (value instanceof Boolean) edit.putBoolean(e.getKey(), (Boolean)value);
        }
        edit.commit();
    }
    interface Work<T> { T run() throws Exception; }
    private <T> T main(Work<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work::run); instrument.runOnMainSync(task); return task.get();
    }
    private Activity launch(Class<? extends Activity> type) {
        return instrument.startActivitySync(new Intent(instrument.getTargetContext(), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private Object field(Object object, String name) throws Exception {
        java.lang.reflect.Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private View text(View root, String title) {
        if (root instanceof TextView && title.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View result = text(((ViewGroup) root).getChildAt(i), title); if (result != null) return result;
        }
        return null;
    }
    private void click(Activity activity, String title) throws Exception {
        main(() -> {
            View view = text(activity.getWindow().getDecorView(), title); assertNotNull(title, view);
            while (!view.isClickable()) view = (View) view.getParent(); assertTrue(view.performClick()); return null;
        }); instrument.waitForIdleSync();
    }
    private void snapshot(String name) throws Exception {
        Thread.sleep(750);
        android.graphics.Bitmap image = instrument.getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(instrument.getTargetContext().getExternalFilesDir(null), name + ".png"))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } image.recycle();
    }
    @Test public void everyMenuPageRendersBothRowsInsideClickableCells() throws Exception {
        Activity activity = launch(BrowserActivity.class);
        Dialog[] dialog = {null};
        try {
            BrowserBottomMenu menu = main(() -> {
                java.util.List<BrowserBottomMenu.Entry> entries = new java.util.ArrayList<>();
                for (int i = 0; i < 30; i++) entries.add(new BrowserBottomMenu.Entry(i, "fixture_" + i, "菜单 " + i, activity.getDrawable(R.drawable.via_menu_power)));
                BrowserBottomMenu result = new BrowserBottomMenu(activity, entries, id -> {}); result.show();
                dialog[0] = (Dialog) field(result, "dialog"); return result;
            });
            RecyclerView pager = main(() -> (RecyclerView) field(menu, "pager"));
            for (int page = 0; page < 3; page++) {
                int position = page; main(() -> { pager.scrollToPosition(position); return null; });
                instrument.waitForIdleSync(); Thread.sleep(300);
                main(() -> {
                    Rect viewport = new Rect(); assertTrue(pager.getGlobalVisibleRect(viewport));
                    for (int i = position * 10; i < position * 10 + 10; i++) {
                        View label = text(dialog[0].getWindow().getDecorView(), "菜单 " + i); assertNotNull(label);
                        ViewGroup cell = (ViewGroup) label.getParent();
                        for (int child = 0; child < cell.getChildCount(); child++) {
                            View content = cell.getChildAt(child); Rect visible = new Rect();
                            assertTrue("Visible icon/text for item " + i, content.getGlobalVisibleRect(visible));
                            assertTrue("Content width for " + i, visible.width() > 0);
                            assertEquals("No vertical clipping for " + i, content.getHeight(), visible.height());
                            assertTrue("Content stays in page " + i, viewport.contains(visible));
                        }
                        assertTrue("Cell remains clickable", cell.isClickable());
                    } return null;
                });
                snapshot("menu-page-" + page);
            }
        } finally { main(() -> { if (dialog[0] != null) dialog[0].dismiss(); activity.finish(); return null; }); }
    }
    @Test public void realMenuSecondRowSurvivesLargeFontAndDensityChanges() throws Exception {
        Activity activity = launch(BrowserActivity.class);
        android.content.res.Configuration original = new android.content.res.Configuration(activity.getResources().getConfiguration());
        try {
            for (float scale : new float[]{1f, 1.5f, 2f}) {
                final float font = scale;
                BrowserBottomMenu menu = main(() -> {
                    android.content.res.Configuration config = new android.content.res.Configuration(original);
                    config.fontScale = font; config.densityDpi = font == 1 ? 480 : 560;
                    activity.getResources().updateConfiguration(config, null);
                    java.lang.reflect.Method builder = BrowserActivity.class.getDeclaredMethod("buildMenuEntries"); builder.setAccessible(true);
                    BrowserBottomMenu result = new BrowserBottomMenu(activity, new BrowserPrefs(activity),
                        (java.util.List<BrowserBottomMenu.Entry>) builder.invoke(activity), id -> {});
                    result.show(); return result;
                });
                try {
                    RecyclerView pager = main(() -> (RecyclerView) field(menu, "pager"));
                    main(() -> { pager.scrollToPosition(1); return null; }); instrument.waitForIdleSync(); Thread.sleep(500);
                    main(() -> {
                        View page = pager.getLayoutManager().findViewByPosition(1); assertNotNull(page);
                        java.util.List<TextView> labels = new java.util.ArrayList<>(); collectLabels(page, labels);
                        assertEquals("All ten actual menu labels", 10, labels.size());
                        Rect pageRect = new Rect(); assertTrue(page.getGlobalVisibleRect(pageRect));
                        for (TextView label : labels) {
                            Rect rect = new Rect(); assertTrue(label.getText().toString(), label.getGlobalVisibleRect(rect));
                            assertEquals(label.getText().toString(), label.getHeight(), rect.height());
                            assertTrue(pageRect.contains(rect));
                            ViewGroup cell = (ViewGroup) label.getParent(); Rect icon = new Rect();
                            assertTrue(cell.getChildAt(0).getGlobalVisibleRect(icon));
                            assertEquals(cell.getChildAt(0).getHeight(), icon.height());
                        } return null;
                    });
                    snapshot("menu-real-font-" + font);
                } finally { main(() -> { ((Dialog) field(menu, "dialog")).dismiss(); return null; }); }
            }
        } finally { main(() -> { activity.getResources().updateConfiguration(original, null); activity.finish(); return null; }); }
    }
    private void collectLabels(View view, java.util.List<TextView> labels) {
        if (view instanceof TextView) labels.add((TextView) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectLabels(((ViewGroup) view).getChildAt(i), labels);
    }

    @Test public void logoControlsStayInsideRoundedBackingWithAndWithoutSlider() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrument.getTargetContext());
        int mode = prefs.homeLogoMode(); prefs.setHomeLogoMode(0);
        Activity activity = launch(BrowserHomeCustomizeActivity.class);
        try {
            click(activity, "Logo");
            for (boolean expanded : new boolean[]{false, true}) {
                if (expanded) click(activity, "高度");
                instrument.waitForIdleSync(); Thread.sleep(750);
                Rect[] bounds = main(() -> {
                    View card = (View) field(activity, "panelHost"), tabs = (View) field(activity, "bottomTabs");
                    assertSame("White card and tabs share one backing surface", card.getParent(), tabs.getParent());
                    Rect cardBounds = new Rect(), tabsBounds = new Rect(), sheetBounds = new Rect();
                    assertTrue(card.getGlobalVisibleRect(cardBounds)); assertTrue(tabs.getGlobalVisibleRect(tabsBounds));
                    assertTrue(((View) card.getParent()).getGlobalVisibleRect(sheetBounds));
                    assertTrue("White card is inset on the left", cardBounds.left > sheetBounds.left);
                    assertTrue("White card is inset on the right", cardBounds.right < sheetBounds.right);
                    assertEquals("No gap between card and tabs", cardBounds.bottom, tabsBounds.top);
                    assertEquals("Tabs use the full backing width", sheetBounds.width(), tabsBounds.width());
                    return new Rect[]{cardBounds, tabsBounds, sheetBounds};
                });
                android.graphics.Bitmap pixels = instrument.getUiAutomation().takeScreenshot();
                try {
                    Rect card = bounds[0], tabs = bounds[1], sheet = bounds[2];
                    int step = Math.max(1, Math.round(activity.getResources().getDisplayMetrics().density * 2));
                    assertEquals("Bottom corner exposes grey backing, not square white", 0xffeeeeee,
                        pixels.getPixel(card.left + step, card.bottom - step));
                    assertEquals("Card remains white inside its rounded edge", 0xffffffff,
                        pixels.getPixel(card.centerX(), card.bottom - step));
                    assertEquals("Grey backing runs down to the tabs", 0xffeeeeee,
                        pixels.getPixel(sheet.left + step, tabs.centerY()));
                } finally { pixels.recycle(); }
                snapshot(expanded ? "customize-nested-logo-expanded" : "customize-nested-logo-collapsed");
            }
            click(activity, "Logo");
            main(() -> { assertNull(field(activity, "panelHost")); return null; });
            click(activity, "Logo");
            main(() -> { assertNotNull(field(activity, "panelHost")); return null; });
        } finally { prefs.setHomeLogoMode(mode); main(() -> { activity.finish(); return null; }); }
    }

    @Test public void homepageBookmarksUseFixedColumnsForSingleAndIncompleteRows() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrument.getTargetContext());
        int mode = prefs.homeLogoMode(), effect = prefs.homeSearchEffect(), width = prefs.homeFavoriteWidth(), iconStyle = prefs.homeFavoriteIconStyle();
        String css = prefs.homeCustomCss(), background = prefs.homeBackgroundUri();
        prefs.setHomeLogoMode(0); prefs.setHomeSearchEffect(0); prefs.setHomeCustomCss(""); prefs.setHomeBackgroundUri("");
        prefs.setHomeFavoriteIconStyle(1);
        Activity activity = launch(BrowserActivity.class);
        try {
            main(() -> { java.lang.reflect.Method home = BrowserActivity.class.getDeclaredMethod("showHome"); home.setAccessible(true); home.invoke(activity); return null; });
            for (int iconWidth : new int[]{48, 72}) {
                prefs.setHomeFavoriteWidth(iconWidth);
                final int[] counts = {1, 2, 8};
                int firstLeft = -1;
                for (int count : counts) {
                    main(() -> {
                        android.widget.GridLayout grid = (android.widget.GridLayout) field(activity, "homeGrid"); grid.removeAllViews();
                        ((View) field(activity, "homeScroll")).setVisibility(View.VISIBLE);
                        android.webkit.WebView htmlHome = (android.webkit.WebView) field(activity, "homeCustomWeb");
                        if (htmlHome != null) htmlHome.setVisibility(View.GONE);
                        java.lang.reflect.Method item = BrowserActivity.class.getDeclaredMethod("buildQuickLinkItem", BrowserDatabaseHelper.Entry.class, boolean.class);
                        item.setAccessible(true);
                        for (int i = 0; i < count; i++) grid.addView((View) item.invoke(activity,
                            new BrowserDatabaseHelper.Entry(-i - 1, "书签 " + i, "https://bookmark-layout.test/" + i, 0), false));
                        java.lang.reflect.Method layout = BrowserActivity.class.getDeclaredMethod("layoutHomeBookmarks"); layout.setAccessible(true); layout.invoke(activity);
                        return null;
                    });
                    instrument.waitForIdleSync(); Thread.sleep(200);
                    int left = main(() -> {
                        android.widget.GridLayout grid = (android.widget.GridLayout) field(activity, "homeGrid");
                        Rect first = new Rect(); assertTrue(grid.getChildAt(0).getGlobalVisibleRect(first));
                        Rect viewport = new Rect(); ((View) field(activity, "homeScroll")).getGlobalVisibleRect(viewport);
                        assertTrue("Single bookmark starts left of the page center", first.centerX() < viewport.centerX());
                        assertEquals("No stretching to fill available columns", Math.round(iconWidth * activity.getResources().getDisplayMetrics().density), first.width());
                        int columns = grid.getColumnCount();
                        for (int i = columns; i < count; i++) {
                            Rect row = new Rect(), reference = new Rect();
                            grid.getChildAt(i).getGlobalVisibleRect(row); grid.getChildAt(i % columns).getGlobalVisibleRect(reference);
                            assertEquals("Incomplete row uses the same column position", reference.left, row.left);
                        }
                        return first.left;
                    });
                    if (firstLeft >= 0) assertEquals("Adding bookmarks does not move the first column", firstLeft, left);
                    firstLeft = left;
                }
            }
            prefs.setHomeFavoriteWidth(width); prefs.setHomeFavoriteIconStyle(iconStyle);
            main(() -> { java.lang.reflect.Method render = BrowserActivity.class.getDeclaredMethod("renderHomeGrid"); render.setAccessible(true); render.invoke(activity); return null; });
            snapshot("homepage-bookmarks-left-aligned");
        } finally {
            prefs.setHomeLogoMode(mode); prefs.setHomeSearchEffect(effect); prefs.setHomeCustomCss(css); prefs.setHomeBackgroundUri(background);
            prefs.setHomeFavoriteWidth(width); prefs.setHomeFavoriteIconStyle(iconStyle);
            main(() -> { activity.finish(); return null; });
        }
    }

    @Test public void bothSearchInputsReleaseFocusOnBackAndCanBeUsedAgain() throws Exception {
        Activity activity = launch(BrowserActivity.class);
        try {
            main(() -> { java.lang.reflect.Method home = BrowserActivity.class.getDeclaredMethod("showHome"); home.setAccessible(true); home.invoke(activity); return null; });
            for (int id : new int[]{R.id.browser_url_input, R.id.browser_home_search}) {
                android.widget.EditText input = activity.findViewById(id);
                for (int attempt = 0; attempt < 2; attempt++) {
                    main(() -> {
                        assertTrue("No input-method fullscreen extraction", (input.getImeOptions() & android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
                        if (id == R.id.browser_url_input) {
                            java.lang.reflect.Method focus = BrowserActivity.class.getDeclaredMethod("focusAddressBar"); focus.setAccessible(true); focus.invoke(activity);
                        } else {
                            input.requestFocus(); ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(input, 0);
                        }
                        input.setText("输入测试"); return null;
                    });
                    long deadline = System.currentTimeMillis() + 4000;
                    while (System.currentTimeMillis() < deadline && !main(() -> {
                        androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
                        return insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
                    })) Thread.sleep(50);
                    assertTrue("Keyboard has opened", main(() -> androidx.core.view.ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView()).isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())));
                    instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
                    Thread.sleep(650); instrument.waitForIdleSync();
                    main(() -> {
                        assertFalse("Back releases input focus", input.hasFocus());
                        assertFalse("Address editing is no longer active", (boolean) field(activity, "addressBarEditing"));
                        assertFalse("Closing the keyboard must not close the browser", activity.isFinishing());
                        return null;
                    });
                }
            }
            snapshot("search-focus-after-back");
        } finally { main(() -> { activity.finish(); return null; }); }
    }

    @Test public void searchSubmitAndOutsideTapReleaseBothInputs() throws Exception {
        Activity activity = launch(BrowserActivity.class);
        try {
            main(() -> {
                java.lang.reflect.Method home = BrowserActivity.class.getDeclaredMethod("showHome"); home.setAccessible(true); home.invoke(activity);
                android.widget.EditText middle = activity.findViewById(R.id.browser_home_search); middle.requestFocus();
                View page = (View) field(activity, "webContainer"); int[] location = new int[2]; page.getLocationOnScreen(location);
                long now = android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, 0, location[0] + 8, location[1] + 8, 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 10, 1, location[0] + 8, location[1] + 8, 0);
                activity.dispatchTouchEvent(down); activity.dispatchTouchEvent(up); down.recycle(); up.recycle();
                assertFalse("Tapping page space releases the middle input", middle.hasFocus());
                java.lang.reflect.Method focus = BrowserActivity.class.getDeclaredMethod("focusAddressBar"); focus.setAccessible(true); focus.invoke(activity);
                android.widget.EditText address = activity.findViewById(R.id.browser_url_input); address.setText("about:blank");
                java.lang.reflect.Method submit = BrowserActivity.class.getDeclaredMethod("loadUrlFromInput"); submit.setAccessible(true); submit.invoke(activity);
                assertFalse(address.hasFocus()); assertFalse(middle.hasFocus());
                assertFalse((boolean) field(activity, "addressBarEditing")); return null;
            });
        } finally { main(() -> { activity.finish(); return null; }); }
    }

    @Test public void sliderIsInlineAboveOptionsAndKeepsItsValueWhenReopened() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrument.getTargetContext()); int old = prefs.homeSearchRadius();
        Activity activity = launch(BrowserHomeCustomizeActivity.class);
        try {
            click(activity, "搜索框"); click(activity, "圆角值");
            SeekBar slider = main(() -> (SeekBar) field(activity, "activeSlider")); assertNotNull(slider);
            main(() -> {
                assertTrue("Slider is in same window", slider.getRootView() == activity.getWindow().getDecorView());
                Rect sliderRect = new Rect(), options = new Rect();
                assertTrue(slider.getGlobalVisibleRect(sliderRect));
                assertTrue(((View) field(activity, "activePanel")).getGlobalVisibleRect(options));
                assertTrue("Slider above option strip", sliderRect.bottom <= options.top);
                float density = activity.getResources().getDisplayMetrics().density;
                assertEquals("Original panel height", Math.round(136 * density), ((View) field(activity, "panelHost")).getHeight());
                assertEquals("Original options height", Math.round(96 * density), ((View) field(activity, "activePanel")).getHeight());
                slider.setProgress(37); return null;
            });
            assertEquals(37, prefs.homeSearchRadius());
            click(activity, "圆角值"); assertNull(main(() -> field(activity, "activeSlider")));
            click(activity, "圆角值");
            assertEquals(37, (int) main(() -> ((SeekBar) field(activity, "activeSlider")).getProgress()));
            java.util.concurrent.CountDownLatch visual = new java.util.concurrent.CountDownLatch(1);
            main(() -> { ((android.webkit.WebView) field(activity, "previewWeb")).postVisualStateCallback(1,
                new android.webkit.WebView.VisualStateCallback() { public void onComplete(long id) { visual.countDown(); } }); return null; });
            assertTrue(visual.await(5, java.util.concurrent.TimeUnit.SECONDS));
            snapshot("customize-inline-slider");
        } finally { prefs.setHomeSearchRadius(old); main(() -> { activity.finish(); return null; }); }
    }
    @Test public void cssEditorSavesToPreviewAndDoesNotReplaceLogo() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrument.getTargetContext()); String old = prefs.homeCustomCss(), logo = prefs.homeLogoUri();
        Activity activity = launch(BrowserHomeCustomizeActivity.class);
        try {
            click(activity, "高级"); click(activity, "自定义 CSS");
            main(() -> {
                ViewGroup editor = (ViewGroup) field(activity, "editor"); assertNotNull(editor);
                android.widget.EditText source = findEdit(editor); assertNotNull(source);
                source.setText(".search_bar{display:none!important}"); return null;
            });
            click(activity, "保存");
            assertEquals(".search_bar{display:none!important}", prefs.homeCustomCss());
            assertTrue(main(() -> ((String) field(activity, "renderedHtml")).contains(prefs.homeCustomCss())));
            assertEquals(logo, prefs.homeLogoUri());
            assertNull(main(() -> field(activity, "editor")));
            snapshot("customize-css");
        } finally { prefs.setHomeCustomCss(old); main(() -> { activity.finish(); return null; }); }
    }
    @Test public void underlineAndCssChangeRenderedPreviewAndActualHomepage() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrument.getTargetContext());
        int style = prefs.homeSearchStyle(), mode = prefs.homeLogoMode(), effect = prefs.homeSearchEffect();
        String css = prefs.homeCustomCss(), logo = prefs.homeLogoText();
        Activity activity = null, browser = null;
        try {
            prefs.setHomeSearchStyle(0); prefs.setHomeSearchEffect(0); prefs.setHomeCustomCss("");
            prefs.setHomeLogoMode(3); prefs.setHomeLogoText("<strong id='custom-logo'>测试 Logo</strong>");
            activity = launch(BrowserHomeCustomizeActivity.class); final Activity shown = activity;
            Thread.sleep(600);
            click(activity, "搜索框"); click(activity, "搜索框样式");
            android.webkit.WebView preview = main(() -> (android.webkit.WebView) field(shown, "previewWeb"));
            assertEquals("\"0px\"", js(preview, "getComputedStyle(document.querySelector('.search_bar')).borderTopWidth"));
            assertEquals("\"测试 Logo\"", js(preview, "document.getElementById('custom-logo').textContent"));
            browser = launch(BrowserActivity.class); final Activity home = browser;
            main(() -> { java.lang.reflect.Method show = BrowserActivity.class.getDeclaredMethod("showHome"); show.setAccessible(true); show.invoke(home); return null; });
            android.webkit.WebView actual = main(() -> (android.webkit.WebView) field(home, "homeCustomWeb"));
            assertNotNull(actual); Thread.sleep(600);
            assertEquals("\"0px\"", js(actual, "getComputedStyle(document.querySelector('.search_bar')).borderTopWidth"));
            assertEquals("\"测试 Logo\"", js(actual, "document.getElementById('custom-logo').textContent"));
        } finally {
            prefs.setHomeSearchStyle(style); prefs.setHomeLogoMode(mode); prefs.setHomeSearchEffect(effect); prefs.setHomeCustomCss(css); prefs.setHomeLogoText(logo);
            final Activity a = activity, b = browser; main(() -> { if (a != null) a.finish(); if (b != null) b.finish(); return null; });
        }
    }
    @Test public void narrowLogoUsesCoverAndSameGeometryOnHomepage() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrument.getTargetContext());
        prefs.setHomeLogoWidth(19); prefs.setHomeLogoSize(72); prefs.setHomeLogoRadius(50);
        Activity custom = launch(BrowserHomeCustomizeActivity.class), browser = null;
        try {
            Thread.sleep(700); click(custom, "Logo"); click(custom, "宽度");
            android.webkit.WebView preview = main(() -> (android.webkit.WebView)field(custom, "previewWeb"));
            String measure = "(()=>{let e=document.querySelector('img.smaller'),s=getComputedStyle(e);return [s.width,s.height,s.objectFit,s.borderRadius]})()";
            assertEquals("[\"19px\",\"72px\",\"cover\",\"18px\"]", js(preview, measure));
            snapshot("customize-logo-width19");
            browser = launch(BrowserActivity.class); final Activity home = browser;
            main(() -> { java.lang.reflect.Method m=BrowserActivity.class.getDeclaredMethod("showHome");m.setAccessible(true);m.invoke(home);return null; });
            Thread.sleep(700);
            android.webkit.WebView actual = main(() -> (android.webkit.WebView)field(home, "homeCustomWeb"));
            assertTrue(main(actual::isShown)); assertEquals(js(preview, measure), js(actual, measure));
            snapshot("homepage-logo-width19");
        } finally { final Activity b=browser; main(() -> {custom.finish();if(b!=null)b.finish();return null;}); }
    }
    @Test public void configurationBoundariesChangeRenderedStylesAndAutoDimensions() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrument.getTargetContext());
        Activity custom = launch(BrowserHomeCustomizeActivity.class);
        try {
            Thread.sleep(700);
            android.webkit.WebView web = main(() -> (android.webkit.WebView)field(custom,"previewWeb"));
            prefs.setHomeLogoWidth(90);prefs.setHomeLogoSize(0);
            prefs.setHomeSearchStroke(7);prefs.setHomeSearchAlpha(100);prefs.setHomeSearchStrokeAlpha(60);
            prefs.setHomeFavoriteWidth(0);prefs.setHomeFavoriteHeight(70);
            refresh(custom);
            assertCssPixels(web, "getComputedStyle(document.querySelector('.search_bar')).borderTopWidth", 7);
            assertEquals(60, Double.parseDouble(js(web,"document.querySelector('.search_bar').getBoundingClientRect().height")), .6);
            assertEquals("\"30px\"",js(web,"getComputedStyle(document.querySelector('.search_bar')).borderRadius"));
            assertEquals(90,Double.parseDouble(js(web,"document.querySelector('img.smaller').getBoundingClientRect().width")),.01);
            assertEquals(70,prefs.homeFavoriteWidth());assertEquals(70,prefs.homeFavoriteHeight());
            prefs.setHomeSearchStyle(1); refresh(custom);
            assertEquals("[\"0px\",\"0px\",\"rgba(0, 0, 0, 0)\"]",js(web,"(()=>{let s=getComputedStyle(document.querySelector('.search_bar'));return [s.borderTopWidth,s.borderRadius,s.backgroundColor]})()"));
            assertCssPixels(web,"getComputedStyle(document.querySelector('.search_bar')).borderBottomWidth",7);
            prefs.setHomeSearchStroke(0);prefs.setHomeSearchVisible(false);refresh(custom);
            assertEquals("\"none\"",js(web,"getComputedStyle(document.querySelector('.search_part')).display"));
            assertEquals("\"18px\"",js(web,"getComputedStyle(document.querySelector('#content')).top"));
            prefs.setHomeFavoriteHeight(0);assertEquals(54,prefs.homeFavoriteWidth());assertEquals(54,prefs.homeFavoriteHeight());
            prefs.setHomeBackgroundColor(0xff202020);prefs.setHomeSearchVisible(true);prefs.setHomeSearchStroke(3);refresh(custom);
            assertEquals("\"rgba(255, 255, 255, 0.6)\"",js(web,"getComputedStyle(document.querySelector('.search_bar')).borderBottomColor"));
            prefs.setHomeSearchStyle(0);prefs.setHomeSearchAlpha(100);refresh(custom);
            assertEquals("\"rgb(27, 27, 27)\"",js(web,"getComputedStyle(document.querySelector('#search_input')).color"));
            prefs.setHomeLogoMode(2);prefs.setHomeLogoText("文字测试");prefs.setHomeLogoBold(true);prefs.setHomeLogoItalic(true);refresh(custom);
            assertEquals("[\"28px\",\"700\",\"italic\"]",js(web,"(()=>{let s=getComputedStyle(document.querySelector('.logo'));return [s.fontSize,s.fontWeight,s.fontStyle]})()"));
        } finally {main(() -> {custom.finish();return null;});}
    }
    @Test public void eachSliderPersistsItsEndpointsAndAutoSlot() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrument.getTargetContext());
        Activity custom = launch(BrowserHomeCustomizeActivity.class);
        try {
            String[][] groups={{"Logo","宽度","高度","圆角值"},{"搜索框","圆角值","不透明度","描边粗细","描边不透明度"},{"收藏","宽度","高度","圆角值"}};
            for (String[] group : groups) {
                click(custom,group[0]);
                for (int i=1;i<group.length;i++) {
                    click(custom,group[i]);
                    SeekBar bar=main(() -> (SeekBar)field(custom,"activeSlider")); assertNotNull(bar);
                    main(() -> {bar.setProgress(bar.getMax());return null;});
                    if (group[0].equals("Logo") && group[i].equals("宽度")) assertEquals(127,prefs.homeLogoWidth());
                    if (group[0].equals("搜索框") && group[i].equals("描边粗细")) assertEquals(7,prefs.homeSearchStroke());
                    main(() -> {bar.setProgress(0);return null;});
                    if (group[i].equals("宽度") || group[i].equals("高度")) {
                        assertNotNull(main(() -> text(custom.getWindow().getDecorView(),"自适应")));
                        if (group[0].equals("Logo")) assertEquals(0,group[i].equals("宽度")?prefs.homeLogoWidth():prefs.homeLogoSize());
                        else assertEquals(0,group[i].equals("宽度")?prefs.homeFavoriteRawWidth():prefs.homeFavoriteRawHeight());
                    }
                }
            }
            assertEquals(54,prefs.homeFavoriteWidth());assertEquals(54,prefs.homeFavoriteHeight());
        } finally {main(() -> {custom.finish();return null;});}
    }
    @Test public void imageBackgroundAndFavoriteColorKeepTheirDistinctEffects() throws Exception {
        BrowserPrefs prefs=new BrowserPrefs(instrument.getTargetContext());
        BrowserDatabaseHelper db=BrowserDatabaseHelper.getInstance(instrument.getTargetContext());
        String fixture="https://via-customize-fixture.invalid/"+System.nanoTime();
        assertTrue(db.addQuickLink("测试图标",fixture));prefs.setHomeMode(0);
        java.io.File picture=new java.io.File(instrument.getTargetContext().getCacheDir(),"customize-effects.png");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);bitmap.eraseColor(android.graphics.Color.RED);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(picture)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        prefs.setHomeBackgroundUri(android.net.Uri.fromFile(picture).toString());prefs.setHomeBackgroundShade(80);
        prefs.setHomeLogoMode(1);prefs.setHomeLogoUri(android.net.Uri.fromFile(picture).toString());prefs.setHomeLogoWidth(80);prefs.setHomeLogoSize(80);prefs.setHomeLogoRadius(100);
        Activity custom=launch(BrowserHomeCustomizeActivity.class);
        try {
            Thread.sleep(700);android.webkit.WebView web=main(() -> (android.webkit.WebView)field(custom,"previewWeb"));
            assertEquals("true",js(web,"getComputedStyle(document.body,'::before').backgroundImage.includes('0.8')"));
            assertEquals("\"40px\"",js(web,"getComputedStyle(document.querySelector('img.smaller')).borderRadius"));
            click(custom,"收藏");click(custom,"图标色彩");
            assertEquals("\"rgba(0, 0, 0, 0)\"",js(web,"getComputedStyle(document.querySelector('.box .title')).backgroundColor"));
            assertEquals("\"none\"",js(web,"getComputedStyle(document.querySelector('.favicon')).filter"));
            click(custom,"图标样式");assertEquals("0",js(web,"document.querySelectorAll('.favicon').length"));
            click(custom,"高级");click(custom,"搜索框效果");
            assertEquals("\"blur(10px)\"",js(web,"getComputedStyle(document.querySelector('.search_bar')).backdropFilter"));
            click(custom,"搜索区域");assertEquals("\"none\"",js(web,"getComputedStyle(document.querySelector('.search_part')).display"));
            main(() -> {java.lang.reflect.Method m=BrowserHomeCustomizeActivity.class.getDeclaredMethod("resetAll");m.setAccessible(true);m.invoke(custom);return null;});
            assertEquals(0,prefs.homeLogoWidth());assertEquals(72,prefs.homeLogoSize());assertEquals(28,prefs.homeLogoTextSize());assertTrue(prefs.homeLogoBold());
            assertEquals(0,prefs.homeFavoriteRawHeight());assertTrue(prefs.homeSearchVisible());assertEquals("",prefs.homeBackgroundUri());
        } finally {
            main(() -> {custom.finish();return null;});
            for(BrowserDatabaseHelper.Entry e:db.listQuickLinks())if(fixture.equals(e.url))db.removeQuickLink(e.id);
            picture.delete();
        }
    }
    private void assertCssPixels(android.webkit.WebView web,String expression,double expected) throws Exception {
        assertEquals(expected,Double.parseDouble(js(web,"parseFloat("+expression+")")),.3);
    }

    private void refresh(Activity custom) throws Exception {
        main(() -> {java.lang.reflect.Method m=BrowserHomeCustomizeActivity.class.getDeclaredMethod("updatePreview");m.setAccessible(true);m.invoke(custom);return null;});
        Thread.sleep(250);
    }

    private String js(android.webkit.WebView web, String expression) throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        String[] result = {null};
        main(() -> { web.evaluateJavascript(expression, value -> { result[0] = value; latch.countDown(); }); return null; });
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)); return result[0];
    }

    private android.widget.EditText findEdit(View root) {
        if (root instanceof android.widget.EditText) return (android.widget.EditText) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            android.widget.EditText result = findEdit(((ViewGroup) root).getChildAt(i)); if (result != null) return result;
        } return null;
    }
}
