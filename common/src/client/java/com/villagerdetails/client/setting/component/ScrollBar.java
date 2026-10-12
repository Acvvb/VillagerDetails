package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.config.ui.ComponentLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * 内容区右侧的竖直滚动条组件。
 *
 * <p>位置 / 宽度由界面决定（{@code UiLayout.SCROLLBAR_*}），这里只负责「画」和「拖」：
 * <ul>
 *     <li>滑块高度按「一页能放几行 / 总行数」算，最短 {@link ComponentLayout#SCROLLBAR_MIN_THUMB} 像素</li>
 *     <li>按住滑块上下拖 → 实时设置滚动位置；点轨道空白处 → 往那个方向翻一页</li>
 *     <li>滚轮仍由界面统一处理（滚轮在内容区任意位置都能滚）</li>
 * </ul>
 *
 * <p>数值单位是「行」（不是像素），和界面的 {@code rowScroll} 一致。
 * 材质用原版 {@code widget/scroller} / {@code widget/scroller_background}，材质包可替换。
 *
 * <p>内容不足一页时界面会把整条滚动条隐藏（{@code visible = false}），那一列让给行控件。
 */
public class ScrollBar extends AbstractWidget {

    /**
     * 原版滚动条轨道材质
     */
    private static final Identifier BACKGROUND_SPRITE =
            Identifier.withDefaultNamespace("widget/scroller_background");
    /**
     * 原版滚动条滑块材质
     */
    private static final Identifier THUMB_SPRITE =
            Identifier.withDefaultNamespace("widget/scroller");

    /**
     * 一共几行
     */
    private final IntSupplier totalRows;
    /**
     * 一页能放几行
     */
    private final IntSupplier pageRows;
    /**
     * 当前滚动到第几行（读）
     */
    private final DoubleSupplier scroll;
    /**
     * 请求把滚动位置设到第几行（写，界面自己决定要不要做平滑动画）
     */
    private final DoubleConsumer onScroll;

    /**
     * 正在拖动滑块
     */
    private boolean dragging;
    /**
     * 按下时鼠标的 y，用来算拖动位移
     */
    private double dragAnchorY;
    /**
     * 按下时的滚动位置（行）
     */
    private double dragAnchorScroll;

    public ScrollBar(int x, int y, int width, int height,
                     IntSupplier totalRows, IntSupplier pageRows,
                     DoubleSupplier scroll, DoubleConsumer onScroll) {
        super(x, y, width, height, Component.translatable("villagerdetails.ui.scrollbar"));
        this.totalRows = totalRows;
        this.pageRows = pageRows;
        this.scroll = scroll;
        this.onScroll = onScroll;
    }

    /**
     * 还能往下滚几行（0 表示一页放得下，不需要滚动）
     */
    private double maxScroll() {
        return Math.max(0, totalRows.getAsInt() - pageRows.getAsInt());
    }

    /**
     * 滑块高度：按「可见比例」缩放，最短 SCROLLBAR_MIN_THUMB，最长整条轨道
     */
    private int thumbHeight() {
        int track = Math.max(1, getHeight());
        int total = totalRows.getAsInt();
        if (total <= 0) {
            return track;
        }
        int visible = Math.min(pageRows.getAsInt(), total);
        int minThumb = Math.min(ComponentLayout.SCROLLBAR_MIN_THUMB, track);
        int scaled = Math.round((float) track * visible / total);
        return Math.clamp(scaled, minThumb, track);
    }

    /**
     * 滑块上边界：把 [0, maxScroll] 映射到轨道的剩余行程上
     */
    private int thumbY() {
        int max = (int) Math.round(maxScroll());
        int travel = getHeight() - thumbHeight();
        if (max <= 0 || travel <= 0) {
            return getY();
        }
        double ratio = Math.clamp(scroll.getAsDouble() / max, 0.0D, 1.0D);
        return getY() + (int) Math.round(travel * ratio);
    }

    /**
     * 请求滚动到指定行（越界由界面自己夹住）
     */
    private void requestScroll(double rows) {
        onScroll.accept(Math.clamp(rows, 0.0D, maxScroll()));
    }

    @Override
    public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubleClick) {
        if (!this.visible || !this.active || event.button() != 0
                || !isMouseOver(event.x(), event.y())) {
            return false;
        }

        int thumbY = thumbY();
        int thumbBottom = thumbY + thumbHeight();
        if (event.y() >= thumbY && event.y() <= thumbBottom) {
            // 抓住滑块：记下抓取点，之后按位移换算
            this.dragging = true;
            this.dragAnchorY = event.y();
            this.dragAnchorScroll = scroll.getAsDouble();
        } else {
            // 点轨道空白：往点的那个方向翻一页
            double page = pageRows.getAsInt();
            requestScroll(scroll.getAsDouble() + (event.y() < thumbY ? -page : page));
        }
        return true;
    }

    @Override
    public boolean mouseDragged(@NonNull MouseButtonEvent event, double dragX, double dragY) {
        if (!this.dragging) {
            return false;
        }
        int travel = getHeight() - thumbHeight();
        if (travel <= 0) {
            return true;
        }
        // 滑块能走的行程对应 maxScroll 行
        double rowsPerPixel = maxScroll() / travel;
        requestScroll(dragAnchorScroll + (event.y() - dragAnchorY) * rowsPerPixel);
        return true;
    }

    @Override
    public void onRelease(@NonNull MouseButtonEvent event) {
        this.dragging = false;
    }

    @Override
    protected void extractWidgetRenderState(@NonNull GuiGraphicsExtractor graphics,
                                            int mouseX, int mouseY, float partialTick) {
        int track = getHeight();
        if (track <= 0 || maxScroll() <= 0) {
            // 一页放得下：整条不画（界面本来也会把它隐藏，这里是双保险）
            return;
        }
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE,
                getX(), getY(), getWidth(), track);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, THUMB_SPRITE,
                getX(), thumbY(), getWidth(), thumbHeight());
    }

    @Override
    protected void updateWidgetNarration(@NonNull NarrationElementOutput narration) {
        narration.add(NarratedElementType.TITLE, createNarrationMessage());
    }
}
