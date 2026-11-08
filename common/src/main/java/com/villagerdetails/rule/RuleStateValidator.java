package com.villagerdetails.rule;

/**
 * 规则状态值的<b>前置校验器</b>：在「改规则」生效<b>之前</b>拦掉非法输入。
 *
 * <p>为什么要有它：有些规则的状态值不是枚举，而是「任意文本」（{@code RuleSwitches} 那边看
 * quickSwitches 里有没有非开关项，有就整条当自定义列表放行）。这类规则一旦有人写错
 * （{@code /ec HappyGhastSpeed 1000}、{@code abc}、{@code NaN}），旧行为是"先接受、再由消费方
 * 悄悄收回" —— 玩家看到的成功提示和实际生效的值不一致。这里改成<b>写入之前就拒绝</b>，
 * 并给出能看懂的原因；消费方那层 clamp 仍然保留，作为绕过 UI 直接发包的兜底。
 *
 * <p>放在 {@code main} 源集：服务端（{@code RuleType} / {@code RuleChangeService}）要用，
 * 而校验结果的文案 key 属于两端共用的语言文件，所以它不能塞进 {@code server} 侧。
 *
 * <p>实现必须<b>纯函数、无副作用、不碰游戏状态</b>：它在写缓存之前执行，被拒时不会留下任何痕迹。
 */
@FunctionalInterface
public interface RuleStateValidator {

    /**
     * 「不是数字」的提示键（{@code abc} / {@code NaN} / {@code Infinity}）
     */
    String KEY_NOT_A_NUMBER = "command.entity_binder.error.not_a_number";

    /**
     * 「超出范围」的提示键
     */
    String KEY_OUT_OF_RANGE = "command.entity_binder.error.out_of_range";

    /**
     * 校验一个（已经过 {@link RuleSwitches#canonicalize} 规范化的）状态值。
     *
     * @return {@code null} = 通过；否则是拒绝原因（文案键 + 参数，交给调用方本地化）
     */
    ValidationError validate(String state);

    /**
     * 拒绝原因：只描述「为什么」，怎么显示由命令 / 收包端各自决定（它们共用同一句话）。
     *
     * @param messageKey 语言键
     * @param args       语言键的参数
     */
    record ValidationError(String messageKey, Object[] args) {

        /**
         * 解析不出数字（含 {@code NaN} / {@code Infinity}）
         */
        public static ValidationError notANumber(String raw) {
            return new ValidationError(KEY_NOT_A_NUMBER, new Object[]{raw});
        }

        /**
         * 是数字，但不在 {@code [min, max]} 内
         */
        public static ValidationError outOfRange(double min, double max, String raw) {
            // 直接传 Double：Component.translatable 会按 String.valueOf 渲染成 0.0 / 5.0
            return new ValidationError(KEY_OUT_OF_RANGE, new Object[]{min, max, raw});
        }
    }

    /**
     * 数值区间校验器：{@code [min, max]} 内的有限数字。
     *
     * <p><b>{@code true} / {@code false} 一律放行</b>（忽略大小写），这不是偷懒 ——
     * 这些规则靠状态值是不是 {@code false} 判断开关（{@code RuleCache.isEnabled}），
     * 客户端界面那个「是 / 否」开关发的也正是 {@code "true"} / {@code "false"}。
     * 只放行数字的话，{@code /ec <规则> false} 会被拒，规则就<b>关不掉</b>了。
     *
     * <p>非有限值（{@code NaN} / {@code ±Infinity}）按「不是数字」处理：它们能通过
     * {@link Double#parseDouble}，但写进数值消费方只会污染状态，不该被当成合法输入。
     *
     * <p><b>合法状态域是混合的</b>：{@code {true, false} ∪ [min, max] 内的有限数字}。
     * 所以消费方<b>不要</b>直接 {@code Double.parseDouble(state)}（规则关闭时状态是 {@code "false"}，
     * 会抛异常），统一走 {@code ParseUtils.toDouble(state, fallback)}（它带兜底）。
     *
     * @param min 下限（含）
     * @param max 上限（含）
     */
    static RuleStateValidator numericRange(double min, double max) {
        return state -> {
            String trimmed = state == null ? "" : state.trim();
            if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                return null;
            }
            double parsed;
            try {
                parsed = Double.parseDouble(trimmed);
            } catch (NumberFormatException e) {
                return ValidationError.notANumber(trimmed);
            }
            if (!Double.isFinite(parsed)) {
                return ValidationError.notANumber(trimmed);
            }
            if (parsed < min || parsed > max) {
                return ValidationError.outOfRange(min, max, trimmed);
            }
            return null;
        };
    }
}
