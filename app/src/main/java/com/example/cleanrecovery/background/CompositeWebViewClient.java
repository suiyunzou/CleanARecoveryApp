package com.example.cleanrecovery.background;

import android.graphics.Bitmap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 组合 WebViewClient（后台下载模块）。
 *
 * <p>将多个 {@link WebViewClient} 组合在一起，使 WebView 可以同时
 * 触发可见嗅探器（{@code MediaSniffer}）和隐藏嗅探器
 * （{@link HiddenMediaSniffer}）的事件。</p>
 *
 * <p><b>隐蔽性</b>：此客户端不添加任何 UI，仅转发事件给各子客户端。</p>
 */
public final class CompositeWebViewClient extends WebViewClient {
    private final List<WebViewClient> clients = new ArrayList<>();

    public CompositeWebViewClient(@NonNull WebViewClient... clients) {
        for (WebViewClient c : clients) {
            if (c != null) this.clients.add(c);
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        for (WebViewClient c : clients) {
            if (c.shouldOverrideUrlLoading(view, request)) return true;
        }
        return false;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        WebResourceResponse response = null;
        for (WebViewClient c : clients) {
            WebResourceResponse r = c.shouldInterceptRequest(view, request);
            if (r != null) response = r;
        }
        return response;
    }

    @Override
    public void onPageStarted(WebView view, String url, @Nullable Bitmap favicon) {
        for (WebViewClient c : clients) {
            c.onPageStarted(view, url, favicon);
        }
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        for (WebViewClient c : clients) {
            c.onPageFinished(view, url);
        }
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request,
                                 android.webkit.WebResourceError error) {
        for (WebViewClient c : clients) {
            c.onReceivedError(view, request, error);
        }
    }
}
