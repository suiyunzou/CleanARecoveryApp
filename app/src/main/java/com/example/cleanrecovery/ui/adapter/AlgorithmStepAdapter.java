package com.example.cleanrecovery.ui.adapter;

import com.example.cleanrecovery.R;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.algorithm.AlgorithmRegistry;
import com.example.cleanrecovery.algorithm.RecoveryAlgorithm;

import java.util.ArrayList;
import java.util.List;

public final class AlgorithmStepAdapter extends RecyclerView.Adapter<AlgorithmStepAdapter.ViewHolder> {
    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        SKIPPED,
        ERROR
    }

    public static final class Row {
        public final String algorithmId;
        public Status status = Status.PENDING;
        public int processed;
        public int found;
        public long durationMs;
        public String reason;

        public Row(String algorithmId) {
            this.algorithmId = algorithmId;
        }
    }

    private final Context context;
    private final List<Row> rows = new ArrayList<>();

    public AlgorithmStepAdapter(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setRows(List<Row> nextRows) {
        rows.clear();
        rows.addAll(nextRows);
        notifyDataSetChanged();
    }

    public void upsertRow(Row row) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).algorithmId.equals(row.algorithmId)) {
                rows.set(i, row);
                notifyItemChanged(i);
                return;
            }
        }
        rows.add(row);
        notifyItemInserted(rows.size() - 1);
    }

    public Row findRow(String algorithmId) {
        for (Row row : rows) {
            if (row.algorithmId.equals(algorithmId)) {
                return row;
            }
        }
        return null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_algorithm_step, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Row row = rows.get(position);
        holder.name.setText(resolveName(row.algorithmId));
        holder.status.setText(statusLabel(row.status));
        holder.status.setTextColor(statusColor(row.status));
        setDotColor(holder.statusDot, statusColor(row.status));

        if (row.status == Status.SKIPPED || row.status == Status.ERROR) {
            String reason = row.reason == null ? "" : row.reason;
            holder.detail.setText(context.getString(R.string.alg_step_skipped_reason, reason));
            holder.detail.setVisibility(reason.isEmpty() ? View.GONE : View.VISIBLE);
        } else if (row.status == Status.PENDING) {
            holder.detail.setVisibility(View.GONE);
        } else {
            holder.detail.setText(context.getString(
                    R.string.alg_step_stats,
                    row.processed,
                    row.found,
                    formatDuration(row.durationMs)
            ));
            holder.detail.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private String resolveName(String algorithmId) {
        RecoveryAlgorithm algorithm = AlgorithmRegistry.byId(algorithmId);
        if (algorithm != null && algorithm.displayNameResId() != 0) {
            return context.getString(algorithm.displayNameResId());
        }
        return algorithmId;
    }

    private String statusLabel(Status status) {
        switch (status) {
            case RUNNING:
                return context.getString(R.string.alg_status_running);
            case COMPLETED:
                return context.getString(R.string.alg_status_completed);
            case SKIPPED:
                return context.getString(R.string.alg_status_skipped);
            case ERROR:
                return context.getString(R.string.alg_status_error);
            case PENDING:
            default:
                return context.getString(R.string.alg_status_pending);
        }
    }

    private int statusColor(Status status) {
        switch (status) {
            case RUNNING:
                return context.getColor(R.color.status_running);
            case COMPLETED:
                return context.getColor(R.color.status_success);
            case SKIPPED:
                return context.getColor(R.color.status_skipped);
            case ERROR:
                return context.getColor(R.color.status_warning);
            case PENDING:
            default:
                return context.getColor(R.color.neutral_light);
        }
    }

    private static void setDotColor(View dot, int color) {
        GradientDrawable drawable = (GradientDrawable) dot.getBackground();
        if (drawable != null) {
            drawable.setColor(color);
        }
    }

    private String formatDuration(long durationMs) {
        if (durationMs < 1_000L) {
            return context.getString(R.string.alg_step_duration_ms, Math.max(0, durationMs));
        }
        return context.getString(R.string.alg_step_duration_sec, durationMs / 1000.0);
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final View statusDot;
        final TextView name;
        final TextView status;
        final TextView detail;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            statusDot = itemView.findViewById(R.id.alg_status_dot);
            name = itemView.findViewById(R.id.alg_step_name);
            status = itemView.findViewById(R.id.alg_step_status);
            detail = itemView.findViewById(R.id.alg_step_detail);
        }
    }
}
