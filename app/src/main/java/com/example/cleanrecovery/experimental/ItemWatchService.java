package com.example.cleanrecovery.experimental;

import android.app.*;
import android.content.Intent;
import android.hardware.*;
import android.os.*;
import com.example.cleanrecovery.R;
import java.util.Locale;

/** Explicit user-started foreground session. No automatic restart after process death. */
public final class ItemWatchService extends Service implements SensorEventListener {
    public static volatile boolean running;
    public static volatile String status = "未开启守护";
    public static volatile float strength;
    private SensorManager sensors;
    private PowerManager.WakeLock wakeLock;
    private MotionDetector detector;
    private boolean armed;
    private float lastLux = -1;
    private long lightAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> { log("守护已达 8 小时，自动停止"); stopSelf(); };

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        try {
            String channel = "experimental_item_watch";
            if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class)
                    .createNotificationChannel(new NotificationChannel(channel, "物品守护", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ExperimentalLabActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ItemWatchService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel) : new Notification.Builder(this);
            startForeground(7301, builder.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("物品守护正在运行")
                    .setContentText("记录移动与光线变化 · 点击查看 · 最长 8 小时")
                    .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "停止守护", stop).build()).build());
            sensors = getSystemService(SensorManager.class);
            Sensor accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer == null) throw new IllegalStateException("本机没有加速度计");
            detector = new MotionDetector(); armed = false; lastLux = -1;
            if (!sensors.registerListener(this, accelerometer, 20000)) throw new IllegalStateException("无法读取加速度计");
            Sensor light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (light != null) sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CleanRecovery:ItemWatch");
            wakeLock.acquire(8*60*60*1000L);
            handler.postDelayed(timeout, 8*60*60*1000L);
            running = true; status = "请放好手机，保持静止 3 秒";
            log("开始守护" + (light == null ? "（无光线传感器）" : ""));
        } catch (Exception e) { status = "无法开始：" + e.getMessage(); log(status); stopSelf(); }
        return START_NOT_STICKY;
    }
    @Override public void onSensorChanged(SensorEvent e) {
        long ms = e.timestamp/1000000;
        if (e.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lux = e.values[0];
            if (armed && lastLux >= 0 && lux > Math.max(20, lastLux*3) && ms-lightAt > 5000) {
                log("环境光明显变亮（可能打开或移出遮挡）"); lightAt = ms;
            }
            lastLux = lux; return;
        }
        MotionDetector.Event event = detector.sample(ms, e.values[0], e.values[1], e.values[2]);
        strength = (float)detector.intensity;
        switch (event) {
            case ARMED: armed = true; status = "守护中 · 物品静止"; log("已就绪，开始记录"); break;
            case MOVED: status = "检测到移动或转动"; log(status); break;
            case SETTLED: status = "守护中 · 已恢复静止";
                log(String.format(Locale.CHINA, "恢复静止 · 运动信号持续约 %.1f 秒", detector.durationMs/1000.0)); break;
            default: break;
        }
    }
    private void log(String message) { LabHistory.add(this, message); }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (sensors != null) sensors.unregisterListener(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (running) { log("守护已停止"); status = "未开启守护"; }
        running = false; strength = 0;
        stopForeground(true); super.onDestroy();
    }
}
