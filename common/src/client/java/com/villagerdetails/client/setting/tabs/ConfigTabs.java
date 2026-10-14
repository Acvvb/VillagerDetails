package com.villagerdetails.client.setting.tabs;

import com.villagerdetails.client.setting.dto.UiTabsSource;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.client.setting.row.ServerRuleDataRow;
import com.villagerdetails.client.setting.row.ServerRuleRow;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.type.FirstCategoryType;
import com.villagerdetails.client.setting.type.SecondCategoryType;
import com.villagerdetails.client.setting.type.style.StyleType;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 二级分类的构建：
 * <ul>
 *     <li>本机的一级分类（配置项 / 功能开关）→ 用 {@link SecondCategoryType} 枚举</li>
 *     <li>「服务器」「服务端配置」→ 用<b>服务端下发的分类顺序</b>（{@code RuleDescriptor.Category}），
 *         客户端不再拿自己的 {@code RuleCategoryType} 枚举去分组 —— 服务端新增分类时老客户端也认</li>
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
     * 「服务端配置项」的二级分类：依旧按规则分类走，但只放「服务端下发了逐条配置模板
     * <b>并且</b>规则开着」的那几条规则；某个分类下一条都没有就跳过。
     *
     * <p>哪些规则能出现在这里、叫什么名字，<b>完全由服务端同步下来的描述符决定</b>，
     * 客户端不写死规则名单。这里也不加「开启 / 关闭」两个过滤页签 —— 能出现在这个分类里的规则全都是开着的。
     */
    private static List<UiTabsSource> ruleDataTabs() {
        List<RuleDescriptor> supported = ServerRuleClientState.configurableRules();

        List<UiTabsSource> tabs = new ArrayList<>();
        for (RuleDescriptor.Category category : ServerRuleClientState.categories()) {
            List<RuleDescriptor> rules = supported.stream()
                    .filter(rule -> rule.categories().contains(category.registerName()))
                    .toList();
            if (rules.isEmpty()) {
                continue;
            }
            tabs.add(new UiTabsSource(category.registerName(), Component.literal(category.text()),
                    () -> rules.stream()
                            .map(rule -> (ConfigRow) new ServerRuleDataRow(rule))
                            .toList()));
        }
        return tabs;
    }

    private static List<UiTabsSource> clientTabs(FirstCategoryType main) {
        List<UiTabsSource> tabs = new ArrayList<>();
        for (SecondCategoryType sub : main.subCategories()) {
            List<ConfigEntryType> entries = dynamicSubEntries(main, sub);
            if (entries == null) {
                entries = sub.options();
            }
            if (entries.isEmpty()) {
                continue;
            }
            List<ConfigRow> rows = entries.stream()
                    .map(entry -> (ConfigRow) entry)
                    .toList();
            tabs.add(new UiTabsSource(sub.name(), sub.getDisplayName(), () -> rows));
        }
        return tabs;
    }

    /**
     * 「功能开关」下的四个动态页签：归属不写死在配置项上，按一级分类 + 样式 / 当前状态过滤。
     *
     * <p>返回 {@code null} 表示这不是动态页签，走 {@link SecondCategoryType#options()} 的静态归属。
     */
    private static List<ConfigEntryType> dynamicSubEntries(FirstCategoryType main, SecondCategoryType sub) {
        return switch (sub) {
            case TOGGLE_ALL -> Arrays.stream(ConfigEntryType.values())
                    .filter(entry -> entry.getMainCategories().contains(main))
                    .toList();
            case TOGGLE_ENABLED -> Arrays.stream(ConfigEntryType.values())
                    .filter(entry -> entry.getMainCategories().contains(main))
                    .filter(entry -> entry.getStyle() == StyleType.TOGGLE)
                    .filter(ConfigEntryType::getToggle)
                    .toList();
            case TOGGLE_DISABLED -> Arrays.stream(ConfigEntryType.values())
                    .filter(entry -> entry.getMainCategories().contains(main))
                    .filter(entry -> entry.getStyle() == StyleType.TOGGLE)
                    .filter(entry -> !entry.getToggle())
                    .toList();
            case TOGGLE_TRIGGER -> Arrays.stream(ConfigEntryType.values())
                    .filter(entry -> entry.getMainCategories().contains(main))
                    .filter(entry -> entry.getStyle() == StyleType.TRIGGER)
                    .toList();
            default -> null;
        };
    }

    private static List<UiTabsSource> serverTabs() {
        List<UiTabsSource> tabs = new ArrayList<>();

        // 分类顺序与文字都由服务端给（和 /ec 命令一致的那套分类，但客户端不查自己的枚举）
        for (RuleDescriptor.Category category : ServerRuleClientState.categories()) {
            List<RuleDescriptor> rules = ServerRuleClientState.rulesIn(category.registerName());
            if (rules.isEmpty()) {
                continue;
            }
            tabs.add(new UiTabsSource(category.registerName(), Component.literal(category.text()),
                    () -> toRows(rules)));
        }

        // 再加上按状态过滤的两个分类：纯模板逻辑（读服务端给的 enabled 字段），客户端补得起
        tabs.add(new UiTabsSource(FILTER_ENABLED, Component.translatable("villagerdetails.ui.rule_filter.enabled"),
                () -> rowsByState(true)));
        tabs.add(new UiTabsSource(FILTER_DISABLED, Component.translatable("villagerdetails.ui.rule_filter.disabled"),
                () -> rowsByState(false)));

        return tabs;
    }

    private static List<ConfigRow> rowsByState(boolean enabled) {
        return ServerRuleClientState.rules().stream()
                .filter(rule -> rule.enabled() == enabled)
                .map(rule -> (ConfigRow) new ServerRuleRow(rule))
                .toList();
    }

    private static List<ConfigRow> toRows(List<RuleDescriptor> rules) {
        List<ConfigRow> rows = new ArrayList<>(rules.size());
        for (RuleDescriptor rule : rules) {
            rows.add(new ServerRuleRow(rule));
        }
        return rows;
    }
}
