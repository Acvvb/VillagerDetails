package com.villagerdetails.client.setting.screen;

import com.villagerdetails.client.setting.component.ValueSlider;
import com.villagerdetails.client.setting.config.ui.UiLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.NonNull;

/**
 * 「按基准尺寸等比缩放」的界面基类：模组的配置界面都继承它。
 *
 * <p><b>为什么要它</b>：界面布局是照 {@link UiLayout#DESIGN_WIDTH}（480 = 1920 屏 + GUI Scale 4）
 * 设计的，写死的 40 / 100 / 50 这些宽度在 Scale 1~4 都放得下；但玩家把 GUI Scale 调到 5 / 6 时
 * 逻辑宽度只剩 320、240，固定布局必然重叠。之前试过截断文字、缩写按钮，那是「局部删减」，
 * 会破坏 UI 的完整性。这里换个思路：<b>布局一个像素都不改，整块像一张图一样按比例缩小</b>。
 *
 * <p>实现要点（三个坐标空间必须对齐，否则会静默坏掉悬停 / 点击）：
 * <ol>
 *     <li><b>基准空间</b>：所有子类代码（布局、控件坐标、绘制）都在这个空间里，尺寸永远是
 *         {@code uiWidth() × uiHeight()}，即缩放后的 480 宽；</li>
 *     <li><b>渲染</b>：{@link #extractRenderState} 里 {@code pose.scale(scale)} 之后才画，
 *         所以 1 个基准像素 = {@code scale} 个真实逻辑像素；</li>
 *     <li><b>输入</b>：MC 送进来的是真实坐标，统一在 {@link #mouseClicked} / {@link #mouseScrolled}
 *         这些入口除以 {@code scale} 再往下传 —— 所以命中判定、拖拽、滚轮全部与基准空间一致。</li>
 * </ol>
 *
 * <p><b>子类实现哪几个方法</b>：{@link #initScaled}、{@link #extractScaled}
 * （可选 {@link #extractScaledBackground}、{@link #mouseClickedScaled}、{@link #mouseScrolledScaled}）。
 * 名字带 {@code Scaled} 表示「坐标系已经是基准空间」，可以直接用布局常量。
 *
 * <p><b>缩放上限</b>：{@code scale <= 1}。窗口比 480 宽时不做放大，保持原生清晰度（和以前完全一样）；
 * 只有更窄时才等比缩小。
 *
 * <p><b>附带约束</b>：缩放后不要再开 scissor 做局部裁剪 —— 26.x 的 {@code enableScissor} 会把矩形
 * 按当前 pose 变换后再存栈，而悬停命中用的是基准坐标，两套空间对不上就会让 tooltip / 悬停高亮失效。
 * 所以窄窗口下 {@code RowListPage} 改用「整行步进 + 整行裁剪」（见 {@code setSnapToRows}）。
 */
public abstract class ScaledScreen extends Screen {

    /**
     * 当前缩放因子（{@code <= 1}）
     */
    private float scale = 1.0F;

    /**
     * 基准空间下的界面宽度 / 高度（缩放前）
     */
    private int uiWidth;

    /**
     * 基准空间下的界面高度（缩放前）
     */
    private int uiHeight;

    /**
     * @param title 界面标题
     */
    protected ScaledScreen(Component title) {
        super(title);
    }

    // ==============================================================
    // 生命周期
    // ==============================================================

    /**
     * MC 每次初始化 / 窗口尺寸变化都是「先写好 {@code width/height} 再调 {@code init()}」，
     * 所以在这里换算基准尺寸是安全的；子类不要覆盖本方法。
     */
    @Override
    protected final void init() {
        updateScale();
        initScaled();
    }

    /**
     * 算缩放因子与基准尺寸
     */
    private void updateScale() {
        float factor = (float) this.width / UiLayout.DESIGN_WIDTH;
        this.scale = factor >= 1.0F ? 1.0F : Math.max(UiLayout.MIN_SCALE, factor);
        this.uiWidth = Math.max(1, Math.round(this.width / this.scale));
        this.uiHeight = Math.max(1, Math.round(this.height / this.scale));
    }

    /**
     * 子类在「基准空间」里初始化控件（相当于原来的 {@code init()}）
     */
    protected abstract void initScaled();

    // ==============================================================
    // 基准空间查询
    // ==============================================================

    /**
     * 当前缩放因子；等于 1 表示窗口够宽、没有任何缩放
     */
    protected final float uiScale() {
        return scale;
    }

    /**
     * 是否处于缩放状态（窄窗口）
     */
    protected final boolean isScaled() {
        return scale < 0.999F;
    }

    /**
     * 基准空间里的界面宽度：布局、居中计算都用它，不要再用 {@code this.width}
     */
    protected final int uiWidth() {
        return uiWidth;
    }

    /**
     * 基准空间里的界面高度：页脚、行数计算都用它，不要再用 {@code this.height}
     */
    protected final int uiHeight() {
        return uiHeight;
    }

    /**
     * 真实鼠标 X → 基准空间 X
     */
    protected final int uiX(double screenX) {
        return (int) Math.round(screenX / scale);
    }

