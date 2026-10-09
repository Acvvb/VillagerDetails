package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.config.ui.ComponentLayout;
import com.villagerdetails.client.setting.row.ConfigRow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * 设置界面里那几种「小控件」的工厂：齿轮、数值输入框，以及若干组件共用的绘制小工具。
 */
public final class SettingWidgets {

    private static final int EDIT_BOX_MAX_LENGTH = 8;
    private static final int NUMBER_DECIMALS = 1;

    private SettingWidgets() {
    }

    /**
     * 齿轮按钮：滑块行用来在「滑条」和「直接输入数值」之间切换
     */
    public static Button gear(int x, int y, int width, int height, Runnable onClick) {
        return Button.builder(Component.literal("⚙"), button -> onClick.run())
                .bounds(x, y, width, height)
                .build();
    }

    /**
     * 数值输入框：绑定到某一行的数值，输入合法数字立即生效，生效后回调（用来刷新重置按钮）
     */
    public static EditBox numberBox(int x, int y, int width, int height, ConfigRow row, Runnable onChanged) {
        EditBox box = new EditBox(Minecraft.getInstance().font, x, y, width, height, Component.empty());
        box.setMaxLength(EDIT_BOX_MAX_LENGTH);
        box.setValue(String.format(Locale.ROOT, "%." + NUMBER_DECIMALS + "f", row.getNumber()));
        box.setResponder(text -> {
            try {
                row.setNumber(Double.parseDouble(text.trim()));
                onChanged.run();
            } catch (NumberFormatException ignored) {
                // 还没输完 / 不是数字，就保持原值
            }
        });
        return box;
    }

    /**
     * 在原版按钮上自己画一行居中的彩色文字。
     *
     * <p>原版按钮的文字只有白色，所以「按状态变色」的按钮（{@link SwitchButton}、{@link KeyBindButton}）
     * 都得先画按钮底图、再自己把文字画上去 —— 居中的算法是同一套，收在这里。
     *
     * @param argb 带不透明度的颜色（RGB 直接传进来会全透明）
     */
    public static void plainText(GuiGraphicsExtractor graphics, AbstractWidget button, int argb) {
        Font font = Minecraft.getInstance().font;
        Component message = button.getMessage();
        graphics.text(font, message,
                button.getX() + (button.getWidth() - font.width(message)) / 2,
                button.getY() + (button.getHeight() - ComponentLayout.BUTTON_TEXT_HEIGHT) / 2,
                argb);
    }
}
