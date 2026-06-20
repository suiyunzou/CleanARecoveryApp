package com.example.cleanrecovery.ui.adapter;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.recovery.RecoveryItem;
import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.ui.widget.ThumbnailLoader;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public final class RecoveryGridAdapter extends RecyclerView.Adapter<RecoveryGridAdapter.Holder> {
    public interface Listener {
        void onSelectionChanged();
        void onItemClicked(RecoveryItem item, int position);
    }

    private final Context context;
    private final List<RecoveryItem> items;
    private final Listener listener;

    public RecoveryGridAdapter(Context context, List<RecoveryItem> items, Listener listener) {
        this.context = context;
        this.items = items;
        this.listener = listener;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_recovery_grid, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        final RecoveryItem item = items.get(position);
        holder.checkbox.setOnCheckedChangeListener(null);
        holder.checkbox.setChecked(item.selected);
        holder.nameView.setText(item.name);
        if (item.suspectedDeleted) {
            holder.badgeView.setText(R.string.status_deleted);
            holder.badgeView.setBackgroundResource(R.drawable.bg_badge_deleted);
            holder.badgeView.setTextColor(context.getResources().getColor(R.color.badge_deleted_text, context.getTheme()));
        } else {
            holder.badgeView.setText(R.string.status_existing);
            holder.badgeView.setBackgroundResource(R.drawable.bg_badge_existing);
            holder.badgeView.setTextColor(context.getResources().getColor(R.color.badge_existing_text, context.getTheme()));
        }
        int placeholder = placeholderFor(item.type);
        ThumbnailLoader.loadInto(holder.thumbnailView, item, placeholder);
        holder.checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            item.selected = isChecked;
            listener.onSelectionChanged();
        });
        holder.itemView.setClickable(true);
        holder.itemView.setOnClickListener(v -> listener.onItemClicked(item, holder.getBindingAdapterPosition()));
        holder.itemView.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("file path", item.path);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(context, context.getString(R.string.path_copied, item.path), Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static int placeholderFor(RecoveryType type) {
        if (type == RecoveryType.VIDEO) {
            return R.drawable.ic_type_video;
        }
        if (type == RecoveryType.AUDIO) {
            return R.drawable.ic_type_audio;
        }
        if (type == RecoveryType.DOCUMENT) {
            return R.drawable.ic_type_document;
        }
        return R.drawable.ic_type_image;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumbnailView;
        final TextView badgeView;
        final TextView nameView;
        final CheckBox checkbox;

        Holder(View itemView) {
            super(itemView);
            thumbnailView = itemView.findViewById(R.id.grid_thumbnail);
            badgeView = itemView.findViewById(R.id.grid_badge);
            nameView = itemView.findViewById(R.id.grid_name);
            checkbox = itemView.findViewById(R.id.grid_checkbox);
        }
    }
}
