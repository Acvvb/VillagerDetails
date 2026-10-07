package com.villagerdetails.client.setting.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.villagerdetails.client.setting.component.*;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.config.ComponentLayout;
import com.villagerdetails.client.setting.type.ConfigEntry;
import com.villagerdetails.client.setting.type.McIds;
import com.villagerdetails.rule.RuleSwitches;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

import static com.villagerdetails.client.setting.config.UiLayout.*;

/**
 * 列表类配置项的详情界面：一个可以滚动的值列表。
 *
 * <pre>
 *  ┌───────────────────────────────────────────────┐
 *  │ 值 1                                     [ × ] │
 *  │ 值 2                                     [ × ] │
 *  │ …                                             │
 *  │ [                    +                    ]    │ ← 最后一行是添加按钮
 *  └───────────────────────────────────────────────┘
 *  │         [ 取消 ]            [ 完成 ]           │
 * </pre>
 *
 * <p>滚动逻辑和配置主界面的内容行<b>完全同一套</b>：同一个 {@link RowScrollState}（逐帧平滑、
 * 帧率无关）+ 同一个 {@link ScrollBar}（不足一页就不显示，那一列还给内容）+ 同一套画布裁剪
 * （滚动时上下允许露半个项，超出的部分被裁掉）。一页最多 {@code MAX_ROWS_PER_PAGE} 行。
 *
 * <p><b>改动是临时的</b>：进来时把配置里的值拷一份工作副本，增删都只改这份副本，玩家可以随便试；
 * 只有按「<b>完成</b>」才会写回配置并落盘，按「<b>取消</b>」（或 ESC、或其它关闭路径）就整份丢掉。
 *
 * <p>点「+」会在那一行出现一个输入框，<b>点空白处</b>（或按回车）就把它收进那份副本 ——
 * 和绑定快捷键一个手感；按 ESC 或再点一下「+」则放弃这次输入。
 */
public class ListDetailScreen extends Screen {

    private final Screen parent;
    private final ConfigEntry entry;

    /**
     * 滚动状态（和主界面共用同一套）
     */
    private final RowScrollState scroll = new RowScrollState();

    /**
     * 本次编辑的<b>工作副本</b>（界面显示的就是它，最后一行永远是「添加」那一行）。
     *
     * <p>进来时从配置拷一份；增删都只改这份副本，<b>只有按「完成」才会写回配置并落盘</b>，
     * 按「取消」（或 ESC）直接丢掉，玩家中途看到/改的都不会留下。
     */
    private final List<String> values = new ArrayList<>();

    /**
     * 每一行的控件（用 addWidget 加，只进 children，统一在画布里画）
     */
    private final List<List<AbstractWidget>> rowWidgets = new ArrayList<>();

    private ScrollBar scrollBar;

    /**
     * 正在输入要添加的值
     */
    private boolean adding;
    private EditBox addBox;
    private AbstractWidget focusAfterInit;

    /**
     * 当前输入内容的补全候选（只在输入变化时算一次，别每帧算）
     */
    private List<McIds.Entry> suggestions = List.of();
    /**
     * 补全候选里高亮的那一条
     */
    private int suggestionIndex;
    /**
     * 上次提交的值不是合法 id（输入框标红 + 弹窗里给一行提示）
     */
    private boolean invalid;

    public ListDetailScreen(Screen parent, ConfigEntry entry) {
        super(Component.literal("[ ").append(entry.getDisplayName()).append(" ]"));
        this.parent = parent;
        this.entry = entry;
        // 进来时拷一份工作副本；之后增删都只改它，按「完成」才写回配置
        this.values.addAll(entry.getList());
    }

    // ==============================================================
    // 布局（和主界面的内容行同一套算法，只是内容区是居中的固定宽度）
    // ==============================================================

    private int contentWidth() {
        return Math.min(DETAIL_CONTENT_MAX_WIDTH, this.width - DETAIL_CONTENT_SIDE_MARGIN * 2);
    }

    private int contentX() {
        return (this.width - contentWidth()) / 2;
    }

    private int contentRight() {
        return contentX() + contentWidth();
    }

    /**
     * 列表区域的上边界
     */
    private int rowsTop() {
        return DETAIL_LIST_TOP;
    }

    /**
     * 列表区域能到的最下面（页脚按钮上方）
     */
    private int rowsBottom() {
        return footerButtonY(this.height) - ROW_SCROLL_MARGIN;
    }

