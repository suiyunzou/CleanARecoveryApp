package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.ui.activity.BrowserHomeCustomizeActivity;
import com.example.cleanrecovery.ui.widget.GlassToast;
import java.util.concurrent.FutureTask;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GlassToastUiTest {
    private final android.app.Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    interface Work<T>{T run() throws Exception;}
    private <T>T main(Work<T> work)throws Exception{FutureTask<T> task=new FutureTask<>(work::run);instrument.runOnMainSync(task);return task.get();}
    private Activity launch(){return instrument.startActivitySync(new Intent(instrument.getTargetContext(),BrowserHomeCustomizeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private Object field(Object target,String name)throws Exception{java.lang.reflect.Field f=GlassToast.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private Dialog dialog()throws Exception{return main(()->{Object active=field(null,"active");return active==null?null:(Dialog)field(active,"dialog");});}
    private int images(View v){int n=v instanceof ImageView?1:0;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)n+=images(((ViewGroup)v).getChildAt(i));return n;}
    private void snapshot(String name)throws Exception{
        android.graphics.Bitmap b=instrument.getUiAutomation().takeScreenshot();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(instrument.getTargetContext().getExternalFilesDir(null),name+".png"))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}b.recycle();
    }
    @Test public void resetStyleFeedbackIsTextOnlyAndDoesNotTakeFocus()throws Exception{
        Activity activity=launch();GlassToast toast=GlassToast.makeText(activity,"已重置主页定制",GlassToast.LENGTH_LONG);
        try{
            main(()->{java.lang.reflect.Method m=BrowserHomeCustomizeActivity.class.getDeclaredMethod("showAdvancedSheet");m.setAccessible(true);m.invoke(activity);return null;});
            View focus=main(activity::getCurrentFocus);
            toast.show();Thread.sleep(500);Dialog shown=dialog();assertNotNull(shown);
            main(()->{
                View root=shown.getWindow().getDecorView();TextView label=root.findViewWithTag("glass-toast-message");
                assertEquals("已重置主页定制",label.getText().toString());assertEquals(0,images(root));
                int flags=shown.getWindow().getAttributes().flags;
                assertTrue((flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0);
                assertTrue((flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0);
                assertEquals(0,flags&WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                assertSame(focus,activity.getCurrentFocus());
                GradientDrawable background=(GradientDrawable)shown.getWindow().getDecorView().getBackground();
                int alpha=Color.alpha(background.getColor().getDefaultColor());assertTrue(alpha>0&&alpha<255);
                Rect bounds=new Rect();assertTrue(label.getGlobalVisibleRect(bounds));assertEquals(label.getHeight(),bounds.height());
                return null;
            });snapshot("glass-toast-customize");
            toast.cancel();instrument.waitForIdleSync();assertNull(dialog());
        }finally{toast.cancel();main(()->{activity.finish();return null;});}
    }
    @Test public void workerContextReplacementTimeoutAndActivityExitAreSafe()throws Exception{
        Activity activity=launch();GlassToast first=GlassToast.makeText(instrument.getTargetContext(),"第一条提示",GlassToast.LENGTH_LONG);
        GlassToast second=GlassToast.makeText(instrument.getTargetContext(),"第二条提示",GlassToast.LENGTH_SHORT);
        try{
            Thread worker=new Thread(first::show);worker.start();worker.join();Thread.sleep(200);Dialog old=dialog();assertNotNull(old);
            second.show();Thread.sleep(200);assertFalse(main(old::isShowing));first.cancel();Thread.sleep(100);
            Dialog current=dialog();assertNotNull(current);
            assertEquals("第二条提示",main(()->((TextView)current.getWindow().getDecorView().findViewWithTag("glass-toast-message")).getText().toString()));
            Thread.sleep(2300);assertNull(dialog());
            first.show();Thread.sleep(100);assertNotNull(dialog());
            main(()->{activity.finish();return null;});instrument.waitForIdleSync();assertNull(dialog());
        }finally{first.cancel();second.cancel();main(()->{if(!activity.isFinishing())activity.finish();return null;});}
    }
    @Test public void blurAvailabilityChangesUpdateExistingSurface()throws Exception{
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=31);
        String initial=shell("wm disable-blur");
        org.junit.Assume.assumeTrue(initial.contains("Blur supported on device: true"));
        String previousSetting=shell("settings get global disable_window_blurs").trim();
        Activity activity=launch();GlassToast toast=GlassToast.makeText(activity,"磨砂效果切换检查",GlassToast.LENGTH_LONG);
        try{
            shell("settings put global disable_window_blurs 0");Thread.sleep(300);toast.show();Thread.sleep(300);
            Dialog shown=dialog();assertNotNull(shown);
            int translucent=main(()->Color.alpha(((GradientDrawable)shown.getWindow().getDecorView().getBackground()).getColor().getDefaultColor()));
            String toggle=shell("settings put global disable_window_blurs 1");
            int fallback=translucent;
            for(int attempt=0;attempt<12 && fallback<=translucent;attempt++) {
                Thread.sleep(100);
                fallback=main(()->Color.alpha(((GradientDrawable)shown.getWindow().getDecorView().getBackground()).getColor().getDefaultColor()));
            }
            assertTrue("Initial alpha="+translucent+", fallback="+fallback+", toggle="+toggle+", state="+shell("wm disable-blur"),fallback>translucent);
            assertTrue(fallback<255);assertTrue(main(shown::isShowing));
        }finally{shell(previousSetting.equals("null") ? "settings delete global disable_window_blurs" : "settings put global disable_window_blurs "+previousSetting);toast.cancel();main(()->{activity.finish();return null;});}
    }
    private String shell(String command)throws Exception{
        try(android.os.ParcelFileDescriptor result=instrument.getUiAutomation().executeShellCommand(command);
            java.io.FileInputStream input=new java.io.FileInputStream(result.getFileDescriptor());
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){
            byte[] bytes=new byte[1024];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);
            return output.toString("UTF-8");
        }
    }
    @Test public void glassSurfaceWrapsLongTextAndAllowsTouchesThrough()throws Exception{
        Activity activity=launch();java.util.concurrent.atomic.AtomicInteger clicks=new java.util.concurrent.atomic.AtomicInteger();
        GlassToast toast=GlassToast.makeText(activity,"这是较长的操作提示，文字应当自动换行，并保持完整可读，不出现应用图标。",GlassToast.LENGTH_LONG);
        try{
            main(()->{
                FrameLayout body=new FrameLayout(activity);body.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff7090d5,0xffeab9b1,0xff87bcae}));
                body.setOnClickListener(v->clicks.incrementAndGet());activity.setContentView(body);return null;
            });toast.show();Thread.sleep(450);Dialog shown=dialog();assertNotNull(shown);
            Rect box=main(()->{TextView label=shown.getWindow().getDecorView().findViewWithTag("glass-toast-message");assertTrue(label.getLineCount()>1);Rect r=new Rect();assertTrue(label.getGlobalVisibleRect(r));return r;});
            snapshot("glass-toast-surface");
            long t=android.os.SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(t,t,0,box.centerX(),box.centerY(),0);
            android.view.MotionEvent up=android.view.MotionEvent.obtain(t,t+50,1,box.centerX(),box.centerY(),0);
            instrument.sendPointerSync(down);instrument.sendPointerSync(up);down.recycle();up.recycle();instrument.waitForIdleSync();
            assertEquals(1,clicks.get());assertTrue(main(shown::isShowing));
        }finally{toast.cancel();main(()->{activity.finish();return null;});}
    }
}
