package com.example.cleanrecovery.proxy;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 代理节点卡片适配器（FlClash 风格双列网格）。
 *
 * <p>支持按协议分组过滤（{@link #setGroupFilter}）、延迟展示
 * （{@link #applyLatency}）与选中态描边高亮。</p>
 */
public final class ProxyNodeAdapter extends RecyclerView.Adapter<ProxyNodeAdapter.VH> {

    public interface OnSelectListener {
        void onSelect(int position);
    }

    private static final int LATENCY_IDLE = -1;
    private static final int LATENCY_FAILED = -2;

    private final List<ProxyNode> nodes = new ArrayList<>();
    /** 过滤后展示的节点（指向 nodes 的子集）。 */
    private final List<ProxyNode> visible = new ArrayList<>();
    /** 与 nodes 一一对应的测速结果：-1 未测 / -2 失败 / &gt;0 毫秒。 */
    private int[] latency = new int[0];
    private String groupFilter = ProxyActivity.GROUP_ALL;
    private int selected = -1;
    private OnSelectListener listener;

    public void setListener(OnSelectListener l) { this.listener = l; }

    public void setNodes(List<ProxyNode> list) {
        nodes.clear();
        if (list != null) nodes.addAll(list);
        latency = new int[nodes.size()];
        java.util.Arrays.fill(latency, LATENCY_IDLE);
        selected = -1;
        refilter();
    }

    private int[] latency() { return latency; }

    public void setGroupFilter(String group) {
        groupFilter = group == null || group.isEmpty() ? ProxyActivity.GROUP_ALL : group;
        refilter();
    }

    public String groupFilter() { return groupFilter; }

    private void refilter() {
        visible.clear();
        for (ProxyNode n : nodes) {
            if (ProxyActivity.GROUP_ALL.equals(groupFilter)
                    || groupFilter.equalsIgnoreCase(n.protocol)) {
                visible.add(n);
            }
        }
        notifyDataSetChanged();
    }

    public int visibleCount() { return visible.size(); }

    public void setSelectedByNode(ProxyNode target) {
        selected = -1;
        if (target == null) {
            notifyDataSetChanged();
            return;
        }
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i) == target || eqNode(visible.get(i), target)) {
                selected = i;
                break;
            }
        }
        notifyDataSetChanged();
    }

    private static boolean eqNode(ProxyNode a, ProxyNode b) {
        return safe(a.name).equals(safe(b.name)) && a.port == b.port
                && safe(a.server).equals(safe(b.server));
    }

    private static String safe(String v) { return v == null ? "" : v; }

    /** 测速结果回填；index 为全量列表下标。 */
    public void applyLatency(int index, int msOrNull) {
        int[] arr = latency();
        if (index < 0 || index >= arr.length) return;
        arr[index] = msOrNull;
        notifyDataSetChanged();
    }

    public void clearLatency() {
        java.util.Arrays.fill(latency(), LATENCY_IDLE);
        notifyDataSetChanged();
    }

    public ProxyNode selectedNode() {
        return (selected >= 0 && selected < visible.size()) ? visible.get(selected) : null;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_proxy_node, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ProxyNode n = visible.get(position);
        h.name.setText(safe(n.name).isEmpty() ? ProxyNode.DEFAULT_NAME : n.name);

        String protocol = (n.protocol == null || n.protocol.isEmpty()
                ? "ss" : n.protocol).toUpperCase(Locale.ROOT);
        String endpoint = safe(n.server).isEmpty()
                ? h.itemView.getContext().getString(R.string.proxy_profile_local)
                : n.server + ":" + n.port;
        h.detail.setText(protocol + " · " + endpoint);

        int globalIndex = nodes.indexOf(n);
        int ms = (globalIndex >= 0 && globalIndex < latency().length)
                ? latency()[globalIndex] : LATENCY_IDLE;
        bindLatency(h.latency, ms);

        boolean active = position == selected;
        h.itemView.setBackgroundResource(active
                ? R.drawable.bg_proxy_card_active : R.drawable.bg_card);
        h.itemView.setOnClickListener(v -> {
            int p = h.getBindingAdapterPosition();
            if (p < 0 || p >= visible.size()) return;
            int old = selected;
            selected = p;
            if (old >= 0) notifyItemChanged(old);
            notifyItemChanged(p);
            if (listener != null) listener.onSelect(p);
        });
    }

    private static void bindLatency(TextView view, int ms) {
        int color;
        String text;
        if (ms == LATENCY_IDLE) {
            color = R.color.proxy_latency_idle;
            text = view.getContext().getString(R.string.proxy_latency_untested);
        } else if (ms == LATENCY_FAILED) {
            color = R.color.proxy_latency_bad;
            text = view.getContext().getString(R.string.proxy_latency_failed);
        } else {
            text = ms + " ms";
            if (ms < 200) color = R.color.proxy_latency_good;
            else if (ms < 500) color = R.color.proxy_latency_mid;
            else color = R.color.proxy_latency_bad;
        }
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(view.getContext(), color));
        view.setTypeface(Typeface.DEFAULT_BOLD);
    }

    @Override
    public int getItemCount() { return visible.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView detail;
        final TextView latency;

        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.proxy_item_name);
            detail = v.findViewById(R.id.proxy_item_detail);
            latency = v.findViewById(R.id.proxy_item_latency);
        }
    }
}
