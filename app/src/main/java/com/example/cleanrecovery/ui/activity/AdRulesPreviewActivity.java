package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.AdSubscriptionManager;
import com.example.cleanrecovery.ui.browser.ViaUi;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.util.List;

/** Via 规则订阅的预览页：完整规则列表，点行查看原文。 */
public final class AdRulesPreviewActivity extends Activity {
    public static final String EXTRA_URL = "subscription_url";
    public static final String EXTRA_TITLE = "subscription_title";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        SystemUiHelper.apply(this);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.WHITE);
        column.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setColorFilter(0xFF212121);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setBackgroundResource(R.drawable.bg_via_menu_cell);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = new TextView(this);
        title.setText(getIntent().getStringExtra(EXTRA_TITLE));
        title.setTextColor(0xFF212121);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        column.addView(bar, new LinearLayout.LayoutParams(-1, dp(53)));
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE5E5E5);
        column.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        List<String> rules = AdSubscriptionManager.readRuleLines(this,
                getIntent().getStringExtra(EXTRA_URL));
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(new RuleAdapter(rules));
        list.setOnItemClickListener((parent, view, position, id) ->
                new AlertDialog.Builder(this).setTitle("预览")
                        .setMessage(rules.get(position)).setPositiveButton("确定", null).show());
        column.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(column);
    }

    private int dp(float value) { return ViaUi.dp(this, value); }

    private final class RuleAdapter extends BaseAdapter {
        private final List<String> rules;
        RuleAdapter(List<String> rules) { this.rules = rules; }
        @Override public int getCount() { return rules.size(); }
        @Override public String getItem(int position) { return rules.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView ? (TextView) convertView : new TextView(AdRulesPreviewActivity.this);
            row.setText(getItem(position));
            row.setTextSize(14);
            row.setTextColor(0xFF333333);
            row.setSingleLine(true);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setBackgroundResource(R.drawable.bg_via_menu_cell);
            row.setLayoutParams(new FrameLayout.LayoutParams(-1, dp(48)));
            return row;
        }
    }
}
