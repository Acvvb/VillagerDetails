package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.config.UiLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import static com.villagerdetails.client.setting.config.ComponentLayout.*;

/**
 * 原版风格的界面装饰：顶部标题、上下分隔条。
 *
 * <p>分隔条直接用原版精灵 {@code widget/header_separator}、{@code widget/footer_separator}，
 * 所以材质包能正常替换；尺寸也与原版 {@code HeaderAndFooterLayout} 的默认高度保持一致。
 *
 * <p>所有位置 / 尺寸常量都在 {@code setting.config} 里的两个布局配置类：
 * 顶栏底栏高度看 {@link UiLayout}，分隔条和标题的文字偏移看
 * {@link com.villagerdetails.client.setting.config.ComponentLayout}；这里只留颜色。
 */
public final class ScreenDecorations {

    /** 标题文字颜色 */
    public static final int COLOR_TITLE = 0xFFFFFFFF;
    /** 配置项名称标签的文字颜色 */
    public static final int COLOR_LABEL = 0xFFE0E0E0;
    /** 说明 / 提示文字颜色 */
    public static final int COLOR_HINT = 0xFFA0A0A0;

    private ScreenDecorations() {
    }

    /** 画原版的上下分隔条（在 extractBackground 里调用） */
    public static void drawSeparators(GuiGraphicsExtractor graphics, int width, int height) {
        boolean inWorld = Minecraft.getInstance().level != null;
        Identifier header = inWorld ? Screen.INWORLD_HEADER_SEPARATOR : Screen.HEADER_SEPARATOR;
        Identifier footer = inWorld ? Screen.INWORLD_FOOTER_SEPARATOR : Screen.FOOTER_SEPARATOR;

        graphics.blit(RenderPipelines.GUI_TEXTURED, header, 0,
                UiLayout.HEADER_HEIGHT - SEPARATOR_HEIGHT,
                0.0F, 0.0F, width, SEPARATOR_HEIGHT, SEPARATOR_SPRITE_WIDTH, SEPARATOR_SPRITE_HEIGHT);
        graphics.blit(RenderPipelines.GUI_TEXTURED, footer, 0, height - UiLayout.FOOTER_HEIGHT,
                0.0F, 0.0F, width, SEPARATOR_HEIGHT, SEPARATOR_SPRITE_WIDTH, SEPARATOR_SPRITE_HEIGHT);
    }

    /** 画一条横贯指定宽度的原版分隔条（分类标题下面那条线） */
    public static void drawSeparatorLine(GuiGraphicsExtractor graphics, int x, int y, int width) {
        boolean inWorld = Minecraft.getInstance().level != null;
        Identifier header = inWorld ? Screen.INWORLD_HEADER_SEPARATOR : Screen.HEADER_SEPARATOR;
        graphics.blit(RenderPipelines.GUI_TEXTURED, header, x, y,
                0.0F, 0.0F, width, SEPARATOR_HEIGHT, SEPARATOR_SPRITE_WIDTH, SEPARATOR_SPRITE_HEIGHT);
    }

    /** 居中画标题 */
    public static void drawTitle(GuiGraphicsExtractor graphics, Screen screen, Component title) {
        graphics.centeredText(screen.getFont(), title, screen.width / 2, TITLE_Y, COLOR_TITLE);
    }
}
