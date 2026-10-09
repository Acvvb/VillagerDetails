package com.villagerdetails.client.setting.dto;

import com.villagerdetails.client.network.sync.ClientRequests;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import com.villagerdetails.client.setting.type.server.ListSourceServer;
import com.villagerdetails.rule.type.RuleDataKind;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器规则的名单（「服务器配置项」里那些纯名单规则，例如无挤压、可采集）。
 *
 * <p>列表形态是服务端同步下来的（{@code kind.isPlainList()} 为 true 才会用这一页），
 * 所以这里拿到的 id 范围 / 校验规则也来自 {@link RuleDataKind}。
 */
public record RuleListSource(RuleType rule, RuleDataKind kind) implements ListSourceServer {

    @Override
    public Component title() {
        return Component.translatable(rule.getDisplayNameKey());
    }

    @Override
    public Component description() {
        return Component.translatable(rule.getDisplayInfoKey());
    }

    @Override
    public List<String> current() {
        ServerRuleClientState.RuleData data = ServerRuleClientState.ruleData(rule);
        return data == null ? List.of() : new ArrayList<>(data.data().keySet());
    }

    @Override
    public void apply(List<String> values) {
        ServerRuleClientState.RuleData data = ServerRuleClientState.ruleData(rule);
        List<String> before = data == null ? List.of() : new ArrayList<>(data.data().keySet());

        // 新增的（名单里只有 true 这一种值）
        for (String id : values) {
            if (!before.contains(id)) {
                ClientRequests.sendRuleData(rule, id, "true");
            }
        }
        // 删掉的
        for (String id : before) {
            if (!values.contains(id)) {
                ClientRequests.sendRuleDataRemove(rule, id);
            }
        }
    }

    @Override
    public boolean isValidId(String id) {
        return kind.isValidId(id);
    }

    @Override
    public Registry<?> idRegistry() {
        return kind.registry();
    }
}