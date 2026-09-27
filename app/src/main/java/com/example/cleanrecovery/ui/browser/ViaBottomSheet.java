package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Content-only sheet: custom Via controls, bounded height and scrollable large text. */
public final class ViaBottomSheet {
    private ViaBottomSheet() {}
    public static LinearLayout content(Activity a) {
        LinearLayout card = new LinearLayout(a); card.setOrientation(1);
        card.setPadding(ViaUi.dp(a, 28), ViaUi.dp(a, 28), ViaUi.dp(a, 28), ViaUi.dp(a, 28));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(ViaUi.surfaceColor(a));
        bg.setCornerRadius(ViaUi.dp(a, 22)); card.setBackground(bg);
        View handle = new View(a); GradientDrawable line = new GradientDrawable();
        line.setColor(0xFFB8BCC2); line.setCornerRadius(ViaUi.dp(a, 3)); handle.setBackground(line);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViaUi.dp(a, 44), ViaUi.dp(a, 6));
        hp.gravity = Gravity.CENTER_HORIZONTAL; hp.bottomMargin = ViaUi.dp(a, 24); card.addView(handle, hp);
        return card;
    }
    public static TextView text(Activity a, String value, int size, int color) {
        TextView t = new TextView(a); t.setText(value); t.setTextSize(size); t.setTextColor(ViaUi.textColor(a, color));
        return t;
    }
    public static TextView button(Activity a, String value, boolean primary) {
        TextView t = text(a, value, 15, primary ? Color.WHITE : ViaUi.ACCENT);
        t.setGravity(Gravity.CENTER); t.setMinHeight(ViaUi.dp(a, 48));
        if (primary) { GradientDrawable bg = new GradientDrawable(); bg.setColor(ViaUi.ACCENT);
            bg.setCornerRadius(ViaUi.dp(a, 16)); t.setBackground(bg); t.setTextColor(Color.WHITE);
        } else t.setBackgroundResource(com.example.cleanrecovery.R.drawable.bg_via_menu_cell);
        t.setClickable(true); t.setFocusable(true); return t;
    }
    public static TextView outlineButton(Activity a, String value) {
        TextView t = button(a, value, false); t.setTextColor(0xFF999999);
        GradientDrawable shape = new GradientDrawable(); shape.setColor(Color.TRANSPARENT);
        shape.setCornerRadius(ViaUi.dp(a, 16)); shape.setStroke(ViaUi.dp(a, 1), 0xFFBBBBBB);
        t.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x18999999), shape, null));
        return t;
    }
    public static ScrollView scroll(Activity a, View content) { return scroll(a, content, .82f); }
    public static ScrollView scroll(Activity a, View content, float maxFraction) {
        ScrollView scroll = new ScrollView(a) {
            @Override protected void onMeasure(int width, int height) {
                int max = (int) (getResources().getDisplayMetrics().heightPixels * maxFraction);
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false); scroll.addView(content); return scroll;
    }
    public static Dialog show(Activity a, LinearLayout content) { return show(a, content, .82f); }
    public static Dialog show(Activity a, LinearLayout content, float maxFraction) {
        Dialog dialog = new Dialog(a); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(scroll(a, content, maxFraction)); Window w = dialog.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); w.setDimAmount(.48f);
        dialog.show(); configure(a, w); return dialog;
    }
    public static void configure(Activity a, Window w) {
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        w.setLayout(Math.min(ViaUi.dp(a, 480), a.getResources().getDisplayMetrics().widthPixels), -2);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); w.setDimAmount(.48f);
    }
}
