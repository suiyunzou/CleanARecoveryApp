package com.example.cleanrecovery.experimental;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.example.cleanrecovery.ui.browser.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Event markers only: no fabricated continuous motion or quiet intervals. */
public final class WatchTimelineView extends LinearLayout {
    private final SimpleDateFormat date=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA);
    public WatchTimelineView(Context context) { super(context); setOrientation(VERTICAL); }
    public static String kind(String message) {
        if(message.contains("无法") || message.contains("中断")) return "unknown";
        if(message.contains("停止")) return "stop";
        if(message.startsWith("开始守护")) return "start";
        if(message.contains("变亮")) return "light";
        if(message.contains("恢复静止") || message.contains("已就绪")) return "still";
        if(message.contains("移动") || message.contains("转动")) return "motion";
        return "unknown";
    }
    private int color(String kind) {
        switch(kind) {
            case "motion": return 0xffd88336;
            case "light": return 0xffb39520;
            case "start": case "still": return 0xff458975;
            case "stop": return 0xff888888;
            default: return ViaUi.ACCENT;
        }
    }
    private String symbol(String kind) {
        switch(kind) {
            case "motion": return "↝";
            case "light": return "☀";
            case "start": return "▶";
            case "still": return "✓";
            case "stop": return "■";
            default: return "?";
        }
    }
    private TextView text(String value,int size,int color) {
        TextView view=new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private int dp(int n) { return ViaUi.dp(getContext(),n); }
    public void setEvents(JSONArray array) {
        removeAllViews(); setPadding(ViaUi.pageInset(getContext()),0,ViaUi.pageInset(getContext()),0);
        List<JSONObject> events=new ArrayList<>();
        for(int i=0;i<array.length();i++) if(array.optJSONObject(i)!=null) events.add(array.optJSONObject(i));
        if(events.isEmpty()) { addView(text("暂无记录 · 开始守护后显示事件图",13,ViaUi.textColor(getContext(),ViaUi.TEXT_SUB))); return; }
        addView(text("最近 "+events.size()+" 条事件 · 点击查看详情",12,ViaUi.textColor(getContext(),ViaUi.TEXT_SUB)));
        addView(new Distribution(events),new LayoutParams(-1,dp(116)));
        addView(text("↝ 移动   ☀ 光线   ✓ 静止   ■ 停止",12,ViaUi.textColor(getContext(),ViaUi.TEXT_SUB)));
        addView(text("仅表示事件发生时刻；标记间隔不代表持续静止。",11,ViaUi.textColor(getContext(),ViaUi.TEXT_SUB)));
        for(int i=0;i<events.size();i++) {
            JSONObject event=events.get(i); String message=event.optString("message"),kind=event.optString("type",kind(message));
            LinearLayout row=new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
            final int ink=color(kind); final boolean last=i==events.size()-1; final String glyph=symbol(kind);
            View marker=new View(getContext()) {
                final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas canvas) {
                    float x=getWidth()/2f,y=getHeight()/2f;
                    paint.setColor(0xffcccccc); paint.setStrokeWidth(dp(1));
                    canvas.drawLine(x,0,x,last?y:getHeight(),paint);
                    paint.setColor(ink); canvas.drawCircle(x,y,dp(11),paint);
                    paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(14));
                    canvas.drawText(glyph,x,y-(paint.ascent()+paint.descent())/2,paint);
                }
            };
            row.addView(marker,new LayoutParams(dp(30),-1));
            LinearLayout labels=new LinearLayout(getContext()); labels.setOrientation(VERTICAL); labels.setPadding(dp(12),dp(14),0,dp(14));
            labels.addView(text(date.format(new Date(event.optLong("time"))),12,ViaUi.textColor(getContext(),ViaUi.TEXT_SUB)));
            labels.addView(text(message,14,ViaUi.textColor(getContext(),ViaUi.TEXT)));
            row.addView(labels,new LayoutParams(0,-2,1));
            row.setBackgroundResource(com.example.cleanrecovery.R.drawable.bg_via_menu_cell);
            row.setFocusable(true); row.setContentDescription(date.format(new Date(event.optLong("time")))+"，"+message);
            row.setOnClickListener(v -> detail(event)); addView(row,new LayoutParams(-1,-2));
        }
    }
    private void detail(JSONObject event) {
        new ViaDialogBuilder(getContext()).setTitle(date.format(new Date(event.optLong("time"))))
                .setMessage(event.optString("message")).setPositiveButton("知道了",null).show();
    }
    private final class Distribution extends View {
        private final List<JSONObject> events; private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long first,last;
        Distribution(List<JSONObject> list) {
            super(WatchTimelineView.this.getContext()); events=new ArrayList<>(list);
            events.sort(Comparator.comparingLong(e -> e.optLong("time")));
            first=events.get(0).optLong("time"); last=events.get(events.size()-1).optLong("time");
            setContentDescription("事件时间分布图，详情见下方可点击时间线");
        }
        private float x(JSONObject event) { return dp(8)+(getWidth()-dp(16))*(last==first?.5f:(event.optLong("time")-first)/(float)(last-first)); }
        @Override protected void onDraw(Canvas c) {
            p.setStrokeWidth(dp(1)); p.setColor(0xffbbbbbb); c.drawLine(dp(8),dp(72),getWidth()-dp(8),dp(72),p);
            for(JSONObject e:events) {
                String type=e.optString("type",kind(e.optString("message"))); float x=x(e);
                int lane=type.equals("motion")?0:type.equals("light")?1:2;
                p.setColor(color(type)); c.drawLine(x,dp(20+lane*17),x,dp(72),p); c.drawCircle(x,dp(20+lane*17),dp(4),p);
            }
            p.setColor(ViaUi.textColor(getContext(),ViaUi.TEXT_SUB)); p.setTextSize(dp(10)); p.setTextAlign(Paint.Align.LEFT);
            c.drawText(date.format(new Date(first)),0,dp(96),p); p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(date.format(new Date(last)),getWidth(),dp(110),p);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if(e.getAction()==android.view.MotionEvent.ACTION_DOWN) return true;
            if(e.getAction()==android.view.MotionEvent.ACTION_UP) {
                JSONObject nearest=events.get(0);
                for(JSONObject item:events) if(Math.abs(x(item)-e.getX())<Math.abs(x(nearest)-e.getX())) nearest=item;
                detail(nearest); performClick(); return true;
            }
            return super.onTouchEvent(e);
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
