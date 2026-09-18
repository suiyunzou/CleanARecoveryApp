package com.example.cleanrecovery.experimental;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.*;
import android.os.*;
import android.view.Surface;
import android.view.OrientationEventListener;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.*;
import com.example.cleanrecovery.R;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.*;

/** Explicit, bounded camera+microphone foreground session; never restarted automatically. */
public final class WaveShutterService extends Service implements LifecycleOwner,SensorEventListener {
    public static volatile boolean running;
    public static volatile String status="未开启待命";
    public static volatile long deadline;
    public static volatile int shots;
    private final LifecycleRegistry lifecycle=new LifecycleRegistry(this);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService frames=Executors.newSingleThreadExecutor();
    private SensorManager sensors;
    private PowerManager.WakeLock wake;
    private ProcessCameraProvider provider;
    private ImageCapture capture;
    private ImageAnalysis drain;
    private AcousticEngine audio;
    private WaveTrigger trigger;
    private boolean alive,cameraReady,capturing,proximityKnown,near,accelKnown,wasBlocked=true;
    private long motionUntil,lastAcceleration,lastAudio,lastNotification;
    private double carrierBaseline;
    private final float[] gravity=new float[3];
    private int rotation=Surface.ROTATION_0,required;
    private OrientationEventListener orientation;
    private Notification.Builder notification;
    @Override public Lifecycle getLifecycle() { return lifecycle; }
    @Override public void onCreate() { super.onCreate(); lifecycle.setCurrentState(Lifecycle.State.CREATED); }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || "stop".equals(intent.getAction())) { finishSession("待命已停止"); return START_NOT_STICKY; }
        if(running) return START_NOT_STICKY;
        try {
            required=intent.getIntExtra("waves",3)==2?2:3;
            int minutes=intent.getIntExtra("minutes",3); if(minutes!=1 && minutes!=3 && minutes!=5) minutes=3;
            trigger=new WaveTrigger(required); shots=0; alive=true; running=true;
            deadline=SystemClock.elapsedRealtime()+minutes*60000L;
            String channel="wave_shutter";
            if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(channel,"挥手快门待命",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,WaveShutterActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,WaveShutterService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            notification=(Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this))
                    .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("挥手快门 · 相机与麦克风待命")
                    .setContentText("正在准备后置相机").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                    .addAction(new Notification.Action.Builder(null,"停止待命",stop).build());
            if(Build.VERSION.SDK_INT>=30) startForeground(7302,notification.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA|ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(7302,notification.build());
            sensors=getSystemService(SensorManager.class);
            Sensor proximity=sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY),acceleration=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if(proximity==null || acceleration==null) throw new IllegalStateException("需要距离和加速度传感器，无法可靠启用口袋保护");
            if(!sensors.registerListener(this,proximity,SensorManager.SENSOR_DELAY_NORMAL)
                    || !sensors.registerListener(this,acceleration,SensorManager.SENSOR_DELAY_GAME)) throw new IllegalStateException("防误触传感器不可用");
            Sensor gyro=sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE); if(gyro!=null) sensors.registerListener(this,gyro,SensorManager.SENSOR_DELAY_GAME);
            wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CleanRecovery:WaveShutter"); wake.acquire(minutes*60000L+10000);
            orientation=new OrientationEventListener(this) {
                @Override public void onOrientationChanged(int angle) {
                    if(angle==ORIENTATION_UNKNOWN) return;
                    rotation=angle>=315 || angle<45?Surface.ROTATION_0:angle<135?Surface.ROTATION_270:angle<225?Surface.ROTATION_180:Surface.ROTATION_90;
                }
            }; orientation.enable();
            lifecycle.setCurrentState(Lifecycle.State.STARTED);
            status="正在准备后置相机"; lastAudio=SystemClock.elapsedRealtime(); handler.post(watchdog);
            com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
            future.addListener(() -> {
                if(!alive) return;
                try {
                    provider=future.get();
                    if(!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) throw new IllegalStateException("没有后置摄像头");
                    capture=new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setJpegQuality(95).setTargetAspectRatio(AspectRatio.RATIO_4_3).build();
                    drain=new ImageAnalysis.Builder().setTargetResolution(new android.util.Size(320,240))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                    // Keep a repeating stream ready without retaining or analysing pictures.
                    drain.setAnalyzer(frames,ImageProxy::close);
                    androidx.camera.core.Camera camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,capture,drain);
                    camera.getCameraInfo().getCameraState().observe(this,state -> {
                        if(!alive) return;
                        if(state.getError()!=null) { finishSession("相机被占用或不可用，待命已停止"); return; }
                        cameraReady=state.getType()==CameraState.Type.OPEN;
                        if(cameraReady && audio==null) startAudio();
                    });
                } catch(Exception e) { finishSession("相机无法准备："+e.getMessage()); }
            },ContextCompat.getMainExecutor(this));
        } catch(Exception e) { finishSession("无法待命："+e.getMessage()); }
        return START_NOT_STICKY;
    }
    private void startAudio() {
        status="请保持静止，正在校准声波";
        audio=new AcousticEngine(this,new AcousticEngine.Listener() {
            @Override public void onResult(AcousticDetector.Result result) { long time=SystemClock.elapsedRealtime(); handler.post(() -> { if(alive && SystemClock.elapsedRealtime()-time<400) acceptAudio(result,time); }); }
            @Override public void onStopped(String reason) { handler.post(() -> { if(alive) finishSession(reason); }); }
        },240);
        audio.start();
    }
    private boolean sensorsSafe(long now) { return proximityKnown && !near && accelKnown && now-lastAcceleration<1200 && now>=motionUntil; }
    private void acceptAudio(AcousticDetector.Result result,long now) {
        lastAudio=now;
        boolean safe=sensorsSafe(now) && cameraReady && !capturing;
        if(!safe) {
            trigger.inhibit(now,1500); wasBlocked=true;
            if(capturing) return;
            status=!proximityKnown?"等待距离传感器":near?"遮挡保护 · 已清空挥手计数":!cameraReady?"等待相机就绪":"晃动保护 · 请保持手机稳定";
            return;
        }
        if(wasBlocked) { wasBlocked=false; carrierBaseline=0; trigger.inhibit(now,1500); audio.recalibrate(); status="遮挡或晃动已解除，重新校准"; return; }
        if(result.state==AcousticDetector.State.IDLE && result.energy<.04f && result.carrier>carrierBaseline) carrierBaseline=result.carrier;
        if(carrierBaseline>0 && result.carrier<carrierBaseline*.12) {
            trigger.inhibit(now,1500); status="声路被遮挡或音量过低 · 暂停触发"; return;
        }
        boolean fire=trigger.accept(result.state,result.energy,true,now);
        switch(result.state) {
            case CALIBRATING: status="正在校准 · 保持静止"; break;
            case WEAK: status="声波信号弱 · 暂停触发"; break;
            case NOISY: status="声波干扰较大 · 暂停触发"; break;
            default: status=trigger.count()==required?"挥手已完成，等待手移开":"待命中 · 完整挥动 "+trigger.count()+" / "+required+" 次";
        }
        if(fire) takePhoto(now);
    }
    private void takePhoto(long now) {
        if(!alive || capturing || !cameraReady || !sensorsSafe(now) || now-lastAudio>400 || now>=deadline) return;
        capturing=true; status="正在拍摄"; trigger.inhibit(now,3000);
        File directory=new File(getFilesDir(),"wave-photos");
        if(!directory.isDirectory() && !directory.mkdirs()) { finishSession("无法创建照片目录"); return; }
        File file=new File(directory,"wave-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,6)+".jpg");
        capture.setTargetRotation(rotation);
        capture.takePicture(new ImageCapture.OutputFileOptions.Builder(file).build(),ContextCompat.getMainExecutor(this),new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(ImageCapture.OutputFileResults output) {
                shots++; capturing=false;
                if(alive) {
                    trigger.inhibit(SystemClock.elapsedRealtime(),3000); status="拍摄成功 · 已拍 "+shots+" 张，冷却中";
                    Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);
                    if(v!=null && v.hasVibrator()) { if(Build.VERSION.SDK_INT>=26) v.vibrate(VibrationEffect.createOneShot(90,VibrationEffect.DEFAULT_AMPLITUDE)); else v.vibrate(90); }
                }
            }
            @Override public void onError(ImageCaptureException error) { file.delete(); capturing=false; if(alive) finishSession("拍摄失败，待命已停止："+error.getMessage()); }
        });
    }
    private final Runnable watchdog=new Runnable() {
        @Override public void run() {
            if(!alive) return;
            long now=SystemClock.elapsedRealtime();
            if(now>=deadline) { finishSession("待命时间已到 · 已拍 "+shots+" 张"); return; }
            if(now-lastAudio>12000) { finishSession("相机或麦克风未响应，待命已停止"); return; }
            if(now-lastNotification>1000) {
                notification.setContentText(status+" · 剩余 "+Math.max(0,(deadline-now)/1000)+" 秒");
                getSystemService(NotificationManager.class).notify(7302,notification.build()); lastNotification=now;
            }
            handler.postDelayed(this,500);
        }
    };
    @Override public void onSensorChanged(SensorEvent event) {
        long now=SystemClock.elapsedRealtime();
        if(event.sensor.getType()==Sensor.TYPE_PROXIMITY) {
            if(!Float.isFinite(event.values[0])) { proximityKnown=false; return; }
            proximityKnown=true; near=event.values[0]<Math.min(5,event.sensor.getMaximumRange());
            if(near) { trigger.inhibit(now,2000); wasBlocked=true; }
        } else if(event.sensor.getType()==Sensor.TYPE_ACCELEROMETER) {
            double delta=0;
            for(int i=0;i<3;i++) {
                if(!Float.isFinite(event.values[i])) { accelKnown=false; return; }
                delta+=Math.pow(event.values[i]-gravity[i],2);
                gravity[i]=accelKnown?gravity[i]*.85f+event.values[i]*.15f:event.values[i];
            }
            if(!accelKnown || Math.sqrt(delta)>1.2) motionUntil=now+1000;
            accelKnown=true; lastAcceleration=now;
        } else if(event.sensor.getType()==Sensor.TYPE_GYROSCOPE) {
            double speed=Math.sqrt(event.values[0]*event.values[0]+event.values[1]*event.values[1]+event.values[2]*event.values[2]);
            if(!Double.isFinite(speed) || speed>.35) motionUntil=now+1000;
        }
    }
    private void finishSession(String reason) { status=reason; alive=false; running=false; stopSelf(); }
    @Override public void onDestroy() {
        alive=false; running=false; handler.removeCallbacksAndMessages(null);
        if(audio!=null) audio.stop("待命已停止");
        if(sensors!=null) sensors.unregisterListener(this); if(orientation!=null) orientation.disable();
        lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
        if(provider!=null) { if(capture!=null) provider.unbind(capture); if(drain!=null) provider.unbind(drain); }
        if(drain!=null) drain.clearAnalyzer(); frames.shutdown();
        if(wake!=null && wake.isHeld()) wake.release(); stopForeground(true); super.onDestroy();
    }
    final class LocalBinder extends Binder { WaveShutterService service() { return WaveShutterService.this; } }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) { }
}
