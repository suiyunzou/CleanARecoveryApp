package com.example.cleanrecovery.update;

import android.app.*;
import android.os.Bundle;
import android.os.Handler;
import android.content.Intent;
import com.example.cleanrecovery.ui.activity.AppUpdateActivity;

/** Foreground, once-a-day checks avoid an unnecessary permanent background service. */
public final class AutomaticUpdateChecks implements Application.ActivityLifecycleCallbacks {
    private boolean running; private java.lang.ref.WeakReference<Activity> resumed=new java.lang.ref.WeakReference<>(null);
    public static void initialize(Application app){app.registerActivityLifecycleCallbacks(new AutomaticUpdateChecks());}
    @Override public void onActivityResumed(Activity a){
        resumed=new java.lang.ref.WeakReference<>(a);
        if(a instanceof AppUpdateActivity||!GitHubUpdates.prefs(a).getBoolean("automatic",true)||running)return;
        // Only stable landing pages, never camera, permission or onboarding screens.
        String name=a.getClass().getSimpleName();if(!name.equals("BrowserActivity")&&!name.equals("MainActivity")&&!name.equals("MusicHomeActivity"))return;
        long now=System.currentTimeMillis(),last=GitHubUpdates.prefs(a).getLong("last_check",0);if(now>=last&&now-last<24*60*60*1000L)return;
        running=true;GitHubUpdates.prefs(a).edit().putLong("last_check",now).apply();
        android.content.Context context=a.getApplicationContext();
        new Thread(()->{GitHubUpdates.Release found=null;try{GitHubUpdates.Release r=GitHubUpdates.check();if(r.code>GitHubUpdates.installedCode(context))found=r;}catch(Exception ignored){}
            GitHubUpdates.Release result=found;new Handler(android.os.Looper.getMainLooper()).post(()->{running=false;Activity current=resumed.get();if(result==null||current==null||current.isFinishing()||current.isDestroyed()||current instanceof AppUpdateActivity||!GitHubUpdates.prefs(context).getBoolean("automatic",true))return;
                String currentName=current.getClass().getSimpleName();
                if(!currentName.equals("BrowserActivity")&&!currentName.equals("MainActivity")&&!currentName.equals("MusicHomeActivity"))return;
                new AlertDialog.Builder(current).setTitle("发现新版本 "+result.name).setMessage("有可用更新，是否查看更新说明？").setNegativeButton("稍后",null).setPositiveButton("查看更新",(d,w)->current.startActivity(new Intent(current,AppUpdateActivity.class))).show();});
        },"github-update-check").start();
    }
    @Override public void onActivityPaused(Activity a){if(resumed.get()==a)resumed.clear();}
    @Override public void onActivityCreated(Activity a,Bundle b){} @Override public void onActivityStarted(Activity a){} @Override public void onActivityStopped(Activity a){} @Override public void onActivitySaveInstanceState(Activity a,Bundle b){} @Override public void onActivityDestroyed(Activity a){}
}
