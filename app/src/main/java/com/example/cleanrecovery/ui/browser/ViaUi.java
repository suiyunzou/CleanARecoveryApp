package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;

import com.example.cleanrecovery.R;

/**
 * VIA 视觉语言通用件：圆角白卡弹窗（单选/复选/滑杆/输入）、圆形开关。
 * 颜色/圆角/字号均对齐模拟器实测 VIA 7.2.1（.task/via-ref/）。
 */
public final class ViaUi {
    public static final int ACCENT = 0xFF6F8DE1;
    public static final int TEXT = 0xFF212121;
    public static final int TEXT_SUB = 0xFF757575;
    public static final int RING = 0xFFC9C9C9;

    private ViaUi() {
    }

    public static int textColor(Context context, int dayColor) {
        if (!new BrowserPrefs(context).nightMode()) return dayColor;
        if (dayColor == TEXT) return 0xFFCCCCCC;
        if (dayColor == TEXT_SUB) return 0xFF999999;
        return dayColor;
    }

    public static int surfaceColor(Context context) {
        return new BrowserPrefs(context).nightMode() ? 0xFF1C1C1E : Color.WHITE;
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** VIA 圆形开关：on=实心蓝圆，off=灰描边圆。 */
    public static ImageView switchView(Context c, boolean on) {
        ImageView v = new ImageView(c);
        renderSwitch(v, on);
        return v;
    }

    public static void renderSwitch(ImageView v, boolean on) {
        v.setImageResource(on ? R.drawable.bg_via_toggle_on : R.drawable.bg_via_toggle);
    }

    /** 圆角白卡容器（dialog 内容根）。 */
    private static LinearLayout card(Activity activity) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surfaceColor(activity));
        bg.setCornerRadius(dp(activity, 14));
        card.setBackground(bg);
        card.setPadding(dp(activity, 16), dp(activity, 18), dp(activity, 16), dp(activity, 8));
        return card;
    }

    private static Dialog bottomlessDialog(Activity activity, View content) {
        return bottomlessDialog(activity, content, 350);
    }

    private static Dialog bottomlessDialog(Activity activity, View content, int widthDp) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(dp(activity, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
            WindowManager.LayoutParams p = w.getAttributes();
            p.dimAmount = 0.35f;
            w.setAttributes(p);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return dialog;
    }

    private static TextView text(Activity a, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(textColor(a, color));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    /** 单选卡：点击选项立即生效并关闭。selected < 0 表示无选中。 */
    public static Dialog radioDialog(Activity a, String title, String[] options,
                                     int selected, OnPick pick) {
        return radioDialog(a, title, null, options, selected, pick);
    }

    public static Dialog radioDialog(Activity a, String title, String message, String[] options,
                                     int selected, OnPick pick) {
        LinearLayout card = card(a);
        if (title != null) {
            card.addView(text(a, title, 17, TEXT, true));
            card.addView(space(a, 10));
        }
        if (message != null) card.addView(text(a, message, 14, TEXT, false));
        ScrollView scroll = new ScrollView(a);
        LinearLayout choices = new LinearLayout(a);
        choices.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(choices, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final Dialog[] holder = new Dialog[1];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(a, 12), 0, dp(a, 12));
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.bg_via_menu_cell);
            ImageView dot = new ImageView(a);
            dot.setImageResource(idx == selected
                    ? R.drawable.bg_via_radio_on : R.drawable.bg_via_radio);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20));
            dotParams.rightMargin = dp(a, 18);
            row.addView(dot, dotParams);
            TextView label = text(a, options[idx], 16, TEXT, false);
            row.addView(label);
            row.setOnClickListener(v -> {
                holder[0].dismiss();
                if (pick != null) pick.onPick(idx);
            });
            choices.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        holder[0] = bottomlessDialog(a, card);
        return holder[0];
    }

    public interface OnPick {
        void onPick(int index);
    }

    /** 纯文字列表弹窗：点击选项立即生效并关闭（无单选圆圈，如“导入/备份书签”）。 */
    public static Dialog listDialog(Activity a, String title, String[] options, OnPick pick) {
        LinearLayout card = card(a);
        if (title != null) {
            card.addView(text(a, title, 17, TEXT, true));
            card.addView(space(a, 10));
        }
        final Dialog[] holder = new Dialog[1];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(a, 14), 0, dp(a, 14));
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.bg_via_menu_cell);
            TextView label = text(a, options[idx], 16, TEXT, false);
            row.addView(label);
            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                if (pick != null) pick.onPick(idx);
            });
            card.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        holder[0] = bottomlessDialog(a, card);
        return holder[0];
    }

    /** 复选卡：底部 取消/确定 文字按钮。 */
    public static void checkboxDialog(Activity a, String title, String[] options,
                                      boolean[] checked, OnConfirm confirm) {
        checkboxDialog(a, title, null, options, checked, confirm);
    }

    public static void checkboxDialog(Activity a, String title, String message, String[] options,
                                      boolean[] checked, OnConfirm confirm) {
        LinearLayout card = card(a);
        card.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        TextView titleView = text(a, title, 17, TEXT, true);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setMinHeight(dp(a, 38));
        card.addView(titleView);
        card.addView(space(a, 6));
        if (message != null && !message.isEmpty()) {
            TextView messageView = text(a, message, 15, TEXT, false);
            messageView.setGravity(Gravity.CENTER_VERTICAL);
            messageView.setMinHeight(dp(a, 35));
            card.addView(messageView);
        }
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(a, 11), 0, dp(a, 11));
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.bg_via_menu_cell);
            ImageView box = new ImageView(a);
            renderSwitch(box, checked[idx]);
            LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20));
            boxParams.rightMargin = dp(a, 18);
            row.addView(box, boxParams);
            row.addView(text(a, options[idx], 16, TEXT, false));
            row.setOnClickListener(v -> {
                checked[idx] = !checked[idx];
                renderSwitch(box, checked[idx]);
            });
            card.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(space(a, 10));
        final Dialog[] holder = new Dialog[1];
        LinearLayout buttons = buttonRow(a, () -> {
            if (holder[0] != null) holder[0].dismiss();
        }, () -> {
            if (holder[0] != null) holder[0].dismiss();
            if (confirm != null) confirm.onConfirm(checked);
        });
        for (int i = 0; i < buttons.getChildCount(); i++) buttons.getChildAt(i).setMinimumHeight(dp(a, 51));
        card.addView(buttons);
        holder[0] = bottomlessDialog(a, card, 338);
        Window checkboxWindow = holder[0].getWindow();
        if (checkboxWindow != null) {
            WindowManager.LayoutParams params = checkboxWindow.getAttributes();
            params.y = dp(a, 7);
            checkboxWindow.setAttributes(params);
        }
        holder[0].show();
    }

    public static Dialog jsMessageDialog(Activity activity, String title, String message,
                                         boolean confirm, boolean offerSuppression,
                                         java.util.function.BiConsumer<Boolean, Boolean> answer) {
        return jsMessageDialog(activity, title, message, confirm, offerSuppression, "取消", "确定", answer);
    }

    public static Dialog jsMessageDialog(Activity activity, String title, String message,
                                         boolean confirm, boolean offerSuppression, String cancelLabel,
                                         String acceptLabel, java.util.function.BiConsumer<Boolean, Boolean> answer) {
        return choiceDialog(activity, title, message, confirm,
                offerSuppression ? activity.getString(R.string.via_js_ignore_minute) : null,
                cancelLabel, acceptLabel, false, answer);
    }

    public static Dialog permissionDialog(Activity activity, String title, String message,
                                           java.util.function.BiConsumer<Boolean, Boolean> answer) {
        return choiceDialog(activity, title, message, true, "不再询问", "拒绝", "允许", true, answer);
    }

    private static Dialog choiceDialog(Activity activity, String title, String message,
                                       boolean confirm, String checkboxLabel, String cancelLabel,
                                       String acceptLabel, boolean cancelOnOutside,
                                       java.util.function.BiConsumer<Boolean, Boolean> answer) {
        LinearLayout content = card(activity);
        content.addView(text(activity, title, 17, TEXT, true));
        content.addView(space(activity, 12));
        content.addView(text(activity, message, 14, TEXT, false));
        boolean[] suppress = {false};
        if (checkboxLabel != null) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(activity, 14), 0, dp(activity, 4));
            ImageView box = switchView(activity, false);
            row.addView(box, new LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20)));
            TextView label = text(activity, checkboxLabel, 15, TEXT_SUB, false);
            label.setPadding(dp(activity, 14), 0, 0, 0);
            row.addView(label);
            row.setOnClickListener(v -> { suppress[0] = !suppress[0]; renderSwitch(box, suppress[0]); });
            content.addView(row);
        }
        Dialog dialog = bottomlessDialog(activity, content);
        boolean[] answered = {false};
        java.util.function.Consumer<Boolean> finish = accepted -> {
            if (answered[0]) return;
            answered[0] = true;
            dialog.dismiss();
            answer.accept(accepted, suppress[0]);
        };
        LinearLayout buttons = buttonRow(activity, () -> finish.accept(false), () -> finish.accept(true));
        ((TextView) buttons.getChildAt(0)).setText(cancelLabel);
        ((TextView) buttons.getChildAt(1)).setText(acceptLabel);
        if (!confirm) buttons.removeViewAt(0);
        content.addView(buttons);
        dialog.setCanceledOnTouchOutside(cancelOnOutside);
        dialog.setOnCancelListener(d -> {
            if (!answered[0]) { answered[0] = true; answer.accept(false, null); }
        });
        dialog.show();
        return dialog;
    }

    public static Dialog jsPromptDialog(Activity activity, String title, String message,
                                        String initial, java.util.function.Consumer<String> answer) {
        LinearLayout content = card(activity);
        content.addView(text(activity, title, 17, TEXT, true));
        content.addView(space(activity, 12));
        if (message != null && !message.isEmpty()) content.addView(text(activity, message, 14, TEXT, false));
        EditText input = new EditText(activity);
        styleInput(activity, input);
        input.setSingleLine(true);
        input.setText(initial == null ? "" : initial);
        input.setHint(initial);
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        Dialog dialog = bottomlessDialog(activity, content);
        boolean[] answered = {false};
        java.util.function.Consumer<String> finish = value -> {
            if (answered[0]) return;
            answered[0] = true;
            dialog.dismiss();
            answer.accept(value);
        };
        content.addView(buttonRow(activity, () -> finish.accept(null), () -> finish.accept(input.getText().toString())));
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> finish.accept(null));
        dialog.show();
        return dialog;
    }

    public interface OnConfirm {
        void onConfirm(boolean[] checked);
    }

    public static void passwordDialog(Activity a, String title, String message, OnPasswordOk onOk) {
        LinearLayout card = card(a);
        card.addView(text(a, title, 17, TEXT, true));
        if (message != null && !message.isEmpty()) {
            card.addView(space(a, 8));
            card.addView(text(a, message, 14, TEXT_SUB, false));
        }
        EditText password = new EditText(a);
        password.setHint("密码");
        password.setSingleLine(true);
        password.setTextSize(15);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        card.addView(password, new LinearLayout.LayoutParams(-1, -2));
        final Dialog[] holder = new Dialog[1];
        card.addView(buttonRow(a, () -> holder[0].dismiss(), () -> {
            if (password.getText().length() == 0) { password.requestFocus(); return; }
            String value = password.getText().toString();
            holder[0].dismiss();
            if (onOk != null) onOk.onOk(value);
        }));
        holder[0] = bottomlessDialog(a, card);
        holder[0].show();
    }

    public interface OnPasswordOk { void onOk(String password); }

    /** 底部 取消/确定 行。ok 为 null 时只显示取消。 */
    public static LinearLayout buttonRow(Activity a, Runnable onCancel, Runnable onOk) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView cancel = text(a, "取消", 15, ACCENT, false);
        cancel.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        cancel.setClickable(true);
        cancel.setBackgroundResource(R.drawable.bg_via_menu_cell);
        cancel.setOnClickListener(v -> {
            if (onCancel != null) onCancel.run();
        });
        row.addView(cancel);
        if (onOk != null) {
            TextView ok = text(a, "确定", 15, ACCENT, false);
            ok.setPadding(dp(a, 16), dp(a, 12), dp(a, 8), dp(a, 12));
            ok.setClickable(true);
            ok.setBackgroundResource(R.drawable.bg_via_menu_cell);
            ok.setOnClickListener(v -> onOk.run());
            row.addView(ok);
        }
        return row;
    }

    /** 输入卡：标题 + 若干下划线输入行 + 可选复选 + 取消/确定。 */
    public static void inputDialog(Activity a, String title, InputField field,
                                   String checkboxLabel, boolean checkboxOn,
                                   Runnable onCancel, OnInputOk onOk) {
        inputDialog(a, title, new InputField[]{field}, checkboxLabel, checkboxOn, onCancel, onOk);
    }

    public static void inputDialog(Activity a, String title, InputField[] fields,
                                   String checkboxLabel, boolean checkboxOn,
                                   Runnable onCancel, OnInputOk onOk) {
        inputDialog(a, title, null, fields, checkboxLabel, checkboxOn, onCancel, onOk);
    }

    public static void inputDialog(Activity a, String title, String message, InputField[] fields,
                                   String checkboxLabel, boolean checkboxOn,
                                   Runnable onCancel, OnInputOk onOk) {
        LinearLayout card = card(a);
        if (title != null && title.length() > 0) {
            card.addView(text(a, title, 17, TEXT, true));
            card.addView(space(a, 12));
        }
        if (message != null) { card.addView(text(a, message, 14, TEXT, false)); card.addView(space(a, 8)); }
        final EditText[] inputs = new EditText[fields.length];
        for (int i = 0; i < fields.length; i++) {
            inputs[i] = new EditText(a);
            inputs[i].setHint(fields[i].hint);
            inputs[i].setText(fields[i].initial == null ? "" : fields[i].initial);
            inputs[i].setTextSize(16);
            inputs[i].setTextColor(textColor(a, TEXT));
            inputs[i].setHintTextColor(0xFF9E9E9E);
            inputs[i].setBackgroundResource(R.drawable.bg_via_input);
            inputs[i].setPadding(dp(a, 2), dp(a, 10), dp(a, 2), dp(a, 12));
            inputs[i].setSingleLine(!fields[i].multiline);
            final int idx2 = i;
            if (fields[i].onFieldClick != null) {
                inputs[i].setFocusable(false);
                inputs[i].setClickable(true);
                inputs[i].setOnClickListener(v -> fields[idx2].onFieldClick.accept(inputs[idx2]));
            } else if (fields[i].onClick != null) {
                inputs[i].setFocusable(false);
                inputs[i].setClickable(true);
                inputs[i].setOnClickListener(v -> fields[idx2].onClick.run());
            }
            card.addView(inputs[i], new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        final ImageView[] check = checkboxLabel == null ? null : new ImageView[1];
        final boolean[] checkState = {checkboxOn};
        if (checkboxLabel != null) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(a, 14), 0, dp(a, 4));
            ImageView box = switchView(a, checkboxOn);
            check[0] = box;
            box.setOnClickListener(v -> {
                checkState[0] = !checkState[0];
                renderSwitch(box, checkState[0]);
            });
            row.addView(box, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
            TextView label = text(a, checkboxLabel, 15, 0xFF666666, false);
            label.setPadding(dp(a, 14), 0, 0, 0);
            row.addView(label);
            card.addView(row);
        }
        card.addView(space(a, 6));
        // 确定/取消都必须关闭弹窗（此前只跑回调，弹窗滞留）
        final Dialog[] holder = new Dialog[1];
        card.addView(buttonRow(a, () -> {
            if (holder[0] != null) holder[0].dismiss();
            if (onCancel != null) onCancel.run();
        }, () -> {
            String[] values = new String[inputs.length];
            for (int i = 0; i < inputs.length; i++) values[i] = inputs[i].getText().toString();
            boolean checkbox = checkState[0];
            if (holder[0] != null) holder[0].dismiss();
            if (onOk != null) onOk.onOk(values, checkbox);
        }));
        holder[0] = bottomlessDialog(a, card);
        holder[0].show();
    }

    public interface OnInputOk {
        void onOk(String[] values, boolean checkbox);
    }

    /** Via account dialog: username, revealable password, mandatory legal agreement. */
    public static Dialog loginDialog(Activity a, String username, Runnable openTerms,
                                     Runnable openPrivacy, OnInputOk onOk) {
        LinearLayout card = card(a);
        card.addView(text(a, "登录/注册", 17, TEXT, true));
        card.addView(space(a, 12));

        EditText user = new EditText(a);
        user.setHint("用户名 (账号不存在将自动创建)");
        user.setText(username == null ? "" : username);
        styleInput(a, user);
        card.addView(user, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout passwordRow = new FrameLayout(a);
        EditText password = new EditText(a);
        password.setHint("密码");
        password.setSingleLine(true);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        styleInput(a, password);
        password.setPadding(dp(a, 2), dp(a, 10), dp(a, 44), dp(a, 12));
        passwordRow.addView(password, new FrameLayout.LayoutParams(-1, -2));
        ImageView reveal = new ImageView(a);
        reveal.setImageResource(R.drawable.ic_via_password_hidden);
        reveal.setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 10));
        reveal.setContentDescription("显示密码");
        FrameLayout.LayoutParams revealParams = new FrameLayout.LayoutParams(dp(a, 44), dp(a, 52), Gravity.END);
        passwordRow.addView(reveal, revealParams);
        reveal.setOnClickListener(v -> {
            int selection = password.getSelectionStart();
            password.setTransformationMethod(password.getTransformationMethod() == null
                    ? PasswordTransformationMethod.getInstance() : null);
            password.setSelection(Math.max(0, selection));
        });
        card.addView(passwordRow, new LinearLayout.LayoutParams(-1, -2));

        final boolean[] agreed = {false};
        LinearLayout agreement = new LinearLayout(a);
        agreement.setGravity(Gravity.CENTER_VERTICAL);
        agreement.setPadding(0, dp(a, 14), 0, dp(a, 4));
        ImageView check = switchView(a, false);
        agreement.addView(check, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        String terms = "使用协议", privacy = "隐私政策";
        String sentence = "请阅读并同意 " + terms + " 与 " + privacy;
        SpannableString legal = new SpannableString(sentence);
        legal.setSpan(click(openTerms), sentence.indexOf(terms), sentence.indexOf(terms) + terms.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        legal.setSpan(click(openPrivacy), sentence.indexOf(privacy), sentence.indexOf(privacy) + privacy.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView label = text(a, legal.toString(), 15, 0xFF666666, false);
        label.setText(legal);
        label.setLinkTextColor(ACCENT);
        label.setMovementMethod(LinkMovementMethod.getInstance());
        label.setPadding(dp(a, 10), 0, 0, 0);
        agreement.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        View.OnClickListener toggle = v -> {
            agreed[0] = !agreed[0];
            renderSwitch(check, agreed[0]);
        };
        check.setOnClickListener(toggle);
        agreement.setOnClickListener(toggle);
        card.addView(agreement);

        final Dialog[] holder = new Dialog[1];
        card.addView(buttonRow(a, () -> holder[0].dismiss(), () -> {
            if (user.getText().length() == 0) { user.requestFocus(); return; }
            if (password.getText().length() == 0) { password.requestFocus(); return; }
            if (!agreed[0]) {
                agreement.setAlpha(0.45f);
                agreement.postDelayed(() -> agreement.setAlpha(1f), 180);
                return;
            }
            holder[0].dismiss();
            if (onOk != null) onOk.onOk(new String[]{user.getText().toString(),
                    password.getText().toString()}, true);
        }));
        holder[0] = bottomlessDialog(a, card);
        holder[0].show();
        return holder[0];
    }

    private static void styleInput(Activity a, EditText input) {
        input.setTextSize(16);
        input.setTextColor(textColor(a, TEXT));
        input.setHintTextColor(0xFF9E9E9E);
        input.setBackgroundResource(R.drawable.bg_via_input);
        input.setPadding(dp(a, 2), dp(a, 10), dp(a, 2), dp(a, 12));
        input.setSingleLine(true);
    }

    private static ClickableSpan click(Runnable action) {
        return new ClickableSpan() {
            @Override public void onClick(View widget) { if (action != null) action.run(); }
        };
    }

    public static final class InputField {
        final String hint;
        final String initial;
        final boolean multiline;
        /** 只读行（如添加书签的「目录」）：点击触发 onClick（对齐 Via 跳转行为） */
        public Runnable onClick;
        /** 只读行点击（可回填文本版本） */
        public androidx.core.util.Consumer<EditText> onFieldClick;

        public InputField(String hint, String initial) {
            this(hint, initial, false);
        }

        public InputField(String hint, String initial, boolean multiline) {
            this.hint = hint;
            this.initial = initial;
            this.multiline = multiline;
        }
    }

    /** VIA 字体大小弹窗：居中百分比 + 蓝色滑杆，即时回调，点外部关闭。 */
    public static Dialog sliderDialog(Activity a, int initial, int min, int max,
                                      String suffix, OnSlider change) {
        FrameLayout box = new FrameLayout(a);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surfaceColor(a));
        bg.setCornerRadius(dp(a, 16));
        card.setBackground(bg);
        int pad = dp(a, 20);
        card.setPadding(pad, dp(a, 16), pad, dp(a, 12));

        TextView value = text(a, initial + suffix, 17, TEXT, false);
        card.addView(value);
        SeekBar seek = new SeekBar(a);
        seek.setMax(max - min);
        seek.setProgress(initial - min);
        seek.getProgressDrawable().setTint(ACCENT);
        seek.getThumb().setTint(ACCENT);
        seek.setPadding(dp(a, 4), 0, dp(a, 4), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText((progress + min) + suffix);
                if (fromUser && change != null) change.onChange(progress + min);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        card.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(card, new FrameLayout.LayoutParams(dp(a, 300), ViewGroup.LayoutParams.WRAP_CONTENT));
        Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(box);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams p = w.getAttributes();
            p.dimAmount = 0.35f;
            w.setAttributes(p);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return dialog;
    }

    /** VIA 网页字体卡：50%..200%，每格 5%，站点入口显示作用范围。 */
    public static Dialog textZoomDialog(Activity a, int initial, boolean siteOnly,
                                        OnSlider change) {
        LinearLayout content = card(a);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(a, 16), dp(a, 28), dp(a, 16), dp(a, 18));

        int normalized = Math.max(50, Math.min(200, initial));
        normalized = 50 + Math.round((normalized - 50) / 5f) * 5;
        TextView value = text(a, normalized + "%", 17, TEXT, false);
        value.setGravity(Gravity.CENTER);
        content.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(space(a, 8));

        SeekBar seek = new SeekBar(a);
        seek.setMax(30);
        seek.setProgress((normalized - 50) / 5);
        seek.getProgressDrawable().setTint(ACCENT);
        seek.getThumb().setTint(ACCENT);
        seek.setPadding(0, 0, 0, 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int zoom = 50 + progress * 5;
                value.setText(zoom + "%");
                if (change != null) change.onChange(zoom);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        content.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (siteOnly) {
            content.addView(space(a, 6));
            TextView hint = text(a, "仅对当前网站生效", 13, TEXT_SUB, false);
            hint.setGravity(Gravity.CENTER);
            content.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return bottomlessDialog(a, content, 350);
    }

    public interface OnSlider {
        void onChange(int value);
    }

    private static Space space(Activity a, int heightDp) {
        Space s = new Space(a);
        s.setMinimumHeight(dp(a, heightDp));
        return s;
    }
}
