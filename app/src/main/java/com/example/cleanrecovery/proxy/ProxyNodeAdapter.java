package com.example.cleanrecovery.proxy;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 代理节点列表适配器（单选）。 */
public final class ProxyNodeAdapter extends RecyclerView.Adapter<ProxyNodeAdapter.VH> {

    public interface OnSelectListener {
        void onSelect(int position);
    }

    private final List<ProxyNode> nodes = new ArrayList<>();
    private int selected = -1;
    private OnSelectListener listener;

    public void setListener(OnSelectListener l) { this.listener = l; }

    public void setNodes(List<ProxyNode> list) {
        nodes.clear();
        if (list != null) nodes.addAll(list);
        selected = -1;
        notifyDataSetChanged();
    }

    public List<ProxyNode> nodes() { return nodes; }

    public void setSelected(int pos) {
        if (pos < 0 || pos >= nodes.size()) return;
        int old = selected;
        selected = pos;
        if (old >= 0) notifyItemChanged(old);
        notifyItemChanged(pos);
    }

    public int selected() { return selected; }

    public ProxyNode selectedNode() {
        return (selected >= 0 && selected < nodes.size()) ? nodes.get(selected) : null;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_proxy_node, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ProxyNode n = nodes.get(position);
        h.name.setText(n.name == null ? ProxyNode.DEFAULT_NAME : n.name);
        String protocol = n.protocol == null ? "SS" : n.protocol.toUpperCase(Locale.ROOT);
        String endpoint = n.server == null || n.server.isEmpty()
                ? "配置节点" : n.server + ":" + n.port;
        String cipher = n.cipher == null || n.cipher.isEmpty()
                ? "" : n.cipher + " · ";
        h.detail.setText(h.itemView.getContext().getString(
                R.string.proxy_node_detail, cipher, endpoint));
        h.protocol.setText(protocol);
        h.radio.setChecked(position == selected);
        h.itemView.setOnClickListener(v -> {
            int p = h.getAdapterPosition();
            if (p < 0) return;
            setSelected(p);
            if (listener != null) listener.onSelect(p);
        });
    }

    @Override
    public int getItemCount() { return nodes.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView detail;
        final TextView protocol;
        final RadioButton radio;

        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.proxy_item_name);
            detail = v.findViewById(R.id.proxy_item_detail);
            protocol = v.findViewById(R.id.proxy_item_protocol);
            radio = v.findViewById(R.id.proxy_item_radio);
        }
    }
}
