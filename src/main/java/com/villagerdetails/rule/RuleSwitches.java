package com.villagerdetails.rule;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.SwitchComponentType;
import com.villagerdetails.rule.type.RuleType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则快捷开关的显示与取值逻辑 —— 和 {@code /ec} 命令（RuleCommand）里那套完全一致，
 * 界面上的按钮组就是按 {@link RuleType#getQuickSwitches()} 渲染的。
 *
 * <p>颜色约定也照搬命令：选中且开启 = 绿，选中且关闭 = 红，未选中 = 灰。
 */
public final class RuleSwitches {

    /**
     * 选中 + 开启（命令里的 0x2ecc71）
     */
    public static final int COLOR_SELECTED_ON = 0x2ECC71;
    /**
     * 选中 + 关闭（命令里的 0xe74c3c）
     */
    public static final int COLOR_SELECTED_OFF = 0xE74C3C;
    /**
     * 未选中：和 {@code /ec} 一致，用 0xFFAAAAAA 灰。
     */
    public static final int COLOR_UNSELECTED = 0xFFFFFFF;

    private RuleSwitches() {
    }

    /**
     * 状态值是否为「关」：非 false（false 之外的取值都算开启）
     */
    public static boolean isEnabledState(String state) {
        return !SwitchComponentType.FALSE_STR.equalsIgnoreCase(state == null ? "" : state);
    }

    public static boolean isNoneState(String state) {
        return state == null || state.isEmpty() || SwitchComponentType.NONE.equalsIgnoreCase(state);
    }

    /**
     * 当前状态下这个选项是否被选中。
     *
     * <p>「单选还是多选」直接问规则自己（{@link RuleType#isMultiSelect()}），
     * 不再让调用方把一个 boolean 传进来 —— 那样调用点得先取出来再转发，
     * 而且 IDEA 会把它当成「值始终相同」的参数来报。
     */
    public static boolean isSelected(RuleType rule, String state, String option) {
        if (SwitchComponentType.NONE.equalsIgnoreCase(option)) {
            return isNoneState(state);
        }
        if (rule.isMultiSelect()) {
            if (isNoneState(state)) {
                return false;
            }
            for (String part : state.split(",")) {
                if (part.trim().equalsIgnoreCase(option)) {
                    return true;
                }
            }
            return false;
        }
        return option.equalsIgnoreCase(state == null ? "" : state);
    }

    /**
     * 点这个选项之后应该发给服务端的状态（和命令生成的 state 参数一致）
     */
    public static String nextState(RuleType rule, String state, String option) {
        if (SwitchComponentType.NONE.equalsIgnoreCase(option)) {
            return SwitchComponentType.NONE;
        }
        if (!rule.isMultiSelect()) {
            return option;
        }
        // 多选：点一下加进去 / 再去掉
        if (isSelected(rule, state, option)) {
            List<String> parts = new ArrayList<>();
            if (state != null) {
                for (String part : state.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase(option)) {
                        parts.add(trimmed);
                    }
                }
            }
            return parts.isEmpty() ? SwitchComponentType.NONE : String.join(",", parts);
        }
        if (isNoneState(state)) {
            return option;
        }
        return state + "," + option;
    }

    /**
     * 按钮颜色：照搬 /ec 的规则
     */
    public static int colorOf(RuleType rule, String option, String state) {
        boolean selected = isSelected(rule, state, option);
        if (rule.isMultiSelect()) {
            return selected ? COLOR_SELECTED_ON : COLOR_UNSELECTED;
        }
        boolean isOn = isEnabledState(option) && !SwitchComponentType.NONE.equalsIgnoreCase(option);
        return selected ? (isOn ? COLOR_SELECTED_ON : COLOR_SELECTED_OFF) : COLOR_UNSELECTED;
    }

    /**
     * 选项显示名（false / true / ops / master / admin / owner，自定义取值原样显示）
     */
    public static String displayNameOf(String option) {
        return SwitchComponentType.displayNameOf(option);
    }

    /**
     * 当前状态的显示名；空 / none 一律显示 none（和 /ec 提示里一致）
     */
    public static String currentDisplay(String state) {
        return isNoneState(state) ? SwitchComponentType.NONE : SwitchComponentType.displayNameOf(state);
    }

    /**
     * 便捷方法：取某条规则当前状态的显示名
     */
    public static String currentDisplay(RuleType rule) {
        return currentDisplay(RuleCache.getState(rule));
    }

    /** 规则当前状态是不是它自己声明的默认状态（是的话重置按钮置灰） */
    public static boolean isDefaultState(RuleType rule) {
        String current = RuleCache.getState(rule);
        String fallback = rule.getState();
        String a = isNoneState(current) ? "" : current;
        String b = isNoneState(fallback) ? "" : fallback;
        return a.equalsIgnoreCase(b);
    }

    /**
     * 校验并规范化客户端发来的状态字符串，规则和 {@code /ec <规则> <状态>} 一致。
     *
     * @return 规范化后的状态；非法输入返回 null
     */
    public static String canonicalize(RuleType rule, String state) {
        boolean custom = SwitchComponentType.isCustomList(rule.getQuickSwitches());

        if (rule.isMultiSelect()) {
            Set<String> selected = new LinkedHashSet<>();
            if (state != null) {
                for (String part : state.split(",")) {
                    String trimmed = part.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    if (SwitchComponentType.NONE.equalsIgnoreCase(trimmed)) {
                        return "";
                    }
                    if (custom) {
                        selected.add(trimmed);
                    } else {
                        String match = matchOption(rule, trimmed);
                        if (match == null) {
                            return null;
                        }
                        selected.add(match);
                    }
                }
            }
            return String.join(",", selected);
        }

        if (isNoneState(state)) {
            return "";
        }
        if (custom) {
            return state.trim();
        }
        return matchOption(rule, state.trim());
    }

    private static String matchOption(RuleType rule, String state) {
        if (state == null) {
            return null;
        }
        for (String option : rule.getQuickSwitches()) {
            if (option.equalsIgnoreCase(state)) {
                return option;
            }
        }
        return null;
    }
}
