package com.villagerdetails.client.setting.row;

import com.villagerdetails.client.network.sync.ClientRequests;
import com.villagerdetails.client.setting.type.style.StyleType;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「服务端配置项」里的一行：{@code 规则名 … [列表] [重置]}。
 *
 * <p>和普通的服务器规则行（{@link ServerRuleRow}）不同：这一行的数据不放在规则状态里，
 * 而是「id → 值」的独立内容，所以右边只有一个「列表」按钮（点进去逐条配）和一个「重置」。
 *
 * <p>列表长什么样（纯名单 / id → 数字）完全由服务端下发的 {@link RuleDescriptor#template()} 决定，
 * 客户端只是照着渲染；内容也是服务端给的 {@link RuleDescriptor#data()}。
 */
public record ServerRuleDataRow(RuleDescriptor rule) implements ConfigRow {

    @Override
    public String getRegisterName() {
        return rule.registerName();
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(rule.name());
    }

    @Override
    public Component getDisplayInfo() {
        return Component.literal(rule.info());
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
        return rule.enabled();
    }

    @Override
    public void setToggle(boolean value) {
        // 开关不在这条路径上改
    }

    /**
     * 逐条配置的模板（纯名单 / 值控件形态 / id 来源）
     */
    public RuleDescriptor.ListTemplate template() {
        return rule.template();
    }

    /**
     * 服务端同步过来的当前数据（id → 值，保持服务端给的顺序）
     */
    public Map<String, String> data() {
        Map<String, String> data = new LinkedHashMap<>();
        for (RuleDescriptor.DataEntry entry : rule.data()) {
            data.put(entry.id(), entry.value());
        }
        return data;
    }

    /**
     * 列表按钮上的预览文字：{@code [id=值,…]}，纯名单就只显示 id
     */
    public Component listDisplay() {
        return preview(data());
    }

    @Override
    public boolean isDefault() {
        return rule.data().isEmpty();
    }

    @Override
    public void reset() {
        ClientRequests.sendRuleDataReset(rule.registerName());
    }

    /**
     * {@code [id=值,id=值,…]}；纯名单不显示 {@code =true}（业务上「在名单里」就是是）
     */
    private Component preview(Map<String, String> data) {
        boolean plain = rule.template() != null && rule.template().plain();
        List<String> items = new ArrayList<>(data.size());
        for (Map.Entry<String, String> entry : data.entrySet()) {
            items.add(plain ? entry.getKey() : entry.getKey() + "=" + entry.getValue());
        }
        return ListPreview.of(items);
    }
}
