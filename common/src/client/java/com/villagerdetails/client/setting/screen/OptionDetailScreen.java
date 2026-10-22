package com.villagerdetails.client.setting.screen;

import com.villagerdetails.client.setting.component.*;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

import java.util.function.DoubleFunction;

import static com.villagerdetails.client.setting.config.ui.UiLayout.*;

/**
 * 颜色项的详情界面：说明 + 红/绿/蓝三条滑条 + 预览色块 + 可输入的色值框。
 *
 * <p>控件都取自 {@code setting.component} 里的独立组件（{@link ValueSlider}、{@link ColorSwatch}、
 * {@link ColorValueBox}、{@link ResetButton}），这里只负责摆放和联动。
 */
public class OptionDetailScreen extends Screen {

    /**
     * 关闭后返回的那一页
     */
    private final Screen parent;

    /**
     * 正在编辑的颜色配置项
     */
    private final ConfigEntryType entry;

    /**
     * 内容区左边界（init 时算好，后续所有控件都按它摆）
     */
    private int contentX;

    /**
     * 内容区宽度（窗口窄了会缩）
     */
    private int contentWidth;

    /**
     * 可输入的色值框（{@code #RRGGBB}），和三条滑条双向联动
     */
    private EditBox hexBox;

    /**
     * 本页的「重置」按钮
     */
    private ResetButton resetButton;

    /**
     * 红通道滑条（0-255）
     */
    private ValueSlider redSlider;

    /**
     * 绿通道滑条（0-255）
     */
    private ValueSlider greenSlider;

    /**
     * 蓝通道滑条（0-255）
     */
    private ValueSlider blueSlider;

    /**
     * 防止「滑条 → 色值框 → 滑条」互相触发
     */
    private boolean syncing;

    /**
     * @param parent 关闭后返回的那一页
     * @param entry  正在编辑的颜色配置项
     */
    public OptionDetailScreen(Screen parent, ConfigEntryType entry) {
        super(Component.literal("[ ").append(entry.getDisplayName()).append(" ]"));
        this.parent = parent;
        this.entry = entry;
    }

    /**
     * 初始化：三条通道滑条、预览色块、色值框、重置按钮、完成按钮
     */
    @Override
    protected void init() {
        this.contentWidth = Math.min(DETAIL_CONTENT_MAX_WIDTH, this.width - DETAIL_CONTENT_SIDE_MARGIN * 2);
        this.contentX = (this.width - contentWidth) / 2;

        int y = DETAIL_SLIDER_TOP;
        this.redSlider = channelSlider(y, "red", 16);
        addRenderableWidget(redSlider);
        y += DETAIL_SLIDER_STEP;
        this.greenSlider = channelSlider(y, "green", 8);
        addRenderableWidget(greenSlider);
        y += DETAIL_SLIDER_STEP;
        this.blueSlider = channelSlider(y, "blue", 0);
        addRenderableWidget(blueSlider);
        y += DETAIL_SLIDER_STEP;

        // 预览色块组件 + 可输入的色值框组件
        addRenderableWidget(new ColorSwatch(contentX, y, DETAIL_SWATCH_WIDTH, WIDGET_HEIGHT, entry::getColor));
        this.hexBox = ColorValueBox.create(contentX + DETAIL_SWATCH_WIDTH + DETAIL_VALUE_GAP, y,
                contentWidth - DETAIL_SWATCH_WIDTH - DETAIL_VALUE_GAP, WIDGET_HEIGHT,
                entry::getColor, this::onColorChanged);
        addRenderableWidget(hexBox);
        y += WIDGET_HEIGHT + DETAIL_ROW_GAP;

        int buttonY = Math.min(y + DETAIL_BUTTON_GAP, this.height - DETAIL_BUTTON_BOTTOM_MARGIN);
        this.resetButton = ResetButton.forEntry(entry, footerLeftButtonX(this.width), buttonY,
                FOOTER_BUTTON_WIDTH, WIDGET_HEIGHT, this::rebuildWidgets);
        addRenderableWidget(resetButton);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, _ -> this.onClose())
                .bounds(footerRightButtonX(this.width), buttonY, FOOTER_BUTTON_WIDTH, WIDGET_HEIGHT)
                .build());
    }

    /**
     * 造一条通道滑条（0-255，步进 1，格式化成「红：N」这种）
     *
     * @param y       纵坐标
     * @param channel 通道名（"red" / "green" / "blue"）
     * @param shift   通道在 ARGB 里的位移（红 = 16，绿 = 8，蓝 = 0）
     */
    private ValueSlider channelSlider(int y, String channel, int shift) {
        int current = (entry.getColor() >> shift) & 0xFF;
        DoubleFunction<Component> formatter = value -> Component.translatable(
                "villagerdetails.ui.channel." + channel,
                Integer.toString((int) Math.round(value)));

        return new ValueSlider(contentX, y, contentWidth, WIDGET_HEIGHT, 0.0D, 255.0D, 1.0D, current,
                value -> {
                    setChannel(shift, (int) Math.round(value));
                    syncHexBox();
                },
                formatter,
                () -> ClientRenderConfig.get().save());
    }

    /**
     * 色值框输入了合法值：写入配置，并让三条滑条和重置按钮跟上
     */
    private void onColorChanged(int rgb) {
        if (syncing) {
            return;
        }
        entry.setColor(rgb);
        ClientRenderConfig.get().save();

        syncing = true;
        try {
            refreshSliders();
        } finally {
            syncing = false;
        }
        refreshResetButton();
    }

    /**
     * 滑条变了 → 把色值框的文字同步过来（正在输入时不动它）
     */
    private void syncHexBox() {
        if (hexBox == null || hexBox.isFocused()) {
            return;
        }
        syncing = true;
        try {
            hexBox.setValue(ColorValueBox.hex(entry.getColor()));
        } finally {
            syncing = false;
        }
        refreshResetButton();
    }

    /**
     * 让三条滑条回到当前颜色对应的位置
     */
    private void refreshSliders() {
        int rgb = entry.getColor();
        if (redSlider != null) {
            redSlider.setActualValue((rgb >> 16) & 0xFF);
        }
        if (greenSlider != null) {
            greenSlider.setActualValue((rgb >> 8) & 0xFF);
        }
        if (blueSlider != null) {
            blueSlider.setActualValue(rgb & 0xFF);
        }
    }

    /**
     * 刷新重置按钮的灰 / 亮状态
     */
    private void refreshResetButton() {
        if (resetButton != null) {
            resetButton.refreshState();
        }
    }

    /**
     * 只改某一个通道，其余通道保持不变
     */
    private void setChannel(int shift, int channelValue) {
        int cleared = entry.getColor() & ~(0xFF << shift);
        entry.setColor(cleared | ((channelValue & 0xFF) << shift));
    }

    /**
     * 关闭：颜色改动已经每次即时落盘，这里只负责回上一页
     */
    @Override
    public void onClose() {
        ClientRenderConfig.get().save();
        this.minecraft.gui.setScreen(this.parent);
    }

    /**
     * 背景之上再补一条顶 / 底分隔线
     */
    @Override
    public void extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        ScreenDecorations.drawSeparators(graphics, this.width, this.height);
    }

    /**
     * 渲染一帧：标题 + 说明 + 交给组件的滑条 / 色块 / 色值框 / 按钮
     */
    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenDecorations.drawTitle(graphics, this, this.title);

        graphics.textWithWordWrap(this.font, entry.getDisplayInfo(),
                contentX, DETAIL_DESC_TOP, contentWidth, ScreenDecorations.COLOR_HINT);

        // 色块、色值框、滑条、重置按钮都由各自组件渲染
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
}
