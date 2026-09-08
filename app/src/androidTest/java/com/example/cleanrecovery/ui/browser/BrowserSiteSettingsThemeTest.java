package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.ui.activity.BrowserSiteSettingsActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserSiteSettingsThemeTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private <T> T main(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task=new FutureTask<>(action);instrumentation.runOnMainSync(task);return task.get();
    }
    private View find(View root,String text) {
        if(root instanceof TextView && text.contentEquals(((TextView)root).getText()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            View result=find(((ViewGroup)root).getChildAt(i),text);if(result!=null)return result;
        }
        return null;
    }
    private AccessibilityNodeInfo option(String text) throws Exception {
        long end=System.currentTimeMillis()+5000;
        while(System.currentTimeMillis()<end) {
            AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
            if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))
                if(text.contentEquals(node.getText()))return node;
            Thread.sleep(50);
        }
        throw new AssertionError("Dialog option missing: "+text);
    }
    private int settledSurface(AccessibilityNodeInfo choice, boolean night) throws Exception {
        long end=System.currentTimeMillis()+5000;
        Rect previousBounds=new Rect();
        int previousColor=0, stable=0;
        java.util.List<String> samples=new java.util.ArrayList<>();
        while(System.currentTimeMillis()<end) {
            choice.refresh();
            Rect bounds=new Rect();choice.getBoundsInScreen(bounds);
            Bitmap screen=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(screen);
            int color;
            try { color=screen.getPixel(bounds.right+3,bounds.centerY()); }
            finally { screen.recycle(); }
            samples.add(Integer.toHexString(color));
            stable=bounds.equals(previousBounds)&&color==previousColor?stable+1:1;
            if(stable>=3) {
                System.out.println("Settled dialog night="+night+" pixels="+samples);
                return color;
            }
            previousBounds.set(bounds);previousColor=color;
            Thread.sleep(100);
        }
        throw new AssertionError("Dialog surface never settled: "+samples);
    }
    @Test public void siteSettingsAndActualModeDialogRestoreDayAfterNight() throws Exception {
        BrowserPrefs prefs=new BrowserPrefs(instrumentation.getTargetContext());
        boolean original=prefs.nightMode();
        String host="theme-"+java.util.UUID.randomUUID()+".test";
        try {
            for(boolean night:new boolean[]{true,false}) {
                prefs.setNightMode(night);prefs.setSiteSettingsEnabled(host,true);prefs.setSiteJsMode(host,-1);
                Activity activity=instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),BrowserSiteSettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(BrowserSiteSettingsActivity.EXTRA_HOST,host));
                try {
                    main(() -> {
                        ViewGroup content=activity.findViewById(android.R.id.content);
                        int background=((ColorDrawable)content.getChildAt(0).getBackground()).getColor();
                        assertEquals(night?android.graphics.Color.BLACK:android.graphics.Color.WHITE,background);
                        for(String text:new String[]{"网站设定","JavaScript","启用 \""+host+"\" 的网站设定"}) {
                            TextView label=(TextView)find(content,text);assertNotNull(text,label);
                            assertTrue("Readable setting title: "+text,androidx.core.graphics.ColorUtils.calculateContrast(label.getCurrentTextColor(),background)>4.5);
                        }
                        androidx.core.view.WindowInsetsControllerCompat bars=new androidx.core.view.WindowInsetsControllerCompat(activity.getWindow(),activity.getWindow().getDecorView());
                        assertEquals(!night,bars.isAppearanceLightStatusBars());assertEquals(!night,bars.isAppearanceLightNavigationBars());
                        View row=find(content,"JavaScript");while(!row.isClickable())row=(View)row.getParent();
                        assertTrue(row.performClick());return null;
                    });
                    AccessibilityNodeInfo choice=option("禁止");
                    instrumentation.waitForIdleSync();
                    // Accessibility can arrive before the dialog's window animation finishes.
                    // Require stable bounds and pixels, independently of the expected theme.
                    int color=settledSurface(choice,night);
                    double light=androidx.core.graphics.ColorUtils.calculateLuminance(color);
                    assertTrue("Actual dialog surface follows mode, pixel="+Integer.toHexString(color),night?light<.1:light>.8);
                    while(!choice.isClickable())choice=choice.getParent();
                    assertTrue(choice.performAction(AccessibilityNodeInfo.ACTION_CLICK));instrumentation.waitForIdleSync();
                    assertEquals("The themed dialog still saves the selected site setting",0,prefs.siteJsMode(host));
                }finally {
                    main(() -> {activity.finish();return null;});
                    long end=System.currentTimeMillis()+5000;
                    while(!main(activity::isDestroyed)&&System.currentTimeMillis()<end)Thread.sleep(25);
                    assertTrue("Finish the previous themed activity before changing preferences",main(activity::isDestroyed));
                }
            }
        }finally {
            prefs.resetSiteSettings(host);prefs.setNightMode(original);
            instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs",0).edit().commit();
        }
    }
}
