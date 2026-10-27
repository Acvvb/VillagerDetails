package com.villagerdetails.command;

import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.rule.RuleSwitches;
import com.villagerdetails.rule.type.RuleCategoryType;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.util.List;

import static com.villagerdetails.command.CommandConstants.COMMAND_BASE;

/**
 * {@code /ec} 规则管理命令用到的<b>聊天组件（字体渲染）</b>全部收在这里：
 *
 * <ul>
 *     <li>整行：分类帮助行、规则列表行、规则详情；</li>
 *     <li>基础件：可点击的规则名、分类按钮、快捷开关按钮、分隔线。</li>
 * </ul>
 *
 * <p>命令类只负责「取数据 → 调这里拿组件 → 发出去」，不再自己拼颜色 / 点击 / 悬停。
 * 所有文字都只收翻译键，代码里不写死文案；悬停内容按规范先在服务端解析成文本再包 literal。
 */
public final class RuleMessageComponents {

    private RuleMessageComponents() {
    }

    // ============================================================
    // 语言键（只有渲染用到的放这里）
    // ============================================================

    private static final String K_HELP_CATEGORIES = "command.entity_binder.help.categories";
    private static final String K_TOGGLE_CATEGORY = "command.entity_binder.toggle.category_label";
    private static final String K_TOGGLE_VALUE = "command.entity_binder.toggle.value_label";
    private static final String K_TOGGLE_HOVER = "command.entity_binder.toggle.hover";

    // ============================================================
    // 整行组件（命令直接发）
    // ============================================================

    /**
     * 根命令（{@code /ec}）的帮助行：一行分类按钮，点哪个就跳到对应分类的规则列表。
     */
    public static MutableComponent helpCategories() {
        MutableComponent result = Component.empty();
        result.append(Component.translatable(K_HELP_CATEGORIES));
        result.append(categoryButtons(List.of(RuleCategoryType.values())));
        return result;
    }

    /**
     * 规则列表里的一条：「规则名 + 快捷开关按钮」两行。
     *
     * @param rule         要展示的规则
     * @param currentState 规则当前状态（决定开关按钮的高亮）
     */
    public static MutableComponent ruleListLine(RuleType rule, String currentState) {
        return Component.empty()
                .append("\n")
                .append(ruleName(rule))
                .append("\n")
                .append(quickSwitches(rule, currentState));
    }

    /**
     * 规则详情：分隔线 + 规则名 + 分类 + 描述 + 快捷开关。
     *
     * @param rule         要展示的规则
     * @param currentState 规则当前状态（决定开关按钮的高亮）
     */
    public static MutableComponent ruleDetail(RuleType rule, String currentState) {
        return Component.empty()
                .append(divider())
                .append(ruleName(rule))
                .append(Component.translatable(K_TOGGLE_CATEGORY))
                .append(categoryButtons(rule.getCategory()))
                .append("\n")
                .append(Component.translatable(rule.getDisplayInfo()))
                .append(Component.translatable(K_TOGGLE_VALUE))
                .append(quickSwitches(rule, currentState));
    }

    // ============================================================
    // 基础组件
    // ============================================================

    /**
     * 一条规则的可点击名称：{@code [显示名 (registerName)]}。
     *
     * <p>悬停显示规则描述，点击执行 {@code /ec <registerName>} 直接查看该规则。
     */
    private static MutableComponent ruleName(RuleType ruleType) {
        MutableComponent nameComp = Component.empty()
                .append(Component.literal("§f"))
                .append(Component.translatable(ruleType.getDisplayName()))
                .append(Component.literal(" §f(" + ruleType.getRegisterName() + ")  "));

        return nameComp.withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand(
                        String.join(" ", COMMAND_BASE, ruleType.getRegisterName())))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.translatable(ruleType.getDisplayInfo()))));
    }

    /**
     * 分类按钮行：每个分类一个按钮，点击执行 {@code /ec list <category>}。
     */
    private static MutableComponent categoryButtons(List<RuleCategoryType> ruleCategoryTypes) {
        MutableComponent result = Component.empty();
        for (RuleCategoryType type : ruleCategoryTypes) {
            String command = String.join(" ", COMMAND_BASE, "list", type.getRegisterName());
            MutableComponent component = clickableButton(
                    "[" + type.getDisplayName() + "]", 0x5dade2, command, null);
            result.append(component).append(" ");
        }
        return result;
    }

    /**
     * 一条规则的整行快捷开关按钮，按钮之间用空格分隔。
     *
     * <p>按钮集合来自 {@link RuleType#getQuickSwitches()}，每个按钮的显示名 / 颜色
     * 都由 {@link RuleSwitches} 统一决定，与客户端界面保持一致。
     */
    private static MutableComponent quickSwitches(RuleType rule, String currentState) {
        MutableComponent result = Component.empty();
        List<String> switches = rule.getQuickSwitches();
        for (int i = 0; i < switches.size(); i++) {
            if (i > 0) result.append(" ");
            result.append(switchButton(rule, switches.get(i), currentState));
        }
        return result;
    }

    /**
     * 单个快捷开关按钮。
     *
     * <p>点击执行 {@code /ec <rule> <option>}；颜色 / 显示名 / 悬停文字全部来自
     * {@link RuleSwitches}，客户端界面（{@code ServerRuleRow}）用的是同一套 —— 两边永远一致。
     */
    private static MutableComponent switchButton(RuleType rule, String option, String currentState) {
        String command = String.join(" ", COMMAND_BASE, rule.getRegisterName(), option);
        String hoverText = ServerTranslations.resolve(
                Component.translatable(K_TOGGLE_HOVER, RuleSwitches.currentDisplay(currentState)));
        return clickableButton("[" + RuleSwitches.displayNameOf(option) + "]",
                RuleSwitches.colorOf(option, currentState), command, hoverText);
    }

    /**
     * 可点击按钮组件。
     *
     * @param text      按钮文字（含方括号，如 {@code [enable]}）
     * @param color     文字颜色（RGB）
     * @param command   点击后执行的命令
     * @param hoverText 悬停提示；{@code null} 或空串表示不加悬停
     */
    private static MutableComponent clickableButton(String text, int color,
                                                    String command, String hoverText) {
        MutableComponent component = Component.literal(text)
                .withStyle(style -> style.withColor(TextColor.fromRgb(color)))
                .withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command)));

        if (hoverText != null && !hoverText.isEmpty()) {
            component.withStyle(style -> style.withHoverEvent(
                    new HoverEvent.ShowText(Component.literal(hoverText))));
        }
        return component;
    }

    /**
     * 一条分隔线（在「规则详情」输出里用，把上一次输出和本次输出隔开）。
     */
    private static MutableComponent divider() {
        return Component.literal("§6================================================\n")
                .withStyle(style -> style.withColor(TextColor.fromRgb(0x555555)));
    }
}
