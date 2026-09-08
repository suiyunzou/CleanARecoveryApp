package com.example.cleanrecovery.ui.browser;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.PermissionRequest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.ui.activity.BrowserActivity;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BrowserPermissionFlowTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private SharedPreferences storage;
    private Map<String,Object> original;
    private BrowserPrefs prefs;
    private BrowserActivity activity;
    private BrowserPermissionController controller;
    private String site, origin;
    private boolean usedPage;

    @Before public void setup() {
        storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        original = snapshot(); prefs = new BrowserPrefs(instrumentation.getTargetContext());
        site = "permission-" + UUID.randomUUID() + ".test:8443"; origin = "https://" + site + "/";
        prefs.setRestoreTabs(0); prefs.setExitClearFlags(Collections.emptySet());
        prefs.setClearDataOnExit(false); prefs.setScriptsEnabled(false);
        prefs.setPermission("camera", "ask"); prefs.setPermission("microphone", "ask");
        prefs.setPermission("location", "ask");
    }

    @After public void cleanup() throws Exception {
        try {
            if (activity != null) {
                main(() -> { activity.finish(); return null; });
                long end = System.currentTimeMillis() + 5000;
                while (!main(() -> activity.isDestroyed()) && System.currentTimeMillis() < end) Thread.sleep(25);
                assertTrue("Destroy the activity before restoring saved session state", main(() -> activity.isDestroyed()));
            } else if (controller != null) main(() -> { controller.destroy(); return null; });
        } finally {
            try {
                if (usedPage) BrowserDatabaseHelper.getInstance(instrumentation.getTargetContext()).getWritableDatabase()
                        .delete(BrowserDatabaseHelper.TABLE_HISTORY, "url=?", new String[]{origin});
            } finally {
            SharedPreferences.Editor edit = storage.edit().clear();
            for (Map.Entry<String,Object> entry : original.entrySet()) {
                String key = entry.getKey(); Object value = entry.getValue();
                if (value instanceof String) edit.putString(key, (String)value);
                else if (value instanceof Boolean) edit.putBoolean(key, (Boolean)value);
                else if (value instanceof Integer) edit.putInt(key, (Integer)value);
                else if (value instanceof Long) edit.putLong(key, (Long)value);
                else if (value instanceof Float) edit.putFloat(key, (Float)value);
                else if (value instanceof Set) edit.putStringSet(key, new HashSet<>((Set<String>)value));
                else throw new AssertionError("Unsupported preference type: " + key);
            }
            assertTrue(edit.commit()); assertEquals("Restore every user preference", original, snapshot());
            }
        }
    }

    @Test public void protectedMediaIsGrantedWithoutGrantingUnknownResources() throws Exception {
        main(() -> {
            controller = new BrowserPermissionController(new Activity(), prefs);
            Request request = new Request(origin, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID, "unknown.resource");
            controller.request(request);
            assertGrant(request, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
            Request unknown = new Request(origin, "unknown.resource"); controller.request(unknown);
            assertEquals(1, unknown.denials); assertEquals(0, unknown.grants);
            return null;
        });
    }

    @Test public void rejectingOrCancellingCameraKeepsAlreadyAllowedResources() throws Exception {
        start(); prefs.setPermission("microphone", "allow");
        Request first = request(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        node(site + " 请求使用"); node("- 摄像头：用于提供网站需要的拍照或录像等功能");
        assertFalse("Only the resource requiring a decision belongs in the prompt", hasText("- 麦克风：用于提供网页需要的语音输入或音频录制等功能"));
        click("拒绝");
        assertGrant(first, PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        assertFalse("One-time refusal must not enable site overrides", prefs.siteSettingsEnabled(site));
        Request second = request(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        click("不再询问"); cancelDialog();
        assertGrant(second, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        assertFalse("Cancelling ignores the checked remember option", prefs.siteSettingsEnabled(site));
    }

    @Test public void rememberedRefusalOnlyChangesAskedResourcesAndCurrentPort() throws Exception {
        start(); prefs.setSiteSettingsEnabled(site, true);
        prefs.setSitePermissionMode("microphone", site, 1);
        prefs.setSitePermissionMode("camera", site, 3);
        Request denied = request(PermissionRequest.RESOURCE_AUDIO_CAPTURE, PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        click("不再询问"); click("拒绝");
        assertGrant(denied, PermissionRequest.RESOURCE_AUDIO_CAPTURE);
        assertEquals(2, new BrowserPrefs(activity).sitePermissionMode("camera", site));
        assertEquals("Remembering only the asked camera must preserve the microphone", 1, prefs.sitePermissionMode("microphone", site));
        Request again = request(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        assertEquals("The saved refusal is consumed on the next request", 1, again.denials);
        assertEquals("Other ports keep their own policy", "ask", prefs.permission("camera", site.replace(":8443", ":9443")));
        prefs.resetSiteSettings(site);
        Request reset = request(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        node("不再询问"); cancelDialog(); assertEquals(1, reset.denials);
    }

    @Test public void rememberedAllowanceEnablesSiteAndSurvivesControllerRecreation() throws Exception {
        start();
        Request first = request(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE);
        click("不再询问"); click("允许");
        assertGrant(first, PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE);
        assertTrue(prefs.siteSettingsEnabled(site));
        assertEquals(1, prefs.sitePermissionMode("camera", site)); assertEquals(1, prefs.sitePermissionMode("microphone", site));
        main(() -> {
            BrowserPermissionController reopened = new BrowserPermissionController(activity, new BrowserPrefs(activity));
            try { Request again = new Request(origin, PermissionRequest.RESOURCE_VIDEO_CAPTURE); reopened.request(again); assertGrant(again, PermissionRequest.RESOURCE_VIDEO_CAPTURE); }
            finally { reopened.destroy(); }
            return null;
        });
    }

    @Test public void locationChoicesCarryRetentionAndPersistOnlyWhenRemembered() throws Exception {
        start();
        List<String> results = new ArrayList<>();
        for (boolean allow : new boolean[]{false, true}) for (boolean remember : new boolean[]{false, true}) {
            prefs.resetSiteSettings(site); results.clear();
            main(() -> { controller.requestLocation(origin, (url, accepted, retain) -> results.add(url + ":" + accepted + ":" + retain)); return null; });
            node("允许网站访问地理位置"); node(site + " 想要使用你的位置信息。");
            if (remember) click("不再询问"); click(allow ? "允许" : "拒绝");
            await(() -> results.size() == 1);
            assertEquals(Collections.singletonList(origin + ":" + allow + ":" + remember), results);
            assertEquals(remember, prefs.siteSettingsEnabled(site));
            assertEquals(remember ? (allow ? 1 : 2) : -1, prefs.sitePermissionMode("location", site));
            if (remember) {
                main(() -> { controller.requestLocation(origin, (url, accepted, retain) -> results.add(url + ":" + accepted + ":" + retain)); return null; });
                assertEquals("A stored refusal retains=false; a stored allowance retains=true", origin + ":" + allow + ":" + allow, results.get(1));
            }
        }
        prefs.resetSiteSettings(site); results.clear();
        main(() -> { controller.requestLocation(origin, (url, accepted, retain) -> results.add(accepted + ":" + retain)); return null; });
        click("不再询问"); cancelDialog();
        assertEquals(Collections.singletonList("false:false"), results); assertFalse(prefs.siteSettingsEnabled(site));
    }

    @Test public void cancelledWebRequestCannotGrantOrSaveItsOldPrompt() throws Exception {
        start(); Request old = request(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
        click("不再询问");
        main(() -> { controller.cancel(old); controller.onRuntimeResult(); return null; });
        assertEquals(0, old.grants); assertEquals(0, old.denials); assertFalse(prefs.siteSettingsEnabled(site));
        Request next = request(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        assertGrant(next, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
    }

    @Test public void realWebViewCameraRequestUsesRememberedChoiceAndCanCaptureAfterReset() throws Exception {
        prefs.setIncognitoMode(true); prefs.setJsEnabled(true); start(); usedPage = true;
        assertEquals("Real camera capture requires the granted Android fixture", android.content.pm.PackageManager.PERMISSION_GRANTED,
                activity.checkSelfPermission(android.Manifest.permission.CAMERA));
        android.webkit.WebView page = main(() -> {
            Field field = BrowserActivity.class.getDeclaredField("tabs"); field.setAccessible(true);
            return ((TabManager)field.get(activity)).current().webView;
        });
        String html = "<!doctype html><html><head><link rel='icon' href='data:,'></head><body>Permission fixture<script>"
                + "window.result='ready';window.acquire=async function(){window.result='pending';try{"
                + "let stream=await navigator.mediaDevices.getUserMedia({video:true});let tracks=stream.getVideoTracks();"
                + "let live=tracks.length===1&&tracks[0].readyState==='live';stream.getTracks().forEach(t=>t.stop());"
                + "window.result=live&&tracks[0].readyState==='ended'?'captured-and-stopped':'invalid-track';"
                + "}catch(error){window.result=error.name;}};</script></body></html>";
        for (boolean allow : new boolean[]{false, true}) {
            prefs.resetSiteSettings(site);
            main(() -> { page.loadDataWithBaseURL(origin, html, "text/html", "UTF-8", null); return null; });
            awaitPageValue(page, "window.result", "\"ready\"");
            assertEquals("The test document must have a real secure origin", "true", javascript(page, "window.isSecureContext"));
            javascript(page, "window.acquire();true"); click("不再询问"); click(allow ? "允许" : "拒绝");
            awaitPageValue(page, "window.result", allow ? "\"captured-and-stopped\"" : "\"NotAllowedError\"");
            assertEquals(allow ? 1 : 2, prefs.sitePermissionMode("camera", site));
            javascript(page, "window.acquire();true");
            awaitPageValue(page, "window.result", allow ? "\"captured-and-stopped\"" : "\"NotAllowedError\"");
            assertFalse("Remembered native WebView requests must not prompt again", hasText("不再询问"));
        }
    }

    @Test public void permissionThemeAndJavascriptCheckboxKeepTheirOwnSemantics() throws Exception {
        start();
        for (boolean night : new boolean[]{false, true}) {
            prefs.setNightMode(night); Request pending = request(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
            main(() -> {
                Dialog shown = (Dialog)field("dialog");
                android.widget.TextView label = findText(shown.getWindow().getDecorView(), "不再询问"); assertNotNull(label);
                android.view.View surface = label;
                while (!(surface.getBackground() instanceof android.graphics.drawable.GradientDrawable)) surface = (android.view.View)surface.getParent();
                int background = ((android.graphics.drawable.GradientDrawable)surface.getBackground()).getColor().getDefaultColor();
                assertTrue("Permission surface follows day/night", night ? androidx.core.graphics.ColorUtils.calculateLuminance(background) < .1 : androidx.core.graphics.ColorUtils.calculateLuminance(background) > .8);
                assertTrue("The remember option stays readable", androidx.core.graphics.ColorUtils.calculateContrast(label.getCurrentTextColor(), background) > 4.5);
                return null;
            });
            cancelDialog(); assertEquals(1, pending.denials);
        }
        List<String> answers = new ArrayList<>();
        Dialog js = main(() -> ViaUi.jsMessageDialog(activity, "JS fixture", "message", true, true,
                (accepted, suppress) -> answers.add(accepted + ":" + suppress)));
        click(activity.getString(com.example.cleanrecovery.R.string.via_js_ignore_minute)); click("确定");
        await(() -> answers.size() == 1); assertEquals(Collections.singletonList("true:true"), answers);
        main(() -> { js.cancel(); return null; }); instrumentation.waitForIdleSync(); assertEquals(1, answers.size());
        answers.clear();
        Dialog cancelled = main(() -> ViaUi.jsMessageDialog(activity, "JS fixture", "message", true, true,
                (accepted, suppress) -> answers.add(accepted + ":" + suppress)));
        main(() -> { cancelled.cancel(); return null; }); await(() -> answers.size() == 1);
        assertEquals("JS cancel must still signal null suppression", Collections.singletonList("false:null"), answers);
    }

    private android.widget.TextView findText(android.view.View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView)view).getText())) return (android.widget.TextView)view;
        if (view instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup)view).getChildCount(); i++) {
            android.widget.TextView found = findText(((android.view.ViewGroup)view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }

    private String javascript(android.webkit.WebView page, String script) throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> value = new java.util.concurrent.atomic.AtomicReference<>();
        main(() -> { page.evaluateJavascript(script, result -> { value.set(result); done.countDown(); }); return null; });
        assertTrue("Real WebView evaluation completes", done.await(5, java.util.concurrent.TimeUnit.SECONDS)); return value.get();
    }
    private void awaitPageValue(android.webkit.WebView page, String script, String expected) throws Exception {
        long end = System.currentTimeMillis() + 10000; String actual;
        do { actual = javascript(page, script); if (expected.equals(actual)) return; Thread.sleep(30); }
        while (System.currentTimeMillis() < end);
        assertEquals("The native media request must resolve in the actual page", expected, actual);
    }

    // These runtime cases start with denied OS permissions; the other prompt cases use granted OS fixtures.
    @Test public void systemCameraDenialKeepsProtectedMediaAndTheSiteChoice() throws Exception {
        start(); assertSystemDenied(android.Manifest.permission.CAMERA);
        prefs.setSiteSettingsEnabled(site, true); prefs.setSitePermissionMode("camera", site, 1);
        Request request = request(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        denySystemPermission();
        assertGrant(request, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        assertEquals("Android refusal must not overwrite the site's own choice", 1, prefs.sitePermissionMode("camera", site));
    }

    @Test public void locationCallbackPrecedesSystemChoiceAndIsNotRepeatedAfterDenial() throws Exception {
        start(); assertSystemDenied(android.Manifest.permission.ACCESS_COARSE_LOCATION);
        assertSystemDenied(android.Manifest.permission.ACCESS_FINE_LOCATION);
        List<String> results = new ArrayList<>();
        main(() -> {
            controller.requestLocation(origin, (url, allow, retain) -> results.add(allow + ":" + retain + ":"
                    + activity.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)));
            return null;
        });
        click("不再询问"); click("允许"); await(() -> results.size() == 1);
        assertEquals(Collections.singletonList("true:true:" + android.content.pm.PackageManager.PERMISSION_DENIED), results);
        denySystemPermission(); await(() -> field("runtimeResult") == null);
        assertEquals("Android's result cannot invoke the web callback a second time", 1, results.size());
        assertEquals(1, prefs.sitePermissionMode("location", site));
    }

    @Test public void cancelledRequestIgnoresLateAndroidResultAndReleasesTheNextRequest() throws Exception {
        start(); assertSystemDenied(android.Manifest.permission.CAMERA);
        prefs.setPermission("camera", "allow");
        Request old = request(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        Runnable late = main(() -> (Runnable)field("runtimeResult")); assertNotNull(late);
        main(() -> { controller.cancel(old); return null; });
        Request during = request(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        assertEquals("A new request must not replace the pending Android callback", 1, during.denials);
        denySystemPermission(); await(() -> field("runtimeResult") == null);
        Request next = request(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        assertGrant(next, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
        main(() -> { late.run(); return null; });
        assertEquals(0, old.grants); assertEquals(0, old.denials); assertEquals(1, next.grants);
    }

    private void assertSystemDenied(String permission) {
        assertEquals("Runtime fixture must begin denied: " + permission, android.content.pm.PackageManager.PERMISSION_DENIED,
                activity.checkSelfPermission(permission));
    }
    private Object field(String name) throws Exception {
        Field field = BrowserPermissionController.class.getDeclaredField(name); field.setAccessible(true); return field.get(controller);
    }
    private void denySystemPermission() throws Exception {
        long end = System.currentTimeMillis() + 5000; AccessibilityNodeInfo deny = null;
        while (deny == null && System.currentTimeMillis() < end) {
            deny = systemDeny(instrumentation.getUiAutomation().getRootInActiveWindow());
            if (deny == null) Thread.sleep(30);
        }
        assertNotNull("The actual Android permission dialog must be shown", deny);
        System.out.println("Android refusal action: " + deny.getViewIdResourceName() + " / " + deny.getText());
        assertTrue(deny.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
    private AccessibilityNodeInfo systemDeny(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String id = node.getViewIdResourceName();
        if (id != null && id.contains("permissioncontroller") && (id.endsWith(":id/permission_deny_button")
                || id.endsWith(":id/permission_deny_and_dont_ask_again_button"))) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = systemDeny(node.getChild(i)); if (found != null) return found; }
        return null;
    }

    private void start() throws Exception {
        activity = (BrowserActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        controller = main(() -> { Field field = BrowserActivity.class.getDeclaredField("permissions"); field.setAccessible(true); return (BrowserPermissionController)field.get(activity); });
    }
    private Request request(String... resources) throws Exception {
        Request request = new Request(origin, resources); main(() -> { controller.request(request); return null; }); return request;
    }
    private void assertGrant(Request request, String... resources) throws Exception {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            await(() -> request.grants + request.denials > 0);
        assertEquals("Resolve each request exactly once", 1, request.grants); assertEquals(0, request.denials);
        assertEquals(new HashSet<>(Arrays.asList(resources)), new HashSet<>(Arrays.asList(request.accepted)));
    }
    private void cancelDialog() throws Exception {
        main(() -> { Field field = BrowserPermissionController.class.getDeclaredField("dialog"); field.setAccessible(true); ((Dialog)field.get(controller)).cancel(); return null; });
        await(() -> field("webRequest") == null && field("locationRequest") == null);
    }
    private AccessibilityNodeInfo node(String text) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) for (AccessibilityNodeInfo child : root.findAccessibilityNodeInfosByText(text)) if (text.contentEquals(child.getText() == null ? "" : child.getText())) return child;
            Thread.sleep(30);
        }
        throw new AssertionError("Missing dialog text: " + text);
    }
    private boolean hasText(String text) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        return root != null && !root.findAccessibilityNodeInfosByText(text).isEmpty();
    }
    private void click(String text) throws Exception {
        AccessibilityNodeInfo node = node(text); while (!node.isClickable() && node.getParent() != null) node = node.getParent();
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.waitForIdleSync();
    }
    private <T> T main(Callable<T> action) throws Exception { FutureTask<T> result = new FutureTask<>(action); instrumentation.runOnMainSync(result); return result.get(); }
    private void await(Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!main(condition) && System.currentTimeMillis() < end) Thread.sleep(25);
        assertTrue("The actual callback must arrive before inspecting its result", main(condition));
    }
    private Map<String,Object> snapshot() { Map<String,Object> values = new HashMap<>(); for (Map.Entry<String,?> entry : storage.getAll().entrySet()) values.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>)entry.getValue()) : entry.getValue()); return values; }
    private static class Request extends PermissionRequest {
        final Uri origin; final String[] resources; String[] accepted; int grants, denials;
        Request(String origin, String... resources) { this.origin = Uri.parse(origin); this.resources = resources; }
        @Override public Uri getOrigin() { return origin; }
        @Override public String[] getResources() { return resources; }
        @Override public void grant(String[] resources) { grants++; accepted = resources; }
        @Override public void deny() { denials++; }
    }
}
