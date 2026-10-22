package com.villagerdetails.client.setting.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.villagerdetails.client.setting.component.RowListPage;
import com.villagerdetails.client.setting.component.ScreenDecorations;
import com.villagerdetails.client.setting.component.SuggestionPopup;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

import static com.villagerdetails.client.setting.config.ui.UiLayout.*;

/**
 * 两个「列表详情页」的公共骨架（列表页 / 键值列表页）。
 *
 * <p>它们只是同一件事的两种排版：<b>一个可以滚动的行列表 + 顶部说明 + 底部「取消 / 完成」</b>。
 * 所以几何、面板、画布裁剪、滚动推进、页脚按钮、焦点交接都在这里；
 * 子类只管「行长什么样」「按完成时写回什么」。
 *
 * <p><b>改动一律是临时的</b>：子类持有一份工作副本，只有按「完成」才写回（{@link #onFinish}），
 * 「取消」/ ESC / 其它关闭路径都整份丢掉。
 *
 * <p>分页滚动那一套在 {@link RowListPage}；补全弹窗在 {@link SuggestionPopup}。
 */
public abstract class AbstractDetailScreen extends Screen {

    /**
     * 按「完成」/「取消」后返回的那一页（通常是配置主界面）
     */
    private final Screen parent;

    /**
     * 一个可以滚动的行列表（和配置主界面共用同一个组件）
     *
     * <p>{@code this::totalRows} 只是交给列表<b>稍后</b>渲染时用的回调，构造期不会被调用，
     * 所以这里的「this 提前逸出」是安全的；javac 的 this-escape 检查无法区分，故就地压制。
     */
    @SuppressWarnings("this-escape")
    protected final RowListPage page = new RowListPage(this::totalRows, MAX_ROWS_PER_PAGE);

    /**
     * 界面重建后要接管的焦点（新出现的输入框用 {@link #requestFocus}）
     */
    private AbstractWidget focusAfterInit;

    /**
     * @param title  界面标题（会显示在顶部）
     * @param parent 关闭后返回的那一页
     */
    protected AbstractDetailScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    // ==============================================================
    // 子类要实现的
    // ==============================================================

    /**
     * 内容区的最大宽度（窗口窄了会再收）
     */
    protected abstract int contentMaxWidth();

    /**
     * 列表区域的上边界
     */
    protected abstract int rowsTop();

    /**
     * 一共几行：数据行 + 最后那行「+」
     */
    protected abstract int totalRows();

    /**
     * 顶部那行说明
     */
    protected abstract Component description();

    /**
     * 往 {@link #page} 里塞行控件（基类已经 {@code clearRows()} 过）
     */
    protected abstract void buildRows();

    /**
     * 按「完成」：把工作副本写回（本机配置落盘 / 服务器规则发差异给服务端）。
     *
     * <p>返回 {@code true} = 校验通过、已写回，基类会关掉这一页回上一页；
     * 返回 {@code false} = 校验没过（子类自己把错误显示出来），界面保持打开，玩家继续改。
     * 「取消」不走这里，永远直接丢副本关屏。
     */
    protected abstract boolean onFinish();

    /**
     * 子类自己的页脚控件（默认什么都不加）
     */
    protected void addFooterWidgets() {
    }

    // ==============================================================
    // 几何
    // ==============================================================

    /**
     * 内容区宽度：取「子类声明的最大宽度」和「窗口宽度减左右边距」的较小值
     */
    protected int contentWidth() {
        return Math.min(contentMaxWidth(), this.width - DETAIL_CONTENT_SIDE_MARGIN * 2);
    }

    /**
     * 内容区左边界（居中）
     */
    protected int contentX() {
        return (this.width - contentWidth()) / 2;
    }

    /**
     * 内容区右边界
     */
    protected int contentRight() {
        return contentX() + contentWidth();
    }

    /**
     * 内容区右边界（需要滚动时给滚动条让出一列）
     */
    protected int rowsRight() {
        return page.rowsRight();
    }

    // ==============================================================
    // 生命周期
    // ==============================================================

