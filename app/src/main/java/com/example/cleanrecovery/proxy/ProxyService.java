package com.example.cleanrecovery.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 代理前台服务：管理 Mihomo/兼容 SS 本地代理生命周期。
 *
 * <p>启动时根据传入节点启动本地 SOCKS5（+可选 HTTP）代理，通知栏显示状态与端口；
 * 停止时关闭所有本地服务。{@link ProxyEngine#current()} 暴露运行时状态供 UI/路由读取。</p>
 *
 * <p>不使用 VpnService：SS 本地代理方案默认走本地 SOCKS5/HTTP + WebView ProxyController，
 * 仅需常规 INTERNET/前台权限。</p>
 */
public final class ProxyService extends Service {

    private static final String TAG = "ProxyService";
    private static final String CHANNEL_ID = "proxy_channel";
    private static final int NOTI_ID = 2001;

    public static final String ACTION_START = "cleanrecovery.proxy.START";
    public static final String ACTION_STOP = "cleanrecovery.proxy.STOP";
    public static final String EXTRA_NODE = "node_json";

    private ProxyEngine engine;
    private NotificationManager nm;
    private ExecutorService worker;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        worker = Executors.newSingleThreadExecutor();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopEngine();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // START
        // FGS 要求：start-foreground-service 后必须先 startForeground，再做任何判断/退出，
        // 否则系统抛 ForegroundServiceDidNotStartInTimeException。故先以占位通知提升前台。
        startForeground(NOTI_ID, buildNotification("启动中…", null));
        String nodeJson = intent != null ? intent.getStringExtra(EXTRA_NODE) : null;
        ProxyNode node = ProxyServiceExtras.fromJson(nodeJson);
        if (node == null || !node.isValid()) {
            Log.w(TAG, "invalid node, stop");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        worker.execute(() -> startEngine(node));
        return START_STICKY;
    }

    private void startEngine(ProxyNode node) {
        try {
            stopEngine();
            engine = new ProxyEngine(this, node, new ProxyPrefs(this).loadNodes());
            int socks5Port = engine.start(true);
            nm.notify(NOTI_ID, buildNotification(
                    engine.engineName() + " 运行中 SOCKS5:" + socks5Port
                            + " HTTP:" + engine.httpPort(), node));
            Log.i(TAG, "engine=" + engine.engineName()
                    + " started, socks5=" + socks5Port + " http=" + engine.httpPort());
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            nm.notify(NOTI_ID, buildNotification("启动失败: " + e.getMessage(), node));
            stopEngine();
            stopForeground(true);
            stopSelf();
        }
    }

    private synchronized void stopEngine() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
    }

    @Override
    public void onDestroy() {
        stopEngine();
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "代理服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mihomo 本地代理运行状态");
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.enableLights(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text, ProxyNode node) {
        Intent open = new Intent(this, ProxyActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = node != null ? "代理：" + node.display() : "代理服务";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
