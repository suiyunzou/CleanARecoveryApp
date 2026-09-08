package com.example.cleanrecovery.ui.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P0③ 死设置生效化与 1:1 对齐单元测试：
 * 验证 UA 预设映射、简化浏览器标识算法、标签管理索引操作。
 */
public class BrowserSettingsUnitTest {

    @Test
    public void siteMode_preservesViaFourStatePermissionValues() {
        assertEquals(0, BrowserPrefs.normalizeSiteMode(0));
        assertEquals(1, BrowserPrefs.normalizeSiteMode(1));
        assertEquals(2, BrowserPrefs.normalizeSiteMode(2));
        assertEquals(3, BrowserPrefs.normalizeSiteMode(3));
        assertEquals(-1, BrowserPrefs.normalizeSiteMode(-1));
        assertEquals(-1, BrowserPrefs.normalizeSiteMode(4));
    }

    @Test
    public void presetUserAgent_coversAllViaPresets() {
        assertNull("预设0为默认，应返回null", BrowserPrefs.presetUserAgent(0));
        assertNull("越界预设应返回null", BrowserPrefs.presetUserAgent(-1));
        assertNull("越界预设应返回null", BrowserPrefs.presetUserAgent(9));

        String androidPhone = BrowserPrefs.presetUserAgent(1);
        assertNotNull(androidPhone);
        assertTrue(androidPhone.contains("Android") && androidPhone.contains("Mobile"));

        String androidTablet = BrowserPrefs.presetUserAgent(2);
        assertNotNull(androidTablet);
        assertTrue(androidTablet.contains("Android") && androidTablet.contains("SM-T837A"));

        String winChrome = BrowserPrefs.presetUserAgent(3);
        assertNotNull(winChrome);
        assertTrue(winChrome.contains("Windows NT 10.0") && winChrome.contains("Chrome/"));

        String winIe = BrowserPrefs.presetUserAgent(4);
        assertNotNull(winIe);
        assertTrue(winIe.contains("Trident/7.0"));

        String macOs = BrowserPrefs.presetUserAgent(5);
        assertNotNull(macOs);
        assertTrue(macOs.contains("Macintosh"));

        String safariIphone = BrowserPrefs.presetUserAgent(6);
        assertNotNull(safariIphone);
        assertTrue(safariIphone.contains("iPhone;") && safariIphone.contains("Mobile/"));

        String safariIpad = BrowserPrefs.presetUserAgent(7);
        assertNotNull(safariIpad);
        assertTrue(safariIpad.contains("Macintosh") && safariIpad.contains("Safari/"));

        String symbian = BrowserPrefs.presetUserAgent(8);
        assertNotNull(symbian);
        assertTrue(symbian.contains("Symbian"));
    }

    @Test
    public void simplifyUa_anonymizesDeviceAndRoundsChromeVersion() {
        String rawUa = "Mozilla/5.0 (Linux; Android 15; sdk_gphone64_x86_64 Build/AP4A.241205.013) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.88 Mobile Safari/537.36";

        // 开启简化：对齐 Via 原版 m3437h
        String simplified = BrowserPrefs.simplifyUa(rawUa);
        assertNotNull(simplified);
        assertTrue("应包含 Android 10; K", simplified.contains("Android 10; K"));
        assertTrue("Chrome 小版本号应归一为 0.0.0", simplified.contains("Chrome/128.0.0.0"));
        assertFalse("不应泄露具体机型 sdk_gphone64_x86_64", simplified.contains("sdk_gphone64_x86_64"));
        assertFalse("不应泄露系统 Build 批次", simplified.contains("AP4A.241205.013"));
    }

    @Test
    public void tabManager_indexOfAndCloseSemantics() {
        TabManager tm = new TabManager();
        TabManager.Tab t1 = tm.newTab(null);
        TabManager.Tab t2 = tm.newTab(null);
        TabManager.Tab t3 = tm.newTab(null);

        assertEquals(3, tm.size());
        assertEquals(0, tm.indexOf(t1));
        assertEquals(1, tm.indexOf(t2));
        assertEquals(2, tm.indexOf(t3));
        assertEquals(2, tm.currentIndex());

        TabManager.Tab remaining = tm.close(1); // 关闭中间标签
        assertNotNull(remaining);
        assertEquals(2, tm.size());
        assertEquals(0, tm.indexOf(t1));
        assertEquals(-1, tm.indexOf(t2));
        assertEquals(1, tm.indexOf(t3));
    }
}
