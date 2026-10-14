package com.villagerdetails.client.setting.screen;

import com.villagerdetails.client.network.sync.ClientRequests;
import com.villagerdetails.client.setting.component.OptionLabel;
import com.villagerdetails.client.setting.component.RowListPage;
import com.villagerdetails.client.setting.component.SuggestionPopup;
import com.villagerdetails.client.setting.component.SwitchButton;
import com.villagerdetails.client.setting.config.ui.ComponentLayout;
import com.villagerdetails.client.setting.network.RuleAuxCache;
import com.villagerdetails.client.setting.row.ServerRuleDataRow;
import com.villagerdetails.rule.RuleSwitches;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

import java.util.*;

import static com.villagerdetails.client.setting.config.ui.UiLayout.*;

/**
 * 「服务端配置项」里某条规则的数据详情页：一个「id → 值」的列表。
 *
 * <pre>
 *  ┌──────────────────────────────────────────────────────┐
 *  │ minecraft:stone            [ 1.5 ]            [ × ]  │ ← 值可能是数字框，也可能是自由文本
 *  │ minecraft:deepslate        [ 1.2 ]            [ × ]  │
 *  │ [                       +                       ]    │ ← 最后一行是添加按钮
 *  └──────────────────────────────────────────────────────┘
 *  │              [ 取消 ]          [ 完成 ]              │
 * </pre>
 *
 * <p>分页滚动 / 滚动条 / 画布裁剪 / 面板 / 页脚 / 补全弹窗都在基类
 * {@link AbstractDetailScreen} 和那两个组件里。
 *
 * <p><b>列表长什么样完全由服务端下发的模板决定</b>（{@link RuleDescriptor.ListTemplate}）：
 * 值控件是数字框还是文本框、id 该从哪来，客户端只是照着渲染 —— 它不认识「方块抗性」这种语义。
 *
 * <p><b>改动是临时的</b>：进来时把服务端同步过来的配置拷一份工作副本，增删改都只动副本；
 * 按「<b>完成</b>」时才把<b>和工作副本的差异</b>发给服务端（新增/修改 → 一条设置请求，
 * 删掉的 → 一条删除请求），按「取消」或 ESC 则什么都不发。
 *
 * <p>点「+」会出现 id 和值两个输入框：id 的候选与「原版值预填」都来自服务端按需下发的清单
 * （{@link RuleAuxCache}），客户端只做子串过滤。
 */
public class RuleDataDetailScreen extends AbstractDetailScreen {

    /**
     * 值输入框最长多少字符
     */
    private static final int MAX_VALUE_LENGTH = 16;

    /**
     * 新增那一行 id 输入框的长度上限
     */
    private static final int MAX_ADD_ID_LENGTH = 128;

    /**
     * 正在编辑的规则（描述符：名称 / 描述 / 模板 / 当前数据都是服务端给的）
     */
    private final RuleDescriptor rule;

    /**
     * 逐条配置的模板
     */
    private final RuleDescriptor.ListTemplate template;

    /**
     * 工作副本：id → 值（保持插入顺序，界面就按这个顺序画）
     */
    private final LinkedHashMap<String, String> values = new LinkedHashMap<>();

    /**
     * 进来时的原样，按「完成」时用来算差异
     */
    private final LinkedHashMap<String, String> original = new LinkedHashMap<>();

    /**
     * id 输入框的补全弹窗
     */
    private final SuggestionPopup popup = new SuggestionPopup();

    /**
     * 正在新增（最后一行变成 id + 值两个输入框）
     */
    private boolean adding;
    private EditBox addIdBox;
    private EditBox addValueBox;

    public RuleDataDetailScreen(Screen parent, ServerRuleDataRow row) {
        super(Component.literal("[ ").append(row.getDisplayName()).append(" ]"), parent);
        this.rule = row.rule();
        this.template = row.template();
        // 数据是服务端同步过来的，列表形态（数字 / 文本）也是服务端带过来的模板
        this.values.putAll(row.data());
        this.original.putAll(this.values);
        // id 候选和原版值预填都要问服务端要：这一页打开时请求一次
        RuleAuxCache.requestOnce(rule.registerName());
    }

    // ==============================================================
    // 基类要的那几样
    // ==============================================================

    @Override
    protected int contentMaxWidth() {
        return RULE_DATA_CONTENT_MAX_WIDTH;
    }

    @Override
    protected int rowsTop() {
        return RULE_DATA_LIST_TOP;
    }

    @Override
    protected int totalRows() {
        // 每个条目一行，最后一行是「添加」
        return values.size() + 1;
    }

    @Override
    protected Component description() {
        // 描述是服务端按我们的界面语言翻译好的，直接用
        return Component.literal(rule.info());
    }

    @Override
    protected void onFinish() {
        // 输入框里还填着东西就一起收进来（校验不过就当作没填）
        flushAddRow();
        sendChanges();
    }

    // ==============================================================
    // 模板
    // ==============================================================

