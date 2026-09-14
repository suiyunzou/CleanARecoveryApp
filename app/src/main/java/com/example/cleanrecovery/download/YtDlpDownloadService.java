package com.example.cleanrecovery.download;

import android.app.*;
import android.content.*;
import android.os.*;
import android.media.MediaScannerConnection;
import android.util.AtomicFile;
import androidx.core.app.NotificationCompat;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.activity.UniversalDownloadActivity;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.yausername.youtubedl_android.YoutubeDL;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

/** Sequential foreground batches. Each item owns its formats, partial files and result. */
public final class YtDlpDownloadService extends Service {
    public static final String RESOLVE = "resolve", RETRY_RESOLVE = "retry_resolve", DOWNLOAD = "download", PAUSE = "pause", CANCEL = "cancel", UPDATE = "update";
    public enum Phase { IDLE, RESOLVING, READY, DOWNLOADING, STOPPING, PAUSED, COMPLETE, ERROR, UPDATING }
    public interface Listener { void changed(); }
    public static final class Job {
        public String url, error = "", path;
        public YtDlpMedia media;
        public YtDlpMedia.Choice choice;
        File work;
        Job(String url, File work) { this.url = url; this.work = work; }
    }
    /** UI and state mutations run on the main thread; worker results are posted in order. */
    public static final class State {
        public Phase phase = Phase.IDLE;
        public String url = "", message = "";
        public int progress = -1;
        public YtDlpTransferProgress transfer;
        public final List<Job> jobs = new ArrayList<>();
        public boolean busy() { return phase == Phase.RESOLVING || phase == Phase.DOWNLOADING || phase == Phase.STOPPING || phase == Phase.UPDATING; }
    }
    private static final State state = new State();
    private static Listener listener;
    private static boolean restored;
    public static State state() { return state; }
    public static void listen(Listener value) { listener = value; }
    public static void unlisten(Listener value) { if (listener == value) listener = null; }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Thread runningThread;
    private volatile String stopReason;
    private volatile String processId;
    private PowerManager.WakeLock wakeLock;
    private static final String CHANNEL = "ytdlp_download";
    private static final int NOTIFICATION = 3107;
    private long lastNotification;