    /**
     * 一页能放几行：受窗口高度和 MAX_ROWS_PER_PAGE 双重限制，至少 1 行
     */
    private int rowsPerPage() {
        // 上限先兜一次底，免得 clamp 的 min > max 抛异常
        return Math.clamp((rowsBottom() - rowsTop()) / ROW_HEIGHT, 1, Math.max(1, MAX_ROWS_PER_PAGE));
    }

    /**
     * 总行数：每个值一行，最后一行是「添加」
     */
    private int totalRows() {
        return values.size() + 1;
    }

    /**
     * 这一页的下边界
     */
    private int rowsViewportBottom() {
        return rowsTop() + rowsPerPage() * ROW_HEIGHT;
    }

    /**
     * 画布上边界：往上让出行间空隙，滚动时上面那半行能露出来
     */
    private int rowsCanvasTop() {
        return rowsTop() - ROW_GAP_Y;
    }

    private int rowY(int index) {
        return rowsTop() + index * ROW_HEIGHT;
    }

    private float rowDrawY(int index) {
        return rowY(index) - scroll.offsetPx(ROW_HEIGHT);
    }

    /**
     * 和画布有交集就画（上下允许露半个项，多出来的由画布裁掉）
     */
    private boolean rowVisible(int index) {
        float y = rowDrawY(index);
        return y + WIDGET_HEIGHT > rowsCanvasTop() && y < rowsViewportBottom();
    }

    private boolean scrollingNeeded() {
        return totalRows() > rowsPerPage();
    }

    /**
     * 内容区右边界：需要滚动时给滚动条让出一列
     */
    private int rowsRight() {
        return scrollingNeeded() ? contentRight() - SCROLLBAR_WIDTH - SCROLLBAR_GAP : contentRight();
    }

    private int scrollBarX() {
        return contentRight() - SCROLLBAR_WIDTH;
    }

    /**
     * 每个值右边那个「×」删除按钮的 x
     */
    private int deleteX() {
        return rowsRight() - DETAIL_DELETE_WIDTH;
    }

    /**
     * 值文字能占的宽度
     */
    private int labelWidth() {
        return Math.max(0, deleteX() - WIDGET_GAP - contentX());
    }

    // ==============================================================
    // 控件
    // ==============================================================

