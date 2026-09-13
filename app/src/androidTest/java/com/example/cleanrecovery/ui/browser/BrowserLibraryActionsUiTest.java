package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.ui.activity.BookmarksActivity;
import com.example.cleanrecovery.ui.activity.HistoryActivity;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserLibraryActionsUiTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private BrowserDatabaseHelper db;
    private Activity activity;
    private String prefix;
    private String closedTabs;
    private BrowserPrefs prefs;
    interface Work<T> { T run() throws Exception; }
    private <T> T main(Work<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work::run);
        instrumentation.runOnMainSync(task);
        return task.get();
    }
    @Before public void setUp() {
        db = BrowserDatabaseHelper.getInstance(instrumentation.getTargetContext());
        prefs = new BrowserPrefs(instrumentation.getTargetContext());
        closedTabs = prefs.closedTabs();
        prefix = "library-test-" + java.util.UUID.randomUUID();
    }
    @After public void tearDown() throws Exception {
        if (activity != null) main(() -> { activity.finish(); return null; });
        prefs.setClosedTabs(closedTabs);
        for (BrowserDatabaseHelper.Entry e : db.listBookmarks()) if (e.url.contains(prefix)) db.removeBookmark(e.id);
        for (BrowserDatabaseHelper.Entry e : db.listHistory()) if (e.url.contains(prefix)) db.removeHistory(e.id);
        for (String folder : db.listFolders()) if (folder.startsWith(prefix)) db.deleteFolderWithBookmarks(folder);
    }
    private void launch(Class<? extends Activity> type) {
        activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), type)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
    }
    private Object field(String name) throws Exception {
        java.lang.reflect.Field f = activity.getClass().getSuperclass().getDeclaredField(name);
        f.setAccessible(true); return f.get(activity);
    }
    private View find(View root, String text) {
        if (root instanceof TextView && !(root instanceof EditText)) {
            String label = ((TextView) root).getText().toString();
            if (text.equals(label) || (text.equals("删除") && label.startsWith("删除("))) return root;
        }
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = find(((ViewGroup) root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private void click(String text) throws Exception {
        main(() -> {
            View view = find(activity.getWindow().getDecorView(), text); assertNotNull(text, view);
            while (!view.isClickable()) view = (View) view.getParent();
            assertTrue(view.performClick()); return null;
        }); instrumentation.waitForIdleSync();
    }
    private void search(String text) throws Exception {
        main(() -> { ((EditText) field("search")).setText(text); return null; });
    }
    @Test public void historyTabsOpenClosedTabsAndBackReturnsToHistory() throws Exception {
        prefs.setClosedTabs("[{\"u\":\"https://" + prefix + ".invalid/\",\"t\":\"" + prefix + "\"}]");
        launch(HistoryActivity.class);
        click("标签页");
        main(() -> {
            assertNotNull(find(activity.getWindow().getDecorView(), "关闭的标签页"));
            assertNotNull(find(activity.getWindow().getDecorView(), prefix)); return null;
        });
        click("更多");
        main(() -> {
            assertNotNull(find(activity.getWindow().getDecorView(), "标签页设置"));
            activity.onBackPressed();
            assertNotNull(find(activity.getWindow().getDecorView(), "关闭的标签页"));
            activity.onBackPressed();
            assertNotNull(find(activity.getWindow().getDecorView(), "标签页"));
            assertFalse(activity.isFinishing()); return null;
        });
    }
    @Test public void historySelectionClearsWhenFilteredOutAndBackExitsEdit() throws Exception {
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("title", prefix); values.put("url", "https://" + prefix + ".invalid/");
        values.put("visit_time", System.currentTimeMillis());
        db.getWritableDatabase().insertOrThrow(BrowserDatabaseHelper.TABLE_HISTORY, null, values);
        launch(HistoryActivity.class); search(prefix); click("编辑"); click("全选");
        main(() -> { assertTrue(find(activity.getWindow().getDecorView(), "删除").isEnabled()); return null; });
        search(prefix + "-absent");
        main(() -> {
            assertFalse(find(activity.getWindow().getDecorView(), "删除").isEnabled());
            activity.onBackPressed(); assertFalse(activity.isFinishing());
            assertNotNull(find(activity.getWindow().getDecorView(), "编辑")); return null;
        });
    }
    @Test public void bookmarkBatchMoveUsesSelectedRowsAndBackExitsEdit() throws Exception {
        assertTrue(db.addBookmark(prefix, "https://" + prefix + ".invalid/", "根目录"));
        assertTrue(db.addFolder(prefix + "-folder"));
        launch(BookmarksActivity.class); search(prefix); click("编辑"); click(prefix);
        main(() -> {
            assertTrue(find(activity.getWindow().getDecorView(), "移动").isEnabled());
            assertTrue(find(activity.getWindow().getDecorView(), "打开").isEnabled());
            java.lang.reflect.Method move = activity.getClass().getSuperclass().getDeclaredMethod("moveSelectionTo", String.class);
            move.setAccessible(true); move.invoke(activity, prefix + "-folder");
            activity.onBackPressed(); assertFalse(activity.isFinishing()); return null;
        });
        boolean found = false;
        for (BrowserDatabaseHelper.Entry e : db.listBookmarks()) if (e.url.contains(prefix)) {
            assertEquals(prefix + "-folder", e.folder); found = true;
        }
        assertTrue(found);
    }
    @Test public void bookmarkMoreIsAboveBottomBarWithoutDimmingScreen() throws Exception {
        launch(BookmarksActivity.class); click("更多");
        snapshot("bookmarks-more");
        main(() -> {
            PopupWindow popup = (PopupWindow) field("bookmarkMorePopup");
            assertTrue(popup.isShowing());
            View menu = popup.getContentView(); View bottom = (View) field("bottomBar");
            int[] menuPos = new int[2], bottomPos = new int[2];
            menu.getLocationOnScreen(menuPos); bottom.getLocationOnScreen(bottomPos);
            assertTrue(menuPos[0] >= 0);
            assertTrue(menuPos[0] < bottom.getWidth() / 4);
            assertTrue(menuPos[1] + menu.getHeight() <= bottomPos[1] + 2);
            assertNotNull(find(menu, "添加书签")); assertNotNull(find(menu, "备份书签"));
            popup.dismiss(); return null;
        });
    }
    @Test public void libraryToolbarAndConfirmationMatchReferenceTypography() throws Exception {
        assertTrue(db.addBookmark(prefix, "https://" + prefix + ".invalid/", "根目录"));
        launch(BookmarksActivity.class); search(prefix); click("编辑");
        main(() -> {
            ViewGroup bar = (ViewGroup) field("bottomBar");
            float density = activity.getResources().getDisplayMetrics().density;
            String[] labels = {"全选", "移动", "删除", "打开"};
            for (int i = 0; i < labels.length; i++) {
                TextView button = (TextView) find(bar, labels[i]);
                assertTrue(button.getLeft() >= 12 * density);
                assertTrue(button.getRight() <= bar.getWidth() - 12 * density + 1);
                assertTrue(button.getWidth() >= 48 * density);
                assertEquals(14 * density, button.getTextSize(), .1f);
                assertFalse(button.getTypeface().isBold());
            }
            assertEquals(0xff888888, ((TextView) find(bar, "移动")).getCurrentTextColor());
            int[] pos = new int[2]; bar.getLocationOnScreen(pos);
            androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets((View) field("root"));
            int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
            assertEquals(activity.getResources().getDisplayMetrics().heightPixels - bottom, pos[1] + bar.getHeight(), 2);
            return null;
        });
        snapshot("bookmarks-edit");
        click("全选"); click("删除");
        main(() -> {
            android.app.Dialog dialog = (android.app.Dialog) field("libraryConfirmDialog");
            TextView title = (TextView) find(dialog.getWindow().getDecorView(), "删除");
            TextView body = (TextView) find(dialog.getWindow().getDecorView(), "你确定继续吗？");
            float density = activity.getResources().getDisplayMetrics().density;
            assertEquals(16 * density, title.getTextSize(), .1f); assertTrue(title.getTypeface().isBold());
            assertEquals(14 * density, body.getTextSize(), .1f); assertFalse(body.getTypeface().isBold());
            assertEquals(338 * density, dialog.getWindow().getAttributes().width, 2);
            return null;
        });
        snapshot("bookmarks-delete");
        main(() -> { ((android.app.Dialog) field("libraryConfirmDialog")).dismiss(); return null; });
        main(() -> {
            ViewGroup bar = (ViewGroup) field("bottomBar");
            int width = Math.round(320 * activity.getResources().getDisplayMetrics().density);
            bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(bar.getHeight(), View.MeasureSpec.EXACTLY));
            bar.layout(0, bar.getTop(), width, bar.getBottom());
            for (int i = 0; i < bar.getChildCount(); i++) {
                View action = bar.getChildAt(i);
                assertTrue(action.getLeft() >= bar.getPaddingLeft());
                assertTrue(action.getRight() <= width - bar.getPaddingRight());
            }
            return null;
        });
    }
    @Test public void browserPagesKeepToolbarAndContentGutters() throws Exception {
        Class<?>[] pages = {BookmarksActivity.class,
                com.example.cleanrecovery.ui.activity.BrowserSettingsActivity.class,
                com.example.cleanrecovery.ui.activity.BrowserSearchSettingsActivity.class,
                com.example.cleanrecovery.ui.activity.BrowserHomeCustomizeActivity.class,
                com.example.cleanrecovery.ui.activity.BrowserSiteSettingsActivity.class,
                com.example.cleanrecovery.ui.activity.BrowserDownloadsActivity.class,
                com.example.cleanrecovery.ui.activity.BrowserSnifferActivity.class,
                com.example.cleanrecovery.ui.activity.BookmarkEditorActivity.class};
        for (Class<?> page : pages) {
            launch((Class<? extends Activity>) page);
            main(() -> {
                View back = firstImage(activity.getWindow().getDecorView());
                assertNotNull(page.getSimpleName(), back);
                ViewGroup toolbar = (ViewGroup) back.getParent();
                int gutter = ViaUi.toolbarInset(activity);
                assertTrue(page.getSimpleName(), toolbar.getPaddingLeft() >= gutter);
                assertTrue(page.getSimpleName(), toolbar.getPaddingRight() >= gutter);
                return null;
            });
            if (page == BookmarksActivity.class) snapshot("bookmarks-gutters");
            if (page == com.example.cleanrecovery.ui.activity.BrowserSiteSettingsActivity.class) {
                main(() -> {
                    TextView section = (TextView) find(activity.getWindow().getDecorView(), "内容");
                    assertNotNull(section);
                    assertTrue(section.getPaddingLeft() >= ViaUi.pageInset(activity));
                    return null;
                });
                snapshot("site-settings-gutters");
            }
            main(() -> { activity.finish(); return null; });
            instrumentation.waitForIdleSync(); activity = null;
        }
    }
    private View firstImage(View view) {
        if (view instanceof android.widget.ImageView) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = firstImage(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    @Test public void historyClearPopupUsesBoldHeadingAndButtonAnchor() throws Exception {
        launch(HistoryActivity.class); click("清空");
        snapshot("history-clear");
        main(() -> {
            PopupWindow popup = (PopupWindow) field("bookmarkMorePopup");
            TextView title = (TextView) find(popup.getContentView(), "清除浏览历史");
            assertNotNull(title); assertTrue(title.getTypeface().isBold());
            assertEquals(14 * activity.getResources().getDisplayMetrics().density, title.getTextSize(), .1f);
            assertNull(find(popup.getContentView(), "取消"));
            View anchor = find((View) field("bottomBar"), "清空");
            int[] menu = new int[2], button = new int[2];
            popup.getContentView().getLocationOnScreen(menu); anchor.getLocationOnScreen(button);
            assertEquals(button[0] + anchor.getWidth(), menu[0] + popup.getContentView().getWidth(), 2);
            assertEquals(button[1] + anchor.getHeight()/2, menu[1] + popup.getContentView().getHeight(), 2);
            popup.dismiss(); return null;
        });
    }
    private void snapshot(String name) throws Exception {
        instrumentation.waitForIdleSync();
        Thread.sleep(250);
        java.io.File folder = new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null), "library-visual");
        folder.mkdirs();
        android.graphics.Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(folder, name + ".png"))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { image.recycle(); }
    }
    @Test public void bookmarkIconsUseSiteColorsAndAlignedFallbacks() throws Exception {
        String url = "https://" + prefix + ".invalid/";
        android.graphics.Bitmap site = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888);
        site.eraseColor(0xff4285f4);
        BrowserFavicons.put(url, site);
        site.recycle();
        launch(BookmarksActivity.class);
        main(() -> {
            android.widget.LinearLayout list = (android.widget.LinearLayout) field("listBox");
            list.removeAllViews();
            Class<?> type = activity.getClass().getSuperclass();
            java.lang.reflect.Method folder = type.getDeclaredMethod("addFolderRow", String.class);
            folder.setAccessible(true); folder.invoke(activity, "文件夹");
            java.lang.reflect.Method bookmark = type.getDeclaredMethod("addBookmarkRow", BrowserDatabaseHelper.Entry.class);
            bookmark.setAccessible(true);
            bookmark.invoke(activity, new BrowserDatabaseHelper.Entry(-1, "无网站图标的书签", "https://missing-" + prefix + ".invalid/", 0));
            bookmark.invoke(activity, new BrowserDatabaseHelper.Entry(-2, "已有网站图标的书签", url, 0));
            float density = activity.getResources().getDisplayMetrics().density;
            for (int i = 0; i < 3; i++) {
                ViewGroup row = (ViewGroup) list.getChildAt(i);
                android.widget.ImageView icon = (android.widget.ImageView) row.getChildAt(0);
                assertEquals(24 * density, icon.getLayoutParams().width, .1f);
                if (i == 2) {
                    assertNull(icon.getColorFilter());
                    assertTrue(icon.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
                    assertEquals(0xff4285f4, ((android.graphics.drawable.BitmapDrawable) icon.getDrawable()).getBitmap().getPixel(10, 10));
                } else assertFalse(icon.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
            }
            return null;
        });
        snapshot("bookmarks-icons");
    }
    @Test public void bookmarkBatchOpenReturnsOnlySelectedUrls() throws Exception {
        String url = "https://" + prefix + ".invalid/";
        assertTrue(db.addBookmark(prefix, url, "根目录"));
        launch(BookmarksActivity.class); search(prefix); click("编辑"); click("全选"); click("打开");
        main(() -> {
            assertTrue(activity.isFinishing());
            java.lang.reflect.Field result = Activity.class.getDeclaredField("mResultData");
            result.setAccessible(true);
            Intent data = (Intent) result.get(activity);
            assertEquals("open_urls", data.getStringExtra("library_action"));
            assertEquals(java.util.Collections.singletonList(url), data.getStringArrayListExtra("urls"));
            return null;
        });
    }
    @Test public void bookmarkDragOrderSurvivesLeavingFolder() throws Exception {
        android.content.SharedPreferences library = instrumentation.getTargetContext().getSharedPreferences("via_library", 0);
        int originalSort = library.getInt("sort", 1);
        String folder = prefix + "-sort";
        assertTrue(db.addFolder(folder));
        assertTrue(db.addBookmark(prefix + "-one", "https://" + prefix + ".invalid/one", folder));
        assertTrue(db.addBookmark(prefix + "-two", "https://" + prefix + ".invalid/two", folder));
        try {
            launch(BookmarksActivity.class); click(folder); click("编辑");
            String[] before = main(() -> ((java.util.List<String>) field("bookmarkRowKeys")).toArray(new String[0]));
            int[] points = main(() -> {
                ViewGroup list = (ViewGroup) field("listBox");
                ViewGroup first = (ViewGroup) list.findViewWithTag(before[0]);
                ViewGroup second = (ViewGroup) list.findViewWithTag(before[1]);
                View handle = second.getChildAt(second.getChildCount() - 2);
                int[] start = new int[2], end = new int[2]; handle.getLocationOnScreen(start); first.getLocationOnScreen(end);
                return new int[]{start[0] + handle.getWidth()/2, start[1] + handle.getHeight()/2,
                        end[0] + first.getWidth()/2, end[1] + first.getHeight()/2};
            });
            long down = android.os.SystemClock.uptimeMillis();
            touch(down, android.view.MotionEvent.ACTION_DOWN, points[0], points[1]);
            Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 200);
            for (int i = 1; i <= 10; i++) {
                touch(down, android.view.MotionEvent.ACTION_MOVE, points[0] + (points[2]-points[0])*i/10f,
                        points[1] + (points[3]-points[1])*i/10f);
                Thread.sleep(30);
            }
            touch(down, android.view.MotionEvent.ACTION_UP, points[2], points[3]);
            instrumentation.waitForIdleSync();
            main(() -> {
                assertEquals(before[1], ((java.util.List<String>) field("bookmarkRowKeys")).get(0));
                activity.onBackPressed(); activity.onBackPressed(); return null;
            });
            click(folder);
            main(() -> { assertEquals(before[1], ((java.util.List<String>) field("bookmarkRowKeys")).get(0)); return null; });
        } finally { library.edit().putInt("sort", originalSort).remove("order:" + folder).commit(); }
    }
    private void touch(long down, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.getUiAutomation().injectInputEvent(event, true); event.recycle();
    }
    @Test public void folderMovesRejectCyclesAndDeleteDescendants() {
        String parent = prefix + "-parent", child = prefix + "-child";
        assertTrue(db.addFolder(parent)); assertTrue(db.addFolder(child));
        assertTrue(db.moveFolder(child, parent)); assertFalse(db.moveFolder(parent, child));
        assertTrue(db.addBookmark(prefix, "https://" + prefix + ".invalid/", child));
        assertTrue(db.renameFolder(parent, prefix + "-renamed"));
        assertEquals(prefix + "-renamed", db.folderParent(child));
        assertEquals(1, db.deleteFolderWithBookmarks(prefix + "-renamed"));
        assertFalse(db.listFolders().contains(child));
    }
    @Test public void v5MigrationPreservesFolders() {
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            database.execSQL("CREATE TABLE bookmark_folders (id INTEGER PRIMARY KEY, name TEXT, add_time INTEGER)");
            database.execSQL("INSERT INTO bookmark_folders VALUES (1, 'existing', 1)");
            db.onUpgrade(database, 5, 6);
            try (android.database.Cursor c = database.rawQuery("SELECT name, parent FROM bookmark_folders", null)) {
                assertTrue(c.moveToFirst()); assertEquals("existing", c.getString(0)); assertEquals("", c.getString(1));
            }
        }
    }
}
