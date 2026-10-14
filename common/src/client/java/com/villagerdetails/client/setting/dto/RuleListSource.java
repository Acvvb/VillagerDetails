package com.villagerdetails.client.setting.dto;

import com.villagerdetails.client.network.sync.ClientRequests;
import com.villagerdetails.client.setting.network.RuleAuxCache;
import com.villagerdetails.client.setting.type.server.ListSourceServer;
import com.villagerdetails.client.setting.util.McIds;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器规则的名单（「服务端配置项」里那些纯名单规则，例如无挤压、可采集）。
 *
 * <p>列表形态、内容、候选清单全部来自服务端：
 * <ul>
 *     <li>名称 / 描述：描述符里翻译好的文本</li>
 *     <li>当前有哪些 id：描述符里的 {@code data}</li>
 *     <li>补全候选：按需问服务端要（{@link RuleAuxCache}），客户端只做子串过滤</li>
 * </ul>
 */
public record RuleListSource(RuleDescriptor rule) implements ListSourceServer {

    @Override
    public Component title() {
        return Component.literal(rule.name());
    }

    @Override
    public Component description() {
        return Component.literal(rule.info());
    }

    @Override
    public void prepare() {
        RuleAuxCache.requestOnce(rule.registerName());
    }

    @Override
    public List<String> current() {
        List<String> ids = new ArrayList<>(rule.data().size());
        for (RuleDescriptor.DataEntry entry : rule.data()) {
            ids.add(entry.id());
        }
        return ids;
    }

    @Override
    public void apply(List<String> values) {
        List<String> before = current();

        // 新增的（名单里只有 true 这一种值）
        for (String id : values) {
            if (!before.contains(id)) {
                ClientRequests.sendRuleData(rule.registerName(), id, "true");
            }
        }
        // 删掉的
        for (String id : before) {
            if (!values.contains(id)) {
                ClientRequests.sendRuleDataRemove(rule.registerName(), id);
            }
        }
    }

    @Override
    public boolean isValidId(String id) {
        return RuleAuxCache.knowsId(rule.registerName(), id);
    }

    @Override
    public String normalizeId(String id) {
        return RuleAuxCache.canonicalId(id);
    }

    @Override
    public List<McIds.Entry> suggestions(String query, int limit) {
        return RuleAuxCache.suggestions(rule.registerName(), query, limit);
    }
}
