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

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class RecoveryGridAdapter extends RecyclerView.Adapter<RecoveryGridAdapter.Holder> {
    public interface Listener {
        void onSelectionChanged();
        void onItemClicked(RecoveryItem item, int position);
        boolean onItemLongClicked(RecoveryItem item, int position);
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
        holder.typeBadgeView.setText(typeBadge(item));
        holder.metaView.setText(buildMeta(item));
        holder.pathView.setText(shortPath(item.path));
        holder.previewBadgeView.setText(canPreview(item.type)
                ? R.string.results_preview_available
                : R.string.results_open_details);
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
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) {
                return false;
            }
            return listener.onItemLongClicked(item, adapterPosition);
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

    private String buildMeta(RecoveryItem item) {
        StringBuilder builder = new StringBuilder();
        builder.append(RecoveryItem.formatSize(item.size));
        builder.append(" · ");
        builder.append(sourceLabel(item.sourceKind));
        if (item.modifiedAt > 0) {
            builder.append(" · ");
            builder.append(DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(item.modifiedAt)));
        }
        if (item.width > 0 && item.height > 0) {
            builder.append(" · ");
            builder.append(item.width).append("x").append(item.height);
        }
        return builder.toString();
    }

    private String sourceLabel(com.example.cleanrecovery.recovery.RecoverySourceKind sourceKind) {
        switch (sourceKind) {
            case MEDIASTORE_TRASH:
            case MEDIASTORE_PENDING:
                return context.getString(R.string.results_source_media_index);
            case GENERIC_THUMBNAIL:
            case OEM_GALLERY_CACHE:
            case KNOWN_CACHE_BLOB:
            case CARVED_FROM_KNOWN_BLOB:
                return context.getString(R.string.results_source_cache);
            case ACCESSIBLE_SIGNATURE_MATCH:
                return context.getString(R.string.results_source_signature);
            case VISIBLE_SHARED_FILE:
            default:
                return context.getString(R.string.results_source_storage);
        }
    }

    private String typeBadge(RecoveryItem item) {
        if (item.type == RecoveryType.IMAGE) {
            return context.getString(R.string.results_type_image_short);
        }
        if (item.type == RecoveryType.VIDEO) {
            return context.getString(R.string.results_type_video_short);
        }
        if (item.type == RecoveryType.AUDIO) {
            return context.getString(R.string.results_type_audio_short);
        }
        String name = item.name == null ? "" : item.name;
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            return name.substring(dot + 1).toUpperCase(Locale.US);
        }
        return context.getString(R.string.results_type_file_short);
    }

    private static boolean canPreview(RecoveryType type) {
        return type == RecoveryType.IMAGE
                || type == RecoveryType.VIDEO
                || type == RecoveryType.AUDIO;
    }

    private static String shortPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return path;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumbnailView;
        final TextView badgeView;
        final TextView nameView;
        final TextView metaView;
        final TextView pathView;
        final TextView typeBadgeView;
        final TextView previewBadgeView;
        final CheckBox checkbox;

        Holder(View itemView) {
            super(itemView);
            thumbnailView = itemView.findViewById(R.id.grid_thumbnail);
            badgeView = itemView.findViewById(R.id.grid_badge);
            nameView = itemView.findViewById(R.id.grid_name);
            metaView = itemView.findViewById(R.id.grid_meta);
            pathView = itemView.findViewById(R.id.grid_path);
            typeBadgeView = itemView.findViewById(R.id.grid_type_badge);
            previewBadgeView = itemView.findViewById(R.id.grid_preview_badge);
            checkbox = itemView.findViewById(R.id.grid_checkbox);
        }
    }
}
