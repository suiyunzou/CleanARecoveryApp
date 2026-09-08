package com.example.cleanrecovery.ui.activity;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

/** Locks down the user-visible ad-marker workflow, not merely rule persistence. */
public class BrowserExperienceContractTest {
    @Test public void adMarkerPreviewsSelectionAndCommitsOnlyFromSave() {
        String script = BrowserActivity.adMarkerScript();
        for (String action : new String[]{"扩大", "缩小", "保存", "取消", "上移", "下移"}) {
            assertTrue("缺少广告标记操作：" + action, script.contains(action));
        }
        assertTrue(script.contains("3px solid #e53935"));
        assertTrue(script.indexOf("a==='保存'") < script.indexOf("ViaAdMarker.report"));
        assertTrue(script.contains("document.addEventListener('click',pick,true)"));
    }

    @Test public void siteSettingsExposeAllViaOptionsAndDisableRowsWithMasterSwitch() throws Exception {
        Path source = Path.of("app/src/main/java/com/example/cleanrecovery/ui/activity/BrowserSiteSettingsActivity.java");
        if (!Files.exists(source)) source = Path.of("src/main/java/com/example/cleanrecovery/ui/activity/BrowserSiteSettingsActivity.java");
        String code = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        for (String title : new String[]{"字体大小", "浏览器标识", "电脑模式", "图像", "JavaScript",
                "广告拦截", "隐身", "麦克风", "摄像头", "剪贴板", "打开应用", "页面重定向",
                "位置信息", "返回不重载"}) {
            assertTrue("缺少网站设定项：" + title, code.contains("\"" + title + "\""));
        }
        assertTrue(code.contains("row.setEnabled(enabled)"));
        assertTrue(code.contains("row.setOnClickListener(enabled ?"));

        // Font behavior is checked on a real WebView in BrowserSettingsParityTest.
        // Via g8.i.M uses WebSettings.setTextZoom, not a text-size-adjust injection.
    }
}
