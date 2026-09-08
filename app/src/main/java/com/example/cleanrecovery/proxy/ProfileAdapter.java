package com.example.cleanrecovery.proxy;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置（订阅）卡片适配器：名称 + 使用中标签 + ⋮菜单 +
 * 域名/来源·节点数 + 相对更新时间，激活态描边高亮。
 */
public final class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.VH> {

    public interface OnActionsListener {
        void onOpen(ProxySubscription sub);

        void onMenu(ProxySubscription sub, View anchor);
    }

    private final List<ProxySubscription> items = new ArrayList<>();
    private final Map<String, Integer> nodeCounts = new HashMap<>();
    private String activeId = "";
    private OnActionsListener listener;

    public void setListener(OnActionsListener l) { this.listener = l; }

    public void setItems(List<ProxySubscription> list, String activeId,
                         Map<String, Integer> counts) {
        items.clear();
        if (list != null) items.addAll(list);
        nodeCounts.clear();
        if (counts != null) nodeCounts.putAll(counts);
        this.activeId = activeId == null ? "" : activeId;
        notifyDataSetChanged();
    }

    public ProxySubscription get(int position) {
        return (position >= 0 && position < items.size()) ? items.get(position) : null;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_proxy_profile, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ProxySubscription sub = items.get(position);
        boolean active = sub.id.equals(activeId);

        h.name.setText(sub.name == null || sub.name.trim().isEmpty()
                ? h.itemView.getContext().getString(R.string.proxy_title) : sub.name);
        h.activeTag.setVisibility(active ? View.VISIBLE : View.GONE);
        h.itemView.setBackgroundResource(active
                ? R.drawable.bg_proxy_card_active : R.drawable.bg_card);

        String host = hostOf(sub.url);
        Integer count = nodeCounts.get(sub.id);
        int nodeCount = count == null ? 0 : count;
        String source = host.isEmpty()
                ? h.itemView.getContext().getString(R.string.proxy_profile_local)
                : host;
        h.info.setText(h.itemView.getContext().getString(
                R.string.proxy_profile_nodes, nodeCount) + " · " + source);

        h.updated.setText(relativeUpdated(h, sub.updatedAt));

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onOpen(sub);
        });
        h.menu.setOnClickListener(v -> {
            if (listener != null) listener.onMenu(sub, h.menu);
        });
    }

    private static String hostOf(String url) {
        if (url == null) return "";
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        return s;
    }

    private String relativeUpdated(VH h, long updatedAt) {
        if (updatedAt <= 0) {
            return h.itemView.getContext().getString(R.string.proxy_profile_updated_never);
        }
        long diff = System.currentTimeMillis() - updatedAt;
        long minutes = diff / 60000L;
        if (minutes < 1) {
            return h.itemView.getContext().getString(R.string.proxy_profile_updated_just);
        }
        if (minutes < 60) {
            return h.itemView.getContext().getString(
                    R.string.proxy_profile_updated_min, (int) minutes);
        }
        return h.itemView.getContext().getString(
                R.string.proxy_profile_updated_hour, (int) (minutes / 60));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView activeTag;
        final TextView info;
        final TextView updated;
        final ImageButton menu;

        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.proxy_profile_name);
            activeTag = v.findViewById(R.id.proxy_profile_active_tag);
            info = v.findViewById(R.id.proxy_profile_info);
            updated = v.findViewById(R.id.proxy_profile_updated);
            menu = v.findViewById(R.id.proxy_profile_menu);
        }
    }
}
