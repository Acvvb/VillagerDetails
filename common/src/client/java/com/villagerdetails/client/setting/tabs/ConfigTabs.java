package com.villagerdetails.client.setting.tabs;

import com.villagerdetails.client.setting.dto.UiTabsSource;
import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.client.setting.row.ServerRuleDataRow;
import com.villagerdetails.client.setting.row.ServerRuleRow;
import com.villagerdetails.client.setting.rule.ServerRuleHandler;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.type.FirstCategoryType;
import com.villagerdetails.client.setting.type.SecondCategoryType;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 二级分类的构建：
 * <ul>
 *     <li>本机的一级分类（配置项 / 功能开关）→ 用 {@link SecondCategoryType} 枚举</li>
 *     <li>「服务器」「服务端配置」→ 用<b>服务端下发的分类顺序</b>（{@code RuleDescriptor.Category}），
 *         客户端不再拿自己的 {@code RuleCategoryType} 枚举去分组 —— 服务端新增分类时老客户端也认</li>
 * </ul>
 *
 * <p>输出统一是 {@link UiTabsSource}：一个 id + 一个显示名 + 一个「点开这个页签时现取行列表」的
 * {@code Supplier}。行列表延迟求值，所以开关类页签（开启 / 关闭）在每次重建界面时都能反映最新状态。
 */
public final class ConfigTabs {

    /**
     * 「开启」过滤分类的 id（一级「服务器」下，按 {@code enabled == true} 过滤）
     */
    public static final String FILTER_ENABLED = "filter:enabled";
    /**
     * 「关闭」过滤分类的 id（一级「服务器」下，按 {@code enabled == false} 过滤）
     */
    public static final String FILTER_DISABLED = "filter:disabled";

    private ConfigTabs() {
    }

    /**
     * 按一级分类构建二级页签列表。
     *
     * <p>三个分支：
     * <ul>
     *     <li>{@link FirstCategoryType#SERVER} → {@link #serverTabs()}，服务端规则 + 两个状态过滤页；</li>
     *     <li>{@link FirstCategoryType#SERVER_CONFIG} → {@link #ruleDataTabs()}，服务端逐条配置模板；</li>
     *     <li>其余（本机的 OPTIONS / TOGGLES）→ {@link #clientTabs(FirstCategoryType)}，走本地枚举。</li>
     * </ul>
     *
     * @param main 一级分类
     * @return 二级页签列表；某个二级分类下一条都没有时会被跳过，可能返回空列表
     */
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
        // 服务端下发了逐条配置模板、且规则本身处于开启状态的那些规则
        List<RuleDescriptor> supported = ServerRuleHandler.INSTANCE.configurableRules();

        List<UiTabsSource> tabs = new ArrayList<>();
        // 分类顺序也听服务端的：老客户端遇到新分类不会瞎猜，直接按 registerName 归组
        for (RuleDescriptor.Category category : ServerRuleHandler.INSTANCE.categories()) {
            List<RuleDescriptor> rules = supported.stream()
                    .filter(rule -> rule.categories().contains(category.registerName()))
                    .toList();
            if (rules.isEmpty()) {
                continue;   // 这个分类下没东西，不出空页签
            }
            tabs.add(new UiTabsSource(category.registerName(), Component.literal(category.text()),
                    () -> rules.stream()
                            .map(rule -> (ConfigRow) new ServerRuleDataRow(rule))
                            .toList()));
        }
        return tabs;
    }

    /**
     * 本机一级分类下的二级页签：遍历 {@code main.subCategories()}，每个二级分类产出一个页签。
     *
     * <p>哪些配置项进哪一栏，全部交给 {@link SecondCategoryType#entriesOf}：
     * 判断类型的页签（全部 / 开关 / 触发器 / 开启 / 关闭）按谓词现算，
     * 其余按配置项自己声明的静态归属。这里不再写 {@code switch}、也不再判断「是不是动态页签」。
     *
     * @param main 一级分类（OPTIONS 或 TOGGLES）
     */
    private static List<UiTabsSource> clientTabs(FirstCategoryType main) {
        List<UiTabsSource> tabs = new ArrayList<>();
        for (SecondCategoryType sub : main.subCategories()) {
            List<ConfigEntryType> entries = sub.entriesOf(main);
            if (entries.isEmpty()) {
                continue;   // 这个二级分类下没东西，不出空页签
            }
            List<ConfigRow> rows = entries.stream()
                    .map(entry -> (ConfigRow) entry)
                    .toList();
            // 行列表在点开页签时才求值（Supplier），保证页签内容反映当前配置值
            tabs.add(new UiTabsSource(sub.name(), sub.getDisplayName(), () -> rows));
        }
        return tabs;
    }

    /**
     * 「服务器」一级分类的二级页签：
     * <ol>
     *     <li>先按服务端下发的分类顺序生成规则页签（空分类跳过）；</li>
     *     <li>末尾再补「开启 / 关闭」两个状态过滤页签。</li>
     * </ol>
     *
     * <p>两个过滤页签的行列表同样是延迟求值（每次点开时重读 {@link ServerRuleHandler}），
     * 所以规则状态变了不需要重建页签。
     */
    private static List<UiTabsSource> serverTabs() {
        List<UiTabsSource> tabs = new ArrayList<>();

        // 分类顺序与文字都由服务端给（和 /ec 命令一致的那套分类，但客户端不查自己的枚举）
        for (RuleDescriptor.Category category : ServerRuleHandler.INSTANCE.categories()) {
            List<RuleDescriptor> rules = ServerRuleHandler.INSTANCE.rulesIn(category.registerName());
            if (rules.isEmpty()) {
                continue;   // 这个分类下没规则，不出空页签
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

    /**
     * 按 {@code enabled} 状态过滤规则，转成行。
     *
     * <p>只读服务端同步下来的 {@code enabled} 字段，不碰本地逻辑，所以客户端能直接补出这个过滤页签。
     *
     * @param enabled {@code true} 只看开着的规则；{@code false} 只看关着的
     */
    private static List<ConfigRow> rowsByState(boolean enabled) {
        return ServerRuleHandler.INSTANCE.rules().stream()
                .filter(rule -> rule.enabled() == enabled)
                .map(rule -> (ConfigRow) new ServerRuleRow(rule))
                .toList();
    }

    /**
     * 把一组规则描述符转成行列表。
     *
     * <p>手工 {@code ArrayList} 而不是 {@code stream().map(...).toList()}：规则数通常很小，
     * 这里预分配容量，且避免中间流对象（这个方法是热路径，每次点开分类页签都会调）。
     */
    private static List<ConfigRow> toRows(List<RuleDescriptor> rules) {
        List<ConfigRow> rows = new ArrayList<>(rules.size());
        for (RuleDescriptor rule : rules) {
            rows.add(new ServerRuleRow(rule));
        }
        return rows;
    }
}
