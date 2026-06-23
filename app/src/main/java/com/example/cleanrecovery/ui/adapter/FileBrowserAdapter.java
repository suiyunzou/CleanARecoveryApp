package com.example.cleanrecovery.ui.adapter;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.activity.FileBrowserActivity;
import com.example.cleanrecovery.ui.widget.FileBrowserMime;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FileBrowserAdapter extends RecyclerView.Adapter<FileBrowserAdapter.Holder> {
    public interface Listener {
        void onEntryClicked(FileBrowserActivity.FileEntry entry);

        void onEntryLongClicked(FileBrowserActivity.FileEntry entry);

        void onEntryInfoClicked(FileBrowserActivity.FileEntry entry);

        void onSelectionChanged(FileBrowserActivity.FileEntry entry, boolean selected);
    }

    private final List<FileBrowserActivity.FileEntry> entries;
    private final Listener listener;
    private boolean multiSelectMode;
    private final Set<String> selectedPaths = new HashSet<>();
    private String highlightedPath;

    public FileBrowserAdapter(List<FileBrowserActivity.FileEntry> entries, Listener listener) {
        this.entries = entries;
        this.listener = listener;
    }

    public void setMultiSelectMode(boolean enabled) {
        if (multiSelectMode == enabled) {
            return;
        }
        multiSelectMode = enabled;
        if (!enabled) {
            selectedPaths.clear();
        }
        notifyDataSetChanged();
    }

    public boolean isMultiSelectMode() {
        return multiSelectMode;
    }

    public void setSelectedPaths(Set<String> paths) {
        selectedPaths.clear();
        if (paths != null) {
            selectedPaths.addAll(paths);
        }
        notifyDataSetChanged();
    }

    public Set<String> getSelectedPaths() {
        return new HashSet<>(selectedPaths);
    }

    public int getSelectedCount() {
        return selectedPaths.size();
    }

    public void setHighlightedPath(String path) {
        highlightedPath = path;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file_browser_entry, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(entries.get(position), listener, multiSelectMode, selectedPaths, highlightedPath);
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final CheckBox checkbox;
        private final ImageView icon;
        private final TextView name;
        private final TextView meta;
        private final ImageButton info;

        Holder(@NonNull View itemView) {
            super(itemView);
            checkbox = itemView.findViewById(R.id.file_entry_checkbox);
            icon = itemView.findViewById(R.id.file_entry_icon);
            name = itemView.findViewById(R.id.file_entry_name);
            meta = itemView.findViewById(R.id.file_entry_meta);
            info = itemView.findViewById(R.id.file_entry_info);
        }

        void bind(
                final FileBrowserActivity.FileEntry entry,
                final Listener listener,
                boolean multiSelectMode,
                Set<String> selectedPaths,
                String highlightedPath
        ) {
            name.setText(entry.name);
            itemView.setBackgroundResource(entry.file.getAbsolutePath().equals(highlightedPath)
                    ? R.drawable.bg_file_entry_download_hint
                    : R.drawable.bg_card);
            checkbox.setOnCheckedChangeListener(null);
            if (entry.directory) {
                icon.setImageResource(R.drawable.ic_nav_folder);
                icon.setColorFilter(null);
                meta.setText(R.string.file_browser_folder);
            } else {
                FileBrowserMime.Kind kind = FileBrowserMime.kindFor(entry.file, entry.name, false);
                icon.setImageResource(iconForKind(kind));
                icon.clearColorFilter();
                meta.setText(entry.formattedMeta());
            }

            checkbox.setVisibility(multiSelectMode ? View.VISIBLE : View.GONE);
            checkbox.setChecked(selectedPaths.contains(entry.file.getAbsolutePath()));
            checkbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    listener.onSelectionChanged(entry, isChecked);
                }
            });

            info.setVisibility(multiSelectMode ? View.GONE : View.VISIBLE);
            info.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    listener.onEntryInfoClicked(entry);
                }
            });

            itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (multiSelectMode) {
                        checkbox.setChecked(!checkbox.isChecked());
                    } else {
                        listener.onEntryClicked(entry);
                    }
                }
            });
            itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    if (multiSelectMode) {
                        return false;
                    }
                    listener.onEntryLongClicked(entry);
                    return true;
                }
            });
        }

        private static int iconForKind(FileBrowserMime.Kind kind) {
            switch (kind) {
                case VIDEO:
                    return R.drawable.ic_type_video;
                case IMAGE:
                    return R.drawable.ic_type_image;
                case AUDIO:
                    return R.drawable.ic_type_audio;
                case TEXT:
                    return R.drawable.ic_type_text;
                case PDF:
                    return R.drawable.ic_type_pdf;
                case ZIP:
                    return R.drawable.ic_type_zip;
                case APK:
                    return R.drawable.ic_type_apk;
                case DOC:
                    return R.drawable.ic_type_doc;
                case XLS:
                    return R.drawable.ic_type_xls;
                case PPT:
                    return R.drawable.ic_type_ppt;
                case UNKNOWN:
                default:
                    return R.drawable.ic_type_unknown;
            }
        }
    }
}
