package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.config.ui.ComponentLayout;
import com.villagerdetails.client.setting.row.ConfigRow;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则控制按钮组组件：按 {@link ConfigRow#getSwitchOptions()}（来自 {@code RuleType#getQuickSwitches()}）
 * 渲染成一排状态按钮，显示 / 颜色 / 取值都和 {@code /ec} 一致。
 *
 * <p>整体右对齐：从 {@code right} 往左摆，所以按钮组的右端固定贴着「重置」按钮。
 * 按钮之间的间距、文字留白、最小宽度在 {@link ComponentLayout} 里。
 */
public final class RuleSwitchGroup {

    private RuleSwitchGroup() {
    }

    /**
     * 造出这一行的按钮组（返回的控件顺序和选项顺序一致，最左边的最先）。
     *
     * @param right 按钮组右端（通常是「重置」按钮左边一点）
     */
    public static List<AbstractWidget> build(ConfigRow row, Font font, int right, int y, int height) {
        List<ConfigRow.SwitchOption> options = row.getSwitchOptions();
        List<AbstractWidget> widgets = new ArrayList<>(options.size());

        int cursor = right;
        for (int i = options.size() - 1; i >= 0; i--) {
            ConfigRow.SwitchOption option = options.get(i);
            int width = Math.max(ComponentLayout.SWITCH_GROUP_MIN_WIDTH,
                    font.width(option.label()) + ComponentLayout.SWITCH_GROUP_PADDING * 2);
            cursor -= width;
            widgets.addFirst(new SwitchButton(cursor, y, width, height,
                    option.label(), option.color(), option.action())
                    .withTooltip(option.tooltip()));
            cursor -= ComponentLayout.SWITCH_GROUP_GAP;
        }
        return widgets;
    }
}
