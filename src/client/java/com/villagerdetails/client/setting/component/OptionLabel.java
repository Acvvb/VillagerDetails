package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.config.ComponentLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 配置项名称标签组件：自己画文字，并自带「鼠标悬停显示该项描述」的提示。
 *
 * <p>文字在组件内的偏移在 {@link ComponentLayout} 里，颜色用 {@link ScreenDecorations#COLOR_LABEL}。
 */
public class OptionLabel extends AbstractWidget {

    public OptionLabel(int x, int y, int width, int height, Component text) {
        super(x, y, width, height, text);
    }

    /**
     * 挂上「鼠标悬停显示描述」的提示。
     *
     * <p>故意<b>不</b>在构造里调 {@code setTooltip}：那是可被子类覆盖的方法，
     * 在构造里调等于让 {@code this} 提前逸出（javac 的 {@code this-escape} 告警）。
     */
    public OptionLabel withTooltip(Component tooltip) {
        if (tooltip != null) {
            setTooltip(Tooltip.create(tooltip));
        }
        return this;
    }

    /** 直接用某一行的显示名 / 描述 */
    public static OptionLabel forRow(com.villagerdetails.client.setting.row.ConfigRow row,
                                     int x, int y, int width, int height) {
        return new OptionLabel(x, y, width, height, row.getDisplayName()).withTooltip(row.getDisplayInfo());
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.text(Minecraft.getInstance().font, getMessage(),
                getX() + ComponentLayout.LABEL_PADDING, getY() + ComponentLayout.LABEL_TEXT_Y_OFFSET,
                ScreenDecorations.COLOR_LABEL);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        narration.add(NarratedElementType.TITLE, createNarrationMessage());
    }
}
