package com.villagerdetails.client.setting.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

/**
 * 状态按钮：文字颜色按状态走（选中/开启 = 绿，选中/关闭 = 红，未选中 = 黑），外观仍是原版按钮。
 *
 * <p>注意：{@code /ec} 用的是 RGB（{@code 0x2ecc71} 这种，没有 alpha），
 * 而 GUI 绘制要 ARGB —— 必须补上 {@code 0xFF} 不透明度，否则文字会全透明（按钮看着像空白）。
 */
public class SwitchButton extends Button.Plain {

    private int argb;

    public SwitchButton(int x, int y, int width, int height, Component label, int rgb, Runnable onClick) {
        super(x, y, width, height, label, _ -> onClick.run(), DEFAULT_NARRATION);
        this.argb = argbOf(rgb);
    }

    /**
     * 原地翻状态：只换文字和字色，<b>不重建控件</b>。
     *
     * <p>给「列表里一行一个开关」这种场景用：点一下开关就要立刻变色，
     * 但重建整个界面会把玩家正在别的输入框里打的字冲掉。
     */
    public void setState(Component label, int rgb) {
        this.argb = argbOf(rgb);
        setMessage(label);
    }

    /**
     * {@code /ec} 给的是 RGB，GUI 要 ARGB —— 补上不透明度
     */
    private static int argbOf(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    /**
     * 挂上悬停提示。
     *
     * <p>故意<b>不</b>在构造里调 {@code setTooltip}：那是可被子类覆盖的方法，
     * 在构造里调等于让 {@code this} 提前逸出（javac 的 {@code this-escape} 告警）。
     */
    public SwitchButton withTooltip(Component tooltip) {
        if (tooltip != null) {
            setTooltip(Tooltip.create(tooltip));
        }
        return this;
    }

    @Override
    protected void extractContents(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractDefaultSprite(graphics);
        // 只靠字色区分状态，不再额外描边
        SettingWidgets.plainText(graphics, this, argb);
    }
}
