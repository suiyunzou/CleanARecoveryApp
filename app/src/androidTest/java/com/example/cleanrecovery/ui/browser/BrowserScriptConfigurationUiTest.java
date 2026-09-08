package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.ui.activity.BrowserSettingsActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserScriptConfigurationUiTest {
    @Test public void homePanelListsInstalledScriptsRegardlessOfMatchOrEnabledState() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        String first = "__home_scripts_" + java.util.UUID.randomUUID(), second = first + "_disabled";
        Activity activity = null;
        Activity settings = null;
        android.app.Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(BrowserSettingsActivity.class.getName(), null, false);
        try {
            prefs.saveScript(first, "https://one.test/*", "// ==UserScript==\n// @match https://one.test/*\n// ==/UserScript==\n");
            prefs.saveScript(second, "https://two.test/*", "// ==UserScript==\n// @match https://two.test/*\n// @exclude *\n// ==/UserScript==\n");
            prefs.setScriptEnabled(second, false);
            activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                    com.example.cleanrecovery.ui.activity.BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity shown = activity;
            main(() -> {
                java.lang.reflect.Method home = shown.getClass().getDeclaredMethod("showHome");
                home.setAccessible(true); home.invoke(shown);
                assertTrue(shown.findViewById(com.example.cleanrecovery.R.id.browser_site_info).performClick());
                assertTrue(shown.findViewById(com.example.cleanrecovery.R.id.browser_site_card_scripts).performClick());
                return null;
            });
            assertNotNull("Homepage lists an installed script with a site-specific match", node(first));
            assertNotNull("Homepage also lists disabled/excluded scripts", node(second));
            assertFalse("Listing a script must not enable it", prefs.isScriptEnabled(second));
            snapshot("script-home-list");
            click(first);
            click("在设置中查看");
            settings = monitor.waitForActivityWithTimeout(5000);
            assertNotNull("Panel action opens the actual settings activity", settings);
            Activity destination = settings;
            main(() -> {
                assertNotNull(find(destination.getWindow().getDecorView(), "脚本"));
                assertNotNull(find(destination.getWindow().getDecorView(), first));
                assertNull("View in settings opens the list, not the configuration page", find(destination.getWindow().getDecorView(), "运行时机"));
                return null;
            });
        } finally {
            instrumentation.removeMonitor(monitor);
            Activity closingSettings = settings;
            Activity closing = activity;
            main(() -> { if (closingSettings != null) closingSettings.finish(); if (closing != null) closing.finish(); return null; });
            prefs.removeScript(first); prefs.removeScript(second);
        }
    }

    @Test public void viewInSettingsRevealsAndAnimatesOnlyTheSelectedOffscreenRow() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        String prefix = "__script_reveal_" + java.util.UUID.randomUUID();
        java.util.List<String> fixtures = new java.util.ArrayList<>();
        Activity activity = null;
        try {
            for (int i = 0; i < 32; i++) {
                String name = prefix + "_" + i; fixtures.add(name); prefs.saveScript(name, "https://reveal.test/*", "// fixture");
            }
            String target = null;
            for (String name : prefs.scriptNames()) if (name.startsWith(prefix)) target = name;
            String selected = target;
            activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS, true)
                    .putExtra(BrowserSettingsActivity.EXTRA_SELECT_SCRIPT, selected));
            Activity shown = activity;
            View row = main(() -> {
                View found = find(shown.getWindow().getDecorView(), selected); assertNotNull(found);
                while (!found.isClickable()) found = (View)found.getParent(); return found;
            });
            float max = 0;
            long deadline = System.currentTimeMillis() + 2200;
            boolean visibleWhileMoving = false;
            while (System.currentTimeMillis() < deadline) {
                float x = main(row::getTranslationX); max = Math.max(max, x);
                if (x > 1) visibleWhileMoving |= main(() -> row.getGlobalVisibleRect(new android.graphics.Rect()));
                Thread.sleep(20);
            }
            assertTrue("Target visibly moves horizontally, not merely a pressed-state ripple", max > ViaUi.dp(shown, 8));
            assertTrue("Offscreen target must be scrolled into view before animation", visibleWhileMoving);
            main(() -> {
                assertEquals("Target returns to its resting position", 0f, row.getTranslationX(), .1f);
                ViewGroup list = (ViewGroup)row.getParent();
                for (int i = 0; i < list.getChildCount(); i++) assertEquals("Other rows remain still", 0f, list.getChildAt(i).getTranslationX(), .1f);
                return null;
            });
        } finally {
            Activity closing = activity; main(() -> { if (closing != null) closing.finish(); return null; });
            for (String name : fixtures) prefs.removeScript(name);
        }
    }

    @Test public void downloadedUserScriptOpensSourceAndOnlyInstallsAfterSave() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        String name = "__download_ui_" + java.util.UUID.randomUUID();
        String code = "// ==UserScript==\n// @name " + name + "\n// @match https://download.test/*\n// ==/UserScript==\nwindow.downloaded = true;";
        Activity browser = null, editor = null;
        android.app.Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(BrowserSettingsActivity.class.getName(), null, false);
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread serving = new Thread(() -> {
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    String line; while ((line = input.readLine()) != null && !line.isEmpty()) { }
                    byte[] bytes = code.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            serving.start();
            browser = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                    com.example.cleanrecovery.ui.activity.BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity shown = browser;
            main(() -> {
                java.lang.reflect.Method method = shown.getClass().getDeclaredMethod("offerUserScript", String.class);
                method.setAccessible(true);
                assertEquals(true, method.invoke(shown, "http://127.0.0.1:" + server.getLocalPort() + "/sample.user.js"));
                return null;
            });
            editor = monitor.waitForActivityWithTimeout(10000); assertNotNull("Download opens source editor", editor);
            Activity editing = editor;
            instrumentation.waitForIdleSync();
            main(() -> { assertTrue(find(editing.getWindow().getDecorView(), code) instanceof android.widget.EditText); return null; });
            snapshot("script-source");
            assertFalse("Downloaded code must not install automatically", prefs.scriptNames().contains(name));
            row(editor, "保存");
            long deadline = System.currentTimeMillis() + 5000;
            while (!prefs.scriptNames().contains(name) && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertEquals(code, prefs.scriptCode(name));
            assertEquals("https://download.test/*", prefs.scriptMatch(name));
            row(editor, "＋");
            assertNotNull(node("下载脚本"));
            click("添加脚本");
            main(() -> {
                java.util.ArrayList<View> fields = new java.util.ArrayList<>();
                editing.getWindow().getDecorView().findViewsWithText(fields, "脚本源代码", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                assertEquals(1, fields.size());
                String fresh = ((android.widget.EditText) fields.get(0)).getText().toString();
                assertTrue("A second add starts with the userscript template", fresh.contains("New Userscript"));
                assertFalse("A previous download must not leak into a new draft", fresh.contains(name));
                return null;
            });
            serving.join(1000);
        } finally {
            instrumentation.removeMonitor(monitor);
            Activity closing = editor, closingBrowser = browser;
            main(() -> { if (closing != null) closing.finish(); if (closingBrowser != null) closingBrowser.finish(); return null; });
            prefs.removeScript(name);
        }
    }

    @Test public void siteScriptSelectionStaysInDialogAndOffersViaActions() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        String name = "__panel_selection_" + java.util.UUID.randomUUID();
        String other = name + "_other_site";
        Activity activity = null;
        android.app.Dialog[] dialog = new android.app.Dialog[1];
        try {
            prefs.saveScript(name, "*", "// ==UserScript==\n// @name " + name + "\n// @match *\n// ==/UserScript==\n");
            prefs.saveScript(other, "https://other-site.invalid/*", "// ==UserScript==\n// @match https://other-site.invalid/*\n// ==/UserScript==\n");
            activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                    com.example.cleanrecovery.ui.activity.BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("url", "https://panel-selection.invalid/"));
            Activity shown = activity;
            main(() -> {
                java.lang.reflect.Method toggle = shown.getClass().getDeclaredMethod("toggleSiteCard");
                toggle.setAccessible(true); toggle.invoke(shown); return null;
            });
            snapshot("site-panel");
            main(() -> {
                java.lang.reflect.Method toggle = shown.getClass().getDeclaredMethod("toggleSiteCard");
                toggle.setAccessible(true); toggle.invoke(shown);
                java.lang.reflect.Method method = shown.getClass().getDeclaredMethod("showPageScripts");
                method.setAccessible(true); dialog[0] = (android.app.Dialog)method.invoke(shown);
                assertNull("A real webpage still filters scripts by its URL", find(dialog[0].getWindow().getDecorView(), other));
                View item = find(dialog[0].getWindow().getDecorView(), name); assertNotNull(item);
                while (!item.isClickable()) item = (View)item.getParent();
                assertTrue(item.performClick());
                View root = dialog[0].getWindow().getDecorView();
                assertNotNull(find(root, "编辑")); assertNotNull(find(root, "在设置中查看"));
                assertTrue(find(root, "已启用").performClick());
                assertNotNull(find(root, "已禁用"));
                assertFalse(new BrowserPrefs(instrumentation.getTargetContext()).isScriptEnabled(name));
                assertTrue("Selecting and toggling remain in the same dialog", dialog[0].isShowing());
                return null;
            });
            snapshot("script-selection");
        } finally {
            Activity closing = activity;
            main(() -> { if (dialog[0] != null) dialog[0].dismiss(); if (closing != null) closing.finish(); return null; });
            prefs.removeScript(name);
            prefs.removeScript(other);
        }
    }

    @Test public void sourceSaveRenamesFromMetadataAndKeepsDisabledConfiguration() throws Exception {
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        String name = "__source_editor_" + java.util.UUID.randomUUID(), renamed = name + "_renamed";
        String source = "// ==UserScript==\n// @name " + name
                + "\n// @match https://editor.test/*\n// ==/UserScript==\nwindow.editorValue = 1;";
        Activity activity = null;
        try {
            prefs.saveScript(name, "https://editor.test/*", source);
            prefs.setScriptEnabled(name, false);
            prefs.setScriptRunAtOverride(name, "document-start");
            String identity = prefs.scriptStorageId(name);
            activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS, true)
                    .putExtra(BrowserSettingsActivity.EXTRA_EDIT_SCRIPT, name));
            snapshot("script-configuration");
            row(activity, "编辑源代码");
            Activity shown = activity;
            main(() -> {
                android.widget.EditText code = (android.widget.EditText)find(shown.getWindow().getDecorView(), source);
                assertNotNull(code);
                code.setText(source.replace(name, renamed).replace("editor.test", "renamed.test"));
                assertNotNull(find(shown.getWindow().getDecorView(), "帮助"));
                return null;
            });
            row(activity, "保存");
            long deadline = System.currentTimeMillis() + 5000;
            while (!prefs.scriptNames().contains(renamed) && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertTrue(prefs.scriptNames().contains(renamed));
            assertFalse(prefs.scriptNames().contains(name));
            assertFalse(prefs.isScriptEnabled(renamed));
            assertEquals(identity, prefs.scriptStorageId(renamed));
            assertEquals("document-start", prefs.scriptRunAtOverride(renamed));
            assertEquals("https://renamed.test/*", prefs.scriptMatch(renamed));
            main(() -> { assertNotNull(find(shown.getWindow().getDecorView(), "运行时机")); return null; });
        } finally {
            if (activity != null) { Activity closing = activity; main(() -> { closing.finish(); return null; }); }
            prefs.removeScript(name); prefs.removeScript(renamed);
        }
    }

    @Test public void scriptNameOpensInformationAndVersionSurvivesRename() throws Exception {
        BrowserPrefs prefs=new BrowserPrefs(instrumentation.getTargetContext());
        String name="__script_info_"+java.util.UUID.randomUUID(),renamed=name+"_renamed";
        String source="// ==UserScript==\r\n// @name Info\r\n// @version 7.4.1\r\n// ==/UserScript==\r\n// @version ignored-body\r\n";
        Activity activity=null;
        try {
            prefs.saveScript(name,"https://script-info.test/*",source);
            long created=prefs.scriptCreatedAt(name);
            assertTrue("New script records its real creation time",created>0);
            prefs.saveScript(renamed,"https://script-info.test/*",source);
            long updated=prefs.scriptUpdatedAt(renamed);
            prefs.preserveScriptIdentity(name,renamed);prefs.removeScript(name);
            assertEquals("Rename preserves creation time",created,prefs.scriptCreatedAt(renamed));
            assertEquals("Rename keeps the latest source-save timestamp",updated,prefs.scriptUpdatedAt(renamed));
            activity=instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),BrowserSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS,true)
                    .putExtra(BrowserSettingsActivity.EXTRA_EDIT_SCRIPT,renamed));
            Activity shown=activity;
            main(() -> {assertNotNull("Version is visible in configuration",find(shown.getWindow().getDecorView(),"7.4.1"));return null;});
            row(activity,renamed);
            assertNotNull(node("脚本信息"));
            int bytes=source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
            java.util.List<AccessibilityNodeInfo> messages=root.findAccessibilityNodeInfosByText("名称\n"+renamed);
            assertFalse("Information dialog contains the installed script",messages.isEmpty());
            String text=messages.get(0).getText().toString();
            assertTrue(text.contains("版本\n7.4.1"));assertTrue(text.contains("更新于\n"));assertTrue(text.contains("创建于\n"));
            assertTrue("Size is computed from installed UTF-8 source",text.contains(String.format(java.util.Locale.ROOT,"%.1f B",(double)bytes)));
            click("确定");
            main(() -> {assertNotNull("Info dismissal remains on configuration",find(shown.getWindow().getDecorView(),"运行时机"));shown.onBackPressed();return null;});
            instrumentation.waitForIdleSync();
            main(() -> {assertNotNull("List also displays installed version",find(shown.getWindow().getDecorView(),"7.4.1"));return null;});
        } finally {
            if(activity!=null){Activity closing=activity;main(() -> {closing.finish();return null;});}
            prefs.removeScript(name);prefs.removeScript(renamed);
            instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs",0).edit().commit();
        }
    }
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private void snapshot(String name) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("scriptScreenshots"))) return;
        instrumentation.waitForIdleSync();
        Thread.sleep(600); // Wait for the activity/window transition before capturing pixels.
        android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        java.io.File file = new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null), name + ".png");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
    private <T> T main(java.util.concurrent.Callable<T> task) throws Exception {
        FutureTask<T> result = new FutureTask<>(task); instrumentation.runOnMainSync(result); return result.get();
    }
    private View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView)root).getText())) return root;
        if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
            View found = find(((ViewGroup)root).getChildAt(i), text); if(found!=null)return found;
        }
        return null;
    }
    private void row(Activity activity, String text) throws Exception {
        main(() -> { View view=find(activity.getWindow().getDecorView(),text); assertNotNull(text,view);
            while(!view.isClickable() && view.getParent() instanceof View)view=(View)view.getParent();
            assertTrue(text,view.performClick()); return null; });
        instrumentation.waitForIdleSync();
    }
    private AccessibilityNodeInfo node(String text) throws Exception {
        long end=System.currentTimeMillis()+5000;
        while(System.currentTimeMillis()<end) {
            AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
            if(root!=null)for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(text))
                if(text.contentEquals(n.getText()==null?"":n.getText()))return n;
            Thread.sleep(50);
        }
        throw new AssertionError("Missing active dialog text: "+text);
    }
    private void click(String text) throws Exception {
        AccessibilityNodeInfo n=node(text);
        while(!n.isClickable() && n.getParent()!=null)n=n.getParent();
        assertTrue(text,n.performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.waitForIdleSync();
    }
    private AccessibilityNodeInfo input(AccessibilityNodeInfo node) {
        if(node==null)return null;
        if("android.widget.EditText".contentEquals(node.getClassName()))return node;
        for(int i=0;i<node.getChildCount();i++) { AccessibilityNodeInfo found=input(node.getChild(i)); if(found!=null)return found; }
        return null;
    }
    @Test public void configurationAndRadioDialogHaveReadableDayAndNightColors() throws Exception {
        BrowserPrefs prefs=new BrowserPrefs(instrumentation.getTargetContext());
        boolean original=prefs.nightMode();
        String name="__config_theme_"+java.util.UUID.randomUUID();
        String source="// @run-at document-end\nwindow.configTheme=true;";
        prefs.saveScript(name,"*",source);
        try {
            for(boolean night:new boolean[]{false,true}) {
                prefs.setNightMode(night);
                Activity activity=instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),BrowserSettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS,true)
                        .putExtra(BrowserSettingsActivity.EXTRA_EDIT_SCRIPT,name));
                try {
                    main(() -> {
                        ViewGroup content=activity.findViewById(android.R.id.content);
                        int background=((android.graphics.drawable.ColorDrawable)content.getChildAt(0).getBackground()).getColor();
                        assertTrue("Page background follows mode",night?androidx.core.graphics.ColorUtils.calculateLuminance(background)<.1:androidx.core.graphics.ColorUtils.calculateLuminance(background)>.8);
                        TextView label=(TextView)find(content,"运行时机"); assertNotNull(label);
                        assertTrue("Setting title readable",androidx.core.graphics.ColorUtils.calculateContrast(label.getCurrentTextColor(),background)>4.5);
                        android.app.Dialog dialog=ViaUi.radioDialog(activity,"运行时机",new String[]{"document-start","document-end"},1,index -> {});
                        dialog.show();
                        try {
                            TextView choice=(TextView)find(dialog.getWindow().getDecorView(),"document-start"); assertNotNull(choice);
                            View surface=(View)choice.getParent();
                            while(!(surface.getBackground() instanceof android.graphics.drawable.GradientDrawable))surface=(View)surface.getParent();
                            android.graphics.drawable.GradientDrawable drawable=(android.graphics.drawable.GradientDrawable)surface.getBackground();
                            int color=drawable.getColor().getDefaultColor();
                            assertTrue("Dialog surface follows mode",night?androidx.core.graphics.ColorUtils.calculateLuminance(color)<.1:androidx.core.graphics.ColorUtils.calculateLuminance(color)>.8);
                            assertTrue("Radio choice readable",androidx.core.graphics.ColorUtils.calculateContrast(choice.getCurrentTextColor(),color)>4.5);
                        } finally {dialog.dismiss();}
                        return null;
                    });
                    row(activity,"编辑源代码");
                    main(() -> {
                        TextView code=(TextView)find(activity.getWindow().getDecorView(),source); assertNotNull(code);
                        assertTrue("Source text readable",androidx.core.graphics.ColorUtils.calculateContrast(code.getCurrentTextColor(),night?android.graphics.Color.BLACK:android.graphics.Color.WHITE)>4.5);
                        return null;
                    });
                } finally {main(() -> {activity.finish();return null;});}
            }
        } finally {prefs.setNightMode(original);prefs.removeScript(name);}
    }

    @Test public void runAtExclusionDeleteAndResetAreOperableFromConfigurationPage() throws Exception {
        BrowserPrefs prefs=new BrowserPrefs(instrumentation.getTargetContext());
        String name="__config_ui_"+java.util.UUID.randomUUID();
        String source="// ==UserScript==\n// @name config UI\n// @match https://config-ui.test/*\n// @run-at document-end\n// ==/UserScript==\nwindow.configUiExecuted=true;";
        Activity activity=null;
        try {
            prefs.saveScript(name,"https://config-ui.test/*",source);
            activity=instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),BrowserSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS,true)
                    .putExtra(BrowserSettingsActivity.EXTRA_EDIT_SCRIPT,name));
            row(activity,"运行时机"); click("document-start");
            assertEquals("Lifecycle choice must persist","document-start",new BrowserPrefs(instrumentation.getTargetContext()).scriptRunAtOverride(name));
            assertEquals("Execution consumes the selected lifecycle","document-start",BrowserUserScripts.runAt(prefs.scriptExecutionCode(name)));
            row(activity,"添加排除"); node("保存");
            AccessibilityNodeInfo initial = input(instrumentation.getUiAutomation().getRootInActiveWindow());
            assertNotNull(initial);
            assertEquals("https://*/*", initial.getText().toString());
            Bundle blank = new Bundle(); blank.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
            assertTrue(initial.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, blank));
            click("保存");
            assertNotNull("Blank input keeps the edit dialog open",input(instrumentation.getUiAutomation().getRootInActiveWindow()));
            assertEquals("Blank save must not create a rule","",prefs.scriptExcludes(name));
            AccessibilityNodeInfo field=input(instrumentation.getUiAutomation().getRootInActiveWindow()); assertNotNull(field);
            Bundle value=new Bundle(); value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"https://config-ui.test/private/*");
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value)); click("保存");
            assertFalse("Saved exclusion blocks its pages",BrowserUserScripts.applies(prefs.scriptExecutionCode(name),prefs.scriptMatch(name),"https://config-ui.test/private/page"));
            assertTrue("Exclusion does not block other pages",BrowserUserScripts.applies(prefs.scriptExecutionCode(name),prefs.scriptMatch(name),"https://config-ui.test/public/page"));
            row(activity,"https://config-ui.test/private/*"); click("删除");
            assertTrue("Deleting through the dialog re-enables matching pages",BrowserUserScripts.applies(prefs.scriptExecutionCode(name),prefs.scriptMatch(name),"https://config-ui.test/private/page"));
            row(activity,"重置"); node("恢复脚本默认配置？"); click("取消");
            assertEquals("Cancelling reset keeps overrides","document-start",prefs.scriptRunAtOverride(name));
            row(activity,"重置"); node("恢复脚本默认配置？"); click("重置");
            assertEquals("Confirmed reset restores default timing","document-end",BrowserUserScripts.runAt(prefs.scriptExecutionCode(name)));
            assertEquals("Configuration UI never rewrites source",source,prefs.scriptCode(name));
        } finally {
            prefs.removeScript(name); Activity finished=activity;
            main(() -> {if(finished!=null)finished.finish();return null;});
        }
    }
}
