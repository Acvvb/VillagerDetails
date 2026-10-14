package com.villagerdetails.client.setting.screen;

import com.villagerdetails.client.setting.component.OptionLabel;
import com.villagerdetails.client.setting.component.RowListPage;
import com.villagerdetails.client.setting.component.SuggestionPopup;
import com.villagerdetails.client.setting.component.SwitchButton;
import com.villagerdetails.client.setting.config.ui.ComponentLayout;
import com.villagerdetails.client.setting.type.server.ListSourceServer;
import com.villagerdetails.rule.RuleSwitches;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

import static com.villagerdetails.client.setting.config.ui.UiLayout.*;

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
 * <p>分页滚动 / 滚动条 / 画布裁剪 / 面板 / 页脚 / 补全弹窗都在基类
 * {@link AbstractDetailScreen} 和那两个组件里，这里只剩这张表自己的东西：
 * 每一行长什么样、点「+」之后的输入框、以及「完成」时写回哪儿（{@link ListSourceServer}）。
 *
 * <p>点「+」会在那一行出现一个输入框，<b>点空白处</b>（或按回车）就把它收进那份副本 ——
 * 和绑定快捷键一个手感；按 ESC 或再点一下「+」则放弃这次输入。
 */
public class ListDetailScreen extends AbstractDetailScreen {

    /**
     * 每个值最长多少字符
     */
    private static final int MAX_VALUE_LENGTH = 64;

    /**
     * 这一页在编辑谁：本机配置项的列表，还是服务器规则的名单
     */
    private final ListSourceServer source;

    /**
     * 本次编辑的<b>工作副本</b>（界面显示的就是它，最后一行永远是「添加」那一行）。
     *
     * <p>进来时从配置拷一份；增删都只改这份副本，<b>只有按「完成」才会写回配置并落盘</b>，
     * 按「取消」（或 ESC）直接丢掉，玩家中途看到/改的都不会留下。
     */
    private final List<String> values = new ArrayList<>();

    /**
     * id 输入框的补全弹窗
     */
    private final SuggestionPopup popup = new SuggestionPopup();

    /**
     * 正在输入要添加的值
     */
    private boolean adding;
    private EditBox addBox;

    public ListDetailScreen(Screen parent, ListSourceServer source) {
        super(Component.literal("[ ").append(source.title()).append(" ]"), parent);
        this.source = source;
        // 进来时拷一份工作副本；之后增删都只改它，按「完成」才写回
        this.values.addAll(source.current());
        // 候选清单要问服务端要的来源在这里发一次请求（本机配置项不用）
        source.prepare();
    }

    // ==============================================================
    // 基类要的那几样
    // ==============================================================

    @Override
    protected int contentMaxWidth() {
        return DETAIL_CONTENT_MAX_WIDTH;
    }

    @Override
    protected int rowsTop() {
        return DETAIL_LIST_TOP;
    }

    @Override
    protected int totalRows() {
        // 每个值一行，最后一行是「添加」
        return values.size() + 1;
    }

    @Override
    protected Component description() {
        return source.description();
    }

    @Override
    protected void onFinish() {
        // 输入框里还填着东西就一起收进来（校验不过就当作没填，不拦着完成）
        flushAddBox();
        source.apply(values);
    }

    // ==============================================================
    // 行的布局
    // ==============================================================

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
    protected void buildRows() {
        for (int i = 0; i < values.size(); i++) {
            int y = page.rowY(i);
            int index = i;
            List<RowListPage.Widget> widgets = new ArrayList<>();

            // 值本身：太长就截断（免得压到右边的删除按钮上）
            String text = this.font.plainSubstrByWidth(values.get(i), labelWidth() - ComponentLayout.LABEL_PADDING * 2);
            widgets.add(RowListPage.at(addWidget(new OptionLabel(contentX() + ComponentLayout.LABEL_PADDING, y,
                    labelWidth(), WIDGET_HEIGHT, Component.literal(text)))));

            // 右边红色的「×」：删掉这个值
            widgets.add(RowListPage.at(addWidget(new SwitchButton(deleteX(), y, DETAIL_DELETE_WIDTH, WIDGET_HEIGHT,
                    Component.literal("×"), RuleSwitches.COLOR_SELECTED_OFF,
                    () -> deleteValue(index))
                    .withTooltip(Component.translatable("villagerdetails.ui.list.delete")))));

            page.addRow(widgets);
        }

        // 最后一行：「+」添加按钮 / 点开后变成输入框
        page.addRow(buildAddRow(page.rowY(values.size())));
    }

