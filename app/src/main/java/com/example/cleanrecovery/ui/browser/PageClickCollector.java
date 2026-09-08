package com.example.cleanrecovery.ui.browser;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebView;

import androidx.core.util.Consumer;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Set;

/**
 * 点击来源采集（Phase 2）：document-start 安装捕获阶段监听，
 * 只上报证据（透明度/面积/是否播放器区域/是否直点链接），
 * 拦截与否由 {@link NavigationGuard} 在 Java 层判定——页面改不了规则。
 *
 * <p>通道：WebViewCompat.addWebMessageListener（受 origin 管控的 WebMessage，
 * 不用 addJavascriptInterface 暴露对象）。内核不支持时静默降级为无信号
 * （守卫退化为仅无手势重定向询问，不会误杀）。</p>
 */
public final class PageClickCollector {

    private static final String TAG = "ViaGuard";
    public static final String CHANNEL = "viaGuard";
    /** 信号可信时窗：点击与导航间隔太久则无关。 */
    public static final long SIGNAL_MAX_AGE_MS = 2000;

    private volatile NavigationGuard.ClickSignal lastSignal;
    private final Consumer<NavigationGuard.ClickSignal> sink;

    public PageClickCollector(Consumer<NavigationGuard.ClickSignal> sink) {
        this.sink = sink;
    }

    /** 最近一次可信点击信号（含新鲜度判断）。 */
    public NavigationGuard.ClickSignal freshSignal() {
        NavigationGuard.ClickSignal s = lastSignal;
        if (s == null) return null;
        long age = System.currentTimeMillis() - s.timestampMs;
        if (age < 0 || age > SIGNAL_MAX_AGE_MS) return null;
        return s;
    }

    /** 每个新 WebView 调用一次（document-start 注入 + 通道注册）。 */
    public void install(WebView webView) {
        if (webView == null) return;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                // 注意：webkit 1.10 的规则校验不允许 "http://*"（通配 host 仅 https 可用），
                // 故放开为 "*"，非 http(s) 来源在 Java 侧丢弃（trustOrigin）
                WebViewCompat.addDocumentStartJavaScript(webView, script(),
                        Collections.singleton("*"));
                WebViewCompat.addWebMessageListener(webView, CHANNEL,
                        Collections.singleton("*"),
                        (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                            if (!trustOrigin(sourceOrigin)) return;
                            if (message == null || message.getData() == null) return;
                            NavigationGuard.ClickSignal s = parse(message.getData());
                            if (s != null) {
                                lastSignal = s;
                                if (sink != null) sink.accept(s);
                            }
                        });
            }
        } catch (Throwable t) {
            Log.w(TAG, "click collector install failed: " + t);
        }
    }

    /** 解析页面 JSON 证据（所有数值在 Java 侧夹紧，脏输入丢弃）。 */
    static NavigationGuard.ClickSignal parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"click".equals(o.optString("t"))) return null;
            float opacity = clamp01((float) o.optDouble("o", 1.0));
            float area = clamp01((float) o.optDouble("a", 0.0));
            boolean overPlayer = o.optBoolean("v", false);
            boolean onAnchor = o.optBoolean("h", false);
            return new NavigationGuard.ClickSignal(System.currentTimeMillis(),
                    opacity, area, overPlayer, onAnchor);
        } catch (Exception e) {
            return null;
        }
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    // ===== document-start 脚本 =====

    static String script() {
        return "(function(){"
                + "if(window.__viaGuardInstalled)return;"
                + "window.__viaGuardInstalled=true;"
                + "function chainOpacity(el){"
                + "var o=1,n=0;"
                + "while(el&&el.nodeType===1&&n<6){"
                + "var s=getComputedStyle(el);"
                + "if(s&&s.opacity!==undefined){var v=parseFloat(s.opacity);if(!isNaN(v)&&v<o)o=v;}"
                + "el=el.parentElement;n++;"
                + "}return o;}"
                + "function report(el,ev){"
                + "try{"
                + "var r=el.getBoundingClientRect();"
                + "var vw=window.innerWidth||1,vh=window.innerHeight||1;"
                + "var area=(r.width*r.height)/(vw*vh);"
                + "var onAnchor=false,cur=el,n=0;"
                + "while(cur&&cur.nodeType===1&&n<4){"
                + "if(cur.tagName==='A'&&cur.hasAttribute('href')){onAnchor=true;break;}"
                + "cur=cur.parentElement;n++;"
                + "}"
                + "var overPlayer=false;"
                + "var vids=document.querySelectorAll('video');"
                + "for(var i=0;i<vids.length&&i<8;i++){"
                + "var vr=vids[i].getBoundingClientRect();"
                + "if(vr.width>0&&vr.height>0&&ev.clientX>=vr.left&&ev.clientX<=vr.right"
                + "&&ev.clientY>=vr.top&&ev.clientY<=vr.bottom){overPlayer=true;break;}"
                + "}"
                + "if(window." + CHANNEL + "&&window." + CHANNEL + ".postMessage){"
                + "window." + CHANNEL + ".postMessage(JSON.stringify({t:'click',"
                + "o:chainOpacity(el),a:area,v:overPlayer,h:onAnchor}));"
                + "}"
                + "}catch(e){}"
                + "}"
                + "document.addEventListener('pointerdown',function(ev){"
                + "if(ev&&ev.target&&ev.target.nodeType===1)report(ev.target,ev);"
                + "},true);"
                + "})();";
    }

    /** 供日志/调试使用：来源 origin 是否可信（仅 https/http 页面上报）。 */
    static boolean trustOrigin(Uri sourceOrigin) {
        if (sourceOrigin == null) return false;
        String s = sourceOrigin.getScheme();
        return "https".equals(s) || "http".equals(s);
    }
}
