package com.villagerdetails.client.setting.dto;

import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.type.server.ListSourceServer;
import com.villagerdetails.client.setting.util.McIds;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 本机配置项的列表（配置项 - 列表，例如床列表）。
 */
public record EntryListSource(ConfigEntryType entry) implements ListSourceServer {

    @Override
    public Component title() {
        return entry.getDisplayName();
    }

    @Override
    public Component description() {
        return entry.getDisplayInfo();
    }

    @Override
    public List<String> current() {
        return entry.getList();
    }

    @Override
    public void apply(List<String> values) {
        entry.setList(values);
        ClientRenderConfig.get().save();
    }

    @Override
    public boolean isValidId(String id) {
        // 本机配置项接受 MC 里任何存在的 id（条目 / 标签 / 注册表分类）
        return McIds.isKnown(id);
    }

    @Override
    public List<McIds.Entry> suggestions(String query, int limit) {
        // 本机配置项的补全走本地注册表索引（远端来源见 RuleListSource）
        return McIds.search(query, limit);
    }
}