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
     * 按「完成」：把工作副本写回（本机配置落盘 / 服务器规则发差异给服务端）
     */
    protected abstract void onFinish();

    /**
     * 子类自己的页脚控件（默认什么都不加）
     */
    protected void addFooterWidgets() {
    }

    // ==============================================================
    // 几何
    // ==============================================================

    protected int contentWidth() {
        return Math.min(contentMaxWidth(), this.width - DETAIL_CONTENT_SIDE_MARGIN * 2);
    }

    protected int contentX() {
        return (this.width - contentWidth()) / 2;
    }

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
     * 「完成」：写回工作副本，然后回上一页
     */
    private void finish() {
        onFinish();
        this.minecraft.gui.setScreen(parent);
    }

    /**
     * 「取消」：这次改动全部丢掉，直接回上一页
     */
    private void cancel() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        // ESC / 其它关闭路径 = 取消：只有按「完成」才会写回
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
        int key = event.key();
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            if (idBox != null && !idValid && !popup.isEmpty()) {
                popup.acceptInto(idBox);
            }
            onCommit.run();
            return true;
        }
        if (key == InputConstants.KEY_TAB) {
            popup.acceptInto(idBox);
            return true;
        }
        if (key == InputConstants.KEY_DOWN) {
            popup.move(1);
            return true;
        }
        if (key == InputConstants.KEY_UP) {
            popup.move(-1);
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE) {
            onCancel.run();
            return true;
        }
        return false;
    }

    // ==============================================================
    // 绘制
    // ==============================================================

    @Override
    public void extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float frameTicks) {
        super.extractBackground(graphics, mouseX, mouseY, frameTicks);
        ScreenDecorations.drawSeparators(graphics, this.width, this.height);
    }

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
