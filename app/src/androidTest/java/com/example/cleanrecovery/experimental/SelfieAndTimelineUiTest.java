package com.example.cleanrecovery.experimental;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SelfieAndTimelineUiTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private Context context; private boolean tilt;
    @Before public void before() {
        context=instrument.getTargetContext(); tilt=new TiltGlassPrefs(context).enabled(); new TiltGlassPrefs(context).setEnabled(false);
    }
    @After public void after() { new TiltGlassPrefs(context).setEnabled(tilt); }
    private <T> T main(Callable<T> action) throws Exception { FutureTask<T> t=new FutureTask<>(action); instrument.runOnMainSync(t); return t.get(); }
    private Object field(Object o,String key) throws Exception { java.lang.reflect.Field f=o.getClass().getDeclaredField(key); f.setAccessible(true); return f.get(o); }
    private void set(Object o,String key,Object value) throws Exception { java.lang.reflect.Field f=o.getClass().getDeclaredField(key); f.setAccessible(true); f.set(o,value); }
    private void snapshot(String name) throws Exception {
        SystemClock.sleep(400); Bitmap b=instrument.getUiAutomation().takeScreenshot(); assertNotNull(b);
        try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),name+".png"))) { b.compress(Bitmap.CompressFormat.PNG,100,out); } b.recycle();
    }
    @Test public void bundledDetectorFindsPortraitAndRejectsBlankWithoutNetwork() throws Exception {
        FaceDetector detector=FaceDetection.getClient(new FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build());
        Bitmap portrait=BitmapFactory.decodeFile(new File(context.getExternalFilesDir(null),"selfie-test-portrait.jpg").getPath());
        assertNotNull("Push the public MediaPipe portrait fixture before this test",portrait);
        Bitmap blank=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888); blank.eraseColor(Color.WHITE);
        try {
            assertFalse(Tasks.await(detector.process(InputImage.fromBitmap(portrait,0)),20,TimeUnit.SECONDS).isEmpty());
            assertTrue(Tasks.await(detector.process(InputImage.fromBitmap(blank,0)),10,TimeUnit.SECONDS).isEmpty());
        } finally { detector.close(); portrait.recycle(); blank.recycle(); }
    }
    @Test public void timelineDrawsLegacyRecordsAndClearsToEmpty() throws Exception {
        Activity activity=instrument.startActivitySync(new Intent(context,ExperimentalLabActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            WatchTimelineView view=main(() -> {
                WatchTimelineView v=new WatchTimelineView(activity); ScrollView scroll=new ScrollView(activity); scroll.setFitsSystemWindows(true); scroll.addView(v); activity.setContentView(scroll);
                JSONArray events=new JSONArray(); String[] messages={"守护已停止","恢复静止 · 运动信号持续约 4.2 秒","环境光明显变亮","检测到移动或转动","开始守护"};
                for(int i=0;i<messages.length;i++) events.put(new JSONObject().put("time",System.currentTimeMillis()-i*60000L).put("message",messages[i]));
                v.setEvents(events); assertEquals(9,v.getChildCount()); return v;
            });
            snapshot("watch-graph");
            main(() -> { view.setEvents(new JSONArray()); assertEquals(1,view.getChildCount()); return null; });
        } finally { main(() -> { activity.finish(); return null; }); }
    }
    @Test public void textExportWritesUtf8Snapshot() throws Exception {
        ExperimentalLabActivity activity=(ExperimentalLabActivity)instrument.startActivitySync(new Intent(context,ExperimentalLabActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        File destination=File.createTempFile("watch-export-",".txt",context.getCacheDir());
        String expected="09-18 10:00:00  检测到移动或转动\n\n09-18 09:59:00  开始守护";
        try {
            main(() -> {
                set(activity,"exportSnapshot",expected);
                java.lang.reflect.Method method=ExperimentalLabActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);
                method.setAccessible(true); method.invoke(activity,74,Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(destination))); return null;
            });
            try(FileInputStream in=new FileInputStream(destination); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] bytes=new byte[512]; int n; while((n=in.read(bytes))!=-1) out.write(bytes,0,n);
                assertEquals(expected,out.toString("UTF-8"));
            }
        } finally { destination.delete(); main(() -> { activity.finish(); return null; }); }
    }
    private RearSelfieActivity open() throws Exception {
        instrument.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.CAMERA);
        RearSelfieActivity activity=(RearSelfieActivity)instrument.startActivitySync(new Intent(context,RearSelfieActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> { ((TextView)field(activity,"start")).performClick(); return null; });
        for(int i=0;i<80;i++) { if(main(() -> field(activity,"camera"))!=null && (Long)main(() -> field(activity,"lastResult"))>0) return activity; SystemClock.sleep(100); }
        String status=main(() -> ((TextView)field(activity,"status")).getText().toString());
        main(() -> { activity.finish(); return null; }); fail(status); return activity;
    }
    @Test public void cameraOpensAndReleasesOnExit() throws Exception {
        RearSelfieActivity activity=open();
        try { snapshot("rear-selfie-camera"); }
        finally { main(() -> { activity.finish(); return null; }); }
        instrument.waitForIdleSync();
        assertNull(main(() -> field(activity,"camera")));
        assertEquals(false,main(() -> field(activity,"armed")));
    }
    @Test public void imageCaptureProducesJpegSeparateFromPreview() throws Exception {
        // Exercise the real camera output with a controlled shutter gate; not face-guidance accuracy.
        SharedPreferences p=context.getSharedPreferences("experimental.RearSelfieActivity",0);
        String previous=p.getString("last_photo",null);
        RearSelfieActivity activity=open(); File result=null;
        try {
            main(() -> { set(activity,"counting",true); set(activity,"lastResult",SystemClock.elapsedRealtime());
                ((Runnable)field(activity,"shutter")).run(); return null; });
            for(int i=0;i<120;i++) { if(!(Boolean)main(() -> field(activity,"capturing"))) break; SystemClock.sleep(100); }
            result=(File)main(() -> field(activity,"lastPhoto")); assertNotNull(result); assertTrue(result.length()>1000);
            BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true; BitmapFactory.decodeFile(result.getPath(),options);
            assertTrue(options.outWidth>=640); assertTrue(options.outHeight>=480);
            System.out.println("Captured JPEG: "+options.outWidth+"x"+options.outHeight+", bytes="+result.length());
            snapshot("rear-selfie-captured");
        } finally {
            main(() -> { activity.finish(); return null; });
            if(result!=null && !result.getName().equals(previous)) result.delete();
            if(previous==null) p.edit().remove("last_photo").commit(); else p.edit().putString("last_photo",previous).commit();
        }
    }
}
