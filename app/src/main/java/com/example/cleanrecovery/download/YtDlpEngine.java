package com.example.cleanrecovery.download;

import android.content.Context;
import android.webkit.CookieManager;
import com.example.cleanrecovery.proxy.ProxyEngine;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.ffmpeg.FFmpeg;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import kotlin.Unit;

/** Runs actual yt-dlp and FFmpeg; all calls must run off the UI thread. */
public final class YtDlpEngine {
    private static File pluginRoot;
    public interface Progress { void update(float percent, long eta, String line); }

    public static synchronized void init(Context context) throws Exception {
        YoutubeDL.getInstance().init(context.getApplicationContext());
        FFmpeg.getInstance().init(context.getApplicationContext());
        String bundle = "2026.08.19";
        android.content.SharedPreferences prefs = context.getSharedPreferences("download_engine", Context.MODE_PRIVATE);
        if (!bundle.equals(prefs.getString("bundle", ""))) {
            String installed = YoutubeDL.getInstance().version(context);
            if (installed == null || installed.compareTo(bundle) < 0) {
                File target = new File(context.getNoBackupFilesDir(), "youtubedl-android/yt-dlp/yt-dlp");
                // The library initializes this directory before we upgrade its packaged script.
                if (!target.getParentFile().isDirectory()) throw new IOException("下载引擎目录不存在");
                android.util.AtomicFile file = new android.util.AtomicFile(target);
                FileOutputStream output = null;
                try (java.io.InputStream input = context.getResources().openRawResource(com.example.cleanrecovery.R.raw.ytdlp)) {
                    output = file.startWrite(); byte[] buffer = new byte[65536]; int n;
                    while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                    file.finishWrite(output);
                } catch (Exception error) { if (output != null) file.failWrite(output); throw error; }
            }
            prefs.edit().putString("bundle", bundle).apply();
        }
        if (pluginRoot == null) {
            File root = new File(context.getNoBackupFilesDir(), "download-plugins");
            for (String name : new String[]{"douyin.py", "bilibili.py"}) {
                File target = new File(root, "douyin/yt_dlp_plugins/extractor/" + name);
                if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
                    throw new IOException("无法准备下载插件目录");
                android.util.AtomicFile file = new android.util.AtomicFile(target);
                FileOutputStream output = null;
                try (java.io.InputStream input = context.getAssets().open("ytdlp/" + name)) {
                    output = file.startWrite(); byte[] buffer = new byte[16384]; int n;
                    while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                    file.finishWrite(output);
                } catch (Exception error) { if (output != null) file.failWrite(output); throw error; }
            }
            pluginRoot = root;
        }
    }

    public static YtDlpMedia resolve(Context context, String url, File work, String processId) throws Exception {
        init(context);
        YoutubeDLRequest request = request(url, work);
        request.addOption("--dump-single-json");
        request.addOption("--skip-download");
        checkInterrupted();
        return YtDlpMedia.parse(YoutubeDL.getInstance().execute(request, processId, null).getOut());
    }

