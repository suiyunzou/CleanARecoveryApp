package com.example.cleanrecovery.experimental;

import android.app.*;
import android.content.*;
import android.hardware.*;
import android.os.*;
import android.view.*;
import android.view.inspector.WindowInspector;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import java.lang.ref.WeakReference;
import java.util.*;

/** One foreground sensor subscription shared by app activities and in-process dialog windows. */
public final class TiltGlassController implements Application.ActivityLifecycleCallbacks, SensorEventListener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private static TiltGlassController instance;
    private final Application app;
    private final SensorManager sensors;
    private final Sensor sensor;
    private final TiltGlassPrefs prefs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<View,TiltGlassRenderer> renderers=new WeakHashMap<>();
    private final TiltGlassModel model=new TiltGlassModel();
    private final float[] gravity={0,0,9.81f};
    private final float[] orientation=new float[9];
    private WeakReference<Activity> active=new WeakReference<>(null);
    private boolean listening,hasSample;
    private long lastSampleMs,lastDiscovery;
    private TiltGlassModel.Tilt current=new TiltGlassModel.Tilt(0,0);
    private String failure="";

    private TiltGlassController(Application application) {
        app=application; prefs=new TiltGlassPrefs(app); sensors=app.getSystemService(SensorManager.class);
        Sensor preferred=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if(preferred==null) preferred=sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensor=preferred!=null ? preferred : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        app.getSharedPreferences(TiltGlassPrefs.NAME,0).registerOnSharedPreferenceChangeListener(this);
        app.registerActivityLifecycleCallbacks(this);
    }
    public static void initialize(Application app) { if(instance==null) instance=new TiltGlassController(app); }
    public static TiltGlassController get() { return instance; }
    public boolean supported() { return Build.VERSION.SDK_INT>=31 && sensor!=null; }
    public String status() {
        if(Build.VERSION.SDK_INT<31) return "实时渐进虚化需要 Android 12 或更新版本";
        if(sensor==null) return "本机没有可用的重力或加速度传感器";
        if(!prefs.enabled()) return "已关闭 · 所有页面恢复清晰";
        if(!failure.isEmpty()) return failure;
        if(!hasSample || SystemClock.elapsedRealtime()-lastSampleMs>1500) return "等待姿态传感器";
        if(current.amount()<.03f) return "基准姿态 · 画面清晰";
        if(current.amount()>=.9999f) return "已达 90° · 全屏虚化";
        String horizontal=current.x<-.08f?"左":"";
        if(current.x>.08f) horizontal="右";
        String vertical=current.y<-.08f?"上":"";
        if(current.y>.08f) vertical="下";
        return horizontal+vertical+"侧向内虚化 · 约 "+Math.round(3+current.amount()*87)+"°";
    }
    public boolean calibrate() {
        if(!hasSample || SystemClock.elapsedRealtime()-lastSampleMs>1500) return false;
        if(sensor.getType()==Sensor.TYPE_ROTATION_VECTOR) prefs.calibrateOrientation(orientation);
        else prefs.calibrate(gravity[0],gravity[1],gravity[2]);
        return true;
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key) {
        if(!"enabled".equals(key) && !"strength".equals(key)) model.reset();
        updateListening();
    }
    private void updateListening() {
        boolean wanted=supported() && prefs.enabled() && active.get()!=null;
        if(wanted && !listening) {
            hasSample=false; failure="";
            try { listening=sensors.registerListener(this,sensor,20000); }
            catch(RuntimeException e) { listening=false; }
            if(!listening) failure="姿态传感器不可用，已保持清晰";
            if(listening) { model.reset(); lastDiscovery=0; handler.post(frame); }
        } else if(!wanted) {
            sensors.unregisterListener(this); listening=false; hasSample=false; model.reset();
            handler.removeCallbacks(frame); clear();
        }
    }
    private final Runnable frame=new Runnable() {
        @Override public void run() {
            Activity activity=active.get();
            if(!listening || activity==null) return;
            long now=SystemClock.elapsedRealtime();
            int rotation=activity.getWindowManager().getDefaultDisplay().getRotation();
            boolean useOrientation=sensor.getType()==Sensor.TYPE_ROTATION_VECTOR
                    && (!prefs.calibrated() || prefs.hasOrientationReference());
            float[] vector=useOrientation ? TiltGlassModel.relativeNormal(orientation,prefs.orientationReference()) : gravity;
            float[] reference=useOrientation ? new float[]{0,0,9.81f} : prefs.reference();
            TiltGlassModel.Tilt target=hasSample && now-lastSampleMs<1500
                    ? TiltGlassModel.direction(remap(vector,rotation),remap(reference,rotation))
                    : new TiltGlassModel.Tilt(0,0);
            current=model.smooth(target,now);
            if(now-lastDiscovery>=250) { discover(); lastDiscovery=now; }
            boolean night=new BrowserPrefs(app).nightMode();
            for(Map.Entry<View,TiltGlassRenderer> entry:new ArrayList<>(renderers.entrySet())) {
                View view=entry.getKey();
                if(view!=null && view.isAttachedToWindow()) entry.getValue().apply(view,current,prefs.strength(),night);
            }
            handler.postDelayed(this,33);
        }
    };
    @androidx.annotation.RequiresApi(31)
    private void discover() {
        Set<View> live=new HashSet<>();
        for(View window:WindowInspector.getGlobalWindowViews()) {
            View content=window.findViewById(android.R.id.content);
            if(content==null) content=window;
            if(content.isAttachedToWindow() && content.isHardwareAccelerated()) {
                live.add(content);
                if(!renderers.containsKey(content)) renderers.put(content,new TiltGlassRenderer());
            }
        }
        Iterator<View> iterator=renderers.keySet().iterator();
        while(iterator.hasNext()) {
            View view=iterator.next();
            if(!live.contains(view)) { if(view!=null) view.setRenderEffect(null); iterator.remove(); }
        }
    }
    private void clear() {
        if(Build.VERSION.SDK_INT>=31) for(View view:renderers.keySet()) if(view!=null) view.setRenderEffect(null);
        renderers.clear(); current=new TiltGlassModel.Tilt(0,0);
    }
    static float[] remap(float[] vector,int rotation) {
        float[] matrix={1,0,0,0,1,0,vector[0],vector[1],vector[2]}, out=new float[9];
        int x=SensorManager.AXIS_X,y=SensorManager.AXIS_Y;
        if(rotation==Surface.ROTATION_90) { x=SensorManager.AXIS_Y; y=SensorManager.AXIS_MINUS_X; }
        else if(rotation==Surface.ROTATION_180) { x=SensorManager.AXIS_MINUS_X; y=SensorManager.AXIS_MINUS_Y; }
        else if(rotation==Surface.ROTATION_270) { x=SensorManager.AXIS_MINUS_Y; y=SensorManager.AXIS_X; }
        SensorManager.remapCoordinateSystem(matrix,x,y,out);
        return new float[]{out[6],out[7],out[8]};
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if(sensor.getType()==Sensor.TYPE_ROTATION_VECTOR) {
            for(float value:event.values) if(!Float.isFinite(value)) return;
            SensorManager.getRotationMatrixFromVector(orientation,event.values);
            for(int i=0;i<3;i++) gravity[i]=orientation[6+i]*9.81f;
            hasSample=true; lastSampleMs=SystemClock.elapsedRealtime();
            return;
        }
        double norm=Math.sqrt(event.values[0]*(double)event.values[0]+event.values[1]*(double)event.values[1]+event.values[2]*(double)event.values[2]);
        if(!Double.isFinite(norm) || norm<6 || norm>14) return;
        float blend=!hasSample || sensor.getType()==Sensor.TYPE_GRAVITY ? 1 : .12f;
        for(int i=0;i<3;i++) gravity[i]+=(event.values[i]-gravity[i])*blend;
        hasSample=true; lastSampleMs=SystemClock.elapsedRealtime();
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) { }
    @Override public void onActivityResumed(Activity activity) { active=new WeakReference<>(activity); updateListening(); }
    @Override public void onActivityPaused(Activity activity) {
        if(active.get()==activity) { active.clear(); updateListening(); }
    }
    @Override public void onActivityDestroyed(Activity activity) {
        if(active.get()==activity) { active.clear(); updateListening(); }
    }
    @Override public void onActivityCreated(Activity a,Bundle b) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a,Bundle b) { }
}
