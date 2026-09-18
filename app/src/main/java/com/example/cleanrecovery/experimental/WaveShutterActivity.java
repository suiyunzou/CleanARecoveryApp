package com.example.cleanrecovery.experimental;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Independent screen-off shutter UI; camera ownership belongs to the bounded service. */
public final class WaveShutterActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status,start,waves,time;
    private SharedPreferences prefs;
    private File exporting;
    private boolean visible;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); prefs=getSharedPreferences("wave_shutter",0);
        if(state!=null && state.getString("exporting")!=null) exporting=new File(getFilesDir(),"wave-photos/"+new File(state.getString("exporting")).getName());
        boolean night=new BrowserPrefs(this).nightMode();
        getWindow().setStatusBarColor(night?Color.BLACK:Color.WHITE); getWindow().setNavigationBarColor(night?Color.BLACK:Color.WHITE);
        androidx.core.view.WindowInsetsControllerCompat bars=new androidx.core.view.WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView()); bars.setAppearanceLightStatusBars(!night); bars.setAppearanceLightNavigationBars(!night);
        LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setFitsSystemWindows(true); root.setBackgroundColor(night?Color.BLACK:Color.WHITE);
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back=new ImageView(this); back.setImageResource(R.drawable.ic_chevron_left); back.setColorFilter(ViaUi.textColor(this,ViaUi.TEXT)); back.setContentDescription("返回");
        back.setPadding(dp(14),dp(14),dp(14),dp(14)); back.setOnClickListener(v -> finish()); bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        bar.addView(text("挥手快门",17,ViaUi.TEXT),new LinearLayout.LayoutParams(0,-2,1)); root.addView(bar);
        ScrollView scroll=new ScrollView(this); LinearLayout content=new LinearLayout(this); content.setOrientation(1); scroll.addView(content); root.addView(scroll);
        content.addView(text("独立拍照 · 限时熄屏待命",14,ViaUi.ACCENT));
        status=text(WaveShutterService.status,16,ViaUi.TEXT); content.addView(status);
        start=action("开启待命",() -> {
            if(WaveShutterService.running) { stopService(new Intent(this,WaveShutterService.class)); WaveShutterService.status="待命已停止"; refresh(); }
            else requestStart();
        }); content.addView(start);
        waves=action("",() -> {
            if(WaveShutterService.running) { Toast.makeText(this,"请先停止待命再更改",Toast.LENGTH_SHORT).show(); return; }
            ViaUi.radioDialog(this,"完整挥动次数",new String[]{"2 次 · 更快","3 次 · 默认"},count()==2?0:1,pick -> { prefs.edit().putInt("waves",pick==0?2:3).apply(); refresh(); }).show();
        }); content.addView(waves);
        time=action("",() -> {
            if(WaveShutterService.running) { Toast.makeText(this,"请先停止待命再更改",Toast.LENGTH_SHORT).show(); return; }
            ViaUi.radioDialog(this,"待命时长",new String[]{"1 分钟","3 分钟","5 分钟"},minutes()==1?0:minutes()==3?1:2,pick -> { prefs.edit().putInt("minutes",new int[]{1,3,5}[pick]).apply(); refresh(); }).show();
        }); content.addView(time);
        content.addView(text("使用方法",14,ViaUi.ACCENT));
        content.addView(text("先保持手机静止，等待校准完成，再按电源键熄屏。手在内置扬声器 / 麦克风附近靠近再移开算一次；连续完成指定次数，移开手并保持稳定后拍一张。拍摄完成才震动，之后冷却 3 秒。",14,ViaUi.TEXT));
        content.addView(text("口袋保护",14,ViaUi.ACCENT));
        content.addView(text("遮挡距离传感器、晃动、声路衰减或噪声过大会清空计数。解除遮挡后重新校准；缺少距离或加速度传感器时不允许待命。无法保证所有口袋和衣物都零误触，收起手机前建议停止待命。",13,ViaUi.TEXT_SUB));
        content.addView(action("照片记录",this::photos));
        content.addView(text("照片保存在本应用内，可查看、分享或导出。待命会占用后置相机和麦克风并持续耗电，通知栏可以停止；退出本页面后仍继续，系统结束或重启后不会自动恢复。",12,ViaUi.TEXT_SUB));
        setContentView(root); refresh();
    }
    private int count() { return prefs.getInt("waves",3)==2?2:3; }
    private int minutes() { int value=prefs.getInt("minutes",3); return value==1 || value==5?value:3; }
    private int dp(int v) { return ViaUi.dp(this,v); }
    private TextView text(String value,int size,int color) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(ViaUi.textColor(this,color)); t.setPadding(ViaUi.pageInset(this),dp(12),ViaUi.pageInset(this),dp(12)); return t; }
    private TextView action(String value,Runnable callback) { TextView t=text(value,14,ViaUi.ACCENT); t.setMinHeight(dp(52)); t.setGravity(Gravity.CENTER_VERTICAL); t.setFocusable(true); t.setBackgroundResource(R.drawable.bg_via_menu_cell); t.setOnClickListener(v -> callback.run()); return t; }
    private void requestStart() {
        ArrayList<String> missing=new ArrayList<>();
        for(String permission:new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO}) if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) missing.add(permission);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]),91); return; }
        new ViaDialogBuilder(this).setTitle("开启挥手快门？").setMessage("将开启后置相机和麦克风待命 "+minutes()+" 分钟，并发出低幅度 19 kHz 探测声。部分人能听见，请使用较低媒体音量，勿贴耳；不适时停止。音频只在内存分析，不保存或上传。\n\n挥手识别受机型、握法和遮挡影响；请先亮屏验证。照片不要求有人脸。")
                .setNegativeButton("取消",null).setPositiveButton("开启待命",(d,w) -> {
                    if(!visible) return;
                    try { ContextCompat.startForegroundService(this,new Intent(this,WaveShutterService.class).putExtra("waves",count()).putExtra("minutes",minutes())); }
                    catch(RuntimeException e) { WaveShutterService.status="无法开启待命："+e.getMessage(); refresh(); }
                }).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==91) { boolean allowed=results.length>0; for(int result:results) allowed &= result==PackageManager.PERMISSION_GRANTED;
            WaveShutterService.status=allowed?"权限已允许，请再次点击开启待命":"需要相机、麦克风和通知权限，可在系统应用设置中授权"; refresh(); }
    }
    private void refresh() {
        boolean active=WaveShutterService.running;
        status.setText(WaveShutterService.status+(active?"\n剩余 "+Math.max(0,(WaveShutterService.deadline-SystemClock.elapsedRealtime())/1000)+" 秒 · 已拍 "+WaveShutterService.shots+" 张":""));
        start.setText(active?"停止待命":"开启待命"); waves.setText("完整挥动次数 · "+count()+" 次"); time.setText("待命时长 · "+minutes()+" 分钟");
    }
    private final Runnable tick=new Runnable() { @Override public void run() { if(visible) { refresh(); handler.postDelayed(this,300); } } };
    private void photos() {
        File[] files=new File(getFilesDir(),"wave-photos").listFiles((dir,name) -> name.endsWith(".jpg"));
        if(files==null || files.length==0) { Toast.makeText(this,"暂无照片",Toast.LENGTH_SHORT).show(); return; }
        Arrays.sort(files,(a,b) -> Long.compare(b.lastModified(),a.lastModified()));
        String[] names=new String[files.length]; SimpleDateFormat date=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA);
        for(int i=0;i<files.length;i++) names[i]=date.format(new Date(files[i].lastModified()));
        ViaUi.listDialog(this,"照片记录",names,index -> photo(files[index])).show();
    }
    private void photo(File file) {
        ViaUi.listDialog(this,"照片",new String[]{"查看照片","分享照片","导出 JPEG"},choice -> {
            if(choice==2) { exporting=file; startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/jpeg").putExtra(Intent.EXTRA_TITLE,file.getName()),92); return; }
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",file);
            Intent send=choice==0?new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/jpeg"):new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM,uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(Intent.createChooser(send,choice==0?"查看照片":"分享照片")); }
            catch(ActivityNotFoundException e) { Toast.makeText(this,"没有可用应用，可选择导出 JPEG",Toast.LENGTH_SHORT).show(); }
        }).show();
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if(code==92 && result==RESULT_OK && data!=null && data.getData()!=null && exporting!=null) {
            File source=exporting; Uri uri=data.getData();
            new Thread(() -> {
                String message="照片已导出";
                try(InputStream in=new FileInputStream(source); OutputStream out=getContentResolver().openOutputStream(uri)) {
                    if(out==null) throw new IOException(); byte[] buffer=new byte[32768]; int n; while((n=in.read(buffer))!=-1) out.write(buffer,0,n);
                } catch(Exception e) { message="导出失败，请重试"; }
                String done=message; handler.post(() -> { if(!isDestroyed()) Toast.makeText(this,done,Toast.LENGTH_SHORT).show(); });
            },"WavePhotoExport").start();
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) { if(exporting!=null) state.putString("exporting",exporting.getName()); super.onSaveInstanceState(state); }
    @Override protected void onResume() { super.onResume(); visible=true; handler.post(tick); }
    @Override protected void onPause() { visible=false; handler.removeCallbacks(tick); super.onPause(); }
}