    public static File download(Context context, String url, YtDlpMedia.Choice choice,
                                File work, String processId, Progress progress) throws Exception {
        init(context);
        YoutubeDLRequest request = request(url, work);
        request.addOption("-f", choice.selector);
        if (choice.audioOnly) {
            // HLS audio may be raw AAC despite yt-dlp reporting ext=mp4. Package the
            // original audio codec into its proper container (AAC -> M4A, etc.) so
            // Android reads the complete duration and seeking works correctly.
            request.addOption("--extract-audio");
            request.addOption("--audio-format", "best");
        }
        request.addOption("--continue");
        request.addOption("--no-overwrites");
        request.addOption("--no-simulate");
        request.addOption("--newline");
        request.addOption("--progress-template", "download:" + YtDlpTransferProgress.PREFIX
                + "{\"downloaded\":%(progress.downloaded_bytes|0)s,\"total\":%(progress.total_bytes|0)s,"
                + "\"estimate\":%(progress.total_bytes_estimate|0)s,\"vcodec\":\"%(info.vcodec)s\"}");
        request.addOption("--progress");
        request.addOption("--no-mtime");
        request.addOption("--windows-filenames");
        request.addOption("--trim-filenames", "160");
        // Include the resolved format ID so a retry with another quality never resumes
        // bytes belonging to the old representation.
        request.addOption("-o", new File(work, "%(title).100B [%(id)s] [%(format_id)s].%(ext)s").getAbsolutePath());
        request.addOption("--print", "after_move:filepath");
        checkInterrupted();
        String out = YoutubeDL.getInstance().execute(request, processId, (percent, eta, line) -> {
            progress.update(percent, eta, line);
            return Unit.INSTANCE;
        }).getOut();
        checkInterrupted();
        // Only yt-dlp's after_move output counts as success, never a .part or failed merge.
        for (String line : out.split("\\r?\\n")) {
            File file = new File(line.trim());
            if (file.isFile() && file.length() > 0 && file.getCanonicalFile().getParentFile().equals(work.getCanonicalFile())
                    && !file.getName().endsWith(".part")) return file;
        }
        throw new IOException("下载未生成完整文件");
    }

    public static void update(Context context) throws Exception {
        init(context);
        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE);
    }

    private static YoutubeDLRequest request(String url, File work) throws Exception {
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("无法创建下载临时目录");
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--ignore-config");
        // Only load the app-owned plugin; updating the official engine preserves it.
        request.addOption("--no-plugin-dirs");
        request.addOption("--plugin-dirs", pluginRoot.getAbsolutePath());
        request.addOption("--no-playlist");
        request.addOption("--socket-timeout", "25");
        request.addOption("--retries", "3");
        request.addOption("--fragment-retries", "3");
        request.addOption("--abort-on-unavailable-fragments");
        ProxyEngine proxy = ProxyEngine.current();
        if (proxy != null && proxy.isRunning() && proxy.httpPort() > 0)
            request.addOption("--proxy", "http://" + ProxyEngine.localHost() + ":" + proxy.httpPort());
        File cookies = new File(work, "cookies.txt");
        writeCookies(url, cookies);
        request.addOption("--cookies", cookies.getAbsolutePath());
        return request;
    }

    /** Scope cookies to exact hosts. Do not turn cookies into a global HTTP header. */
    private static void writeCookies(String url, File target) throws Exception {
        String host = new URI(url).getHost().toLowerCase(java.util.Locale.ROOT);
        Map<String, String> origins = new LinkedHashMap<>();
        origins.put(host, url);
        if (domain(host, "youtube.com") || domain(host, "youtu.be")) {
            origins.put("www.youtube.com", "https://www.youtube.com/");
            origins.put("youtube.com", "https://youtube.com/");
        } else if (domain(host, "bilibili.com") || domain(host, "b23.tv")) {
            origins.put("www.bilibili.com", "https://www.bilibili.com/");
            origins.put("api.bilibili.com", "https://api.bilibili.com/");
        } else if (domain(host, "douyin.com") || domain(host, "iesdouyin.com")) {
            origins.put("www.douyin.com", "https://www.douyin.com/");
            origins.put("www.iesdouyin.com", "https://www.iesdouyin.com/");
        }
        StringBuilder text = new StringBuilder("# Netscape HTTP Cookie File\n");
        for (Map.Entry<String, String> origin : origins.entrySet()) {
            String header = CookieManager.getInstance().getCookie(origin.getValue());
            if (header == null) continue;
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0 || pair.contains("\n") || pair.contains("\r") || pair.contains("\t")) continue;
                text.append(origin.getKey()).append("\tFALSE\t/\t")
                        .append(origin.getValue().startsWith("https:") ? "TRUE" : "FALSE")
                        .append("\t0\t").append(pair.substring(0, eq).trim()).append('\t')
                        .append(pair.substring(eq + 1).trim()).append('\n');
            }
        }
        try (FileOutputStream stream = new FileOutputStream(target)) {
            stream.write(text.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean domain(String host, String root) { return host.equals(root) || host.endsWith("." + root); }
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
}
