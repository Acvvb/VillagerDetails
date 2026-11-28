package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.config.ui.ComponentLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/**
 * 内容区右侧的竖直滚动条<b>显示组件 + 拖动状态机</b>。
 *
 * <p>位置 / 宽度由界面决定（{@code UiLayout.SCROLLBAR_*}），这里负责「画」和「拖动怎么算」：
 * <ul>
 *     <li>滑块高度按「一页能放几行 / 总行数」算，最短 {@link ComponentLayout#SCROLLBAR_MIN_THUMB} 像素
 *         （但不能占满整条轨道，否则「按住拖」没有行程）</li>
 *     <li>按住滑块上下拖 → 滑块始终跟着指针走，滚动行数由滑块位置反算；点轨道空白 → 翻一页</li>
 *     <li>滚轮由界面统一处理（滚轮在内容区任意位置都能滚）</li>
 * </ul>
 *
 * <p><b>输入为什么不在这个类里接</b>（这里踩过坑）：26.x 的拖拽是由
 * {@code ContainerEventHandler} 转发的 —— 只有「按下时命中的那个子控件返回 true」它才会
 * {@code setFocused + setDragging}，之后每帧 {@code mouseDragged} 也只转给「有焦点且 dragging」
 * 的那一个控件。而配置界面在服务端规则同步 / 搜索词变化时会整页 {@code rebuildWidgets()}，
 * 滚动条是重建时新建的实例、屏幕焦点也被清掉 —— 于是拖到一半事件就不来了，必须松手重按。
 *
 * <p>所以现在的分工是：
 * <ul>
 *     <li>本类只提供 {@link #grabAt} / {@link #dragTo} / {@link #endDrag} 这套状态机
 *         （纯几何计算，不碰任何事件分发）；</li>
 *     <li>{@link RowListPage} 把它转成 {@code mousePressedScrollBar/...}；</li>
 *     <li>屏幕在自己的 {@code mouseClickedScaled / mouseDraggedScaled / mouseReleasedScaled} 里
 *         直接调 —— 抓取状态挂在长生命周期的页面组件上，重建界面不会丢，也不依赖焦点。</li>
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
     * 请求把滚动位置设到第几行（写）
     */
    private final DoubleConsumer onScroll;

    /**
     * 按下点相对滑块顶部的像素偏移；{@code < 0} 表示「没在拖」
     *
     * <p>存偏移而不是「按下点的绝对 y」：这样滑块永远跟着指针走，不会因为拖动过程中
     * 列表行数 / 每页行数变化而漂移。
     */
    private double grabOffset = -1.0D;

    public ScrollBar(int x, int y, int width, int height,
                     IntSupplier totalRows, IntSupplier pageRows,
                     DoubleSupplier scroll, DoubleConsumer onScroll) {
        super(x, y, width, height, Component.translatable("villagerdetails.ui.scrollbar"));
        this.totalRows = totalRows;
        this.pageRows = pageRows;
        this.scroll = scroll;
        this.onScroll = onScroll;
    }

    // ==============================================================
    // 几何 / 换算
    // ==============================================================

    /**
     * 还能往下滚几行（0 表示一页放得下，不需要滚动）
     */
    private double maxScroll() {
        return Math.max(0, totalRows.getAsInt() - pageRows.getAsInt());
    }

    /**
     * 滑块高度：按「可见比例」缩放，最短 SCROLLBAR_MIN_THUMB，最长整条轨道
     */
    public int thumbHeight() {
        int track = Math.max(1, getHeight());
        int total = totalRows.getAsInt();
        if (total <= 0) {
            return track;
        }
        int visible = Math.min(pageRows.getAsInt(), total);
        // 最短滑块：SCROLLBAR_MIN_THUMB，但必须给轨道留出行程（track - 1）。
        // 页面很矮时一页只放一行、轨道只有 26 像素，而最短滑块是 32 —— 滑块会占满整条轨道，
        // 行程变成 0，按住拖就「完全没反应」。
        int minThumb = Math.clamp(track - 1, 1, ComponentLayout.SCROLLBAR_MIN_THUMB);
        int scaled = Math.round((float) track * visible / total);
        return Math.clamp(scaled, minThumb, track);
    }

    /**
     * 滑块上边界：把 [0, maxScroll] 映射到轨道的剩余行程上
     */
    public int thumbY() {
        double max = maxScroll();
        int travel = trackTravel();
        if (max <= 0 || travel <= 0) {
            return getY();
        }
        double ratio = Math.clamp(scroll.getAsDouble() / max, 0.0D, 1.0D);
        return getY() + (int) Math.round(travel * ratio);
    }

    /**
     * 滑块能走的行程（像素）
     */
    public int trackTravel() {
        return getHeight() - thumbHeight();
    }

    /**
     * 请求滚动到指定行（越界由 {@link #dragTo} 夹住）
     */
    private void requestScroll(double rows) {
        onScroll.accept(Math.clamp(rows, 0.0D, maxScroll()));
    }

    // ==============================================================
    // 拖动状态机（由界面转发，不依赖控件焦点）
    // ==============================================================

    /**
     * 按下：命中滚动条就接管。
     *
     * @return true = 这次按下属于滚动条（界面应当吃掉这个点击）
     */
    public boolean grabAt(double mouseX, double mouseY) {
        if (!this.visible || !this.active || maxScroll() <= 0) {
            return false;
        }
        if (mouseX < getX() || mouseX >= getX() + (double) getWidth()) {
            return false;
        }
        if (mouseY < getY() || mouseY >= getY() + (double) getHeight()) {
            return false;
        }

        int thumbTop = thumbY();
        int thumbHeight = thumbHeight();
        if (mouseY >= thumbTop && mouseY < thumbTop + thumbHeight) {
            // 抓住滑块：记住「按在滑块的哪个高度上」，之后滑块跟着走
            this.grabOffset = mouseY - thumbTop;
        } else {
            // 点轨道空白：往点的那个方向翻一页（不进入拖动，和原版列表一致）
            double page = pageRows.getAsInt();
            requestScroll(scroll.getAsDouble() + (mouseY < thumbTop ? -page : page));
        }
        return true;
    }

    /**
     * 拖动：滑块跟着指针走，滚动行数由滑块位置反算。
     *
     * @return true = 正在拖滚动条（界面应当吃掉这次拖拽）
     */
    public boolean dragTo(double mouseY) {
        if (this.grabOffset < 0.0D) {
            return false;
        }
        int travel = trackTravel();
        if (travel <= 0) {
            return true;
        }

        double wantedTop = mouseY - this.grabOffset;
        double clampedTop = Math.clamp(wantedTop, getY(), getY() + (double) travel);
        if (clampedTop != wantedTop) {
            // 拖过端点（鼠标跑到轨道外面）时把抓取点重新锚在端点：
            // 否则往回移的那几像素只是「补偿」这段多余位移，滑块会一直贴边不动（死区）。
            this.grabOffset = mouseY - clampedTop;
        }
        requestScroll((clampedTop - getY()) / travel * maxScroll());
        return true;
    }

    /**
     * 松手：结束拖动
     */
    public void endDrag() {
        this.grabOffset = -1.0D;
    }

    /**
     * 界面重建时把「正在拖」的状态接过来。
     *
     * <p>{@code rebuildWidgets()} 会新建一条滚动条（规则同步 / 搜索词变化都会触发），
     * 抓取状态不接过来的话，拖到一半界面一刷新就「松手了」，得重新按一次 —— 表现为
     * 「拖着拖着就卡住不动」。
     */
    public void adoptGrabFrom(ScrollBar previous) {
        if (previous != null) {
            this.grabOffset = previous.grabOffset;
        }
    }

    /**
     * 现在是不是正按着滑块拖
     */
    public boolean isGrabbed() {
        return this.grabOffset >= 0.0D;
    }

    // ==============================================================
    // 绘制
    // ==============================================================

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
