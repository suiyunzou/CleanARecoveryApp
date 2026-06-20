package com.example.cleanrecovery.ui.adapter;

import com.example.cleanrecovery.recovery.RecoveryItem;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public final class RecoveryAdapter extends BaseAdapter {
    public interface SelectionListener {
        void onSelectionChanged();
    }

    public interface ItemClickListener {
        void onItemClicked(RecoveryItem item);
    }

    private final Context context;
    private final List<RecoveryItem> items;
    private final SelectionListener selectionListener;
    private final ItemClickListener itemClickListener;

    public RecoveryAdapter(
            Context context,
            List<RecoveryItem> items,
            SelectionListener selectionListener,
            ItemClickListener itemClickListener
    ) {
        this.context = context;
        this.items = items;
        this.selectionListener = selectionListener;
        this.itemClickListener = itemClickListener;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public RecoveryItem getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final Holder holder;
        if (convertView == null) {
            holder = createHolder();
            convertView = holder.root;
            convertView.setTag(holder);
        } else {
            holder = (Holder) convertView.getTag();
        }

        final RecoveryItem item = getItem(position);
        holder.checkbox.setOnCheckedChangeListener(null);
        holder.checkbox.setChecked(item.selected);
        holder.title.setText(item.name);
        holder.subtitle.setText(item.subtitle(context));
        holder.checkbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                item.selected = isChecked;
                selectionListener.onSelectionChanged();
            }
        });

        holder.root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                itemClickListener.onItemClicked(item);
            }
        });
        return convertView;
    }

    private Holder createHolder() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        CheckBox checkbox = new CheckBox(context);
        checkbox.setFocusable(false);
        root.addView(checkbox, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        root.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(context);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(false);
        textColumn.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setTextSize(12f);
        subtitle.setSingleLine(false);
        textColumn.addView(subtitle);

        return new Holder(root, checkbox, title, subtitle);
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Holder {
        final LinearLayout root;
        final CheckBox checkbox;
        final TextView title;
        final TextView subtitle;

        Holder(LinearLayout root, CheckBox checkbox, TextView title, TextView subtitle) {
            this.root = root;
            this.checkbox = checkbox;
            this.title = title;
            this.subtitle = subtitle;
        }
    }
}