    @Override
    protected void init() {
        // 注意：这里<b>不要</b>重新从配置读 —— values 是本次编辑的工作副本，重建界面（比如增删后刷新）时要留着
        this.rowWidgets.clear();

        for (int i = 0; i < values.size(); i++) {
            List<AbstractWidget> widgets = new ArrayList<>();
            int y = rowY(i);
            int index = i;

            // 值本身：太长就截断（免得压到右边的删除按钮上）
            String text = this.font.plainSubstrByWidth(values.get(i), labelWidth() - ComponentLayout.LABEL_PADDING * 2);
            widgets.add(addWidget(new OptionLabel(contentX() + ComponentLayout.LABEL_PADDING, y,
                    labelWidth(), WIDGET_HEIGHT, Component.literal(text))));

            // 右边红色的「×」：删掉这个值
            widgets.add(addWidget(new SwitchButton(deleteX(), y, DETAIL_DELETE_WIDTH, WIDGET_HEIGHT,
                    Component.literal("×"), RuleSwitches.COLOR_SELECTED_OFF,
                    () -> deleteValue(index))
                    .withTooltip(Component.translatable("villagerdetails.ui.list.delete"))));

            rowWidgets.add(widgets);
        }

        // 最后一行：「+」添加按钮 / 点开后变成输入框
        List<AbstractWidget> addRow = new ArrayList<>();
        int addY = rowY(values.size());
        if (adding) {
            this.addBox = new EditBox(this.font, contentX(), addY, rowsRight() - contentX(), WIDGET_HEIGHT,
                    Component.translatable("villagerdetails.ui.list.add"));
            addBox.setHint(Component.translatable("villagerdetails.ui.list.add.hint"));
            addBox.setMaxLength(MAX_VALUE_LENGTH);
            // 输入变化时才重算补全（search 会遍历整张 id 表）
            addBox.setResponder(text -> {
                this.suggestions = McIds.search(text, maxSuggestions());
                this.suggestionIndex = 0;
                // 又开始打字了，先把上次的报错清掉
                this.invalid = false;
                addBox.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
            });
            addRow.add(addWidget(addBox));
            this.focusAfterInit = addBox;
        } else {
            this.addBox = null;
            this.suggestions = List.of();
            addRow.add(addWidget(Button.builder(Component.literal("+"), button -> startAdd())
                    .bounds(contentX(), addY, rowsRight() - contentX(), WIDGET_HEIGHT)
                    .build()));
        }
        rowWidgets.add(addRow);

        // 滚动条放在行控件之后添加，这样它画在行控件上面
        this.scrollBar = new ScrollBar(scrollBarX(), rowsTop(), SCROLLBAR_WIDTH,
                Math.max(1, rowsViewportBottom() - rowsTop()),
                this::totalRows, this::rowsPerPage,
                () -> (double) scroll.scroll(), this::setScroll);
        addRenderableWidget(scrollBar);

        scroll.clampTo(totalRows(), rowsPerPage());
        updateRowPositions();

        // 左：取消（丢掉这次改动）；右：完成（写回配置并落盘）
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.cancel())
                .bounds(footerLeftButtonX(this.width), footerButtonY(this.height),
                        FOOTER_BUTTON_WIDTH, WIDGET_HEIGHT)
                .build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.finish())
                .bounds(footerRightButtonX(this.width), footerButtonY(this.height),
                        FOOTER_BUTTON_WIDTH, WIDGET_HEIGHT)
                .build());
    }

    /**
     * 每个值最长多少字符
     */
    private static final int MAX_VALUE_LENGTH = 64;

    /**
     * 把所有行控件按当前滚动偏移摆好
     */
    private void updateRowPositions() {
        for (int i = 0; i < rowWidgets.size(); i++) {
            int y = Math.round(rowDrawY(i));
            boolean visible = rowVisible(i);
            for (AbstractWidget widget : rowWidgets.get(i)) {
                widget.setY(y);
                widget.visible = visible;
            }
        }
        updateScrollBar();
    }

    /**
     * 滚动条的显隐 / 位置（不足一页就整条隐藏，那一列还给内容）
     */
    private void updateScrollBar() {
        if (scrollBar == null) {
            return;
        }
        boolean needed = scrollingNeeded();
        scrollBar.visible = needed;
        scrollBar.active = needed;
        scrollBar.setX(scrollBarX());
        scrollBar.setY(rowsTop());
        scrollBar.setWidth(SCROLLBAR_WIDTH);
        scrollBar.setHeight(Math.max(1, rowsViewportBottom() - rowsTop()));
    }

    // ==============================================================
    // 补全弹窗 / 校验提示
    // ==============================================================

    /**
     * 输入框那一行现在该显示几行弹窗内容：0 = 不显示
     */
    private int popupLines() {
        if (!adding) {
            return 0;
        }
        return invalid ? 1 : suggestions.size();
    }

    /**
     * 弹窗上边界：优先画在输入框下面（那边通常是空的），下面放不下就翻到输入框上面。
     */
    private int popupTop() {
        int lines = popupLines();
        if (lines == 0) {
            return 0;
        }
        int boxY = Math.round(rowDrawY(values.size()));
        int height = lines * ComponentLayout.POPUP_LINE_HEIGHT;
        int below = boxY + WIDGET_HEIGHT + WIDGET_GAP;
        if (below + height <= rowsViewportBottom()) {
            return below;
        }
        return Math.max(rowsCanvasTop(), boxY - WIDGET_GAP - height);
    }

    /**
     * 点到第几条候选（-1 = 没点到）
     */
    private int suggestionAt(double mouseX, double mouseY) {
        int lines = popupLines();
        if (invalid || lines == 0) {
            return -1;
        }
        if (mouseX < contentX() || mouseX > rowsRight()) {
            return -1;
        }
        int top = popupTop();
        for (int i = 0; i < lines; i++) {
            int lineY = top + i * ComponentLayout.POPUP_LINE_HEIGHT;
            if (mouseY >= lineY && mouseY < lineY + ComponentLayout.POPUP_LINE_HEIGHT) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 画补全弹窗 / 校验失败提示。
     *
     * <p>候选一行一个：左边是完整 id，右边灰字标注它来自哪个注册表（例如 {@code block / item}）；
     * 高亮那行有底色。校验失败时那一行换成红色的提示文字。
     */
    private void drawPopup(GuiGraphicsExtractor graphics) {
        int lines = popupLines();
        if (lines == 0) {
            return;
        }
        int x = contentX();
        int width = rowsRight() - x;
        int top = popupTop();
        int height = lines * ComponentLayout.POPUP_LINE_HEIGHT;

        graphics.fill(x, top, x + width, top + height, ComponentLayout.POPUP_BACKGROUND);
        for (int i = 0; i < lines; i++) {
            int lineY = top + i * ComponentLayout.POPUP_LINE_HEIGHT;
            if (invalid) {
                graphics.text(this.font, Component.translatable("villagerdetails.ui.list.invalid"),
                        x + 2, lineY + 2, ComponentLayout.POPUP_ERROR);
                continue;
            }
            if (i == suggestionIndex) {
                graphics.fill(x + 1, lineY, x + width - 1, lineY + ComponentLayout.POPUP_LINE_HEIGHT,
                        ComponentLayout.POPUP_SELECTED);
            }
            McIds.Entry option = suggestions.get(i);
            // 标注来源；「一整个注册表」这种分类 id 额外标一下，免得和同名的条目混淆
            String source = option.category()
                    ? Component.translatable("villagerdetails.ui.list.category").getString() + " · " + option.source()
                    : option.source();
            int sourceWidth = this.font.width(source);
            String shown = this.font.plainSubstrByWidth(option.id(), Math.max(0, width - sourceWidth - 8));
            graphics.text(this.font, shown, x + 2, lineY + 2, ComponentLayout.POPUP_TEXT);
            graphics.text(this.font, source, x + width - sourceWidth - 2, lineY + 2,
                    ComponentLayout.POPUP_SOURCE);
        }
        graphics.outline(x, top, width, height, ComponentLayout.POPUP_BORDER);
    }

    /**
     * 拖滚动条时直接定位，不做动画
     */
    private void setScroll(double rows) {
        scroll.set(rows, totalRows(), rowsPerPage());
        updateRowPositions();
    }

    // ==============================================================
    // 增删
    // ==============================================================

    private void startAdd() {
        this.adding = true;
        this.invalid = false;
        this.suggestions = List.of();
        this.suggestionIndex = 0;
        rebuildWidgets();
    }

    /**
     * 把输入框里填的值收进<b>工作副本</b>（点空白处 / 回车 / 按「完成」都走这里）。
     *
     * <p><b>会校验</b>：不是 MC 里真实存在的 id（注册表条目或标签）就不收，
     * 改成输入框标红 + 弹窗里给一行提示，等玩家改对。
     *
     * @return 工作副本有没有变化
     */
    private boolean flushAddBox() {
        if (!adding) {
            return false;
        }
        String text = addBox == null ? "" : addBox.getValue().trim();
        if (text.isEmpty()) {
            // 什么都没填 = 取消，不算错
            this.adding = false;
            return false;
        }
        if (!McIds.isKnown(text)) {
            // 不合法：输入框标红、弹窗改显示错误提示，输入框留着继续改
            this.invalid = true;
            if (addBox != null) {
                addBox.setTextColor(ComponentLayout.POPUP_ERROR);
            }
            return false;
        }
        this.adding = false;
        this.invalid = false;
        // 只加到工作副本里；真正写回配置要等按「完成」
        values.add(text);
        return true;
    }

    /**
     * 点空白处/回车：先把输入框里的值收进工作副本，再刷新界面
     */
    private void commitAdd() {
        flushAddBox();
        if (invalid) {
            // 校验没过：界面不重建，输入框（和里面填的内容）留着让玩家改
            return;
        }
        rebuildWidgets();
    }

    /**
     * ESC：放弃这次输入
     */
    private void cancelAdd() {
        if (!adding) {
            return;
        }
        this.adding = false;
        this.invalid = false;
        rebuildWidgets();
    }

    /**
     * 把高亮的那条候选填进输入框（不直接保存，玩家再确认一次）
     */
    private void acceptSuggestion(int index) {
        if (index < 0 || index >= suggestions.size() || addBox == null) {
            return;
        }
        addBox.setValue(suggestions.get(index).id());
        addBox.setCursorPosition(addBox.getValue().length());
    }

    /**
     * 上下键换一条候选
     */
    private void moveSuggestion(int delta) {
        if (suggestions.isEmpty()) {
            return;
        }
        this.suggestionIndex = Math.floorMod(suggestionIndex + delta, suggestions.size());
    }

    /**
     * 补全候选最多显示几条（还要受这一页的高度限制）
     */
    private int maxSuggestions() {
        int room = Math.max(1, (rowsViewportBottom() - rowsCanvasTop() - WIDGET_HEIGHT) / ComponentLayout.POPUP_LINE_HEIGHT);
        return Math.min(LIST_MAX_SUGGESTIONS, room);
    }

    private void deleteValue(int index) {
        if (index < 0 || index >= values.size()) {
            return;
        }
        // 只改工作副本；真正写回配置要等按「完成」
        values.remove(index);
        rebuildWidgets();
    }

    // ==============================================================
    // 输入 / 生命周期
    // ==============================================================

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

    @Override
    public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubleClick) {
        if (adding) {
            // 点补全候选 = 把它填进输入框（还需再确认一次才会存）
            int hit = suggestionAt(event.x(), event.y());
            if (hit >= 0) {
                acceptSuggestion(hit);
                return true;
            }
            // 点空白处 = 保存（和绑定快捷键一个手感）
            if (addBox != null && !addBox.isMouseOver(event.x(), event.y())) {
                commitAdd();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        if (adding) {
            int key = event.key();
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                // 现在填的不是合法 id，但有候选 → 先用高亮那条补全，再一起存下去
                if (addBox != null && McIds.isKnown(addBox.getValue().trim()) && !suggestions.isEmpty()) {
                    acceptSuggestion(suggestionIndex);
                }
                commitAdd();
                return true;
            }
            if (key == InputConstants.KEY_TAB) {
                acceptSuggestion(suggestionIndex);
                return true;
            }
            if (key == InputConstants.KEY_DOWN) {
                moveSuggestion(1);
                return true;
            }
            if (key == InputConstants.KEY_UP) {
                moveSuggestion(-1);
                return true;
            }
            if (key == InputConstants.KEY_ESCAPE) {
                cancelAdd();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /**
     * 「完成」：把工作副本写回配置并落盘，然后回上一页。
     */
    private void finish() {
        // 输入框里还填着东西就一起收进来（校验不过就当作没填，不拦着完成）
        flushAddBox();
        entry.setList(values);
        ClientRenderConfig.get().save();
        this.minecraft.gui.setScreen(this.parent);
    }

    /**
     * 「取消」：这次改动全部丢掉（配置里一个字都没改过），直接回上一页。
     */
    private void cancel() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void onClose() {
        // ESC / 其它关闭路径 = 取消：只有按「完成」才会保存
        cancel();
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
        if (scroll.advance(frameTicks)) {
            updateRowPositions();
        }

        drawPanel(graphics);
        ScreenDecorations.drawTitle(graphics, this, this.title);
        graphics.textWithWordWrap(this.font, entry.getDisplayInfo(),
                contentX(), DETAIL_DESC_TOP, contentWidth(), ScreenDecorations.COLOR_HINT);

        drawRows(graphics, mouseX, mouseY, frameTicks);

        // 滚动条 / 页脚按钮照旧交给原版渲染
        super.extractRenderState(graphics, mouseX, mouseY, frameTicks);
    }

    /**
     * 列表区的半透明面板：按实际画出来的行数收边（和主界面一致）
     */
    private void drawPanel(GuiGraphicsExtractor graphics) {
        int shown = Math.max(1, Math.min(totalRows(), rowsPerPage()));
        int bottom = rowY(shown - 1) + WIDGET_HEIGHT + PANEL_PADDING;
        graphics.fill(contentX() - PANEL_PADDING, rowsTop() - PANEL_PADDING,
                contentRight() + PANEL_PADDING, bottom, ComponentLayout.PANEL_COLOR);
    }

    /**
     * 把行画在「一页」这块画布里：滚动时上下两端那半个项照常渲染，超出画布的部分被裁掉。
     */
    private void drawRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float frameTicks) {
        graphics.enableScissor(contentX() - PANEL_PADDING, rowsCanvasTop(),
                contentRight() + PANEL_PADDING, rowsViewportBottom());
        try {
            for (List<AbstractWidget> row : rowWidgets) {
                for (AbstractWidget widget : row) {
                    widget.extractRenderState(graphics, mouseX, mouseY, frameTicks);
                }
            }
            // 弹窗画在行控件上面，并且和它们一起被画布裁剪（不会超出面板）
            drawPopup(graphics);
        } finally {
            graphics.disableScissor();
        }
    }
}
