package com.villagerdetails.client.setting.row;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.client.network.sync.ClientRequests;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import com.villagerdetails.client.setting.type.style.StyleType;
import com.villagerdetails.rule.type.RuleDataKind;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「服务器配置项」里的一行：{@code 规则名 … [列表] [重置]}。
 *
 * <p>和普通的服务器规则行（{@link ServerRuleRow}）不同：这一行的数据不放在规则状态里，
 * 而是「id → 值」的独立配置，所以右边只有一个「列表」按钮（点进去逐条配）和一个「重置」。
 * 列表按钮的渲染和「配置项 - 列表」那一项一样，都是 {@code [a,b]} 这种预览。
 *
 * <p>列表形态（纯名单 / id → 数字）来自服务端同步下来的标识，这里只是照着渲染。
 */
public record ServerRuleDataRow(RuleType rule, RuleDataKind kind) implements ConfigRow {

    @Override
    public String getRegisterName() {
        return rule.getRegisterName();
    }

    @Override
    public Component getDisplayName() {
        // 和 /ec 命令共用翻译键，保证显示一致
        return Component.translatable(rule.getDisplayNameKey());
    }

    @Override
    public Component getDisplayInfo() {
        return Component.translatable(rule.getDisplayInfoKey());
    }

    @Override
    public StyleType getStyle() {
        return StyleType.LIST;
    }

    /**
     * 这一行不显示开关（规则开关在「服务器」分类里改），这里只是把当前状态报出去
     */
    @Override
    public boolean getToggle() {
        return RuleCache.isEnabled(rule);
    }

    @Override
    public void setToggle(boolean value) {
        // 开关不在这条路径上改
    }

    /**
     * 服务端同步过来的当前数据（id → 值，保持服务端给的顺序）
     */
    public Map<String, String> data() {
        ServerRuleClientState.RuleData data = ServerRuleClientState.ruleData(rule);
        return data == null ? Map.of() : data.data();
    }

    /**
     * 列表按钮上的预览文字：{@code [id=值,…]}，纯名单就只显示 id
     */
    public Component listDisplay() {
        return preview(kind, data());
    }

    @Override
    public boolean isDefault() {
        return data().isEmpty();
    }

    @Override
    public void reset() {
        ClientRequests.sendRuleDataReset(rule);
    }

    /**
     * {@code [id=值,id=值,…]}；纯名单不显示 {@code =true}（业务上「在名单里」就是是）
     */
    private static Component preview(RuleDataKind kind, Map<String, String> data) {
        List<String> items = new ArrayList<>(data.size());
        for (Map.Entry<String, String> entry : data.entrySet()) {
            items.add(kind.isPlainList() ? entry.getKey() : entry.getKey() + "=" + entry.getValue());
        }
        return ListPreview.of(items);
    }
}
