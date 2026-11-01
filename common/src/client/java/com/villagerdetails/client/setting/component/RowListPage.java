package com.villagerdetails.client.setting.component;

import com.villagerdetails.client.setting.config.ui.ComponentLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static com.villagerdetails.client.setting.config.ui.UiLayout.*;

/**
 * 「一页行列表」组件：配置主界面的内容行和两个详情页的列表行共用的那一整套东西。
 *
 * <ul>
 *     <li>分页：一页最多 {@code maxRowsPerPage} 行，同时受窗口高度限制，至少 1 行</li>
 *     <li>滚动：{@link RowScrollState} 逐帧平滑（帧率无关）+ {@link ScrollBar}（不足一页就不显示，
 *         那一列还给行控件）</li>
 *     <li>摆位：每个行控件记着「相对行顶的偏移」，滚动时按行位置重摆，滚出画布的整行隐藏</li>
 *     <li>画布：{@link #drawRows} 里开 scissor，滚动时上下两端那半个项照常渲染，超出部分被裁掉</li>
 *     <li>面板：{@link #drawPanel} 按<b>实际画出来的行数</b>收边（空列表也撑出一行）</li>
 * </ul>
 *
 * <p>界面要做的只有三件事：{@link #setGeometry} 给几何、{@link #setPanel} 给面板范围、
 * {@link #addRow} 塞行控件。滚轮 / 键盘仍由界面自己决定（它知道鼠标在不在列表区，
 * 见 {@link #isMouseOverRows}）。
 *
 * <p>行控件一律用 {@code addWidget} 加（只进 children），统一由 {@link #drawRows} 画在画布里 ——
 * 这样才不会被 {@code Screen#extractRenderState} 再画一遍。
 */
public final class RowListPage {

    /**
     * 行里的一个控件，外加它<b>相对行顶的 y 偏移</b>。
     *
     * <p>光记控件是不够的：滚动时每帧都要按行位置重摆一遍，如果一律 {@code setY(行 y)}，
     * 那些故意往下挪过的控件（比如色块要垂直居中）就会被摆回行顶。
     */
    public record Widget(AbstractWidget widget, int yOffset) {
    }

    /**
     * 贴着行顶的控件
     */
    public static Widget at(AbstractWidget widget) {
        return new Widget(widget, 0);
    }

    /**
     * 相对行顶往下挪 {@code yOffset} 像素的控件
     */
    public static Widget offset(AbstractWidget widget, int yOffset) {
        return new Widget(widget, yOffset);
    }

    private final RowScrollState scroll = new RowScrollState();
    private final List<List<Widget>> rows = new ArrayList<>();

    /**
     * 一共几行
     */
    private final IntSupplier totalRows;
    /**
     * 一页最多几行
     */
    private final int maxRowsPerPage;

    // ---------------- 几何（每次 init / 窗口大小变化时设一次） ----------------

    private int contentX;
    private int contentRight;
    private int rowsTop;
    private int rowsBottom;
    private int panelLeft;
    private int panelTop;
    private int panelRight;

    private ScrollBar scrollBar;

    /**
     * 是否「整行步进」（缩放后的界面用）。
     *
     * <p>为什么要它：界面整体等比缩放时不能再开 scissor 裁剪行画布 —— 26.x 的
     * {@code enableScissor} 会把矩形按当前 pose 变换后入栈，而控件悬停用的鼠标坐标是「基准空间」的，
     * 两套空间对不上会让 tooltip / 悬停高亮在画布里整体失效。
     *
     * <p>所以缩放时改为：滚动偏移取整到整行（不再有半行）、只画完整落在画布里的行、
     * 绘制时不裁剪。观感从「连续平滑滚动」变成「整行步进」，但悬停、tooltip、点击全部正常。
     */
    private boolean snapToRows;

    public RowListPage(IntSupplier totalRows, int maxRowsPerPage) {
        this.totalRows = totalRows;
        this.maxRowsPerPage = maxRowsPerPage;
    }

    /**
     * 切换「整行步进」模式（缩放后的界面必须打开，见 {@link #snapToRows}）
     */
    public void setSnapToRows(boolean snap) {
        this.snapToRows = snap;
    }

    /**
     * 内容区（行控件那一列）的范围，以及列表区域的上下边界。
     *
     * @param rowsBottom 列表能到的最下面（页脚按钮上方），只用来算「一页几行」
     */
    public void setGeometry(int contentX, int contentRight, int rowsTop, int rowsBottom) {
        this.contentX = contentX;
        this.contentRight = contentRight;
        this.rowsTop = rowsTop;
        this.rowsBottom = rowsBottom;
    }

    /**
     * 列表区那块半透明面板的范围（下边界跟着实际行数走）。
     *
     * <p>面板通常比内容区往外扩 {@code PANEL_PADDING}，也就是行控件画布的左右边界。
     */
    public void setPanel(int left, int top, int right) {
        this.panelLeft = left;
        this.panelTop = top;
        this.panelRight = right;
    }

    // ---------------- 行内容 ----------------

