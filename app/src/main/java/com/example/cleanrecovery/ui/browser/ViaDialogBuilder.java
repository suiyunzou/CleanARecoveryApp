package com.example.cleanrecovery.ui.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.example.cleanrecovery.R;

/** Via appearance for standard browser dialogs, preserving native dialog callbacks and validation. */
public final class ViaDialogBuilder extends AlertDialog.Builder {
    public ViaDialogBuilder(Context context) {
        super(context, new BrowserPrefs(context).nightMode()
                ? R.style.ViaDialogDark : R.style.ViaDialogLight);
    }

    @Override public ViaDialogBuilder setTitle(int title) {
        return setTitle(getContext().getText(title));
    }

    @Override public ViaDialogBuilder setTitle(CharSequence title) {
        TextView heading = new TextView(getContext());
        heading.setText(title);
        heading.setTextSize(18);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setTextColor(ViaUi.textColor(getContext(), ViaUi.TEXT));
        heading.setPadding(dp(16), dp(20), dp(16), dp(12));
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        super.setTitle(title);
        super.setCustomTitle(heading);
        return this;
    }

    @Override public AlertDialog create() {
        AlertDialog dialog = super.create();
        // Do not consume OnShowListener: callers use it to keep invalid inputs open.
        View decor = dialog.getWindow().getDecorView();
        decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { style(dialog); }
            @Override public void onViewDetachedFromWindow(View view) { }
        });
        return dialog;
    }

    @Override public AlertDialog show() {
        AlertDialog dialog = create();
        dialog.show();
        return dialog;
    }

    private void style(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setColor(ViaUi.surfaceColor(getContext()));
        background.setCornerRadius(dp(14));
        window.setBackgroundDrawable(background);
        window.setElevation(0);
        window.setDimAmount(0.35f);
        int available = getContext().getResources().getDisplayMetrics().widthPixels - dp(32);
        window.setLayout(Math.min(dp(350), Math.max(dp(1), available)), ViewGroup.LayoutParams.WRAP_CONTENT);
        // setLayout 后窗口默认锚在左侧，必须显式居中（对齐 Via 弹窗位置）
        window.setGravity(android.view.Gravity.CENTER);
        for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            button.setAllCaps(false);
            button.setTextSize(14);
            button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            button.setTextColor(ViaUi.ACCENT);
            button.setBackgroundResource(R.drawable.bg_via_toolbar_button);
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextSize(14);
            message.setTextColor(ViaUi.textColor(getContext(), ViaUi.TEXT_SUB));
            message.setPadding(dp(16), message.getPaddingTop(), dp(16), message.getPaddingBottom());
        }
        ListView list = dialog.getListView();
        if (list != null) {
            list.setDivider(null);
            list.setDividerHeight(0);
        }
        styleInputs(window.getDecorView());
    }

    private void styleInputs(View view) {
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            input.setBackgroundResource(R.drawable.bg_via_input);
            input.setTextColor(ViaUi.textColor(getContext(), ViaUi.TEXT));
            input.setHintTextColor(ViaUi.textColor(getContext(), ViaUi.TEXT_SUB));
            input.setBackgroundTintList(null);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) styleInputs(group.getChildAt(i));
        }
    }

    private int dp(float value) { return ViaUi.dp(getContext(), value); }
}
