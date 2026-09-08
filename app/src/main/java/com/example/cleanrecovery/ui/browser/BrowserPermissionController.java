package com.example.cleanrecovery.ui.browser;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import com.example.cleanrecovery.ui.widget.GlassToast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bridges per-origin browser choices to Android runtime permissions. */
public final class BrowserPermissionController {
    public static final int REQUEST_CODE = 4812;
    private final Activity activity;
    private final BrowserPrefs prefs;
    private PermissionRequest webRequest;
    private GeolocationPermissions.Callback locationRequest;
    private String locationOrigin;
    private Runnable runtimeResult;
    private Dialog dialog;

    public BrowserPermissionController(Activity activity, BrowserPrefs prefs) {
        this.activity = activity;
        this.prefs = prefs;
    }

    public void request(PermissionRequest request) {
        if (request == null) return;
        if (webRequest != null || locationRequest != null || runtimeResult != null) { request.deny(); return; }
        if (request.getResources() == null || request.getResources().length == 0) { request.deny(); return; }
        String site = BrowserPrefs.siteKey(request.getOrigin() == null ? null : request.getOrigin().toString());
        List<String> allowed = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) { allowed.add(resource); continue; }
            String key = permissionKey(resource);
            if (key == null) continue;
            String mode = prefs.permission(key, site);
            if ("allow".equals(mode)) allowed.add(resource);
            else if ("ask".equals(mode)) asked.add(resource);
        }
        webRequest = request;
        if (asked.isEmpty()) { resolveWeb(request, allowed); return; }
        StringBuilder message = new StringBuilder();
        for (String resource : asked) {
            if (message.length() > 0) message.append('\n');
            message.append(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    ? "- 摄像头：用于提供网站需要的拍照或录像等功能"
                    : "- 麦克风：用于提供网页需要的语音输入或音频录制等功能");
        }
        confirm(site + " 请求使用", message.toString(), (accept, remember) -> {
            if (webRequest != request) return;
            if (Boolean.TRUE.equals(remember)) {
                for (String resource : asked) remember(permissionKey(resource), site, accept);
            }
            if (accept) allowed.addAll(asked);
            resolveWeb(request, allowed);
        });
    }

    private String permissionKey(String resource) {
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) return "camera";
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) return "microphone";
        return null;
    }

    private void resolveWeb(PermissionRequest request, List<String> resources) {
        List<String> androidPermissions = new ArrayList<>();
        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) androidPermissions.add(Manifest.permission.CAMERA);
        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            androidPermissions.add(Manifest.permission.RECORD_AUDIO);
            androidPermissions.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);
        }
        requireAndroid(androidPermissions.toArray(new String[0]), () -> {
            if (webRequest != request) return;
            if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && !granted(Manifest.permission.CAMERA))
                resources.remove(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
            // Via filters its WebView grant set this way; Android still enforces RECORD_AUDIO during capture.
            if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    && !granted(Manifest.permission.RECORD_AUDIO) && !granted(Manifest.permission.MODIFY_AUDIO_SETTINGS))
                resources.remove(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
            webRequest = null;
            if (resources.isEmpty()) request.deny();
            else request.grant(resources.toArray(new String[0]));
        });
    }

    public void requestLocation(String origin, GeolocationPermissions.Callback callback) {
        String site = BrowserPrefs.siteKey(origin);
        String mode = prefs.permission("location", site);
        if (site.isEmpty() || "block".equals(mode) || webRequest != null || locationRequest != null || runtimeResult != null) {
            callback.invoke(origin, false, false);
            return;
        }
        locationRequest = callback;
        locationOrigin = origin;
        java.util.function.BiConsumer<Boolean, Boolean> answer = (allow, remember) -> {
            if (locationRequest != callback) return;
            boolean retain = Boolean.TRUE.equals(remember);
            finishLocation(callback, allow, retain);
            if (retain) remember("location", site.trim().toLowerCase(Locale.ROOT), allow);
            if (allow) requestAndroidLocation(site);
        };
        if ("ask".equals(mode)) confirm("允许网站访问地理位置", site + " 想要使用你的位置信息。", answer);
        else {
            finishLocation(callback, true, true);
            requestAndroidLocation(site);
        }
    }

    private void requestAndroidLocation(String site) {
        requireAndroid(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, () -> {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))
                GlassToast.makeText(activity, site + " 正在使用你的位置信息", GlassToast.LENGTH_SHORT).show();
        });
    }

    private void remember(String key, String site, boolean allow) {
        if (site.isEmpty()) return;
        prefs.setSiteSettingsEnabled(site, true);
        prefs.setSitePermissionMode(key, site, allow ? 1 : 2);
    }

    private void finishLocation(GeolocationPermissions.Callback callback, boolean allowed, boolean retain) {
        if (locationRequest != callback) return;
        String origin = locationOrigin;
        locationRequest = null;
        locationOrigin = null;
        callback.invoke(origin, allowed, retain);
    }

    public void cancelLocation() {
        if (locationRequest != null) {
            finishLocation(locationRequest, false, false);
            if (dialog != null) dialog.dismiss();
        }
    }

    private void confirm(String title, String message, java.util.function.BiConsumer<Boolean, Boolean> answer) {
        if (activity.isFinishing() || activity.isDestroyed()) { answer.accept(false, false); return; }
        dialog = ViaUi.permissionDialog(activity, title, message, answer);
    }

    private boolean granted(String permission) {
        return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requireAndroid(String[] permissions, Runnable result) {
        List<String> missing = new ArrayList<>();
        for (String permission : permissions) if (!granted(permission)) missing.add(permission);
        if (missing.isEmpty()) { result.run(); return; }
        if (runtimeResult != null) return;
        runtimeResult = result;
        activity.requestPermissions(missing.toArray(new String[0]), REQUEST_CODE);
    }

    public void onRuntimeResult() {
        Runnable result = runtimeResult;
        runtimeResult = null;
        if (result != null) result.run();
    }

    public void cancel(PermissionRequest request) {
        if (webRequest == request) {
            webRequest = null;
            if (dialog != null) dialog.dismiss();
        }
    }

    public void destroy() {
        cancelLocation();
        if (dialog != null) dialog.dismiss();
        if (webRequest != null) { webRequest.deny(); webRequest = null; }
        runtimeResult = null;
    }
}