    public void clearRows() {
        rows.clear();
    }

    public void addRow(List<Widget> widgets) {
        rows.add(widgets);
    }

    // ---------------- 几何查询 ----------------

    public int contentX() {
        return contentX;
    }

    public int contentRight() {
        return contentRight;
    }

    /**
     * 一页能放几行：受窗口高度和 {@code maxRowsPerPage} 双重限制，至少 1 行
     */
    public int rowsPerPage() {
        // 上限先兜一次底，免得 clamp 的 min > max 抛异常
        return Math.clamp((rowsBottom - rowsTop) / ROW_HEIGHT, 1, Math.max(1, maxRowsPerPage));
    }

    public int rowY(int index) {
        return rowsTop + index * ROW_HEIGHT;
    }

    /**
     * 这一行现在该摆在哪个 y（带滚动偏移）
     *
     * <p>{@link #snapToRows} 打开时偏移量取整到整行，所以不会出现「半行」。
     */
    public float rowDrawY(int index) {
        if (snapToRows) {
            return rowY(index) - Math.round(scroll.scroll()) * ROW_HEIGHT;
        }
        return rowY(index) - scroll.offsetPx(ROW_HEIGHT);
    }

    /**
     * 这一页的可见区下边界：由「一页的行数」决定，窗口再高也不会多显示（多出来的行要滚上来）
     */
    public int viewportBottom() {
        return rowsTop + rowsPerPage() * ROW_HEIGHT;
    }

    /**
     * 行画布的上边界：往上让出行间空隙，滚动时上面那半行能露出来
     */
    public int canvasTop() {
        return rowsTop - ROW_GAP_Y;
    }

    /**
     * 内容面板的底边：按实际画出来的行数收边。
     *
     * <p>一页放满时它正好等于 {@link #viewportBottom()}；行数不足一页时贴着最后一行收边。
     */
    public int panelBottom() {
        // 空列表也要撑出一行的位置，所以下限是 1；上限先兜一次底，免得 clamp 的 min > max 抛异常
        int perPage = Math.max(1, rowsPerPage());
        int shown = Math.clamp(totalRows.getAsInt(), 1, perPage);
        return rowY(shown - 1) + WIDGET_HEIGHT + PANEL_PADDING;
    }

    /**
     * 内容是否超过一页
     */
    public boolean scrollingNeeded() {
        return totalRows.getAsInt() > rowsPerPage();
    }

    /**
     * 内容区右边界：需要滚动时给滚动条让出一列
     */
    public int rowsRight() {
        return scrollingNeeded() ? contentRight - SCROLLBAR_WIDTH - SCROLLBAR_GAP : contentRight;
    }

    public int scrollBarX() {
        return contentRight - SCROLLBAR_WIDTH;
    }

    /**
     * 鼠标是不是落在列表区里（滚轮要不要滚这个列表由界面拿它判）
     */
    public boolean isMouseOverRows(double mouseX, double mouseY) {
        return mouseX >= contentX && mouseX <= contentRight
                && mouseY >= rowsTop && mouseY <= viewportBottom();
    }

    public float scrollRows() {
        return scroll.scroll();
    }

    // ---------------- 滚动 ----------------

    /**
     * 造好右侧滚动条并交给界面登记。
     *
     * <p>滚动条必须在行控件<b>之后</b>登记，这样它画在行控件上面。
     */
    public void installScrollBar(Consumer<ScrollBar> registrar) {
        this.scrollBar = new ScrollBar(scrollBarX(), rowsTop, SCROLLBAR_WIDTH,
                Math.max(1, viewportBottom() - rowsTop),
                totalRows, this::rowsPerPage,
                () -> (double) scroll.scroll(), this::setScroll);
        registrar.accept(scrollBar);
    }

    /**
     * 把所有行控件按当前滚动偏移摆好，并把滚出画布的整行藏起来
     */
    public void updatePositions() {
        for (int i = 0; i < rows.size(); i++) {
            int y = Math.round(rowDrawY(i));
            boolean visible = rowVisible(i);
            for (Widget entry : rows.get(i)) {
                // 行顶 + 这个控件自己的偏移（色块之类的居中偏移就靠它保住）
                entry.widget().setY(y + entry.yOffset());
                entry.widget().visible = visible;
            }
        }
        updateScrollBar();
    }

    /**
     * 这一行现在该不该画。
     *
     * <p>平滑模式：和画布有交集就画（上下允许露半个项，多出来的由 {@link #drawRows} 的画布裁掉）。
     * 整行模式（缩放后的界面）：必须<b>整个</b>落在画布里 —— 没有裁剪，半行会露到画布外面去。
     *
     * <p>判据用的是 {@link #viewportBottom()}（整页高度）而不是面板底边：行数不足一页时面板贴着
     * 最后一行收边，但画布仍然是一整页 —— 详情页的补全弹窗就画在「添加」那一行下面那片空白里。
     */
    private boolean rowVisible(int index) {
        float y = rowDrawY(index);
        if (snapToRows) {
            return y >= rowsTop && y + WIDGET_HEIGHT <= viewportBottom();
        }
        return y + WIDGET_HEIGHT > canvasTop() && y < viewportBottom();
    }

