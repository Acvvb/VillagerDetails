package com.villagerdetails.rule;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.rule.type.RuleType;

/**
 * 规则开关的取值 / 显示逻辑 —— <b>唯一来源</b>：{@code /ec} 命令和客户端界面都调这里，
 * 两边不会再各自长出一套。
 *
 * <p>开关是<b>单选</b>：一条规则的 {@link RuleType#getQuickSwitches()} 就是它的全部合法取值，
 * 当前状态永远是其中之一（自定义列表规则除外，它放行任意非空文本）。
 * 历史上那套「多选 + none 占位」的机制已经删掉：没有规则声明多选，
 * 「关掉」就是选 {@code false}，空状态不再有任何含义。
 *
 * <p>颜色约定：选中且开启 = 绿，选中且关闭 = 红，未选中 = 灰。
 */
public final class RuleSwitches {

    /**
     * 选中 + 开启
     */
    public static final int COLOR_SELECTED_ON = 0x2ECC71;
    /**
     * 选中 + 关闭
     */
    public static final int COLOR_SELECTED_OFF = 0xE74C3C;
    /**
     * 未选中
     */
    public static final int COLOR_UNSELECTED = 0xAAAAAA;

    private RuleSwitches() {
    }

    // ==============================================================
    // 校验
    // ==============================================================

    /**
     * 校验结果：{@link Ok} 才是可以写进规则缓存的状态。
     *
     * <p>把「为什么被拒」也作为返回值交出去，调用方（命令 / 收包端）就能给出同一套提示，
     * 而不是一个 {@code null} 各自猜原因。
     */
    public sealed interface Canonical permits Canonical.Ok, Canonical.BadState, Canonical.BadOption {

        /**
         * 校验通过：{@code state} 是规范写法（预定义列表里那一项的原文）
         */
        record Ok(String state) implements Canonical {
        }

        /**
         * 空 / 全空白：没有任何规则支持空状态（要「关掉」就选 false）
         */
        record BadState(String raw) implements Canonical {
        }

        /**
         * 预定义列表里没有这一项
         */
        record BadOption(String option) implements Canonical {
        }
    }

    /**
     * 状态值是否算「开启」：除了 {@code false} 都算开启
     */
    public static boolean isEnabledState(String state) {
        return !SwitchComponentType.FALSE_STR.equalsIgnoreCase(state == null ? "" : state);
    }

    /**
     * 校验并规范化一个状态字符串。
     *
     * <ul>
     *     <li>空白 / {@code null} → {@link Canonical.BadState}（不支持空状态）</li>
     *     <li>自定义列表（语言码、逐条规则的自定义值）→ 原样接受 trim 后的文本</li>
     *     <li>预定义列表 → 忽略大小写命中，返回列表里的原文；否则 {@link Canonical.BadOption}</li>
     * </ul>
     */
    public static Canonical canonicalize(RuleType rule, String state) {
        String trimmed = state == null ? "" : state.trim();
        if (trimmed.isEmpty()) {
            return new Canonical.BadState(trimmed);
        }
        if (SwitchComponentType.isCustomList(rule.getQuickSwitches())) {
            return new Canonical.Ok(trimmed);
        }
        for (String option : rule.getQuickSwitches()) {
            if (option.equalsIgnoreCase(trimmed)) {
                return new Canonical.Ok(option);
            }
        }
        return new Canonical.BadOption(trimmed);
    }

    // ==============================================================
    // 显示
    // ==============================================================

    /**
     * 按钮颜色：选中且开启绿、选中且关闭红、未选中灰
     */
    public static int colorOf(String option, String state) {
        boolean selected = option != null && option.equalsIgnoreCase(state == null ? "" : state);
        if (!selected) {
            return COLOR_UNSELECTED;
        }
        return isEnabledState(option) ? COLOR_SELECTED_ON : COLOR_SELECTED_OFF;
    }

    /**
     * 选项显示名（false / true / ops / master / admin / owner，自定义取值原样显示）
     */
    public static String displayNameOf(String option) {
        return SwitchComponentType.displayNameOf(option);
    }

    /**
     * 当前状态的显示名；认不出来的取值（自定义）原样返回，空值返回空串
     */
    public static String currentDisplay(String state) {
        if (state == null || state.isBlank()) {
            return "";
        }
        return SwitchComponentType.displayNameOf(state);
    }

    /**
     * 便捷方法：取某条规则当前状态的显示名
     */
    public static String currentDisplay(RuleType rule) {
        return currentDisplay(RuleCache.getState(rule));
    }

    /**
     * 规则当前状态是不是它自己声明的默认状态（是的话重置按钮置灰）
     */
    public static boolean isDefaultState(RuleType rule) {
        String current = RuleCache.getState(rule);
        String fallback = rule.getState();
        return normalize(current).equalsIgnoreCase(normalize(fallback));
    }

    private static String normalize(String state) {
        return state == null ? "" : state.trim();
    }
}
