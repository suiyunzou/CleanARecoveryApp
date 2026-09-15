package com.example.cleanrecovery.experimental;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.activity.AboutActivity;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExperimentalLabUiTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private Context context;
    private String savedHistory;
    @Before public void save() {
        context=instrument.getTargetContext();
        savedHistory=context.getSharedPreferences("experimental_history",0).getString("events",null);
    }
    @After public void restore() {
        context.stopService(new Intent(context,ItemWatchService.class));
        SystemClock.sleep(200);
        SharedPreferences.Editor edit=context.getSharedPreferences("experimental_history",0).edit();
        if(savedHistory==null) edit.remove("events"); else edit.putString("events",savedHistory);
        edit.commit();
    }
    private <T> T main(java.util.concurrent.Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work); instrument.runOnMainSync(task); return task.get();
    }
    private Activity launch(Class<? extends Activity> type) {
        return instrument.startActivitySync(new Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private View text(View root,String label) {
        if(root instanceof TextView && label.contentEquals(((TextView)root).getText())) return root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            View found=text(((ViewGroup)root).getChildAt(i),label); if(found!=null) return found;
        }
        return null;
    }
    private void click(Activity activity,String label) throws Exception {
        main(() -> {
            View v=text(activity.getWindow().getDecorView(),label); assertNotNull(label,v);
            while(!v.isClickable()) v=(View)v.getParent(); v.performClick(); return null;
        }); instrument.waitForIdleSync();
    }
    private Object field(Object target,String name) throws Exception {
        java.lang.reflect.Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private void screenshot(String name) throws Exception {
        SystemClock.sleep(1000);
        android.graphics.Bitmap bitmap=instrument.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),name+".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
        }
        bitmap.recycle();
    }
    @Test public void settingsToolsEntryOpensLab() throws Exception {
        Activity settings=launch(AboutActivity.class), lab=null;
        Instrumentation.ActivityMonitor monitor=instrument.addMonitor(ExperimentalLabActivity.class.getName(),null,false);
        try {
            main(() -> { View row=settings.findViewById(R.id.settings_experimental_row); assertNotNull(row); row.performClick(); return null; });
            lab=instrument.waitForMonitorWithTimeout(monitor,4000); assertNotNull(lab);
            Activity finalLab=lab;
            main(() -> { assertNotNull(text(finalLab.getWindow().getDecorView(),"趣味性实验")); return null; });
            screenshot("experimental-home");
        } finally {
            instrument.removeMonitor(monitor); Activity finalLab=lab;
            main(() -> { if(finalLab!=null) finalLab.finish(); settings.finish(); return null; });
        }
    }
    @Test public void guardSurvivesPageExitAndStopsExplicitly() throws Exception {
        Activity lab=launch(ExperimentalLabActivity.class);
        try {
            click(lab,"物品守护");
            main(() -> { androidx.core.content.ContextCompat.startForegroundService(context,new Intent(context,ItemWatchService.class)); return null; });
            SystemClock.sleep(3600); assertTrue(ItemWatchService.running);
            screenshot("experimental-watch");
            main(() -> { lab.onBackPressed(); return null; });
            assertTrue(ItemWatchService.running);
            click(lab,"物品守护"); SystemClock.sleep(300); click(lab,"停止守护");
            SystemClock.sleep(300); assertFalse(ItemWatchService.running);
            assertTrue(LabHistory.read(context).toString().contains("守护已停止"));
        } finally { main(() -> { lab.finish(); return null; }); }
    }
    @Test public void acousticConsentAndPageLayout() throws Exception {
        Activity lab=launch(ExperimentalLabActivity.class);
        try {
            click(lab,"声波隔空手势"); screenshot("experimental-acoustic"); click(lab,"开始实验");
            screenshot("experimental-consent");
            main(() -> {
                boolean found=false;
                for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    View cancel=text(root,"取消");
                    if(cancel!=null) { cancel.performClick(); found=true; break; }
                }
                assertTrue(found); assertNull(field(lab,"engine")); return null;
            });
        } finally { main(() -> { lab.finish(); return null; }); }
    }
    @Test public void audioResourcesStopOnLeavingPage() throws Exception {
        boolean had=context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        instrument.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.RECORD_AUDIO);
        Activity lab=launch(ExperimentalLabActivity.class);
        try {
            click(lab,"声波隔空手势");
            main(() -> { java.lang.reflect.Method start=lab.getClass().getDeclaredMethod("startAcoustic"); start.setAccessible(true); start.invoke(lab); return null; });
            SystemClock.sleep(700);
            main(() -> {
                Object engine=field(lab,"engine");
                assertNotNull("Audio engine must actually start before testing teardown",engine);
                assertFalse(((AcousticEngine)engine).isStopping()); return null;
            });
            screenshot("experimental-audio-running");
            main(() -> { lab.onBackPressed(); return null; });
            long until=SystemClock.elapsedRealtime()+4000;
            while(main(() -> field(lab,"engine"))!=null && SystemClock.elapsedRealtime()<until) SystemClock.sleep(50);
            assertNull(main(() -> field(lab,"engine")));
            assertEquals(0,main(() -> lab.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON).intValue());
        } finally {
            main(() -> { lab.finish(); return null; });
            // Permission restoration is performed outside instrumentation: revocation kills this process.
            if(!had) android.util.Log.i("ExperimentalLabTest","RESTORE_RECORD_AUDIO_PERMISSION");
        }
    }

    @Test public void nightModeUsesBrowserPalette() throws Exception {
        com.example.cleanrecovery.ui.browser.BrowserPrefs prefs=new com.example.cleanrecovery.ui.browser.BrowserPrefs(context);
        boolean old=prefs.nightMode(); Activity lab=null;
        try {
            prefs.setNightMode(true); lab=launch(ExperimentalLabActivity.class);
            Activity current=lab;
            main(() -> {
                TextView title=(TextView)text(current.getWindow().getDecorView(),"趣味性实验");
                assertEquals(0xffcccccc,title.getCurrentTextColor()); return null;
            });
            screenshot("experimental-night");
        } finally {
            Activity current=lab; main(() -> { if(current!=null) current.finish(); return null; }); prefs.setNightMode(old);
        }
    }
}