    /**
     * 第 {@code index} 行现在画不画（不可见 = 滚出画布了）
     */
    public boolean isRowVisible(int index) {
        return index >= 0 && index < rows.size() && rowVisible(index);
    }

    /**
     * 滚动条的显隐 / 位置：内容超过一页才显示，不足一页时整条隐藏
     */
    private void updateScrollBar() {
        if (scrollBar == null) {
            return;
        }
        boolean needed = scrollingNeeded();
        scrollBar.visible = needed;
        scrollBar.active = needed;
        scrollBar.setX(scrollBarX());
        scrollBar.setY(rowsTop);
        scrollBar.setWidth(SCROLLBAR_WIDTH);
        scrollBar.setHeight(Math.max(1, viewportBottom() - rowsTop));
    }

    /**
     * 把当前位置夹回合法范围（行数变了之后调，比如删掉了一项）
     */
    public void clamp() {
        scroll.clampTo(totalRows.getAsInt(), rowsPerPage());
    }

    /**
     * 回到顶部（切一级 / 二级分类时调）。
     *
     * <p>滚动位置挂在 {@link RowScrollState} 上，而它是本组件的长生命周期字段 ——
     * 切分类只重建控件、不动它，于是新分类会「继承」上一个分类的滚动位置：如果新分类内容更少，
     * 玩家会看到一个已经被拉到中间/底部的列表，甚至一片空白（看起来像分类没内容）。
     * 切分类是「换了一页内容」，理应回到顶部。
     */
    public void resetScroll() {
        scroll.set(0.0D, totalRows.getAsInt(), rowsPerPage());
    }

    /**
     * 直接设到某一行，不做动画（拖滚动条 / 点轨道时用：要求「手到哪、内容到哪」）
     */
    public void setScroll(double row) {
        scroll.set(row, totalRows.getAsInt(), rowsPerPage());
        updatePositions();
    }

    /**
     * 滚指定行数（滚轮用），走平滑动画
     */
    public void scrollBy(int deltaRows) {
        scroll.scrollBy(deltaRows, totalRows.getAsInt(), rowsPerPage());
    }

    /**
     * 每帧推进一次滚动动画。
     *
     * <p>整行模式（缩放后的界面）不做插值：直接吸附到目标行，避免出现半行。
     *
     * @param frameTicks 这一帧经过了多少个 tick（60fps ≈ 0.33，20fps ≈ 1.0）
     * @return 位置有没有变（没变就不用重摆控件）
     */
    public boolean advance(float frameTicks) {
        if (snapToRows) {
            float target = scroll.target();
            if (scroll.scroll() == target) {
                return false;
            }
            scroll.set(target, totalRows.getAsInt(), rowsPerPage());
            updatePositions();
            return true;
        }
        if (!scroll.advance(frameTicks)) {
            return false;
        }
        updatePositions();
        return true;
    }

    // ---------------- 绘制 ----------------

    /**
     * 列表区那块半透明面板
     */
    public void drawPanel(GuiGraphicsExtractor graphics) {
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom(), ComponentLayout.PANEL_COLOR);
    }

    /**
     * 把行画在「一页」这块画布里。
     *
     * <p>平滑模式：先开 scissor 当画布，滚动时上下两端那半个项照常渲染、超出部分被裁掉 ——
     * 看起来就是「列表被一个方框挡住」。画布下边界用的是整页高度（见 {@link #rowVisible}），
     * 所以详情页的补全弹窗能在最后一行下面那片空白里画全。
     *
     * <p>整行模式（缩放后的界面）：<b>不开 scissor</b>。缩放时 scissor 的矩形会被 pose 变换，
     * 和悬停用的基准坐标对不上，一旦开启就会让画布里的 hover / tooltip 全部失效；这个模式下
     * 滚动量取整到整行，只画完整落在画布里的行，所以本来也不需要裁剪。
     *
     * @param overlay 画在行控件上面、并且和它们一起被裁剪的内容（补全弹窗就是它）；没有就传 null
     */
    public void drawRows(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float frameTicks,
                         Runnable overlay) {
        if (snapToRows) {
            for (List<Widget> row : rows) {
                for (Widget entry : row) {
                    entry.widget().extractRenderState(graphics, mouseX, mouseY, frameTicks);
                }
            }
            if (overlay != null) {
                overlay.run();
            }
            return;
        }
        graphics.enableScissor(panelLeft, canvasTop(), panelRight, viewportBottom());
        try {
            for (List<Widget> row : rows) {
                for (Widget entry : row) {
                    // visible=false 的行会自动跳过（内部判断），这里不用再筛
                    entry.widget().extractRenderState(graphics, mouseX, mouseY, frameTicks);
                }
            }
            if (overlay != null) {
                overlay.run();
            }
        } finally {
            graphics.disableScissor();
        }
    }
}