    /**
     * 最后一行：没在输入时是「+」按钮，输入时是一个 id 输入框
     */
    private List<RowListPage.Widget> buildAddRow(int y) {
        List<RowListPage.Widget> widgets = new ArrayList<>();

        if (!adding) {
            this.addBox = null;
            popup.reset();
            widgets.add(RowListPage.at(addWidget(Button.builder(Component.literal("+"), button -> startAdd())
                    .bounds(contentX(), y, rowsRight() - contentX(), WIDGET_HEIGHT)
                    .build())));
            return widgets;
        }

        EditBox box = new EditBox(this.font, contentX(), y, rowsRight() - contentX(), WIDGET_HEIGHT,
                Component.translatable("villagerdetails.ui.list.add"));
        box.setHint(Component.translatable("villagerdetails.ui.list.add.hint"));
        box.setMaxLength(MAX_VALUE_LENGTH);
        // 输入变化时才重算补全（本机来源要遍历整张 id 表，服务器来源要扫一遍服务端给的清单）
        box.setResponder(text -> {
            popup.update(text, maxSuggestions(), source::suggestions);
            box.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
        });
        this.addBox = box;
        widgets.add(RowListPage.at(addWidget(box)));
        requestFocus(box);
        return widgets;
    }

    /**
     * 补全候选最多显示几条（还要受这一页的高度限制）
     */
    private int maxSuggestions() {
        int room = Math.max(1, (page.viewportBottom() - page.canvasTop() - WIDGET_HEIGHT)
                / ComponentLayout.POPUP_LINE_HEIGHT);
        return Math.min(LIST_MAX_SUGGESTIONS, room);
    }

    /**
     * 弹窗的位置：锚在最后那一行（输入框那一行），随滚动偏移走
     */
    private SuggestionPopup.Bounds popupBounds() {
        return new SuggestionPopup.Bounds(contentX(), rowsRight(),
                Math.round(page.rowDrawY(values.size())), page.canvasTop(), page.viewportBottom());
    }

    // ==============================================================
    // 增删
    // ==============================================================

    private void startAdd() {
        this.adding = true;
        popup.reset();
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
        if (!source.isValidId(text)) {
            // 不合法：输入框标红、弹窗改显示错误提示，输入框留着继续改
            popup.setError(Component.translatable("villagerdetails.ui.list.invalid"));
            if (addBox != null) {
                addBox.setTextColor(ComponentLayout.POPUP_ERROR);
            }
            return false;
        }
        this.adding = false;
        popup.reset();
        // 只加到工作副本里；真正写回配置要等按「完成」。
        // 收进来之前先规范化：服务器规则的 id 要写成注册表里的规范形式，
        // 不然「stone」和已经存在的「minecraft:stone」会被当成两条。
        values.add(source.normalizeId(text));
        return true;
    }

    /**
     * 点空白处/回车：先把输入框里的值收进工作副本，再刷新界面
     */
    private void commitAdd() {
        flushAddBox();
        if (popup.hasError()) {
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
        popup.reset();
        rebuildWidgets();
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
    // 绘制 / 输入
    // ==============================================================

    @Override
    protected void drawOverlay(GuiGraphicsExtractor graphics) {
        if (adding) {
            popup.draw(graphics, this.font, popupBounds());
        }
    }

    @Override
    public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubleClick) {
        if (adding) {
            // 点补全候选 = 把它填进输入框（还需再确认一次才会存）
            int hit = popup.hitTest(popupBounds(), event.x(), event.y());
            if (hit >= 0) {
                popup.acceptInto(addBox);
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
            boolean idValid = addBox != null && source.isValidId(addBox.getValue().trim());
            if (handleEditingKey(event, popup, addBox, idValid, this::commitAdd, this::cancelAdd)) {
                return true;
            }
        }
        return super.keyPressed(event);
    }
}
