package com.villagerdetails.client.setting.screen;

import com.villagerdetails.client.cache.impl.RuleAuxCache;
import com.villagerdetails.client.network.requests.ClientRequests;
import com.villagerdetails.client.setting.component.*;
import com.villagerdetails.client.setting.config.ui.ComponentLayout;
import com.villagerdetails.client.setting.row.ServerRuleDataRow;
import com.villagerdetails.rule.RuleSwitches;
import com.villagerdetails.rule.descriptor.RuleDescriptor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.villagerdetails.client.setting.config.ui.UiLayout.*;

/**
 * 「服务端配置项」里某条规则的数据详情页：一个「id → 值」的列表。
 *
 * <p>行长什么样由服务端下发的模板决定（{@link RuleDescriptor.ListTemplate}），分两种编辑模式：
 *
 * <pre>
 * ① 值模式（valueKind = NUMBER，例如「方块挖掘抗性」）
 *  ┌──────────────────────────────────────────────────────┐
 *  │ minecraft:stone            [ 1.5 ]            [ × ]  │
 *  │ [                       +                       ]    │
 *  └──────────────────────────────────────────────────────┘
 *
 * ② 开关模式（valueKind = NONE，纯名单，例如「可采集」「无挤压」「禁止生成」）
 *  ┌──────────────────────────────────────────────────────┐
 *  │ minecraft:stone            [  是  ]           [ × ]  │
 *  │ [ 输入 id… ]               [  否  ]                  │
 *  └──────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>开关模式的值从哪来</b>：就是这条规则的数据里有没有这个 id —— 有 = 是，没有 = 否。
 * 全部来自本地的工作副本（跟着描述符同步那套原有的增量），不额外问服务端。
 * 新增那一行的开关默认是「否」，填好 id 之后会按那个 id 现在的状态摆好，玩家也可以自己拨。
 *
 * <p><b>改动是临时的</b>：进来时把服务端同步过来的配置拷一份工作副本，增删改都只动副本；
 * 按「<b>完成</b>」时才把<b>和工作副本的差异</b>发给服务端（新增/修改 → 一条设置请求，
 * 删掉的 → 一条删除请求），按「取消」或 ESC 则什么都不发。
 *
 * <p>点「+」会出现 id（和值）输入框：id 的候选与「原版值预填」都来自服务端按需下发的清单
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
     * 开关模式下代表「是」/「否」的两个值（和 {@code RuleDataKind#isValidValue} 认的写法一致）
     */
    private static final String TRUE_VALUE = "true";
    private static final String FALSE_VALUE = "false";

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
     * 正在新增（最后一行变成输入框）
     */
    private boolean adding;

    /**
     * 新增行的 id 输入框（{@link #adding} 为 true 时有值）
     */
    private EditBox addIdBox;

    /**
     * 新增行的值输入框（仅值模式有；开关模式为 null）
     */
    private EditBox addValueBox;

    /**
     * 开关模式新增行的「是 / 否」（值模式这个是 null）
     */
    private SwitchButton addToggle;

    /**
     * 开关模式新增行当前的开关值；刚开始新增时默认「否」
     */
    private boolean addToggleOn;

    /**
     * @param parent 关闭后返回的那一页
     * @param row    这一页在编辑哪条规则的哪条数据
     */
    public RuleDataDetailScreen(Screen parent, ServerRuleDataRow row) {
        super(Component.literal("[ ").append(row.getDisplayName()).append(" ]"), parent);
        this.rule = row.rule();
        this.template = row.template();
        // 数据是服务端同步过来的，列表形态（数字 / 文本）也是服务端带过来的模板
        this.values.putAll(row.data());
        this.original.putAll(this.values);
        // id 候选和原版值预填都要问服务端要：这一页打开时请求一次
        RuleAuxCache.INSTANCE.requestOnce(rule.registerName());
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

    /**
     * 「完成」：先把新增行收进副本（不合法就拦住），再把差异发给服务端。
     *
     * @return true 表示成功（已发请求、关屏）；false 表示校验没过（界面保持打开）
     */
    @Override
    protected boolean onFinish() {
        // 「完成」要先校验当前正在填的这一行；不合法就拦住，让玩家改
        if (adding) {
            String id = RuleAuxCache.INSTANCE.canonicalId(addIdBox == null ? "" : addIdBox.getValue());
            if (id.isEmpty()) {
                // id 都没填 = 作废这次添加，正常保存退出
                this.adding = false;
                popup.reset();
            } else if (!flushAddRow()) {
                // flushAddRow 内部已经把出错的输入框标红 + 弹窗提示，这里只要拦住关闭
                return false;
            }
        }
        sendChanges();
        return true;
    }

    // ==============================================================
    // 两种编辑模式
    // ==============================================================

    /**
     * 这一页是不是「开关模式」：纯名单（列表里只有 id，没有值控件）。
     *
     * <p>由服务端模板决定，客户端不写死哪条规则属于哪种。
     */
    private boolean isPlain() {
        return template != null && template.valueKind() == RuleDescriptor.ValueKind.NONE;
    }

    /**
     * {@code "true"} / 其它 → 开关状态。
     *
     * <p>纯名单里存下来的值只有 {@code "true"}；工作副本里为了表达「拨到否」会存
     * {@code "false"}，真正落库时服务端会把它当成「从名单移除」。
     */
    private static boolean isOn(String value) {
        return !FALSE_VALUE.equalsIgnoreCase(value == null ? "" : value.trim());
    }

    /**
     * 把一个开关按钮刷成指定状态（原地刷新，不重建界面 —— 重建会把正在打的 id 冲掉）
     */
    private static void applyToggleVisual(SwitchButton button, boolean on) {
        if (button != null) {
            button.setState(ToggleSwitchButton.labelOf(on), ToggleSwitchButton.colorOf(on));
        }
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

    /**
     * 去掉首尾空白（复制原值 / 新增时的统一规整入口）
     */
    private String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }

    // ==============================================================
    // 行的布局
    // ==============================================================

    /**
     * 中间那个控件的 x（开关模式下是「是 / 否」开关，值模式下是值输入框）
     */
    private int valueX() {
        return rowsRight() - DETAIL_DELETE_WIDTH - WIDGET_GAP - RULE_DATA_VALUE_WIDTH;
    }

    /**
     * 「×」删除按钮的 x（两种模式的最后一列都是删除）
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

    /**
     * 造所有行：每个条目一行 + 最后一行「添加」。
     */
    @Override
    protected void buildRows() {
        List<Map.Entry<String, String>> entries = new ArrayList<>(values.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            page.addRow(buildRow(entries.get(i), page.rowY(i)));
        }
        page.addRow(buildAddRow(page.rowY(values.size())));
    }

    /**
     * 一个已配置的条目：id 标签 +（是/否开关 或 值控件）+「×」
     */
    private List<RowListPage.Widget> buildRow(Map.Entry<String, String> entry, int y) {
        String id = entry.getKey();
        List<RowListPage.Widget> widgets = new ArrayList<>();
        widgets.add(idLabel(id, y));

        if (isPlain()) {
            widgets.add(buildToggle(id, entry.getValue(), y));
        } else {
            widgets.add(buildValueEditor(id, entry.getValue(), y));
        }
        widgets.add(buildDeleteButton(id, y));
        return widgets;
    }

    /**
     * id 标签：太长就截断（免得压到右边的控件上）
     */
    private RowListPage.Widget idLabel(String id, int y) {
        String shown = this.font.plainSubstrByWidth(id, idLabelWidth() - ComponentLayout.LABEL_PADDING * 2);
        return RowListPage.at(addWidget(new OptionLabel(contentX() + ComponentLayout.LABEL_PADDING, y,
                idLabelWidth(), WIDGET_HEIGHT, Component.literal(shown))));
    }

    /**
     * 开关模式的「是 / 否」：拨一下直接改工作副本（值就是 {@code true} / {@code false}）。
     *
     * <p>按钮原地刷新，不重建界面 —— 重建会把新增行里正在打的 id 冲掉。
     */
    private RowListPage.Widget buildToggle(String id, String value, int y) {
        SwitchButton[] self = new SwitchButton[1];
        boolean on = isOn(value);
        SwitchButton toggle = ToggleSwitchButton.create(valueX(), y, RULE_DATA_VALUE_WIDTH, WIDGET_HEIGHT,
                        on, () -> {
                            boolean next = !isOn(values.get(id));
                            values.put(id, next ? TRUE_VALUE : FALSE_VALUE);
                            applyToggleVisual(self[0], next);
                        })
                .withTooltip(Component.translatable("villagerdetails.ui.rule_data.toggle"));
        self[0] = toggle;
        return RowListPage.at(addWidget(toggle));
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
     * 「×」删除这一行（两种模式都有）
     */
    private RowListPage.Widget buildDeleteButton(String id, int y) {
        return RowListPage.at(addWidget(new SwitchButton(deleteX(), y, DETAIL_DELETE_WIDTH, WIDGET_HEIGHT,
                Component.literal("×"), RuleSwitches.COLOR_SELECTED_OFF,
                () -> deleteValue(id))
                .withTooltip(Component.translatable("villagerdetails.ui.list.delete"))));
    }

    /**
     * 最后一行：「+」添加按钮 / 点开后变成输入框
     */
    private List<RowListPage.Widget> buildAddRow(int y) {
        List<RowListPage.Widget> widgets = new ArrayList<>();

        if (!adding) {
            clearAddWidgets();
            widgets.add(RowListPage.at(addWidget(Button.builder(Component.literal("+"), _ -> startAdd())
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
                    (query, limit) -> RuleAuxCache.INSTANCE.suggestions(rule.registerName(), query, limit));
            idBox.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
            prefillValue(text.trim());
            syncAddToggle(text);
        });
        this.addIdBox = idBox;
        widgets.add(RowListPage.at(addWidget(idBox)));
        requestFocus(idBox);

        if (isPlain()) {
            // 开关模式：右边是「是 / 否」，没有值输入框（这一行还没落库，所以也没有「×」）
            buildAddToggle(y, widgets);
            return widgets;
        }

        EditBox valueBox = new EditBox(this.font, valueX(), y, RULE_DATA_VALUE_WIDTH, WIDGET_HEIGHT,
                Component.translatable("villagerdetails.ui.rule_data.value"));
        valueBox.setMaxLength(MAX_VALUE_LENGTH);
        valueBox.setHint(Component.translatable("villagerdetails.ui.rule_data.value.hint"));
        this.addValueBox = valueBox;
        widgets.add(RowListPage.at(addWidget(valueBox)));
        return widgets;
    }

    /**
     * 开关模式新增行的「是 / 否」：默认「否」，填好 id 之后按那个 id 现在的状态摆。
     */
    private void buildAddToggle(int y, List<RowListPage.Widget> widgets) {
        this.addToggleOn = false;

        SwitchButton toggle = ToggleSwitchButton.create(valueX(), y, RULE_DATA_VALUE_WIDTH, WIDGET_HEIGHT,
                        false, () -> {
                            this.addToggleOn = !this.addToggleOn;
                            applyToggleVisual(addToggle, addToggleOn);
                        })
                .withTooltip(Component.translatable("villagerdetails.ui.rule_data.toggle"));

        this.addToggle = toggle;
        widgets.add(RowListPage.at(addWidget(toggle)));
    }

    /**
     * 新增行里当前填的 id（规范化后）；没填就是空串
     */
    private String addIdValue() {
        return RuleAuxCache.INSTANCE.canonicalId(addIdBox == null ? "" : addIdBox.getValue());
    }

    /**
     * 新增行 id 变化后：把这个 id <b>现在</b>的状态摆到开关上（没配置过就是「否」）。
     */
    private void syncAddToggle(String rawId) {
        if (addToggle == null) {
            return;
        }
        String id = RuleAuxCache.INSTANCE.canonicalId(rawId);
        this.addToggleOn = !id.isEmpty() && isOn(values.get(id));
        applyToggleVisual(addToggle, addToggleOn);
    }

    /**
     * 新一轮新增开始时，把上一轮留下的控件引用清干净
     */
    private void clearAddWidgets() {
        this.addIdBox = null;
        this.addValueBox = null;
        this.addToggle = null;
        this.addToggleOn = false;
        popup.reset();
    }

    /**
     * id 填成合法值之后，把原版值预填进值输入框（玩家自己改过就不动）。
     *
     * <p>原版值是服务端算好一起下发的（客户端不认识方块，算不出来）。
     * 开关模式没有值输入框，走的是 {@link #syncAddToggle}。
     */
    private void prefillValue(String id) {
        if (addValueBox == null || !addValueBox.getValue().isEmpty()) {
            return;
        }
        if (!RuleAuxCache.INSTANCE.knowsId(rule.registerName(), id)) {
            return;
        }
        String defaultValue = RuleAuxCache.INSTANCE.defaultValue(rule.registerName(), id);
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

    /**
     * 进入「添加」状态：最后一行变成输入框
     */
    private void startAdd() {
        this.adding = true;
        popup.reset();
        rebuildWidgets();
    }

    /**
     * 删除某个 id 的条目（只动工作副本）
     */
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
        String id = addIdValue();
        if (id.isEmpty()) {
            // 什么都没填 = 取消，不算错
            this.adding = false;
            return false;
        }
        if (!RuleAuxCache.INSTANCE.knowsId(rule.registerName(), id)) {
            markInvalid(addIdBox, "villagerdetails.ui.rule_data.invalid_id");
            return false;
        }

        // 开关模式的值来自那个「是 / 否」，没有输入框要校验
        String value;
        if (isPlain()) {
            value = addToggleOn ? TRUE_VALUE : FALSE_VALUE;
        } else {
            value = addValueBox == null ? "" : addValueBox.getValue().trim();
            if (value.isEmpty()) {
                // 兼容：值没填就退回这个 id 的原版值（和服务端的预填是同一套算法）。
                // 清单还没到 / 刚打开页面就回车时，输入框可能还是空的 —— 不能因此把人卡死在
                // 「值不合法」上，那不是玩家填错了。
                String fallback = RuleAuxCache.INSTANCE.defaultValue(rule.registerName(), id);
                if (fallback != null) {
                    value = fallback.trim();
                    if (addValueBox != null) {
                        addValueBox.setValue(value);
                    }
                }
            }
            if (!isValidValue(value)) {
                markInvalid(addValueBox, "villagerdetails.ui.rule_data.invalid_value");
                return false;
            }
        }

        this.adding = false;
        popup.reset();
        values.put(id, isPlain() ? value : normalizeValue(value));
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
                ClientRequests.INSTANCE.sendRuleData(rule.registerName(), id, entry.getValue());
            }
        }
        for (String id : original.keySet()) {
            if (!values.containsKey(id)) {
                ClientRequests.INSTANCE.sendRuleDataRemove(rule.registerName(), id);
            }
        }
    }

    // ==============================================================
    // 绘制 / 输入
    // ==============================================================

    /**
     * 在行控件之上画补全弹窗（跟行一起被裁剪）
     */
    @Override
    protected void drawOverlay(GuiGraphicsExtractor graphics) {
        if (adding) {
            popup.draw(graphics, this.font, popupBounds());
        }
    }

    /**
     * 鼠标是不是落在这个控件上（控件可能为 null：两种模式的控件不是同一套）
     */
    private static boolean isOver(AbstractWidget widget, double x, double y) {
        return widget != null && widget.isMouseOver(x, y);
    }

    /**
     * 点击：命中补全候选就填进 id 输入框；否则点列表区空白处（不含输入框/开关自身）= 提交新增；其余交给原版
     */
    @Override
    public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubleClick) {
        if (adding) {
            int hit = popup.hitTest(popupBounds(), event.x(), event.y());
            if (hit >= 0) {
                popup.acceptInto(addIdBox);
                return true;
            }
            // 点列表区内的空白处 = 收下这一条；页脚按钮 / 列表外交给 super（否则「取消 / 完成」会被吞）
            // 开关模式的开关也算「这一行的控件」，点它不能当成「点空白」
            boolean insideBoxes = isOver(addIdBox, event.x(), event.y())
                    || isOver(addValueBox, event.x(), event.y())
                    || isOver(addToggle, event.x(), event.y());
            if (!insideBoxes && page.isMouseOverRows(event.x(), event.y())) {
                commitAdd();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * 按键：新增状态下走「回车 / Tab / 上下 / ESC」补全逻辑，否则交给原版
     */
    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        if (adding) {
            boolean idValid = addIdBox != null && RuleAuxCache.INSTANCE.knowsId(rule.registerName(), addIdBox.getValue().trim());
            if (handleEditingKey(event, popup, addIdBox, idValid, this::commitAdd, this::cancelAdd)) {
                return true;
            }
        }
        return super.keyPressed(event);
    }
}
