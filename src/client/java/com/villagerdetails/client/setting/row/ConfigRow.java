package com.villagerdetails.client.setting.row;

import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.type.style.StyleType;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 配置界面里的一行。实现就是「配置项 / 规则」本身：
 * <ul>
 *     <li>{@link ConfigEntryType}：本机配置项（枚举自己实现这个接口）</li>
 *     <li>{@link ServerRuleRow}：服务器规则（{@code RuleType}）</li>
 *     <li>{@link ServerRuleDataRow}：服务器规则的逐条配置（id → 值）</li>
 * </ul>
 * 行样式跟随所在二级分类；不支持的取值方式用默认实现兜底。
 */
public interface ConfigRow {

    /**
     * 稳定 id（跨界面重建保持状态用）：就是配置 / 规则的注册名
     */
    String getRegisterName();

    Component getDisplayName();

    Component getDisplayInfo();

    StyleType getStyle();

    /**
     * 这一行有没有快捷键绑定框（服务器规则没有）
     */
    default boolean hasKeyBind() {
        return false;
    }

    /**
     * 开关型当前值
     */
    boolean getToggle();

    /**
     * 切换开关
     */
    void setToggle(boolean value);

    /**
     * 快捷开关按钮组。服务器规则按 {@code RuleType#getQuickSwitches()} 渲染（和 {@code /ec} 一致）；
     * 返回空列表表示这一行用普通的「是/否」开关。
     */
    default List<SwitchOption> getSwitchOptions() {
        return List.of();
    }

    /**
     * 一个快捷开关按钮。
     *
     * @param label   按钮文字（和 /ec 里显示的一致）
     * @param color   文字颜色（选中且开启=绿、选中且关闭=红、未选中=灰）
     * @param tooltip 悬停提示
     * @param action  点击后执行
     */
    record SwitchOption(Component label, int color, Component tooltip, Runnable action) {
    }

    // ---------------- 滑块 ----------------

    default double getNumber() {
        return 0.0D;
    }

    default void setNumber(double value) {
    }

    default double minNumber() {
        return 0.0D;
    }

    default double maxNumber() {
        return 1.0D;
    }

    default double stepNumber() {
        return 1.0D;
    }

    default Component formatNumber(double value) {
        return Component.literal(String.valueOf(value));
    }

    // ---------------- 颜色 ----------------

    default int getColor() {
        return 0xFFFFFF;
    }

    // ---------------- 快捷键 ----------------

    default int[] getKeyBind() {
        return new int[0];
    }

    default void setKeyBind(int[] keys) {
    }

    // ---------------- 触发（瞬时动作按钮） ----------------

    /**
     * 触发按钮点击 / 快捷键按下时执行的动作；不支持触发样式的行返回空操作。
     */
    default Runnable getAction() {
        return () -> {
        };
    }

    // ---------------- 重置 / 落盘 ----------------

    /**
     * 这一项当前是否就是默认值（是的话它那一行的重置按钮要置灰）
     */
    boolean isDefault();

    void reset();

    default void save() {
    }
}
