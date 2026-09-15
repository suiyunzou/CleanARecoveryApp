package com.example.cleanrecovery.experimental;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.ui.activity.AboutActivity;
import com.example.cleanrecovery.ui.browser.ViaDialogBuilder;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TiltGlassUiTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private Context context;
    private Map<String,?> saved;
    @Before public void before() {
        context=instrument.getTargetContext(); saved=new HashMap<>(context.getSharedPreferences(TiltGlassPrefs.NAME,0).getAll());
        new TiltGlassPrefs(context).setEnabled(false);
        new TiltGlassPrefs(context).setStrength(55);
    }
    @After public void after() {
        SharedPreferences.Editor edit=context.getSharedPreferences(TiltGlassPrefs.NAME,0).edit().clear();
        for(Map.Entry<String,?> e:saved.entrySet()) {
            if(e.getValue() instanceof Boolean) edit.putBoolean(e.getKey(),(Boolean)e.getValue());
            if(e.getValue() instanceof Integer) edit.putInt(e.getKey(),(Integer)e.getValue());
            if(e.getValue() instanceof Float) edit.putFloat(e.getKey(),(Float)e.getValue());
        }
        edit.commit();
    }
    private <T> T main(java.util.concurrent.Callable<T> work) throws Exception {
        FutureTask<T> task=new FutureTask<>(work); instrument.runOnMainSync(task); return task.get();
    }
    private Activity launch(Class<? extends Activity> type) {
        return instrument.startActivitySync(new Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private Object field(Object target,String name) throws Exception {
        java.lang.reflect.Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private Bitmap snapshot(String name) throws Exception {
        SystemClock.sleep(600); Bitmap bitmap=instrument.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),name+".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        }
        return bitmap;
    }
    private View text(View view,String label) {
        if(view instanceof TextView && label.contentEquals(((TextView)view).getText())) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=text(((ViewGroup)view).getChildAt(i),label); if(found!=null) return found;
        }
        return null;
    }
    private void click(Activity activity,String label) throws Exception {
        main(() -> { View view=text(activity.getWindow().getDecorView(),label); assertNotNull(label,view);
            while(!view.isClickable()) view=(View)view.getParent(); view.performClick(); return null; });
    }
    @Test public void directionalGpuBlurPreservesCenterAndTouch() throws Exception {
        Activity activity=launch(AboutActivity.class); Bitmap clear=null,blur=null;
        try {
            final View[] pattern={null}; final int[] taps={0};
            main(() -> {
                pattern[0]=new View(activity) {
                    final Paint p=new Paint();
                    @Override protected void onDraw(Canvas c) {
                        c.drawColor(Color.WHITE);
                        for(int x=0;x<getWidth();x+=16) {
                            p.setColor((x/16)%2==0?0xff163a80:0xffefbe65); c.drawRect(x,0,x+16,getHeight(),p);
                        }
                    }
                };
                pattern[0].setOnClickListener(v -> taps[0]++); activity.setContentView(pattern[0]); return null;
            });
            clear=snapshot("tilt-fixture-clear");
            TiltGlassRenderer renderer=new TiltGlassRenderer();
            main(() -> { renderer.apply(pattern[0],new TiltGlassModel.Tilt(.45f,0),100,false); return null; });
            blur=snapshot("tilt-fixture-right");
            int w=blur.getWidth(),h=blur.getHeight();
            double central=difference(clear,blur,w/3,w/2,h/3,h/2);
            double edge=difference(clear,blur,w*9/10,w-8,h/3,h/2);
            assertTrue("edge must contain real changed pixels: "+edge,edge>12);
            assertTrue("center should remain clear: "+central,central<2);
            long now=SystemClock.uptimeMillis();
            instrument.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,w/2f,h/2f,0));
            instrument.sendPointerSync(MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,w/2f,h/2f,0));
            instrument.waitForIdleSync(); assertEquals(1,taps[0]);
            main(() -> { renderer.apply(pattern[0],new TiltGlassModel.Tilt(1,0),100,false); return null; });
            Bitmap full=snapshot("tilt-fixture-full-90");
            assertTrue("90 degrees must blur the center",difference(clear,full,w/3,w/2,h/3,h/2)>12);
            assertTrue("90 degrees must reach the opposite edge",difference(clear,full,8,w/10,h/3,h/2)>12);
            full.recycle();
            main(() -> { renderer.apply(pattern[0],new TiltGlassModel.Tilt(0,0),100,false); return null; });
            Bitmap restored=snapshot("tilt-fixture-restored");
            assertTrue(difference(clear,restored,w*9/10,w-8,h/3,h/2)<2); restored.recycle();
        } finally { if(clear!=null) clear.recycle(); if(blur!=null) blur.recycle(); main(() -> { activity.finish(); return null; }); }
    }
    private double difference(Bitmap a,Bitmap b,int x1,int x2,int y1,int y2) {
        long sum=0,count=0;
        for(int y=y1;y<y2;y+=4) for(int x=x1;x<x2;x+=2) {
            int p=a.getPixel(x,y),q=b.getPixel(x,y);
            sum+=Math.abs(Color.red(p)-Color.red(q))+Math.abs(Color.green(p)-Color.green(q))+Math.abs(Color.blue(p)-Color.blue(q)); count+=3;
        }
        return sum/(double)count;
    }
    @Test public void globalBindingFollowsActivitiesDialogsAndDisable() throws Exception {
        Activity first=launch(ExperimentalLabActivity.class),second=null; AlertDialog[] dialog={null};
        try {
            main(() -> { new TiltGlassPrefs(context).setEnabled(true); return null; }); SystemClock.sleep(800);
            main(() -> {
                Map<?,?> map=(Map<?,?>)field(TiltGlassController.get(),"renderers");
                assertTrue(map.containsKey(first.findViewById(android.R.id.content)));
                dialog[0]=new ViaDialogBuilder(first).setTitle("材质测试").setMessage("原生弹窗内容").setPositiveButton("关闭",null).show(); return null;
            }); SystemClock.sleep(500);
            main(() -> {
                Map<?,?> map=(Map<?,?>)field(TiltGlassController.get(),"renderers");
                assertTrue(map.containsKey(dialog[0].findViewById(android.R.id.content))); dialog[0].dismiss(); return null;
            });
            second=launch(AboutActivity.class); SystemClock.sleep(500); Activity current=second;
            main(() -> {
                Map<?,?> map=(Map<?,?>)field(TiltGlassController.get(),"renderers");
                assertTrue(map.containsKey(current.findViewById(android.R.id.content)));
                new TiltGlassPrefs(context).setEnabled(false); return null;
            }); SystemClock.sleep(200);
            main(() -> { assertTrue(((Map<?,?>)field(TiltGlassController.get(),"renderers")).isEmpty());
                assertEquals(false,field(TiltGlassController.get(),"listening")); return null; });
        } finally { Activity current=second; main(() -> { if(dialog[0]!=null) dialog[0].dismiss(); if(current!=null) current.finish(); first.finish(); return null; }); }
    }
    @Test public void controlsOpenStrengthDialogAndToggle() throws Exception {
        Activity lab=launch(ExperimentalLabActivity.class);
        try {
            click(lab,"倾斜渐进玻璃"); click(lab,"开启全局效果");
            assertTrue(new TiltGlassPrefs(context).enabled());
            click(lab,"虚化强度"); SystemClock.sleep(500);
            main(() -> {
                boolean found=false;
                for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    if(text(root,"55%")!=null) found=true;
                }
                assertTrue("strength dialog must actually be shown",found); return null;
            });
            instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); SystemClock.sleep(200);
            click(lab,"关闭全局效果"); assertFalse(new TiltGlassPrefs(context).enabled());
            snapshot("tilt-controls").recycle();
        } finally { main(() -> { lab.finish(); return null; }); }
    }
    @Test public void screenRotationRemapsGravity() {
        float[] g={2,3,9};
        assertArrayEquals(new float[]{2,3,9},TiltGlassController.remap(g,Surface.ROTATION_0),.001f);
        assertArrayEquals(new float[]{-3,2,9},TiltGlassController.remap(g,Surface.ROTATION_90),.001f);
        assertArrayEquals(new float[]{-2,-3,9},TiltGlassController.remap(g,Surface.ROTATION_180),.001f);
        assertArrayEquals(new float[]{3,-2,9},TiltGlassController.remap(g,Surface.ROTATION_270),.001f);
    }

    @Test public void webContentRemainsLiveUnderGlobalComposition() throws Exception {
        Activity activity=launch(AboutActivity.class);
        android.webkit.WebView[] web={null};
        java.util.concurrent.CountDownLatch loaded=new java.util.concurrent.CountDownLatch(1);
        Bitmap before=null,after=null;
        try {
            main(() -> {
                web[0]=new android.webkit.WebView(activity);
                web[0].getSettings().setJavaScriptEnabled(true);
                web[0].setWebViewClient(new android.webkit.WebViewClient() {
                    @Override public void onPageFinished(android.webkit.WebView view,String url) { loaded.countDown(); }
                });
                activity.setContentView(web[0]);
                web[0].loadDataWithBaseURL("https://example.invalid/","<html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='margin:0;height:100vh;background:repeating-linear-gradient(90deg,#163a80 0 8px,#efbe65 8px 16px)'><button style='position:fixed;left:35%;top:40%;width:30%;height:20%' onclick='window.clicks=(window.clicks||0)+1'>点按测试</button></body></html>","text/html","UTF-8",null);
                return null;
            });
            assertTrue(loaded.await(8,java.util.concurrent.TimeUnit.SECONDS));
            main(() -> { new TiltGlassRenderer().apply(activity.findViewById(android.R.id.content),new TiltGlassModel.Tilt(1,0),100,false); return null; });
            before=snapshot("tilt-web-before");
            long now=SystemClock.uptimeMillis();
            instrument.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,before.getWidth()/2f,before.getHeight()/2f,0));
            instrument.sendPointerSync(MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,before.getWidth()/2f,before.getHeight()/2f,0));
            instrument.waitForIdleSync();
            java.util.concurrent.CountDownLatch changed=new java.util.concurrent.CountDownLatch(1);
            String[] result={null};
            main(() -> {
                web[0].evaluateJavascript("document.body.style.background='repeating-linear-gradient(90deg,#000 0 8px,#4eff85 8px 16px)';window.clicks",value -> { result[0]=value; changed.countDown(); }); return null;
            });
            assertTrue(changed.await(5,java.util.concurrent.TimeUnit.SECONDS)); assertEquals("1",result[0]);
            after=snapshot("tilt-web-after");
            assertTrue("blurred web content must remain live",difference(before,after,after.getWidth()*9/10,after.getWidth()-8,after.getHeight()/3,after.getHeight()/2)>10);
        } finally {
            if(before!=null) before.recycle(); if(after!=null) after.recycle();
            main(() -> { activity.finish(); if(web[0]!=null) web[0].destroy(); return null; });
        }
    }
}
