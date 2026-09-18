package com.example.cleanrecovery.experimental;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.*;
import android.net.Uri;
import android.os.*;
import android.speech.tts.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-person, on-device rear-camera guidance. Capture requires an explicit armed session. */
public final class RearSelfieActivity extends ComponentActivity implements SensorEventListener {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean analyzing=new AtomicBoolean();
    private final SelfieGuide guide=new SelfieGuide();
    private PreviewView preview;
    private TextView status,start,share;
    private ProcessCameraProvider provider;
    private androidx.camera.core.Camera camera;
    private ImageCapture capture;
    private ImageAnalysis analysis;
    private FaceDetector detector;
    private TextToSpeech speech;
    private boolean speechReady,voiceEnabled=true,visible,armed,capturing,counting;
    private long nextAnalysis,lastFeedback,lastResult,lastGyro;
    private int session;
    private volatile float angularSpeed;
    private SensorManager sensors;
    private File lastPhoto;
    private String spoken="";
    private long lastFocus;
    private boolean focused,readyNotified;
    private int focusEpoch;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean night=new BrowserPrefs(this).nightMode();
        getWindow().setStatusBarColor(night?Color.BLACK:Color.WHITE);
        getWindow().setNavigationBarColor(night?Color.BLACK:Color.WHITE);
        androidx.core.view.WindowInsetsControllerCompat bars=new androidx.core.view.WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!night); bars.setAppearanceLightNavigationBars(!night);
        LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setFitsSystemWindows(true);
        root.setBackgroundColor(night?Color.BLACK:Color.WHITE);
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back=new ImageView(this); back.setImageResource(R.drawable.ic_chevron_left);
        back.setColorFilter(ViaUi.textColor(this,ViaUi.TEXT)); back.setContentDescription("返回"); back.setPadding(dp(14),dp(14),dp(14),dp(14)); back.setOnClickListener(v -> finish());
        bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=label("后置自拍助手",17); bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(bar);
        preview=new PreviewView(this); preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER); preview.setContentDescription("后置相机预览");
        root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        status=label("内置离线找脸 · 单人正面自拍",15); root.addView(status);
        TextView hint=label("后置镜头朝向自己；根据语音调整，稳定后倒计时拍摄。",12); root.addView(hint);
        start=button("开始引导",() -> begin()); root.addView(start);
        TextView voice=button("语音引导：开启",() -> {});
        voice.setOnClickListener(v -> { voiceEnabled=!voiceEnabled; if(!voiceEnabled && speech!=null) speech.stop(); voice.setText("语音引导："+(voiceEnabled?"开启":"关闭")); }); root.addView(voice);
        share=button("查看 / 导出上一张照片",this::photoActions); share.setVisibility(View.GONE); root.addView(share);
        setContentView(root);
        String saved=getPreferences(0).getString("last_photo",null);
        if(saved!=null) { File file=new File(getFilesDir(),"selfies/"+new File(saved).getName()); if(file.isFile()) { lastPhoto=file; share.setVisibility(View.VISIBLE); } }
        sensors=getSystemService(SensorManager.class);
        speech=new TextToSpeech(this,result -> {
            if(isDestroyed() || result!=TextToSpeech.SUCCESS) return;
            speech.setLanguage(Locale.SIMPLIFIED_CHINESE);
            Set<Voice> voices=speech.getVoices();
            if(voices!=null) for(Voice v:voices) if(!v.isNetworkConnectionRequired() && "zh".equals(v.getLocale().getLanguage())) {
                speech.setVoice(v); speechReady=true; break;
            }
            if(!speechReady && visible && !armed) status.setText("离线语音不可用；仍可使用屏幕提示和震动");
        });
    }
    private int dp(int v) { return ViaUi.dp(this,v); }
    private TextView label(String value,int size) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(ViaUi.textColor(this,ViaUi.TEXT));
        t.setPadding(dp(20),dp(8),dp(20),dp(8)); return t;
    }
    private TextView button(String text,Runnable action) {
        TextView t=label(text,14); t.setTextColor(ViaUi.ACCENT); t.setGravity(Gravity.CENTER); t.setMinHeight(dp(48));
        t.setBackgroundResource(R.drawable.bg_via_menu_cell); t.setFocusable(true); t.setOnClickListener(v -> action.run()); return t;
    }
    private void begin() {
        if(armed) { stopGuidance(); status.setText("已暂停引导"); return; }
        if(capturing) return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},81); return;
        }
        armed=true; guide.reset(); focused=false; readyNotified=false; lastFocus=0; lastFeedback=0; spoken="";
        start.setText("暂停引导"); status.setText("正在开启后置相机…");
        if(camera==null) openCamera(); else feedback("后置镜头朝向自己，请将手机保持竖直",true);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==81) {
            if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) {
                status.setText("相机权限已允许，点击开始引导");
            } else status.setText("相机权限未允许；可在系统应用设置中开启后重试");
        }
    }
    private void openCamera() {
        final int generation=++session;
        com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if(!visible || generation!=session) return;
            try {
                provider=future.get();
                if(!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) throw new IllegalStateException("没有可用的后置摄像头");
                detector=FaceDetection.getClient(new FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL).enableTracking().build());
                Preview surface=new Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build();
                surface.setSurfaceProvider(preview.getSurfaceProvider());
                int rotation=preview.getDisplay().getRotation();
                capture=new ImageCapture.Builder().setTargetRotation(rotation).setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(95).build();
                analysis=new ImageAnalysis.Builder().setTargetRotation(rotation).setTargetResolution(new android.util.Size(640,480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                FaceDetector activeDetector=detector;
                analysis.setAnalyzer(worker,image -> analyze(image,activeDetector,generation));
                camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,surface,capture,analysis);
                feedback("后置镜头朝向自己，请将手机保持竖直",true);
            } catch(Exception e) { closeCamera(); stopGuidance(); status.setText("相机无法开启，请重试："+e.getMessage()); }
        },ContextCompat.getMainExecutor(this));
    }
    @androidx.camera.core.ExperimentalGetImage
    private void analyze(ImageProxy frame,FaceDetector model,int generation) {
        long now=SystemClock.elapsedRealtime();
        if(now<nextAnalysis || !analyzing.compareAndSet(false,true)) { frame.close(); return; }
        nextAnalysis=now+150;
        android.media.Image media=frame.getImage();
        if(media==null) { frame.close(); analyzing.set(false); return; }
        int rotation=frame.getImageInfo().getRotationDegrees();
        int width=rotation%180==0?frame.getWidth():frame.getHeight(),height=rotation%180==0?frame.getHeight():frame.getWidth();
        try {
            model.process(InputImage.fromMediaImage(media,rotation))
                .addOnSuccessListener(ContextCompat.getMainExecutor(this),faces -> {
                    if(visible && generation==session && armed && !capturing) update(faces,width,height);
                }).addOnFailureListener(ContextCompat.getMainExecutor(this),error -> {
                    if(visible && generation==session) { stopGuidance(); status.setText("离线识别暂不可用，点击开始重试"); }
                }).addOnCompleteListener(task -> { frame.close(); analyzing.set(false); });
        } catch(RuntimeException e) { frame.close(); analyzing.set(false); }
    }
    private void update(List<Face> faces,int width,int height) {
        long now=SystemClock.elapsedRealtime(); lastResult=now;
        Face face=faces.size()==1?faces.get(0):null;
        Rect box=face==null?new Rect():face.getBoundingBox();
        String result=guide.evaluate(faces.size(),box.left/(float)width,box.top/(float)height,box.right/(float)width,box.bottom/(float)height,
                face==null?0:face.getHeadEulerAngleY(),face==null?0:face.getHeadEulerAngleX(),face==null?0:face.getHeadEulerAngleZ(),
                face==null?null:face.getLeftEyeOpenProbability(),face==null?null:face.getRightEyeOpenProbability(),
                now-lastGyro<500 && angularSpeed>.12f,now);
        if(!"构图就绪".equals(result)) {
            if(counting) { counting=false; handler.removeCallbacks(shutter); }
            focused=false; focusEpoch++; status.setText(result); feedback(result,false); return;
        }
        if(!focused) {
            status.setText("构图就绪，正在对焦");
            if(now-lastFocus>1800) {
                lastFocus=now; int generation=session,focusToken=focusEpoch;
                MeteringPoint point=new SurfaceOrientedMeteringPointFactory(1,1).createPoint(box.exactCenterX()/width,box.exactCenterY()/height);
                com.google.common.util.concurrent.ListenableFuture<FocusMeteringResult> focus=camera.getCameraControl().startFocusAndMetering(
                        new FocusMeteringAction.Builder(point,FocusMeteringAction.FLAG_AF|FocusMeteringAction.FLAG_AE).setAutoCancelDuration(3,TimeUnit.SECONDS).build());
                focus.addListener(() -> { if(visible && armed && generation==session && focusToken==focusEpoch) {
                    try { focus.get(); focused=true; } catch(Exception ignored) { status.setText("对焦失败，请调整距离"); }
                } },ContextCompat.getMainExecutor(this));
            }
            return;
        }
        if(!counting) { counting=true; feedback("构图就绪，保持不动，即将拍摄",true); if(!readyNotified) { vibrate(70); readyNotified=true; } handler.postDelayed(shutter,1800); }
        status.setText("构图就绪 · 保持不动，即将拍摄");
    }
    private final Runnable shutter=() -> {
        if(!visible || !armed || !counting || capturing || SystemClock.elapsedRealtime()-lastResult>600) { counting=false; return; }
        counting=false; armed=false; capturing=true; start.setEnabled(false); status.setText("正在拍摄…");
        if(speech!=null) speech.stop();
        File dir=new File(getFilesDir(),"selfies");
        if(!dir.isDirectory() && !dir.mkdirs()) { captureFailed(); return; }
        File photo=new File(dir,"selfie-"+System.currentTimeMillis()+".jpg");
        capture.takePicture(new ImageCapture.OutputFileOptions.Builder(photo).build(),ContextCompat.getMainExecutor(this),new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(ImageCapture.OutputFileResults result) {
                lastPhoto=photo; getPreferences(0).edit().putString("last_photo",photo.getName()).apply();
                capturing=false; start.setEnabled(true); start.setText("再拍一张"); share.setVisibility(View.VISIBLE);
                status.setText("照片已保存在应用内 · 可查看或导出");
                if(visible) { feedback("拍摄完成",true); vibrate(100); }
            }
            @Override public void onError(ImageCaptureException error) { photo.delete(); captureFailed(); }
        });
    };
    private void captureFailed() { capturing=false; start.setEnabled(true); start.setText("重新开始引导"); status.setText("拍摄失败，请重试"); }
    private void feedback(String message,boolean force) {
        if(!visible) return;
        long now=SystemClock.elapsedRealtime();
        if(!force && (now-lastFeedback<3000 || message.equals(spoken))) return;
        lastFeedback=now; spoken=message;
        if(speechReady && voiceEnabled && speech!=null) speech.speak(message,TextToSpeech.QUEUE_FLUSH,null,"selfie-guide");
        if(!force && !counting) vibrate(message.startsWith("未找到")?120:30);
    }
    private void vibrate(int duration) {
        Vibrator vibrator=(Vibrator)getSystemService(VIBRATOR_SERVICE);
        if(vibrator!=null && vibrator.hasVibrator()) {
            if(Build.VERSION.SDK_INT>=26) vibrator.vibrate(VibrationEffect.createOneShot(duration,VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(duration);
        }
    }
    private Uri photoUri() { return FileProvider.getUriForFile(this,getPackageName()+".fileprovider",lastPhoto); }
    private void photoActions() {
        if(lastPhoto==null || !lastPhoto.isFile()) return;
        ViaUi.listDialog(this,"照片",new String[]{"查看照片","分享照片","保存到所选位置"},choice -> {
            if(choice==2) startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/jpeg").putExtra(Intent.EXTRA_TITLE,lastPhoto.getName()),82);
            else try {
                Intent intent=choice==0?new Intent(Intent.ACTION_VIEW).setDataAndType(photoUri(),"image/jpeg")
                        :new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM,photoUri());
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent,choice==0?"查看照片":"分享照片"));
            } catch(ActivityNotFoundException e) { status.setText("没有可用的图片应用，请选择保存照片"); }
        }).show();
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if(code==82 && result==RESULT_OK && data!=null && data.getData()!=null && lastPhoto!=null) {
            Uri destination=data.getData(); File source=lastPhoto;
            worker.execute(() -> {
                try(InputStream in=new FileInputStream(source); OutputStream out=getContentResolver().openOutputStream(destination)) {
                    if(out==null) throw new IOException(); byte[] buffer=new byte[32768]; int n;
                    while((n=in.read(buffer))!=-1) out.write(buffer,0,n);
                    handler.post(() -> { if(!isDestroyed()) status.setText("照片已导出"); });
                } catch(Exception e) { handler.post(() -> { if(!isDestroyed()) status.setText("导出失败，请重试"); }); }
            });
        }
    }
    private void stopGuidance() { armed=false; counting=false; guide.reset(); handler.removeCallbacks(shutter); if(speech!=null) speech.stop(); start.setText("开始引导"); }
    private void closeCamera() {
        session++; if(analysis!=null) analysis.clearAnalyzer(); if(provider!=null) provider.unbindAll();
        if(detector!=null) detector.close(); detector=null; camera=null; capture=null;
    }
    @Override protected void onResume() { super.onResume(); visible=true; Sensor gyro=sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE); if(gyro!=null) sensors.registerListener(this,gyro,SensorManager.SENSOR_DELAY_GAME); }
    @Override protected void onPause() { visible=false; stopGuidance(); sensors.unregisterListener(this); closeCamera(); super.onPause(); }
    @Override protected void onDestroy() { if(speech!=null) speech.shutdown(); worker.shutdown(); super.onDestroy(); }
    @Override public void onSensorChanged(SensorEvent e) { angularSpeed=(float)Math.sqrt(e.values[0]*e.values[0]+e.values[1]*e.values[1]+e.values[2]*e.values[2]); lastGyro=SystemClock.elapsedRealtime(); }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) { }
}
