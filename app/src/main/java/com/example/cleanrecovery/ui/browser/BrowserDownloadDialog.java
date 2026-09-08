package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Download confirmation and metadata behavior from installed Via 7.2.1, sa/l and layout/e. */
public final class BrowserDownloadDialog extends Dialog {
    public interface Callback { void onConfirm(String filename, String mimeType); }

    private final Activity activity;
    private final String url;
    private final EditText filename;
    private final TextView size, copy;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String mimeType;
    private volatile boolean closed;
    private Thread metadataWorker;

    public static BrowserDownloadDialog show(Activity activity, String url, String contentDisposition,
                                              String mimeType, long contentLength, Callback callback) {
        BrowserDownloadDialog dialog = new BrowserDownloadDialog(activity, url, contentDisposition, mimeType, contentLength, callback);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(Math.min(ViaUi.dp(activity, 384), activity.getResources().getDisplayMetrics().widthPixels
                    - ViaUi.dp(activity, 72)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.filename.requestFocus();
        selectName(dialog.filename);
        if (URLUtil.isNetworkUrl(url) && contentLength <= 0) dialog.loadMetadata(contentDisposition);
        return dialog;
    }

    private BrowserDownloadDialog(Activity activity, String url, String disposition, String type, long length, Callback callback) {
        super(activity);
        this.activity = activity;
        this.url = url;
        this.mimeType = type;
        setOwnerActivity(activity);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(false);
        boolean night = new BrowserPrefs(activity).nightMode();
        int textColor = night ? 0xbeffffff : 0xde000000;
        int secondary = night ? 0x99ffffff : 0x8a000000;
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, ViaUi.dp(activity, 12), 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(night ? 0xff1c1b1d : Color.WHITE);
        background.setCornerRadius(ViaUi.dp(activity, 18));
        background.setStroke(ViaUi.dp(activity, 1), 0x30808080);
        card.setBackground(background);

        TextView title = text("你想要下载此文件吗？", 16, textColor);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, ViaUi.dp(activity, 8), 0, ViaUi.dp(activity, 8));
        card.addView(title, inset(6));

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(0, ViaUi.dp(activity, 8), 0, ViaUi.dp(activity, 8));
        filename = new EditText(activity);
        filename.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        filename.setImeOptions(EditorInfo.IME_ACTION_DONE);
        filename.setSingleLine();
        filename.setTextSize(14);
        filename.setTextColor(textColor);
        filename.setHintTextColor(secondary);
        filename.setHint("文件名");
        filename.setBackgroundColor(Color.TRANSPARENT);
        filename.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        filename.setSelectAllOnFocus(true);
        filename.setOnFocusChangeListener((view, focused) -> { if (focused) selectName(filename); });
        String guessed = guessFilename(url, disposition, type);
        if (guessed.endsWith(".apk")) this.mimeType = "application/vnd.android.package-archive";
        filename.setText(guessed);
        scroll.addView(filename, new HorizontalScrollView.LayoutParams(-2, -2));
        LinearLayout input = new LinearLayout(activity);
        input.setOrientation(LinearLayout.VERTICAL);
        input.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        View underline = new View(activity);
        underline.setBackgroundColor(0x30808080);
        input.addView(underline, new LinearLayout.LayoutParams(-1, ViaUi.dp(activity, 1)));
        card.addView(input, inset(12));

        size = text("文件大小：" + formatSize(length), 12, secondary);
        size.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        size.setMinLines(1);
        size.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(size, inset(12));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        copy = button("复制链接", () -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(null, url));
            GlassToast.makeText(activity, "已复制链接", GlassToast.LENGTH_SHORT).show();
            dismiss();
        });
        buttons.addView(copy);
        buttons.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        buttons.addView(button(activity.getString(android.R.string.cancel), this::dismiss));
        buttons.addView(button(activity.getString(android.R.string.ok), () -> {
            String name = filename.getText().toString().trim();
            int dot = name.lastIndexOf('.');
            String selectedType = dot < 0 ? this.mimeType : mimeForExtension(name.substring(dot + 1), "application/octet-stream");
            if (callback != null) callback.onConfirm(name, selectedType);
            dismiss();
        }));
        card.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        setContentView(card);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.gravity = Gravity.CENTER;
            attributes.dimAmount = .4f;
            window.setAttributes(attributes);
        }
    }

    private TextView text(String label, int sp, int color) {
        TextView view = new TextView(activity);
        view.setText(label); view.setTextSize(sp); view.setTextColor(color);
        return view;
    }

    private TextView button(String label, Runnable click) {
        TextView view = text(label, 14, ViaUi.ACCENT);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine();
        view.setMinWidth(ViaUi.dp(activity, 56));
        int padding = ViaUi.dp(activity, 16);
        view.setPadding(padding, padding, padding, padding);
        view.setBackgroundResource(com.example.cleanrecovery.R.drawable.bg_via_menu_cell);
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private LinearLayout.LayoutParams inset(int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(ViaUi.dp(activity, 16), 0, ViaUi.dp(activity, 16), ViaUi.dp(activity, bottom));
        return params;
    }

    private static void selectName(EditText input) {
        String value = input.getText().toString();
        int dot = value.lastIndexOf('.');
        if (dot < 0 || value.length() - dot >= 7) input.selectAll();
        else input.setSelection(0, dot);
    }

    private void loadMetadata(String disposition) {
        metadataWorker = new Thread(() -> {
            try {
                JSONObject headers = new JSONObject().put("Referer", url);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) headers.put("Cookie", cookie);
                JSONObject result = BrowserScriptHttp.request(new JSONObject().put("url", url).put("method", "HEAD")
                        .put("timeout", 15000).put("headers", headers), BrowserScriptHttp.currentProxy());
                if (result.optInt("status") != 200) return;
                Map<String, String> responseHeaders = new LinkedHashMap<>();
                for (String line : result.optString("responseHeaders").split("\r?\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) responseHeaders.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
                long length = -1;
                try { length = Long.parseLong(responseHeaders.get("content-length")); } catch (NumberFormatException ignored) { }
                String type = responseHeaders.get("content-type");
                if (type != null && type.indexOf(';') > 0) type = type.substring(0, type.indexOf(';'));
                final String newType = type == null ? mimeType : type;
                final String newName = guessFilename(url, responseHeaders.getOrDefault("content-disposition", disposition), newType);
                final long newLength = length;
                main.post(() -> {
                    if (closed || !isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                    mimeType = newName.endsWith(".apk") ? "application/vnd.android.package-archive" : newType;
                    // Via deliberately replaces even an edited name when its initial HEAD metadata arrives.
                    if (!newName.contentEquals(filename.getText())) {
                        filename.setText(newName);
                        if (filename.hasFocus()) { filename.clearFocus(); filename.requestFocus(); selectName(filename); }
                    }
                    size.setText("文件大小：" + formatSize(newLength));
                    copy.setVisibility(newLength > 64198568 ? View.GONE : View.VISIBLE);
                });
            } catch (Exception ignored) {
                // A failed metadata probe keeps the original editable name and does not prevent confirmation.
            }
        }, "browser-download-metadata");
        metadataWorker.setDaemon(true);
        metadataWorker.start();
    }

    @Override public void dismiss() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        super.dismiss();
    }

    static String formatSize(long bytes) {
        double size = bytes;
        String unit = "B";
        if (size >= 858993459.2) { size /= 1073741824; unit = "GB"; }
        else if (size >= 838860.8) { size /= 1048576; unit = "MB"; }
        else if (size >= 819.2) { size /= 1024; unit = "KB"; }
        return String.format(Locale.ROOT, "%.1f %s", size, unit);
    }

    static String guessFilename(String url, String disposition, String mimeType) {
        String name = dispositionFilename(disposition);
        boolean supplied = name != null;
        if (name == null && url != null) {
            int query = url.indexOf('?');
            if (query > 0) url = url.substring(0, query);
            int slash = url.lastIndexOf('/');
            if (!url.endsWith("/") && slash >= 0) name = url.substring(slash + 1);
        }
        if (name == null) name = "downloadfile";
        else { if (name.contains("%")) name = decode(name); name = name.replace('/', '_'); }
        String extension = extensionForMime(mimeType);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            if (extension == null && mimeType != null && mimeType.startsWith("image/")) extension = "png";
            return extension == null || "bin".equalsIgnoreCase(extension) ? name : name + "." + extension;
        }
        if (!supplied && !MIME_TYPES.containsKey(name.substring(dot + 1).toLowerCase(Locale.ROOT))
                && extension != null && !"bin".equalsIgnoreCase(extension)
                && !extension.equalsIgnoreCase(name.substring(dot + 1))) return name.substring(0, dot + 1) + extension;
        return name;
    }

    private static String dispositionFilename(String disposition) {
        if (disposition == null) return null;
        int start = disposition.indexOf("filename*=");
        if (start < 0) start = disposition.indexOf("filename");
        if (start < 0) return null;
        String value = disposition.substring(start);
        while (value.lastIndexOf(';') > 0) value = value.substring(0, value.lastIndexOf(';')).trim();
        int equals = value.indexOf('=');
        if (equals > 0) {
            value = value.substring(equals + 1).trim();
            int charset = value.indexOf("''");
            if (charset > 0) value = value.substring(charset + 2).trim();
            charset = value.indexOf("' '");
            if (charset > 0) value = value.substring(charset + 3).trim();
        }
        if (!value.isEmpty() && (value.charAt(0) == '"' || value.charAt(0) == '\'')) value = value.substring(1);
        while (!value.isEmpty() && (value.endsWith("\"") || value.endsWith("'"))) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return null;
        return value.indexOf('%') > 0 ? decode(value) : value;
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); } catch (Exception ignored) { return value; }
    }

    private static String mimeForExtension(String extension, String fallback) {
        String type = MIME_TYPES.get(extension.toLowerCase(Locale.ROOT));
        if (type == null) type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? fallback : type;
    }

    private static String extensionForMime(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) return null;
        for (Map.Entry<String, String> entry : MIME_TYPES.entrySet()) if (entry.getValue().equalsIgnoreCase(mimeType)) return entry.getKey();
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
    }

    private static final Map<String, String> MIME_TYPES = installedMimeTypes();

    private static Map<String, String> installedMimeTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        // Filled from the inspected installed APK's m5/c extension precedence table.
        types.put("aac", "audio/aac");
        types.put("abw", "application/x-abiword");
        types.put("apk", "application/vnd.android.package-archive");
        types.put("xapk", "application/xapk-package-archive");
        types.put("arc", "application/x-freearc");
        types.put("asf", "video/x-ms-asf");
        types.put("avi", "video/x-msvideo");
        types.put("azw", "application/vnd.amazon.ebook");
        types.put("bin", "application/octet-stream");
        types.put("bmp", "image/bmp");
        types.put("bz", "application/x-bzip");
        types.put("bz2", "application/x-bzip2");
        types.put("bzip2", "application/x-bzip2");
        types.put("csh", "application/x-csh");
        types.put("css", "text/css");
        types.put("csv", "text/csv");
        types.put("doc", "application/msword");
        types.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        types.put("eot", "application/vnd.ms-fontobject");
        types.put("epub", "application/epub+zip");
        types.put("exe", "application/octet-stream");
        types.put("gif", "image/gif");
        types.put("gtar", "application/x-gtar");
        types.put("gz", "application/x-gzip");
        types.put("htm", "text/html");
        types.put("html", "text/html");
        types.put("mht", "multipart/related");
        types.put("ico", "image/vnd.microsoft.icon");
        types.put("ics", "text/calendar");
        types.put("jar", "application/java-archive");
        types.put("jpeg", "image/jpeg");
        types.put("jpg", "image/jpeg");
        types.put("js", "text/javascript");
        types.put("json", "application/json");
        types.put("jsonld", "application/ld+json");
        types.put("mid", "audio/midi");
        types.put("midi", "audio/x-midi");
        types.put("mjs", "text/javascript");
        types.put("mov", "video/quicktime");
        types.put("mpc", "application/vnd.mpohun.certificate");
        types.put("mpe", "video/mpeg");
        types.put("mpeg", "video/mpeg");
        types.put("mpg", "video/mpeg");
        types.put("mpga", "audio/mpeg");
        types.put("mp4", "video/mp4");
        types.put("mpkg", "application/vnd.apple.installer+xml");
        types.put("mp2", "audio/x-mpeg");
        types.put("mp3", "audio/mpeg");
        types.put("m3u", "audio/x-mpegurl");
        types.put("m4a", "audio/mp4a-latm");
        types.put("m4b", "audio/mp4a-latm");
        types.put("m4p", "audio/mp4a-latm");
        types.put("m4u", "video/vnd.mpegurl");
        types.put("m4v", "video/x-m4v");
        types.put("odp", "application/vnd.oasis.opendocument.presentation");
        types.put("ods", "application/vnd.oasis.opendocument.spreadsheet");
        types.put("odt", "application/vnd.oasis.opendocument.text");
        types.put("oga", "audio/ogg");
        types.put("ogg", "audio/ogg");
        types.put("ogv", "video/ogg");
        types.put("ogx", "application/ogg");
        types.put("otf", "font/otf");
        types.put("png", "image/png");
        types.put("pdf", "application/pdf");
        types.put("pps", "application/vnd.ms-powerpoint");
        types.put("ppt", "application/vnd.ms-powerpoint");
        types.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        types.put("rar", "application/x-rar-compressed");
        types.put("rmvb", "audio/x-pn-realaudio");
        types.put("rtf", "application/rtf");
        types.put("sh", "application/x-sh");
        types.put("svg", "image/svg+xml");
        types.put("swf", "application/x-shockwave-flash");
        types.put("tar", "application/x-tar");
        types.put("tgz", "application/x-compressed");
        types.put("tif", "image/tiff");
        types.put("tiff", "image/tiff");
        types.put("torrent", "application/x-bittorrent");
        types.put("ttf", "font/ttf");
        types.put("txt", "text/plain");
        types.put("vsd", "application/vnd.visio");
        types.put("wav", "audio/wav");
        types.put("weba", "audio/webm");
        types.put("webm", "video/webm");
        types.put("webp", "image/webp");
        types.put("wma", "audio/x-ms-wma");
        types.put("wmv", "audio/x-ms-wmv");
        types.put("woff", "font/woff");
        types.put("woff2", "font/woff2");
        types.put("wps", "application/vnd.ms-works");
        types.put("xhtml", "application/xhtml+xml");
        types.put("xls", "application/vnd.ms-excel");
        types.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        types.put("xml", "text/xml");
        types.put("xul", "application/vnd.mozilla.xul+xml");
        types.put("z", "application/x-compress");
        types.put("zip", "application/zip");
        types.put("3gp", "video/3gpp");
        types.put("3g2", "video/3gpp2");
        types.put("7z", "application/x-7z-compressed");
        return types;
    }
}
