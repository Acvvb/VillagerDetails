package com.villagerdetails.client.setting.tabs;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.client.setting.dto.UiTabsSource;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.client.setting.row.ServerRuleDataRow;
import com.villagerdetails.client.setting.row.ServerRuleRow;
import com.villagerdetails.client.setting.type.FirstCategoryType;
import com.villagerdetails.client.setting.type.SecondCategoryType;
import com.villagerdetails.rule.type.RuleCategoryType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 二级分类的构建：
 * <ul>
 *     <li>本机的一级分类 → 用 {@link SecondCategoryType} 枚举</li>
 *     <li>「服务器」一级分类 → 用 {@link RuleCategoryType}（和 {@code /ec} 命令的分类一致），
 *         末尾再补「开启」「关闭」两个按状态过滤的分类</li>
 * </ul>
 */
public final class ConfigTabs {

    /**
     * 「开启」过滤分类的 id
     */
    public static final String FILTER_ENABLED = "filter:enabled";
    /**
     * 「关闭」过滤分类的 id
     */
    public static final String FILTER_DISABLED = "filter:disabled";

    private ConfigTabs() {
    }

    public static List<UiTabsSource> build(FirstCategoryType main) {
        if (main == FirstCategoryType.SERVER) {
            return serverTabs();
        }
        if (main == FirstCategoryType.SERVER_CONFIG) {
            return ruleDataTabs();
        }
        return clientTabs(main);
    }

    /**
     * 「服务器配置项」的二级分类：依旧按规则分类（{@link RuleCategoryType}）走，
     * 但只放「服务端下发了数据（= 支持逐条配置 + 有权限）+ 规则已开启」的那几条规则；
     * 某个分类下一条都没有就跳过。
     *
     * <p>哪些规则能出现在这里<b>完全由服务端同步下来的内容决定</b>，客户端不写死规则名单。
     * 这里也不加「开启 / 关闭」两个过滤页签 —— 能出现在这个分类里的规则全都是开着的。
     */
    private static List<UiTabsSource> ruleDataTabs() {
        List<RuleType> supported = ServerRuleClientState.configurableRules();

        List<UiTabsSource> tabs = new ArrayList<>();
        for (RuleCategoryType category : RuleCategoryType.values()) {
            List<RuleType> rules = supported.stream()
                    .filter(rule -> rule.getCategory().contains(category))
                    .toList();
            if (rules.isEmpty()) {
                continue;
            }
            tabs.add(new UiTabsSource(category.name(), Component.translatable(category.getDisplayNameKey()),
                    () -> rules.stream()
                            .map(rule -> (ConfigRow) new ServerRuleDataRow(
                                    rule, ServerRuleClientState.ruleData(rule).kind()))
                            .toList()));
        }
        return tabs;
    }

    private static List<UiTabsSource> clientTabs(FirstCategoryType main) {
        List<UiTabsSource> tabs = new ArrayList<>();
        for (SecondCategoryType sub : main.subCategories()) {
            tabs.add(new UiTabsSource(sub.name(), sub.getDisplayName(),
                    () -> sub.options().stream()
                            .map(entry -> (ConfigRow) entry)
                            .toList()));
        }
        return tabs;
    }

    private static List<UiTabsSource> serverTabs() {
        List<UiTabsSource> tabs = new ArrayList<>();

        // 和 /ec 命令一致：按 RuleType 上的分类字段分组
        for (RuleCategoryType category : RuleCategoryType.values()) {
            List<RuleType> rules = visibleRules(RuleType.getRuleTypeListByRuleCategoryType(category));
            if (rules.isEmpty()) {
                continue;
            }
            tabs.add(new UiTabsSource(category.name(), Component.translatable(category.getDisplayNameKey()),
                    () -> toRows(rules)));
        }

        // 再加上按状态过滤的两个分类
        tabs.add(new UiTabsSource(FILTER_ENABLED, Component.translatable("villagerdetails.ui.rule_filter.enabled"),
                () -> rowsByState(true)));
        tabs.add(new UiTabsSource(FILTER_DISABLED, Component.translatable("villagerdetails.ui.rule_filter.disabled"),
                () -> rowsByState(false)));

        return tabs;
    }

    private static List<ConfigRow> rowsByState(boolean enabled) {
        return toRows(visibleRules(Arrays.stream(RuleType.values())
                .filter(rule -> RuleCache.isEnabled(rule) == enabled)
                .toList()));
    }

    /**
     * 只留下这个玩家有权限看的规则。
     *
     * <p>服务端只会下发有权限的那些（没权限的压根收不到），所以这里用收到的清单过滤；
     * 过滤完为空的分类不显示。
     */
    private static List<RuleType> visibleRules(List<RuleType> rules) {
        return rules.stream()
                .filter(ServerRuleClientState::isRuleVisible)
                .toList();
    }

    private static List<ConfigRow> toRows(List<RuleType> rules) {
        List<ConfigRow> rows = new ArrayList<>(rules.size());
        for (RuleType rule : rules) {
            rows.add(new ServerRuleRow(rule));
        }
        return rows;
    }
}