    public static void restore(Context context) {
        if (restored) return;
        restored = true;
        try {
            JSONObject json = new JSONObject(new String(new AtomicFile(new File(context.getFilesDir(), "media-download-state.json")).readFully(), StandardCharsets.UTF_8));
            List<Job> restoredJobs = new ArrayList<>();
            JSONArray array = json.getJSONArray("jobs");
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String name = item.optString("work");
                File work = name.matches("[0-9a-f-]{36}") ? new File(context.getFilesDir(), "media-downloads/" + name) : null;
                Job job = new Job(item.getString("url"), work);
                job.error = item.optString("error");
                job.path = item.optString("path", null);
                if (job.path != null && !new File(job.path).isFile()) job.path = null;
                if (item.has("media")) job.media = YtDlpMedia.restore(item.getJSONObject("media"));
                if (job.media != null) job.choice = findChoice(job.media, item.optString("choice"));
                if (job.work != null) new File(job.work, "cookies.txt").delete();
                restoredJobs.add(job);
            }
            state.jobs.addAll(restoredJobs);
            state.url = json.optString("url");
            String previous = json.optString("phase");
            if ("DOWNLOADING".equals(previous) || "STOPPING".equals(previous) || "PAUSED".equals(previous)) {
                state.phase = Phase.PAUSED; state.message = "上次下载未完成，可继续本批下载";
            } else if ("COMPLETE".equals(previous)) {
                state.phase = Phase.COMPLETE; state.message = json.optString("message");
            } else if (!state.jobs.isEmpty()) {
                state.phase = Phase.READY; state.message = "已恢复上次列表；解析失败的链接可重新解析";
            }
        } catch (Exception ignored) { /* Missing or invalid journal: start a fresh session. */ }
    }
    private static YtDlpMedia.Choice findChoice(YtDlpMedia media, String selector) {
        for (List<YtDlpMedia.Choice> list : Arrays.asList(media.videos, media.audios))
            for (YtDlpMedia.Choice choice : list) if (choice.selector.equals(selector)) return choice;
        return null;
    }
    @Override public void onCreate() {
        super.onCreate(); restore(this);
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "视频下载", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (CANCEL.equals(action) || PAUSE.equals(action)) {
            if (state.busy()) {
                stopReason = action; state.phase = Phase.STOPPING;
                state.message = PAUSE.equals(action) ? "正在暂停…" : "正在取消…"; publish();
                final String id = processId;
                Thread thread = runningThread; if (thread != null) thread.interrupt();
                if (id != null) new Thread(() -> YoutubeDL.getInstance().destroyProcessById(id), "yt-dlp-stop").start();
            } else {
                cleanPending(); state.phase = Phase.IDLE; state.message = "已取消；已下载文件保留";
                completedOperation();
            }
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION, notification());
        if (state.busy()) return START_NOT_STICKY;
        if (RETRY_RESOLVE.equals(action)) {
            state.phase = Phase.RESOLVING; state.message = "正在重试未完成条目…";
            for (Job job : state.jobs) if (job.path == null && job.work == null)
                job.work = new File(getFilesDir(), "media-downloads/" + UUID.randomUUID());
        } else if (RESOLVE.equals(action)) {
            List<String> urls;
            try { urls = YtDlpMedia.normalizeUrls(intent.getStringExtra("url")); }
            catch (Exception error) { fail(error); return START_NOT_STICKY; }
            cleanPending(); state.jobs.clear(); state.url = android.text.TextUtils.join("\n", urls);
            for (String url : urls) state.jobs.add(new Job(url, new File(getFilesDir(), "media-downloads/" + UUID.randomUUID())));
            state.phase = Phase.RESOLVING; state.message = "正在逐个解析链接…";
        } else if (DOWNLOAD.equals(action)) {
            boolean pending = false;
            for (Job job : state.jobs) if (job.path == null && job.media != null && job.choice != null) {
                pending = true;
                if (job.work == null) job.work = new File(getFilesDir(), "media-downloads/" + UUID.randomUUID());
            }
            if (!pending) { fail(new IOException("没有可下载的条目，请先解析并选择画质")); return START_NOT_STICKY; }
            state.phase = Phase.DOWNLOADING; state.message = "正在下载本批视频…";
        } else if (UPDATE.equals(action)) {
            state.phase = Phase.UPDATING; state.message = "正在更新下载引擎…";
        } else { finishForeground(); return START_NOT_STICKY; }
        stopReason = null; state.progress = -1; state.transfer = null;
        final Phase operation = state.phase;
        final List<Job> jobs = new ArrayList<>();
        for (Job job : state.jobs) if (!RETRY_RESOLVE.equals(action) || (job.path == null && (job.media == null || !job.error.isEmpty()))) jobs.add(job);
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CleanRecovery:yt-dlp");
        wakeLock.acquire(6 * 60 * 60 * 1000L); publish(); persist();
        worker.execute(() -> run(operation, jobs));
        return START_NOT_STICKY;
    }
    private void run(Phase operation, List<Job> jobs) {
        runningThread = Thread.currentThread();
        try {
            checkStopped();
            if (operation == Phase.UPDATING) {
                YtDlpEngine.update(this);
                main.post(() -> { if (finishStopped()) return; state.phase = state.jobs.isEmpty() ? Phase.IDLE : Phase.READY; state.message = "下载引擎已更新，请重新解析"; completedOperation(); });
                return;
            }
            for (int index = 0; index < jobs.size(); index++) {
                Job job = jobs.get(index);
                final File jobWork = job.work;
                if (operation == Phase.DOWNLOADING && (job.path != null || job.choice == null)) continue;
                checkStopped(); processId = UUID.randomUUID().toString();
                final String prefix = "(" + (index + 1) + "/" + jobs.size() + ") ";
                main.post(() -> { if (state.phase == operation) { state.message = prefix + (operation == Phase.RESOLVING ? "正在解析…" : "正在下载…"); state.progress = -1; state.transfer = null; publish(); } });
                try {
                    if (operation == Phase.RESOLVING) {
                        String previousSelector = job.choice == null ? null : job.choice.selector;
                        YtDlpMedia media = YtDlpEngine.resolve(this, job.url, jobWork, processId);
                        main.post(() -> {
                            job.media = media; job.error = "";
                            job.choice = previousSelector == null ? null : findChoice(media, previousSelector);
                            if (job.choice == null) job.choice = !media.videos.isEmpty() ? media.videos.get(0) : media.audios.get(0);
                            persist(); publish();
                        });
                    } else {
                        File source = YtDlpEngine.download(this, job.url, job.choice, jobWork, processId, (percent, eta, line) -> main.post(() -> {
                            if (state.phase != Phase.DOWNLOADING) return;
                            YtDlpTransferProgress transfer = YtDlpTransferProgress.parse(line);
                            if (transfer != null) {
                                state.transfer = transfer; state.progress = transfer.percent();
                                state.message = prefix + (transfer.audio ? "正在下载音频…" : "正在下载视频…");
                            } else if (line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]")) {
                                state.message = prefix + "正在合并并处理文件…";
                            }
                            publish();
                        }));
                        checkStopped();
                        File saved = save(source);
                        MediaScannerConnection.scanFile(this, new String[]{saved.getAbsolutePath()}, null, null);
                        main.post(() -> { job.path = saved.getAbsolutePath(); job.error = ""; clean(job); persist(); publish(); });
                    }
                } catch (Exception | LinkageError error) {
                    checkStopped();
                    String detail = errorText(error);
                    main.post(() -> { job.error = detail; persist(); publish(); });
                } finally {
                    if (jobWork != null) new File(jobWork, "cookies.txt").delete();
                }
            }
            main.post(() -> {
                if (finishStopped()) return;
                int success = 0, failures = 0;
                for (Job job : state.jobs) {
                    if (operation == Phase.RESOLVING ? job.media != null : job.path != null) success++;
                    if (!job.error.isEmpty()) failures++;
                }
                state.phase = operation == Phase.RESOLVING ? Phase.READY : Phase.COMPLETE;
                state.message = (operation == Phase.RESOLVING ? "解析完成" : "本批下载结束") + "：成功 " + success + "，失败 " + failures;
                state.progress = -1; completedOperation();
            });
        } catch (Exception | LinkageError error) {
            main.post(() -> { if (!finishStopped()) fail(error); });
        } finally { runningThread = null; Thread.interrupted(); }
    }
    private void checkStopped() throws InterruptedException {
        if (stopReason != null || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
    private boolean finishStopped() {
        if (stopReason == null) return false;
        boolean paused = PAUSE.equals(stopReason);
        state.phase = paused ? Phase.PAUSED : Phase.IDLE;
        state.message = paused ? "已暂停，可继续本批下载" : "已取消；已下载文件保留";
        if (!paused) cleanPending();
        completedOperation(); return true;
    }
    private static String errorText(Throwable error) {
        String detail = error.getMessage(); if (detail == null || detail.isEmpty()) detail = error.getClass().getSimpleName();
        detail = detail.replaceAll("https?://\\S+", "[链接]");
        int at = detail.lastIndexOf("ERROR:"); if (at >= 0) detail = detail.substring(at + 6).trim();
        if (detail.contains("412")) return "站点拒绝请求（HTTP 412），请在应用浏览器打开链接后重新解析";
        if (detail.toLowerCase(Locale.ROOT).contains("cookies") || detail.contains("Sign in"))
            return "需要有效浏览器会话，请打开原链接完成登录或验证后重新解析";
        return detail.substring(0, Math.min(detail.length(), 1000));
    }
    private void fail(Throwable error) { state.phase = Phase.ERROR; state.message = errorText(error); completedOperation(); }
    private File save(File source) throws IOException {
        File dir = new BrowserPrefs(this).downloadDirFile();
        if (dir == null) dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CleanRecovery");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建保存目录，请检查存储权限");
        File target = new File(dir, source.getName()); String name = source.getName(); int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; !target.createNewFile(); i++) target = new File(dir, stem + " (" + i + ")" + ext);
        boolean success = false;
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[256 * 1024]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (stopReason != null || Thread.currentThread().isInterrupted()) throw new IOException("保存已中止");
                out.write(buffer, 0, n);
            }
            out.flush(); success = true;
        } finally { if (!success) target.delete(); }
        return target;
    }
    private void cleanPending() {
        for (Job job : state.jobs) { clean(job); if (job.path == null) { job.media = null; job.choice = null; } }
    }
    private void clean(Job job) { if (job.work != null) { delete(job.work); job.work = null; } }
    private static void delete(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); file.delete(); }
    private void completedOperation() {
        for (Job job : state.jobs) if (job.work != null) new File(job.work, "cookies.txt").delete();
        persist(); publish(); finishForeground();
        if (state.phase == Phase.COMPLETE) {
            Notification notice = new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download)
                    .setContentTitle("本批下载结束").setContentText(state.message).setAutoCancel(true).setContentIntent(openIntent()).build();
            try { getSystemService(NotificationManager.class).notify(NOTIFICATION + 1, notice); } catch (SecurityException ignored) { }
        }
    }
    private void persist() {
        AtomicFile file = new AtomicFile(new File(getFilesDir(), "media-download-state.json")); FileOutputStream output = null;
        try {
            JSONObject json = new JSONObject().put("url", state.url).put("phase", state.phase.name()).put("message", state.message);
            JSONArray jobs = new JSONArray();
            for (Job job : state.jobs) {
                JSONObject item = new JSONObject().put("url", job.url).put("error", job.error).put("path", job.path);
                if (job.work != null) item.put("work", job.work.getName());
                if (job.media != null) item.put("media", job.media.toJson());
                if (job.choice != null) item.put("choice", job.choice.selector);
                jobs.put(item);
            }
            json.put("jobs", jobs); output = file.startWrite(); output.write(json.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(output);
        } catch (Exception ignored) { if (output != null) file.failWrite(output); }
    }
    private void finishForeground() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); stopForeground(true); stopSelf(); }
    private void publish() {
        if (listener != null) listener.changed();
        if (state.busy() && SystemClock.elapsedRealtime() - lastNotification > 1000) {
            lastNotification = SystemClock.elapsedRealtime(); getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
        }
    }
    private PendingIntent openIntent() { return PendingIntent.getActivity(this, 0, new Intent(this, UniversalDownloadActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT); }
    private Notification notification() {
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, YtDlpDownloadService.class).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download).setContentTitle("视频下载")
                .setContentText(state.message).setContentIntent(openIntent()).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, state.progress), state.progress < 0).addAction(0, "取消", cancel).build();
    }
    @Override public void onTimeout(int startId, int fgsType) { stopReason = PAUSE; Thread thread = runningThread; if (thread != null) thread.interrupt(); finishForeground(); }
    @Override public void onDestroy() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); worker.shutdownNow(); super.onDestroy(); }
}
