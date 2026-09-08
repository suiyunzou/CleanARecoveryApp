package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.ui.activity.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserNewScreensTest {
    private final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private Activity open(Class<?> type){return instrument.startActivitySync(new Intent(instrument.getTargetContext(),type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private void capture(String name)throws Exception{Bitmap bitmap=instrument.getUiAutomation().takeScreenshot();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(instrument.getTargetContext().getExternalFilesDir(null),name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    @Test public void portraitScannerHasFullWindowControls()throws Exception{
        Activity a=open(BrowserQrScannerActivity.class);
        try{Thread.sleep(1500);instrument.waitForIdleSync();instrument.runOnMainSync(()->{
            View root=a.getWindow().getDecorView();int[] location=new int[2];root.getLocationOnScreen(location);assertEquals(0,location[0]);
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,a.getRequestedOrientation());assertTrue(text(root).contains("扫描二维码"));
        });capture("browser-portrait-scanner-settled");}finally{instrument.runOnMainSync(a::finish);instrument.waitForIdleSync();}
    }
    @Test public void updateScreenAndBrowserSettingsUseProductLabels()throws Exception{
        Activity settings=open(BrowserSettingsActivity.class);
        try{instrument.waitForIdleSync();instrument.runOnMainSync(()->{String content=text(settings.getWindow().getDecorView());assertTrue(content.contains("检查更新"));assertFalse(content.contains("关于"));});}finally{instrument.runOnMainSync(settings::finish);instrument.waitForIdleSync();}
        Activity updates=open(AppUpdateActivity.class);
        try{Thread.sleep(3000);instrument.runOnMainSync(()->{String content=text(updates.getWindow().getDecorView());assertTrue(content.contains("自动检查更新"));assertTrue(content.contains("每天最多一次"));assertTrue(content.contains("GitHub"));});capture("app-github-updates");}finally{instrument.runOnMainSync(updates::finish);instrument.waitForIdleSync();}
    }
    private String text(View view){StringBuilder result=new StringBuilder();if(view instanceof TextView)result.append(((TextView)view).getText()).append('\n');if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)result.append(text(((ViewGroup)view).getChildAt(i)));return result.toString();}
    @Test public void githubDownloadEntryOpensLatestOrSelectedRelease()throws Exception{
        java.util.concurrent.atomic.AtomicReference<Intent> opened=new java.util.concurrent.atomic.AtomicReference<>();
        android.app.Instrumentation.ActivityMonitor monitor=new android.app.Instrumentation.ActivityMonitor(){
            @Override public android.app.Instrumentation.ActivityResult onStartActivity(Intent intent){
                if(Intent.ACTION_VIEW.equals(intent.getAction())&&intent.getDataString()!=null&&intent.getDataString().startsWith(com.example.cleanrecovery.update.GitHubUpdates.RELEASES)){
                    opened.set(intent);return new android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);
                }return null;
            }
        };
        instrument.addMonitor(monitor);Activity updates=open(AppUpdateActivity.class);
        try{
            java.lang.reflect.Field selected=AppUpdateActivity.class.getDeclaredField("release");selected.setAccessible(true);
            String url=com.example.cleanrecovery.update.GitHubUpdates.RELEASES;
            String json="{\"tag_name\":\"v9.0\",\"assets\":[{\"name\":\"CleanARecovery-999999.apk\",\"state\":\"uploaded\",\"size\":10,\"digest\":\"sha256:"+"ab".repeat(32)+"\",\"browser_download_url\":\""+url+"/download/v9.0/CleanARecovery-999999.apk\"}]}";
            com.example.cleanrecovery.update.GitHubUpdates.Release parsed=com.example.cleanrecovery.update.GitHubUpdates.parse(json);
            instrument.runOnMainSync(()->{try{selected.set(updates,null);}catch(Exception e){throw new AssertionError(e);}clickGithub(updates.getWindow().getDecorView());});
            assertNotNull(opened.get());assertEquals(url+"/latest",opened.get().getDataString());opened.set(null);
            instrument.runOnMainSync(()->{try{selected.set(updates,parsed);}catch(Exception e){throw new AssertionError(e);}clickGithub(updates.getWindow().getDecorView());});
            assertNotNull(opened.get());assertEquals(url+"/tag/v9.0",opened.get().getDataString());
        }finally{instrument.runOnMainSync(updates::finish);instrument.removeMonitor(monitor);instrument.waitForIdleSync();}
    }
    private boolean clickGithub(View view){
        if(view instanceof android.widget.Button&&"前往 GitHub 下载".contentEquals(((TextView)view).getText())){view.performClick();return true;}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(clickGithub(((ViewGroup)view).getChildAt(i)))return true;
        return false;
    }
}
