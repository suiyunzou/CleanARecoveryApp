package com.example.cleanrecovery.experimental;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.ViaDialogBuilder;
import com.example.cleanrecovery.ui.browser.ViaUi;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;

/** Native Via-style experiments; the acoustic session is scoped to the visible page. */
public final class ExperimentalLabActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView title, watchStatus, watchButton, acousticStatus, acousticButton, gestureCount;
    private TextView tiltStatus, tiltButton;
    private SignalView signal;
    private WatchTimelineView history;
    private AcousticEngine engine;
    private boolean visible;
    private boolean pendingAcoustic, pendingWatch, recalibrate;
    private long gestureLabelUntil;
    private String lastGestureLabel="";
    private int page, gestures, position=50;
    private String lastHistory="";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (page==1) {
                watchStatus.setText(ItemWatchService.status);
                watchButton.setText(ItemWatchService.running ? "停止守护" : "开始守护");
                signal.add(ItemWatchService.strength/5f);
                renderHistory();
            } else if(page==3) {
                tiltStatus.setText(TiltGlassController.get().status());
                tiltButton.setText(new TiltGlassPrefs(ExperimentalLabActivity.this).enabled() ? "关闭全局效果" : "开启全局效果");
            }
            handler.postDelayed(this, 250);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean night = new BrowserPrefs(this).nightMode();
        getWindow().setStatusBarColor(night ? Color.BLACK : Color.WHITE);
        getWindow().setNavigationBarColor(night ? Color.BLACK : Color.WHITE);
        androidx.core.view.WindowInsetsControllerCompat insets = new androidx.core.view.WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        insets.setAppearanceLightStatusBars(!night); insets.setAppearanceLightNavigationBars(!night);
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setFitsSystemWindows(true);
        root.setBackgroundColor(night ? Color.BLACK : Color.WHITE);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ViaUi.toolbarInset(this),0,ViaUi.toolbarInset(this),0);
        ImageView back = icon(R.drawable.ic_chevron_left,"返回"); back.setPadding(dp(14),dp(14),dp(14),dp(14));
        back.setOnClickListener(v -> onBackPressed()); bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        title=text("趣味性实验",16,ViaUi.TEXT); title.setTypeface(null,Typeface.BOLD);
        bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        ImageView help=icon(R.drawable.ic_via_info,"使用说明"); help.setPadding(dp(14),dp(14),dp(14),dp(14));
        help.setOnClickListener(v -> help()); bar.addView(help,new LinearLayout.LayoutParams(dp(48),dp(48)));
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(54)));
        View line=new View(this); line.setBackgroundColor(night ? 0xff333333 : 0xffe5e5e5); root.addView(line,new LinearLayout.LayoutParams(-1,1));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        content=new LinearLayout(this); content.setOrientation(1); content.setPadding(0,0,0,dp(24));
        scroll.addView(content); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(state!=null) exportSnapshot=state.getString("export_snapshot");
        setContentView(root); showPage(state==null ? 0 : state.getInt("page",0));
    }
    private void showPage(int next) {
        if (page==2) stopAcoustic("离开页面，实验已停止");
        page=next; content.removeAllViews();
        title.setText(page==0 ? "趣味性实验" : page==1 ? "物品守护" : page==2 ? "声波隔空手势" : "倾斜渐进玻璃");
        if (page==0) {
            section("探索手机的另一面");
            row(R.drawable.ic_via_pulse,"挥手快门","独立拍照 · 限时熄屏待命",() -> startActivity(new Intent(this,WaveShutterActivity.class)));
            row(R.drawable.ic_type_image,"后置自拍助手","离线找脸 · 语音与震动引导",() -> startActivity(new Intent(this,RearSelfieActivity.class)));
            row(R.drawable.ic_via_shield,"物品守护","记录移动、转动与光线变化",() -> showPage(1));
            row(R.drawable.ic_via_pulse,"声波隔空手势","用手掌靠近、远离，推动光球",() -> showPage(2));
            row(R.drawable.ic_via_rotate,"倾斜渐进玻璃","全应用 · 随倾斜逐渐虚化",() -> showPage(3));
        } else if (page==1) {
            section("实时状态");
            watchStatus=paragraph(ItemWatchService.status,16,ViaUi.TEXT);
            signal=new SignalView(); content.addView(signal,new LinearLayout.LayoutParams(-1,dp(120)));
            watchButton=action("开始守护",() -> toggleWatch());
            section("事件时间线");
            history=new WatchTimelineView(this); content.addView(history,new LinearLayout.LayoutParams(-1,-2)); lastHistory=""; renderHistory();
            row(R.drawable.ic_via_share2,"导出记录",null,this::exportHistory);
            row(R.drawable.ic_via_history2,"清空记录",null,() -> new ViaDialogBuilder(this).setTitle("清空事件记录？")
                    .setMessage("仅清除此设备上的守护历史，正在运行的守护会继续记录。")
                    .setNegativeButton("取消",null).setPositiveButton("清空",(d,w) -> { LabHistory.clear(this); renderHistory(); }).show());
        } else if(page==2) {
            section("实验 · 靠近 / 远离");
            acousticStatus=paragraph(engine==null ? "未开始" : "正在停止上一轮",16,ViaUi.TEXT);
            signal=new SignalView(); signal.orb=true; content.addView(signal,new LinearLayout.LayoutParams(-1,dp(200)));
            gestures=0; position=50;
            gestureCount=paragraph("已识别 0 次 · 光球位置 50",13,ViaUi.TEXT_SUB);
            acousticButton=action(engine==null ? "开始实验" : "正在停止",this::toggleAcoustic);
            acousticButton.setEnabled(engine==null);
            paragraph("手机平放，先静止校准约 2 秒；手掌在扬声器附近约 10–30 厘米处靠近或远离。",12,ViaUi.TEXT_SUB);
            row(R.drawable.ic_via_refresh,"重新校准",null,() -> {
                if(engine==null) toggleAcoustic();
                else { stopAcoustic("正在重新校准"); recalibrate=true; }
            });
        } else {
            TiltGlassPrefs prefs=new TiltGlassPrefs(this);
            section("全应用 · 实时材质");
            tiltStatus=paragraph(TiltGlassController.get().status(),16,ViaUi.TEXT);
            tiltButton=action(prefs.enabled() ? "关闭全局效果" : "开启全局效果",() -> {
                prefs.setEnabled(!prefs.enabled());
                tiltButton.setText(prefs.enabled() ? "关闭全局效果" : "开启全局效果");
                tiltStatus.setText(TiltGlassController.get().status());
            });
            tiltButton.setEnabled(TiltGlassController.get().supported());
            paragraph("从倾斜侧向另一侧渐进虚化，相对基准达到 90° 时全屏虚化；转回基准时恢复清晰。",12,ViaUi.TEXT_SUB);
            section("材质与姿态");
            row(R.drawable.ic_via_pulse,"虚化强度",prefs.strength()+"%",() -> {
                android.app.Dialog dialog=ViaUi.sliderDialog(this,prefs.strength(),10,100,"%",prefs::setStrength);
                dialog.setOnDismissListener(d -> { if(!isFinishing() && page==3) showPage(3); });
                dialog.show();
            });
            row(R.drawable.ic_via_phone,"基准姿态",prefs.baselineLabel(),() -> {
                android.app.Dialog dialog=ViaUi.radioDialog(this,"基准姿态",
                        new String[]{"水平基准 · 屏幕朝上平放（0°）","角度基准 · "+prefs.readingAngle()+"°",
                                prefs.calibrated()?"自定义 · 使用已保存姿态":"自定义 · 以当前姿态校准"},prefs.baselineMode(),choice -> {
                            if(choice==TiltGlassPrefs.CUSTOM && !prefs.calibrated()) {
                                if(!TiltGlassController.get().calibrate()) {
                                    new ViaDialogBuilder(this).setTitle("暂时无法校准").setMessage("请先开启全局效果，并等待姿态传感器就绪。")
                                            .setPositiveButton("知道了",null).show();
                                    return;
                                }
                            } else prefs.setBaselineMode(choice);
                            showPage(3);
                        });
                dialog.show();
            });
            row(R.drawable.ic_via_phone,"起始角度",prefs.readingAngle()+"° · 0° 平放，90° 竖直",() -> {
                android.app.Dialog dialog=ViaUi.sliderDialog(this,prefs.readingAngle(),0,90,"°",prefs::setReadingAngle);
                dialog.setOnDismissListener(d -> { if(!isFinishing() && page==3) showPage(3); });
                dialog.show();
            });
            row(R.drawable.ic_via_refresh,"以当前握持姿态为基准","重新校准并保存自定义姿态",() -> {
                if(TiltGlassController.get().calibrate()) showPage(3);
                else new ViaDialogBuilder(this).setTitle("暂时无法校准").setMessage("请先开启全局效果，并等待姿态传感器就绪。")
                        .setPositiveButton("知道了",null).show();
            });
            section("四向联动");
            paragraph("向左倾斜 → 从左向右推进\n向右倾斜 → 从右向左推进\n前 / 后倾斜 → 从上 / 下边缘推进\n斜向倾斜 → 从对应角落推进\n90°及以上 → 全屏虚化",14,ViaUi.TEXT);
        }
        content.setAlpha(0); content.animate().alpha(1).setDuration(160).start();
    }
    private void toggleWatch() {
        if (ItemWatchService.running) { stopService(new Intent(this,ItemWatchService.class)); return; }
        if (getSystemService(SensorManager.class).getDefaultSensor(Sensor.TYPE_ACCELEROMETER)==null) {
            watchStatus.setText("本机没有加速度计，无法开启"); return;
        }
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},71); return;
        }
        startWatch();
    }
    private void startWatch() {
        try { ContextCompat.startForegroundService(this,new Intent(this,ItemWatchService.class)); }
        catch (Exception e) { ItemWatchService.status="无法开启守护："+e.getMessage(); }
    }
    private void toggleAcoustic() {
        if (engine!=null) { stopAcoustic("实验已停止"); return; }
        new ViaDialogBuilder(this).setTitle("开始声波实验")
                .setMessage("将使用麦克风和扬声器发出低幅度 19 kHz 高频声。部分人能听见，请使用较低媒体音量，勿贴耳；不适时停止。录音仅在内存分析，不保存或上传。\n\n请平放手机并保持周围静止，离开页面会停止。")
                .setNegativeButton("取消",null).setPositiveButton("开始",(d,w) -> {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},72);
                    else startAcoustic();
                }).show();
    }
    private void startAcoustic() {
        if (page!=2 || engine!=null) return;
        if (!visible) { pendingAcoustic=true; return; }
        acousticStatus.setText("正在校准，请保持静止…"); acousticButton.setText("停止实验");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        engine=new AcousticEngine(this,new AcousticEngine.Listener() {
            @Override public void onResult(AcousticDetector.Result r) {
                handler.post(() -> {
                    if (!visible || page!=2 || engine==null || engine.isStopping()) return;
                    String label;
                    switch(r.state) {
                        case CALIBRATING: label="正在校准，请保持静止…"; break;
                        case WEAK: label="探测信号太弱，无法判断"; break;
                        case NOISY: label="干扰较大，请保持环境安静"; break;
                        case TOWARD: label="检测到靠近（估计）"; position=Math.min(100,position+10); gestures++; break;
                        case AWAY: label="检测到远离（估计）"; position=Math.max(0,position-10); gestures++; break;
                        default: label="等待手势 · 手机保持静止";
                    }
                    if(r.state==AcousticDetector.State.TOWARD || r.state==AcousticDetector.State.AWAY) {
                        lastGestureLabel=label; gestureLabelUntil=SystemClock.elapsedRealtime()+650;
                    } else if(r.state==AcousticDetector.State.IDLE && SystemClock.elapsedRealtime()<gestureLabelUntil) label=lastGestureLabel;
                    acousticStatus.setText(label); signal.position=position/100f; signal.add(r.energy);
                    gestureCount.setText("已识别 "+gestures+" 次 · 光球位置 "+position);
                });
            }
            @Override public void onStopped(String reason) {
                handler.post(() -> {
                    engine=null; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (page==2) { acousticStatus.setText(reason); acousticButton.setText("开始实验"); acousticButton.setEnabled(true); }
                    if(recalibrate) { recalibrate=false; if(visible && page==2) startAcoustic(); }
                });
            }
        });
        engine.start();
    }
    private void stopAcoustic(String reason) {
        recalibrate=false;
        if (engine!=null) {
            engine.stop(reason);
            if (page==2 && acousticButton!=null) { acousticButton.setText("正在停止"); acousticButton.setEnabled(false); }
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if (isFinishing() || isDestroyed()) return;
        boolean granted=results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED;
        if (request==71 && page==1) {
            if (granted) { if(visible) startWatch(); else pendingWatch=true; }
            else ItemWatchService.status="通知未允许，请在系统设置允许通知后重试";
        }
        if (request==72 && page==2) {
            if (granted) startAcoustic();
            else acousticStatus.setText("麦克风未允许，无法进行声波实验；可在系统设置中授权");
        }
    }
    private void help() {
        new ViaDialogBuilder(this).setTitle("使用说明").setMessage(page==1
                ? "把手机与物品放在一起，点击开始后保持静止 3 秒。记录移动、转动、恢复静止和明显变亮的时间。\n\n支持熄屏运行，最长 8 小时，会持续耗电。通知栏可停止；系统强制结束或重启后不会自动恢复。只保留最近 200 条本地记录，不推断是谁或移动距离。"
                : page==2 ? "利用扬声器发声和麦克风接收的频移估计靠近、远离。光球随识别结果移动。\n\n仅支持前台实验，最长 5 分钟。环境运动和反射可能误触发；不测距离，不识别左右。不同手机高频响应不同，必须在真机验证。信号弱时不要持续提高音量，可停止实验。"
                : page==3 ? "效果应用到本应用页面和应用内弹窗，保留原有点击、滑动和输入。虚化从倾斜侧向另一侧推进，相对基准达到 90° 后整屏虚化，继续翻转不会突然变清晰。转回基准姿态时逐渐恢复。\n\n可选择水平（0°）、角度基准（拖动条可调 0°–90°）或自定义基准。调节起始角度会切换到角度基准。切换选项不会删除已保存的自定义姿态。新用户默认以屏幕与水平面呈 45° 为握持基准（平放为 0°，竖直为 90°），不是要求低头 45°。这是初始参考角度；可将自己舒适的握持姿态设为基准。偏离基准 3° 内保持清晰，偏离 90° 时全屏虚化。优先使用旋转矢量检测竖直握持时的左右转动；仅有重力传感器时，这种转动无法识别。已有基准可重新校准以启用完整方向检测。传感器只在前台使用；关闭开关会移除所有效果。透出的是页面自身的实时内容，不检测眼睛或真实视野边界。\n\n需要 Android 12+ 和硬件加速。独立 SurfaceView 视频画面、系统权限窗和输入法不属于这个合成层。该效果是 Android 实现，并非苹果系统材质。"
                : "物品守护：利用加速度和光线记录物品变化。\n\n声波隔空手势：利用高频声波的频移让光球移动。\n\n倾斜渐进玻璃：让整个应用随手机倾斜变化，在详情页可关闭和校准。\n\n均使用当前手机硬件，硬件不足时会提示；事件记录可在设备本地清除。")
                .setPositiveButton("知道了",null).show();
    }
    private String exportSnapshot;
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==74 && result==RESULT_OK && data!=null && data.getData()!=null) {
            try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())) {
                if(out==null) throw new java.io.IOException();
                out.write((exportSnapshot==null?historyText():exportSnapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Toast.makeText(this,"记录已保存",Toast.LENGTH_SHORT).show();
            } catch(Exception e) { Toast.makeText(this,"保存失败，请重新选择位置",Toast.LENGTH_LONG).show(); }
        }
    }
    private String historyText() {
        JSONArray array=LabHistory.read(this); StringBuilder result=new StringBuilder();
        SimpleDateFormat date=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA);
        for (int i=0;i<array.length();i++) {
            JSONObject event=array.optJSONObject(i); if(event==null) continue;
            if(result.length()>0) result.append("\n\n");
            result.append(date.format(new Date(event.optLong("time")))).append("  ").append(event.optString("message"));
        }
        return result.length()==0 ? "暂无记录" : result.toString();
    }
    private void renderHistory() {
        JSONArray events=LabHistory.read(this); String value=events.toString();
        if(!value.equals(lastHistory)) { history.setEvents(events); lastHistory=value; }
    }
    private void exportHistory() {
        ViaUi.listDialog(this,"导出守护记录",new String[]{"分享文本","保存为 TXT 文件"},choice -> {
            if(choice==0) {
                Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,historyText());
                startActivity(Intent.createChooser(send,"导出守护记录"));
            } else {
                exportSnapshot=historyText();
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain").putExtra(Intent.EXTRA_TITLE,"守护记录-"+System.currentTimeMillis()+".txt"),74);
            }
        }).show();
    }
    private TextView text(String value,int size,int color) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(ViaUi.textColor(this,color)); return t;
    }
    private TextView paragraph(String value,int size,int color) {
        TextView t=text(value,size,color); t.setPadding(ViaUi.pageInset(this),dp(12),ViaUi.pageInset(this),dp(12));
        t.setLineSpacing(dp(3),1); content.addView(t,new LinearLayout.LayoutParams(-1,-2)); return t;
    }
    private void section(String value) { paragraph(value,14,ViaUi.ACCENT); }
    private ImageView icon(int resource,String description) {
        ImageView image=new ImageView(this); image.setImageResource(resource); image.setColorFilter(ViaUi.textColor(this,ViaUi.TEXT));
        image.setContentDescription(description); image.setBackgroundResource(R.drawable.bg_via_menu_cell); return image;
    }
    private void row(int drawable,String label,String subtitle,Runnable click) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ViaUi.pageInset(this),dp(20),ViaUi.pageInset(this),dp(20)); row.setMinimumHeight(dp(60));
        ImageView image=icon(drawable,null); row.addView(image,new LinearLayout.LayoutParams(dp(22),dp(22)));
        LinearLayout labels=new LinearLayout(this); labels.setOrientation(1); labels.setPadding(dp(18),0,dp(12),0);
        labels.addView(text(label,14,ViaUi.TEXT)); if(subtitle!=null) labels.addView(text(subtitle,12,ViaUi.TEXT_SUB));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(icon(R.drawable.ic_chevron_right,null),new LinearLayout.LayoutParams(dp(14),dp(14)));
        row.setBackgroundResource(R.drawable.bg_via_menu_cell); row.setFocusable(true); row.setOnClickListener(v -> click.run());
        content.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private TextView action(String label,Runnable click) {
        TextView t=text(label,14,ViaUi.ACCENT); t.setGravity(Gravity.CENTER); t.setMinimumHeight(dp(48));
        t.setBackgroundResource(R.drawable.bg_via_menu_cell); t.setFocusable(true); t.setOnClickListener(v -> click.run());
        content.addView(t,new LinearLayout.LayoutParams(-1,-2)); return t;
    }
    private int dp(float value) { return ViaUi.dp(this,value); }
    @Override protected void onResume() {
        super.onResume(); visible=true; handler.post(refresh);
        if(pendingAcoustic) { pendingAcoustic=false; if(page==2) startAcoustic(); }
        if(pendingWatch) { pendingWatch=false; if(page==1) startWatch(); }
    }
    @Override protected void onPause() { visible=false; handler.removeCallbacks(refresh); stopAcoustic("离开页面，实验已停止"); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("page",page); state.putString("export_snapshot",exportSnapshot); super.onSaveInstanceState(state); }
    @Override public void onBackPressed() { if(page!=0) showPage(0); else super.onBackPressed(); }

    private final class SignalView extends View {
        final float[] values=new float[80]; final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        int cursor; boolean orb; float position=.5f;
        SignalView() { super(ExperimentalLabActivity.this); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void add(float value) { values[cursor++%values.length]=Math.max(0,Math.min(1,value)); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float left=ViaUi.pageInset(getContext()), width=getWidth()-left*2, middle=getHeight()/2f;
            paint.setColor(0xffc9c9c9); paint.setStrokeWidth(dp(1)); canvas.drawLine(left,middle,left+width,middle,paint);
            paint.setColor(ViaUi.ACCENT);
            if(orb) {
                float cx=left+dp(22)+(width-dp(44))*position;
                paint.setAlpha(30); canvas.drawCircle(cx,middle,dp(35),paint); paint.setAlpha(255); canvas.drawCircle(cx,middle,dp(18),paint);
            } else {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); Path path=new Path();
                for(int i=0;i<values.length;i++) {
                    float x=left+width*i/(values.length-1),y=middle-values[(cursor+i)%values.length]*middle*.8f;
                    if(i==0) path.moveTo(x,y); else path.lineTo(x,y);
                }
                canvas.drawPath(path,paint); paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}