    /**
     * 界面初始化 / 重建：先摆几何，再造行控件，最后补页脚按钮。
     *
     * <p>顺序不能乱：{@code buildRows()} 里要用 {@link #rowsRight()}，
     * 而它取决于「需不需要滚动条」，所以几何必须先于行控件交给列表组件。
     */
    @Override
    protected void init() {
        // 先把几何交给列表组件，再造行控件（造行时要用到 rowsRight()，它取决于「需不需要滚动条」）
        page.setGeometry(contentX(), contentRight(), rowsTop(), footerButtonY(this.height) - ROW_SCROLL_MARGIN);
        page.setPanel(contentX() - PANEL_PADDING, rowsTop() - PANEL_PADDING,
                contentRight() + PANEL_PADDING);

        // 注意：子类<b>不要</b>在这里重新读配置 —— 工作副本要留着，重建界面（增删后刷新）不能把它冲掉
        page.clearRows();
        buildRows();

        // 滚动条放在行控件之后添加，这样它画在行控件上面
        page.installScrollBar(this::addRenderableWidget);

        page.clamp();
        page.updatePositions();

        // 左：取消（丢掉这次改动）；右：完成（写回）
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> cancel())
                .bounds(footerLeftButtonX(this.width), footerButtonY(this.height),
                        FOOTER_BUTTON_WIDTH, WIDGET_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> finish())
                .bounds(footerRightButtonX(this.width), footerButtonY(this.height),
                        FOOTER_BUTTON_WIDTH, WIDGET_HEIGHT)
                .build());

        addFooterWidgets();
    }

    /**
     * 「完成」：只有子类校验通过（{@link #onFinish()} 返回 true）才关屏，
     * 校验没过就留在原处，等玩家把输入改对。
     */
    private void finish() {
        if (onFinish()) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    /**
     * 「取消」：这次改动全部丢掉，直接回上一页
     */
    private void cancel() {
        this.minecraft.gui.setScreen(parent);
    }

    /**
     * ESC / 其它关闭路径一律当作「取消」：只有按「完成」才会写回
     */
    @Override
    public void onClose() {
        cancel();
    }

    // ==============================================================
    // 焦点 / 补全弹窗
    // ==============================================================

    /**
     * 界面重建后把这个控件设成初始焦点（点「+」之后新出现的输入框用）
     */
    protected void requestFocus(AbstractWidget widget) {
        this.focusAfterInit = widget;
    }

    /**
     * 初始焦点：优先交给 {@link #requestFocus} 登记的控件，没有就走原版逻辑
     */
    @Override
    protected void setInitialFocus() {
        if (focusAfterInit != null) {
            AbstractWidget widget = focusAfterInit;
            focusAfterInit = null;
            setInitialFocus(widget);
            return;
        }
        super.setInitialFocus();
    }

    /**
     * 补全弹窗的通用按键：回车 / Tab / 上下 / ESC。
     *
     * @param idBox    补全要填进去的那个输入框
     * @param idValid  当前输入框里的 id 是不是已经合法（不合法且有候选 → 先用高亮那条补全）
     * @param onCommit 回车之后把输入收进工作副本
     * @param onCancel ESC：放弃这次输入
     * @return true = 这个键已经被吃掉，界面别再往下传
     */
    protected final boolean handleEditingKey(KeyEvent event, SuggestionPopup popup, EditBox idBox,
                                             boolean idValid, Runnable onCommit, Runnable onCancel) {
        return switch (event.key()) {
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                if (idBox != null && !idValid && !popup.isEmpty()) {
                    popup.acceptInto(idBox);
                }
                onCommit.run();
                yield true;
            }
            case InputConstants.KEY_TAB -> {
                popup.acceptInto(idBox);
                yield true;
            }
            case InputConstants.KEY_DOWN -> {
                popup.move(1);
                yield true;
            }
            case InputConstants.KEY_UP -> {
                popup.move(-1);
                yield true;
            }
            case InputConstants.KEY_ESCAPE -> {
                onCancel.run();
                yield true;
            }
            default -> false;
        };
    }

    // ==============================================================
    // 绘制
    // ==============================================================

    /**
     * 背景之上再补一条顶 / 底分隔线（和主配置界面观感一致）
     */
    @Override
    public void extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float frameTicks) {
        super.extractBackground(graphics, mouseX, mouseY, frameTicks);
        ScreenDecorations.drawSeparators(graphics, this.width, this.height);
    }

    /**
     * 渲染一帧：先按这一帧的进度把行摆好（逐帧平滑），再画面板 / 标题 / 说明 / 行 / 其它控件。
     */
    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float frameTicks) {
        // 先按这一帧的进度把行摆好（逐帧平滑），再画面板和控件
        page.advance(frameTicks);

        page.drawPanel(graphics);
        ScreenDecorations.drawTitle(graphics, this, this.title);
        graphics.textWithWordWrap(this.font, description(),
                contentX(), DETAIL_DESC_TOP, contentWidth(), ScreenDecorations.COLOR_HINT);

        page.drawRows(graphics, mouseX, mouseY, frameTicks, () -> drawOverlay(graphics));

        // 滚动条 / 页脚按钮照旧交给原版渲染
        super.extractRenderState(graphics, mouseX, mouseY, frameTicks);
    }

    /**
     * 画在行控件上面、并且和它们一起被裁剪的内容（补全弹窗就是它）；默认什么都不画
     */
    protected void drawOverlay(GuiGraphicsExtractor graphics) {
    }
}
