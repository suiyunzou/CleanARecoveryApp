package com.example.cleanrecovery.ui.browser;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Browser-internal resource page, matching Via's res.html (not its unfiltered log.html). */
public final class BrowserResourcePage {
    private BrowserResourcePage() { }

    public static String url(int tabId) {
        return "https://appassets.androidplatform.net/via-res/" + tabId;
    }

    public static String render(List<ViaSnifferStateMachine.Candidate> requests,
                                String title, String note, String empty, boolean dark, boolean rtl) {
        StringBuilder rows = new StringBuilder();
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        // Via takes the request window BEFORE filtering, retaining duplicates and newest-first order.
        for (int i = requests.size() - 1, first = Math.max(0, requests.size() - 64); i >= first; i--) {
            ViaSnifferStateMachine.Candidate entry = requests.get(i);
            if (!entry.mediaLike()) continue;
            String url = entry.url();
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue;
            rows.append("<div class=\"box").append(entry.blockedResponse() ? " block" : "")
                    .append("\"><a href=\"").append(escape(url)).append("\" title=\"")
                    .append(escape(url)).append("\"></a><p class=\"title\">")
                    .append(time.format(new Date(entry.capturedAtMillis())))
                    .append("<span class=\"tag\">").append(entry.blockedResponse() ? "block" : "load")
                    .append("</span>");
            if (!entry.extension().isEmpty()) rows.append("<span class=\"res tag\">")
                    .append(escape(entry.extension())).append("</span>");
            rows.append("</p><p class=\"url\">").append(boldHost(url)).append("</p></div>");
        }
        String foreground = dark ? "#d5d5d5" : "#2b2b2b";
        String urlColor = dark ? "#fafafa" : "#1b1b1b";
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">"
                + "<meta name=\"referrer\" content=\"no-referrer\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none';style-src 'unsafe-inline'\">"
                + "<title>" + escape(title) + "</title><style>"
                + "*{padding:0;margin:0;box-sizing:border-box}"
                + "html{height:100%;-webkit-focus-ring-color:rgba(0,0,0,0);-webkit-tap-highlight-color:"
                + (dark ? "rgba(255,255,255,.1)" : "rgba(0,0,0,.1)") + ";direction:" + (rtl ? "rtl" : "ltr") + "}"
                + "body{min-height:100%;max-width:100%;width:600px;margin:auto;text-align:center;background:"
                + (dark ? "#121212" : "#fff") + ";color:" + foreground + "}"
                + ".box{margin:12px 0;text-align:" + (rtl ? "right" : "left")
                + ";vertical-align:middle;position:relative;display:block;padding:10px}"
                + ".box a{width:100%;height:100%;position:absolute;left:0;top:0}"
                + "span,.url,.box{word-break:break-all}.block{opacity:.5}"
                + ".tag{background:#cd8282;padding:0 8px;margin:0 4px;color:white;font-size:12px}.res{background:#5c91cb}"
                + ".title{font-size:15px;padding:4px 0}"
                + ".url{color:" + urlColor + ";line-height:1.2em;max-height:4.8em;font-size:15px;white-space:normal;word-wrap:break-word;overflow:auto;text-overflow:ellipsis}"
                + ".hint{line-height:1.8em;font-size:15px;white-space:normal;word-wrap:break-word;overflow:auto;text-overflow:ellipsis;padding:50px 5px;text-align:center;margin:auto}"
                + "</style></head><body><p class=\"hint\">" + escape(rows.length() == 0 ? empty : note)
                + "</p>" + rows + "</body></html>";
    }

    private static String boldHost(String url) {
        int start = url.indexOf("://") + 3;
        int end = url.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int index = url.indexOf(delimiter, start);
            if (index >= 0) end = Math.min(end, index);
        }
        return escape(url.substring(0, start)) + "<b>" + escape(url.substring(start, end))
                + "</b>" + escape(url.substring(end));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
