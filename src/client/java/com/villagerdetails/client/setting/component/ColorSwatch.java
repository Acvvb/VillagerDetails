package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.config.ClientRenderConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.IntSupplier;

/**
 * 颜色预览色块组件：颜色实时从外部取（拖动滑条 / 输入色值时能立刻反映）。
 */
public class ColorSwatch extends AbstractWidget {

    private static final int BORDER = 0xFF000000;

    private final IntSupplier color;

    public ColorSwatch(int x, int y, int width, int height, IntSupplier color) {
        super(x, y, width, height, Component.empty());
        this.color = color;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                ClientRenderConfig.toArgb(color.getAsInt()));
        graphics.outline(getX(), getY(), getWidth(), getHeight(), BORDER);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        narration.add(NarratedElementType.TITLE,
                Component.literal(ColorValueBox.hex(color.getAsInt())));
    }
}
