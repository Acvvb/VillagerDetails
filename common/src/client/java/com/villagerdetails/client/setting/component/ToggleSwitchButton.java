package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.rule.RuleSwitches;
import net.minecraft.network.chat.Component;

/**
 * 功能开关的「是 / 否」按钮组件：只靠字色区分状态（关 = 红、开 = 绿），点一下翻转。
 *
 * <p>两种用法：
 * <ul>
 *     <li>挂在配置行上 —— 状态存在行里，点完由行自己落盘</li>
 *     <li>状态由调用方自己持有（列表页里一行一个开关）—— 见 {@link #create(int, int, int, int, boolean, Runnable)}</li>
 * </ul>
 */
public final class ToggleSwitchButton {

    private ToggleSwitchButton() {
    }

    /**
     * 挂在某一行上（状态存在行里，点完由行自己落盘）
     */
    public static SwitchButton create(int x, int y, int width, int height, ConfigRow row, Runnable onToggled) {
        boolean on = row.getToggle();
        return new SwitchButton(x, y, width, height, labelOf(on), colorOf(on), () -> {
            row.setToggle(!row.getToggle());
            row.save();
            onToggled.run();
        });
    }

    /**
     * 状态由调用方自己持有：只给初始值和点击回调，翻成什么由调用方决定，
     * 并自己调 {@link SwitchButton#setState} 把按钮刷新成新状态。
     */
    public static SwitchButton create(int x, int y, int width, int height, boolean on, Runnable onClick) {
        return new SwitchButton(x, y, width, height, labelOf(on), colorOf(on), onClick);
    }

    /**
     * 「是」/「否」的文字
     */
    public static Component labelOf(boolean on) {
        return Component.translatable(on ? "villagerdetails.ui.yes" : "villagerdetails.ui.no");
    }

    /**
     * 开 = 绿，关 = 红
     */
    public static int colorOf(boolean on) {
        return on ? RuleSwitches.COLOR_SELECTED_ON : RuleSwitches.COLOR_SELECTED_OFF;
    }
}