    /**
     * 值合法吗（数字：非负浮点；其余：非空文本）
     */
    private boolean isValidValue(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return false;
        }
        if (template.valueKind() != RuleDescriptor.ValueKind.NUMBER) {
            return true;
        }
        try {
            float parsed = Float.parseFloat(text);
            return Float.isFinite(parsed) && parsed >= 0.0F;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }

    // ==============================================================
    // 行的布局
    // ==============================================================

    /**
     * 值控件（数字框 / 文本框）的 x
     */
    private int valueX() {
        return rowsRight() - DETAIL_DELETE_WIDTH - WIDGET_GAP - RULE_DATA_VALUE_WIDTH;
    }

    /**
     * 「×」删除按钮的 x
     */
    private int deleteX() {
        return rowsRight() - DETAIL_DELETE_WIDTH;
    }

    /**
     * id 文字能占的宽度
     */
    private int idLabelWidth() {
        return Math.max(0, valueX() - WIDGET_GAP - contentX());
    }

    // ==============================================================
    // 控件
    // ==============================================================

    @Override
    protected void buildRows() {
        List<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            page.addRow(buildRow(entries.get(i), page.rowY(i)));
        }
        page.addRow(buildAddRow(page.rowY(values.size())));
    }

    /**
     * 一个已配置的条目：id 标签 + 值控件 + 删除
     */
    private List<RowListPage.Widget> buildRow(Map.Entry<String, String> entry, int y) {
        String id = entry.getKey();
        List<RowListPage.Widget> widgets = new ArrayList<>();

        // id：太长就截断（免得压到右边的值控件上）
        String shown = this.font.plainSubstrByWidth(id, idLabelWidth() - ComponentLayout.LABEL_PADDING * 2);
        widgets.add(RowListPage.at(addWidget(new OptionLabel(contentX() + ComponentLayout.LABEL_PADDING, y,
                idLabelWidth(), WIDGET_HEIGHT, Component.literal(shown)))));

        widgets.add(buildValueEditor(id, entry.getValue(), y));

        widgets.add(RowListPage.at(addWidget(new SwitchButton(deleteX(), y, DETAIL_DELETE_WIDTH, WIDGET_HEIGHT,
                Component.literal("×"), RuleSwitches.COLOR_SELECTED_OFF,
                () -> deleteValue(id))
                .withTooltip(Component.translatable("villagerdetails.ui.list.delete")))));
        return widgets;
    }

    /**
     * 值控件：边打边写进工作副本，打错就标红但不动副本。
     *
     * <p>数字 / 文本两种形态用的是同一个输入框，只是校验不同 —— 形态由服务端模板决定。
     */
    private RowListPage.Widget buildValueEditor(String id, String value, int y) {
        EditBox box = new EditBox(this.font, valueX(), y, RULE_DATA_VALUE_WIDTH, WIDGET_HEIGHT,
                Component.translatable("villagerdetails.ui.rule_data.value"));
        box.setMaxLength(MAX_VALUE_LENGTH);
        box.setValue(value);
        box.setResponder(text -> {
            if (isValidValue(text)) {
                values.put(id, normalizeValue(text));
                box.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
            } else {
                // 还没打完 / 不合法：只标红，副本里保留原值
                box.setTextColor(ComponentLayout.POPUP_ERROR);
            }
        });
        return RowListPage.at(addWidget(box));
    }

    /**
     * 最后一行：「+」添加按钮 / 点开后变成 id + 值两个输入框
     */
    private List<RowListPage.Widget> buildAddRow(int y) {
        List<RowListPage.Widget> widgets = new ArrayList<>();

        if (!adding) {
            this.addIdBox = null;
            this.addValueBox = null;
            popup.reset();
            widgets.add(RowListPage.at(addWidget(Button.builder(Component.literal("+"), button -> startAdd())
                    .bounds(contentX(), y, rowsRight() - contentX(), WIDGET_HEIGHT)
                    .build())));
            return widgets;
        }

        EditBox idBox = new EditBox(this.font, contentX(), y, idLabelWidth() - WIDGET_GAP, WIDGET_HEIGHT,
                Component.translatable("villagerdetails.ui.list.add"));
        idBox.setHint(Component.translatable("villagerdetails.ui.rule_data.add.hint"));
        idBox.setMaxLength(MAX_ADD_ID_LENGTH);
        idBox.setResponder(text -> {
            popup.update(text, maxSuggestions(),
                    (query, limit) -> RuleAuxCache.suggestions(rule.registerName(), query, limit));
            idBox.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
            prefillValue(text.trim());
        });
        this.addIdBox = idBox;
        widgets.add(RowListPage.at(addWidget(idBox)));
        requestFocus(idBox);

        EditBox valueBox = new EditBox(this.font, valueX(), y, RULE_DATA_VALUE_WIDTH, WIDGET_HEIGHT,
                Component.translatable("villagerdetails.ui.rule_data.value"));
        valueBox.setMaxLength(MAX_VALUE_LENGTH);
        valueBox.setHint(Component.translatable("villagerdetails.ui.rule_data.value.hint"));
        this.addValueBox = valueBox;
        widgets.add(RowListPage.at(addWidget(valueBox)));
        return widgets;
    }

    /**
     * id 填成合法值之后，把原版值预填进值输入框（玩家自己改过就不动）。
     *
     * <p>原版值是服务端算好一起下发的（客户端不认识方块，算不出来）。
     */
    private void prefillValue(String id) {
        if (addValueBox == null || !addValueBox.getValue().isEmpty()) {
            return;
        }
        if (!RuleAuxCache.knowsId(rule.registerName(), id)) {
            return;
        }
        String defaultValue = RuleAuxCache.defaultValue(rule.registerName(), id);
        if (defaultValue != null) {
            addValueBox.setValue(defaultValue);
        }
    }

    /**
     * 补全候选最多显示几条（还要受这一页的高度限制）
     */
    private int maxSuggestions() {
        // 至少 1 条；上限先兜一次底，免得 clamp 的 min > max 抛异常
        int room = (page.viewportBottom() - page.canvasTop() - WIDGET_HEIGHT)
                / ComponentLayout.POPUP_LINE_HEIGHT;
        return Math.clamp(room, 1, Math.max(1, LIST_MAX_SUGGESTIONS));
    }

    /**
     * 弹窗的位置：锚在最后那一行（新增那一行），随滚动偏移走
     */
    private SuggestionPopup.Bounds popupBounds() {
        return new SuggestionPopup.Bounds(contentX(), rowsRight(),
                Math.round(page.rowDrawY(values.size())), page.canvasTop(), page.viewportBottom());
    }

    // ==============================================================
    // 增删改（都只动工作副本）
    // ==============================================================

    private void startAdd() {
        this.adding = true;
        popup.reset();
        rebuildWidgets();
    }

    private void deleteValue(String id) {
        values.remove(id);
        rebuildWidgets();
    }

    /**
     * 把输入框里填的 id / 值收进工作副本。
     *
     * @return 工作副本有没有变化
     */
    private boolean flushAddRow() {
        if (!adding) {
            return false;
        }
        String id = RuleAuxCache.canonicalId(addIdBox == null ? "" : addIdBox.getValue());
        String value = addValueBox == null ? "" : addValueBox.getValue().trim();

        if (id.isEmpty()) {
            // 什么都没填 = 取消，不算错
            this.adding = false;
            return false;
        }
        if (!RuleAuxCache.knowsId(rule.registerName(), id)) {
            markInvalid(addIdBox, "villagerdetails.ui.rule_data.invalid_id");
            return false;
        }
        if (!isValidValue(value)) {
            markInvalid(addValueBox, "villagerdetails.ui.rule_data.invalid_value");
            return false;
        }

        this.adding = false;
        popup.reset();
        values.put(id, normalizeValue(value));
        return true;
    }

    /**
     * 校验没过：输入框标红、弹窗改成显示提示，输入框留着让玩家改
     */
    private void markInvalid(EditBox box, String messageKey) {
        popup.setError(Component.translatable(messageKey));
        if (box != null) {
            box.setTextColor(ComponentLayout.POPUP_ERROR);
        }
    }

    /**
     * 点空白处 / 回车：先把新增行收进副本，再刷新界面
     */
    private void commitAdd() {
        flushAddRow();
        if (popup.hasError()) {
            // 校验没过：界面不重建，输入框（和里面填的内容）留着让玩家改
            return;
        }
        rebuildWidgets();
    }

    /**
     * ESC：放弃这次新增
     */
    private void cancelAdd() {
        if (!adding) {
            return;
        }
        this.adding = false;
        popup.reset();
        rebuildWidgets();
    }

    // ==============================================================
    // 完成
    // ==============================================================

    /**
     * 只把和进来时不一样的条目发给服务端
     */
    private void sendChanges() {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String id = entry.getKey();
            if (!entry.getValue().equals(original.get(id))) {
                ClientRequests.sendRuleData(rule.registerName(), id, entry.getValue());
            }
        }
        for (String id : original.keySet()) {
            if (!values.containsKey(id)) {
                ClientRequests.sendRuleDataRemove(rule.registerName(), id);
            }
        }
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
            int hit = popup.hitTest(popupBounds(), event.x(), event.y());
            if (hit >= 0) {
                popup.acceptInto(addIdBox);
                return true;
            }
            // 点空白处 = 收下这一条（和绑定快捷键一个手感）
            boolean insideBoxes = (addIdBox != null && addIdBox.isMouseOver(event.x(), event.y()))
                    || (addValueBox != null && addValueBox.isMouseOver(event.x(), event.y()));
            if (!insideBoxes) {
                commitAdd();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        if (adding) {
            boolean idValid = addIdBox != null && RuleAuxCache.knowsId(rule.registerName(), addIdBox.getValue().trim());
            if (handleEditingKey(event, popup, addIdBox, idValid, this::commitAdd, this::cancelAdd)) {
                return true;
            }
        }
        return super.keyPressed(event);
    }
}