    /**
     * 真实鼠标 Y → 基准空间 Y
     */
    protected final int uiY(double screenY) {
        return (int) Math.round(screenY / scale);
    }

    // ==============================================================
    // 渲染
    // ==============================================================

    /**
     * 先缩放坐标系，再交给子类画内容，最后让原版画所有 renderable 控件（它们也落在缩放后的坐标里）
     */
    @Override
    public final void extractRenderState(@NonNull GuiGraphicsExtractor graphics,
                                         int mouseX, int mouseY, float partialTick) {
        int x = uiX(mouseX);
        int y = uiY(mouseY);
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.scale(scale, scale);
        try {
            extractScaled(graphics, x, y, partialTick);
            super.extractRenderState(graphics, x, y, partialTick);
        } finally {
            pose.popMatrix();
        }
    }

    /**
     * 子类画自己的内容（坐标系已是基准空间）
     *
     * @param mouseX 基准空间里的鼠标 X
     * @param mouseY 基准空间里的鼠标 Y
     */
    protected abstract void extractScaled(@NonNull GuiGraphicsExtractor graphics,
                                          int mouseX, int mouseY, float partialTick);

    /**
     * 全屏背景（模糊 / 暗化）保持不缩放，装饰性的分隔条则跟着布局一起缩放
     */
    @Override
    public final void extractBackground(@NonNull GuiGraphicsExtractor graphics,
                                        int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.scale(scale, scale);
        try {
            extractScaledBackground(graphics);
        } finally {
            pose.popMatrix();
        }
    }

    /**
     * 子类在「基准空间」里画页面装饰（顶 / 底分隔条之类）；默认什么都不画
     */
    protected void extractScaledBackground(@NonNull GuiGraphicsExtractor graphics) {
    }

    // ==============================================================
    // 输入：真实坐标 → 基准空间
    // ==============================================================

    /**
     * 点击：先把坐标换算到基准空间，再交给子类的 {@link #mouseClickedScaled}
     */
    @Override
    public final boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubleClick) {
        return mouseClickedScaled(toUi(event), doubleClick);
    }

    /**
     * 子类要处理点击就覆盖这个（事件坐标已是基准空间）
     */
    protected boolean mouseClickedScaled(@NonNull MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * 松手：同样换算坐标（拖拽结束时要用对位置）
     */
    @Override
    public final boolean mouseReleased(@NonNull MouseButtonEvent event) {
        return super.mouseReleased(toUi(event));
    }

    /**
     * 拖拽：坐标和位移一起换算，滚动条拖动手感才和缩放比例一致
     */
    @Override
    public final boolean mouseDragged(@NonNull MouseButtonEvent event, double dragX, double dragY) {
        return super.mouseDragged(toUi(event), dragX / scale, dragY / scale);
    }

    /**
     * 鼠标移动（有控件靠它做跟踪）
     */
    @Override
    public final void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX / scale, mouseY / scale);
    }

    /**
     * 滚轮：坐标换算到基准空间，滚动量保持原样
     */
    @Override
    public final boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return mouseScrolledScaled(mouseX / scale, mouseY / scale, scrollX, scrollY);
    }

    /**
     * 子类要处理滚轮就覆盖这个（坐标已是基准空间）
     */
    protected boolean mouseScrolledScaled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 把滚轮优先交给「鼠标正悬停的那个滑条」。
     *
     * <p>这些界面里滑条只支持拖拽，滚轮一路落到 {@code super} 后就会去滚背后的列表 ——
     * 鼠标一停在滑条上想微调数值，列表反而跳了。所以子类在 {@code mouseScrolledScaled} 开头
     * 调一次本方法：有滑条接手就吃掉事件，没有才继续走列表滚动。
     *
     * <p>只认本项目自己的 {@link ValueSlider}（它知道步长 / 范围，并且会走
     * {@code setValue → applyValue} 更新文字与配置）。原版 {@code AbstractSliderButton} 用的是
     * 0~1 的比例值且 {@code setValue} 是 protected，没有子类就改不动；本项目所有滑条都是
     * {@link ValueSlider}，所以这里不做反射兜底。
     *
     * @return {@code true} = 这次滚轮已被滑条吃掉，界面别再往下传
     */
    protected final boolean scrollSliderAt(double mouseX, double mouseY, double scrollY) {
        for (GuiEventListener child : this.children()) {
            if (!(child instanceof ValueSlider slider) || !slider.visible || !slider.active) {
                continue;
            }
            if (slider.isMouseOver(mouseX, mouseY)) {
                return slider.handleMouseScrolled(scrollY);
            }
        }
        return false;
    }

    /**
     * 真实坐标的鼠标事件 → 基准空间（缩放为 1 时原样返回，省一次分配）
     */
    private MouseButtonEvent toUi(MouseButtonEvent event) {
        if (scale >= 0.999F) {
            return event;
        }
        return new MouseButtonEvent(event.x() / scale, event.y() / scale, event.buttonInfo());
    }
}
