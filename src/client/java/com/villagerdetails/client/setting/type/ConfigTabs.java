package com.villagerdetails.client.setting.type;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import com.villagerdetails.client.setting.row.ClientEntryRow;
import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.client.setting.row.ServerRuleRow;
import com.villagerdetails.rule.type.RuleCategoryType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 二级分类的构建：
 * <ul>
 *     <li>本机的一级分类 → 用 {@link SubCategory} 枚举</li>
 *     <li>「服务器」一级分类 → 用 {@link RuleCategoryType}（和 {@code /ec} 命令的分类一致），
 *         末尾再补「开启」「关闭」两个按状态过滤的分类</li>
 * </ul>
 */
public final class ConfigTabs {

    /** 「开启」过滤分类的 id */
    public static final String FILTER_ENABLED = "filter:enabled";
    /** 「关闭」过滤分类的 id */
    public static final String FILTER_DISABLED = "filter:disabled";

    private ConfigTabs() {
    }

    public static List<UiTab> build(MainCategory main) {
        if (main == MainCategory.SERVER) {
            return serverTabs();
        }
        return clientTabs(main);
    }

    private static List<UiTab> clientTabs(MainCategory main) {
        List<UiTab> tabs = new ArrayList<>();
        for (SubCategory sub : main.subCategories()) {
            tabs.add(new UiTab(sub.name(), sub.getDisplayName(),
                    () -> sub.options().stream()
                            .map(entry -> (ConfigRow) new ClientEntryRow(entry))
                            .toList()));
        }
        return tabs;
    }

    private static List<UiTab> serverTabs() {
        List<UiTab> tabs = new ArrayList<>();

        // 和 /ec 命令一致：按 RuleType 上的分类字段分组
        for (RuleCategoryType category : RuleCategoryType.values()) {
            List<RuleType> rules = visibleRules(RuleType.getRuleTypeListByRuleCategoryType(category));
            if (rules.isEmpty()) {
                continue;
            }
            tabs.add(new UiTab(category.name(), Component.translatable(category.getDisplayNameKey()),
                    () -> toRows(rules)));
        }

        // 再加上按状态过滤的两个分类
        tabs.add(new UiTab(FILTER_ENABLED, Component.translatable("villagerdetails.ui.rule_filter.enabled"),
                () -> rowsByState(true)));
        tabs.add(new UiTab(FILTER_DISABLED, Component.translatable("villagerdetails.ui.rule_filter.disabled"),
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
