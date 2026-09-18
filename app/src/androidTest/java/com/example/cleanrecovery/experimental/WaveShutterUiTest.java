package com.example.cleanrecovery.experimental;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.BitmapFactory;
import androidx.core.content.ContextCompat;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WaveShutterUiTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private Context context; private Activity activity; private WaveShutterService service;
    private ServiceConnection connection; private boolean tilt; private Set<String> originalFiles;
    @Before public void setup() throws Exception {
        context=instrument.getTargetContext(); tilt=new TiltGlassPrefs(context).enabled(); new TiltGlassPrefs(context).setEnabled(false);
        originalFiles=new HashSet<>(); File[] files=directory().listFiles(); if(files!=null) for(File f:files) originalFiles.add(f.getName());
        for(String permission:new String[]{android.Manifest.permission.CAMERA,android.Manifest.permission.RECORD_AUDIO,android.Manifest.permission.POST_NOTIFICATIONS})
            instrument.getUiAutomation().grantRuntimePermission(context.getPackageName(),permission);
        activity=instrument.startActivitySync(new Intent(context,WaveShutterActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CountDownLatch connected=new CountDownLatch(1);
        connection=new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n,IBinder binder) { service=((WaveShutterService.LocalBinder)binder).service(); connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName n) { }
        };
        main(() -> { ContextCompat.startForegroundService(activity,new Intent(activity,WaveShutterService.class).putExtra("minutes",1));
            context.bindService(new Intent(context,WaveShutterService.class),connection,0); return null; });
        assertTrue(connected.await(8,TimeUnit.SECONDS));
        for(int i=0;i<100;i++) {
            if(main(() -> (Boolean)field("cameraReady") && field("audio")!=null && (Boolean)field("proximityKnown") && (Boolean)field("accelKnown"))) break;
            if(!WaveShutterService.running) fail(WaveShutterService.status);
            SystemClock.sleep(100);
        }
        assertTrue(WaveShutterService.status,main(() -> (Boolean)field("cameraReady")));
        SystemClock.sleep(1600);
    }
    private File directory() { return new File(context.getFilesDir(),"wave-photos"); }
    private <T> T main(Callable<T> action) throws Exception { FutureTask<T> task=new FutureTask<>(action); instrument.runOnMainSync(task); return task.get(); }
    private Object field(String name) throws Exception { java.lang.reflect.Field f=WaveShutterService.class.getDeclaredField(name); f.setAccessible(true); return f.get(service); }
    private void set(String name,Object value) throws Exception { java.lang.reflect.Field f=WaveShutterService.class.getDeclaredField(name); f.setAccessible(true); f.set(service,value); }
    private void shell(String command) throws Exception { try(ParcelFileDescriptor fd=instrument.getUiAutomation().executeShellCommand(command); FileInputStream in=new FileInputStream(fd.getFileDescriptor())) { byte[] b=new byte[256]; while(in.read(b)!=-1) {} } }
    @After public void cleanup() throws Exception {
        shell("input keyevent 224"); shell("wm dismiss-keyguard");
        main(() -> { context.stopService(new Intent(context,WaveShutterService.class)); if(connection!=null) context.unbindService(connection); if(activity!=null) activity.finish(); return null; });
        instrument.waitForIdleSync(); new TiltGlassPrefs(context).setEnabled(tilt);
        File[] files=directory().listFiles(); if(files!=null) for(File f:files) if(!originalFiles.contains(f.getName())) f.delete();
    }
    @Test public void serviceCapturesRealJpegWithScreenActuallyOff() throws Exception {
        shell("input keyevent 223"); SystemClock.sleep(1800);
        PowerManager power=context.getSystemService(PowerManager.class); assertFalse("Must be real screen-off, not a black page",power.isInteractive());
        assertTrue(WaveShutterService.running);
        assertTrue(main(() -> ((PowerManager.WakeLock)field("wake")).isHeld()));
        assertTrue(main(() -> field("audio")!=null && !((AcousticEngine)field("audio")).isStopping()));
        // Controlled shutter decision tests camera/FGS screen-off capability, not physical acoustics.
        main(() -> { java.lang.reflect.Method m=WaveShutterService.class.getDeclaredMethod("takePhoto",long.class); m.setAccessible(true); m.invoke(service,SystemClock.elapsedRealtime()); return null; });
        for(int i=0;i<100 && WaveShutterService.shots==0;i++) SystemClock.sleep(100);
        assertEquals(WaveShutterService.status,1,WaveShutterService.shots); assertFalse(power.isInteractive());
        File[] files=directory().listFiles((dir,name) -> !originalFiles.contains(name)); assertNotNull(files); assertEquals(1,files.length);
        BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true; BitmapFactory.decodeFile(files[0].getPath(),options);
        assertTrue(options.outWidth>=640 && options.outHeight>=480);
        System.out.println("Screen-off JPEG "+options.outWidth+"x"+options.outHeight+", bytes="+files[0].length());
    }
    @Test public void pocketSafetyGateRejectsCaptureAndTimeoutReleasesResources() throws Exception {
        main(() -> {
            set("near",true); java.lang.reflect.Method m=WaveShutterService.class.getDeclaredMethod("takePhoto",long.class); m.setAccessible(true); m.invoke(service,SystemClock.elapsedRealtime());
            assertEquals(false,field("capturing")); assertEquals(0,WaveShutterService.shots);
            WaveShutterService.deadline=SystemClock.elapsedRealtime()-1; ((Runnable)field("watchdog")).run(); return null;
        });
        // Unbind lets stopSelf destroy the service.
        main(() -> { context.unbindService(connection); connection=null; return null; }); SystemClock.sleep(1000);
        assertFalse(WaveShutterService.running); assertFalse(main(() -> ((PowerManager.WakeLock)field("wake")).isHeld()));
        assertTrue(main(() -> ((AcousticEngine)field("audio")).isStopping()));
    }
}
