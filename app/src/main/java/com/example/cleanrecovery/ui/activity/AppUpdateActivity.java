package com.example.cleanrecovery.ui.activity;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.example.cleanrecovery.update.GitHubUpdates;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AppUpdateActivity extends Activity {
    private TextView status; private Button check,download,install;
    private GitHubUpdates.Release release; private File apk;
    private boolean busy;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);com.example.cleanrecovery.ui.widget.SystemUiHelper.apply(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),0,dp(20),0);root.setBackgroundColor(0xffFFFFFF);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(v,i)->{androidx.core.graphics.Insets b=i.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(dp(20)+b.left,b.top,dp(20)+b.right,b.bottom);return i;});
        TextView title=text("‹    检查更新",20);title.setPadding(0,dp(16),0,dp(20));title.setOnClickListener(v->finish());root.addView(title);
        String version="";try{android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);version=info.versionName+"（"+GitHubUpdates.installedCode(this)+"）";}catch(Exception ignored){}
        root.addView(text("当前版本 "+version,16));
        Switch automatic=new Switch(this);automatic.setText("自动检查更新");automatic.setChecked(GitHubUpdates.prefs(this).getBoolean("automatic",true));automatic.setPadding(0,dp(20),0,dp(12));automatic.setOnCheckedChangeListener((b,on)->GitHubUpdates.prefs(this).edit().putBoolean("automatic",on).apply());root.addView(automatic);
        root.addView(text("打开应用时检查，每天最多一次。发现新版后提醒；下载与安装由你确认。",14));
        status=text("更新来源：GitHub Releases",15);status.setPadding(0,dp(24),0,dp(20));
        ScrollView scroll=new ScrollView(this);scroll.addView(status);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        check=button("检查更新",root);check.setOnClickListener(v->check());
        download=button("应用内下载更新",root);download.setVisibility(View.GONE);download.setOnClickListener(v->download());
        install=button("安装更新",root);install.setVisibility(View.GONE);install.setOnClickListener(v->install());
        Button page=button("前往 GitHub 下载",root);page.setOnClickListener(v->openReleasePage());
        setContentView(root);check();
    }
    private void openReleasePage(){
        Uri uri=Uri.parse(release==null?GitHubUpdates.RELEASES+"/latest":release.pageUrl());
        try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}
        catch(android.content.ActivityNotFoundException unavailable){
            try{startActivity(new Intent(this,BrowserActivity.class).setAction(Intent.ACTION_VIEW).setData(uri));}
            catch(Exception e){status.setText("暂时无法打开 GitHub，请稍后重试。");}
        }
    }
    private void check(){if(busy)return;busy=true;check.setEnabled(false);download.setVisibility(View.GONE);status.setText("正在检查更新…");
        worker.execute(()->{try{GitHubUpdates.Release latest=GitHubUpdates.check();long current=GitHubUpdates.installedCode(this);runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;check.setEnabled(true);release=latest;if(latest.code>current){status.setText("发现新版 "+latest.name+"\n安装包 "+String.format(java.util.Locale.ROOT,"%.1f MB",latest.size/1000000.0)+"\n\n"+latest.notes);download.setVisibility(View.VISIBLE);}else status.setText("当前已是最新版本");});}catch(Exception e){failed("检查失败："+e.getMessage());}});
    }
    private void download(){if(busy||release==null)return;busy=true;cancelled.set(false);check.setEnabled(false);download.setEnabled(false);status.setText("正在下载…");
        worker.execute(()->{try{File result=GitHubUpdates.download(getApplicationContext(),release,cancelled,p->runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())status.setText("正在下载 "+p+"%\n退出此页可取消下载");}));runOnUiThread(()->{if(isFinishing()||isDestroyed())return;apk=result;busy=false;check.setEnabled(true);download.setEnabled(true);download.setVisibility(View.GONE);install.setVisibility(View.VISIBLE);status.setText("下载完成，完整性、包名、版本和签名校验通过。点击安装继续。");});}catch(Exception e){failed("下载失败："+e.getMessage());}});
    }
    private void install(){if(apk==null||!apk.isFile())return;
        try{GitHubUpdates.validateApk(this,apk,release.code);
            if(android.os.Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){
                new AlertDialog.Builder(this).setMessage("请允许此应用安装更新，返回后再次点击安装。").setNegativeButton("取消",null).setPositiveButton("去设置",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())))).show();return;
            }
            Uri uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".fileprovider",apk);
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        }catch(Exception e){status.setText("无法安装："+e.getMessage());}
    }
    private void failed(String message){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;check.setEnabled(true);download.setEnabled(true);status.setText(message+"\n请检查网络后重试，也可查看发布页面。");});}
    @Override protected void onDestroy(){cancelled.set(true);worker.shutdownNow();super.onDestroy();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(0xff333333);return t;}
    private Button button(String s,LinearLayout root){Button b=new Button(this);b.setText(s);root.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));return b;}
}
