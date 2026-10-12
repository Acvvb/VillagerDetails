package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.rule.RuleSwitches;
import net.minecraft.network.chat.Component;

/**
 * 功能开关的「是 / 否」按钮组件：只靠字色区分状态（关 = 红、开 = 绿），点一下翻转。
 */
public final class ToggleSwitchButton {

    private ToggleSwitchButton() {
    }

    public static SwitchButton create(int x, int y, int width, int height, ConfigRow row, Runnable onToggled) {
        boolean on = row.getToggle();
        int color = on ? RuleSwitches.COLOR_SELECTED_ON : RuleSwitches.COLOR_SELECTED_OFF;
        Component label = Component.translatable(on ? "villagerdetails.ui.yes" : "villagerdetails.ui.no");

        return new SwitchButton(x, y, width, height, label, color, () -> {
            row.setToggle(!row.getToggle());
            row.save();
            onToggled.run();
        });
    }
}
