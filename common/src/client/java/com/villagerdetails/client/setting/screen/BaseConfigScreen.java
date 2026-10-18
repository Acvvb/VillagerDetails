package com.villagerdetails.client.setting.screen;

import com.villagerdetails.client.network.sync.ClientRequests;
import com.villagerdetails.client.setting.component.*;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.config.ui.ComponentLayout;
import com.villagerdetails.client.setting.dto.EntryListSource;
import com.villagerdetails.client.setting.dto.UiTabsSource;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.client.setting.row.ServerRuleDataRow;
import com.villagerdetails.client.setting.tabs.ConfigTabs;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.client.setting.type.FirstCategoryType;
import com.villagerdetails.client.setting.type.style.StyleType;
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

import java.util.*;

import static com.villagerdetails.client.setting.config.ui.UiLayout.*;

/**
 * 主配置界面。
 *
 * <pre>
 * +-----------------------------------------------------------+
 * |                        界面标题                            |
 * +---------------+---------------+---------------------------+
 * | &gt; 配置项      |  [ 颜色 ] [ 滑块 ] [ 列表 ]                |   ← 二级分类
 * |   功能开关    |  ---------------------------------------  |
 * |   服务器      |  [ 搜索配置…                            ]  |   ← 全局搜索框
 * |               |  连线粗细            [ ⚙ ] [ ====---- ]     |
 * +---------------+---------------+---------------------------+
 * |              [ 全局重置 ]        [ 完成 ]                   |
 * +-----------------------------------------------------------+
 * </pre>
 * <p>
 * 功能开关 / 服务器规则的行是：{@code 规则名 … [是/否] [快捷键] [重置]}
 * （服务器规则没有快捷键绑定框）。
 *
 * <p>内容行的分页 / 平滑滚动 / 滚动条 / 画布裁剪都在 {@link RowListPage} 里，
 * 和两个详情页共用同一套。
 */
public class BaseConfigScreen extends Screen {

    private final Screen parent;

    private FirstCategoryType selectedMain = FirstCategoryType.OPTIONS;
    private String selectedTabId;

    private List<UiTabsSource> tabs = List.of();
    /**
     * 本次重建实际显示的行（已按搜索词过滤）
     */
    private List<ConfigRow> renderedRows = List.of();

    /**
     * 处于「直接输入数值」状态的滑块行 id
     */
    private final Set<String> numericEditing = new HashSet<>();

    /**
     * 本次重建里添加的快捷键绑定框（抢在界面前面处理按键）
     */
    private final List<KeyBindButton> keyBindButtons = new ArrayList<>();

    private EditBox searchBox;
    private String searchQuery = "";
    private boolean pendingRebuild;
    private boolean restoreSearchFocus;

    private int syncVersion;

    /**
     * 二级分类滚动窗口的第一个下标
     */
    private int subTabScroll;

    /**
     * 这次重建要不要把当前选中的二级分类滚进可见窗口。
     * 刚打开和换一级分类时为 true；玩家自己翻页 / 滚轮滚动时不动它。
     */
    private boolean subTabScrollNeedsReveal = true;

    /**
     * 内容行的「一页列表」：分页、平滑滚动、滚动条、行控件摆位、画布裁剪都归它
     * （和两个详情页共用同一个组件）
     */
    private final RowListPage page = new RowListPage(() -> renderedRows.size(), MAX_ROWS_PER_PAGE);

    /**
     * 本次重建里创建的「重置」按钮，值变化后统一刷新灰 / 亮
     */
    private final List<ResetButton> resetButtons = new ArrayList<>();

    /**
     * 底部的「全局重置」
     */
    private Button globalResetButton;

    /**
     * 本次重建时算好的、实际可见的一级分类（面板高度要用，见 drawPanels）
     */
    private List<FirstCategoryType> visibleMains = List.of();

    private AbstractWidget focusAfterInit;

    public BaseConfigScreen(Screen parent) {
        super(Component.translatable("villagerdetails.ui.title"));
        this.parent = parent;
        this.syncVersion = ServerRuleClientState.getVersion();
        restoreSelection();

        // 打开界面时跟服务器打个招呼：要一份最新的全量描述符
        // （顺带把当前界面语言报上去，服务端据此翻译描述符里的文字；服务器没装本 mod 时内部会直接忽略）
        ClientRequests.sendHello();
    }

    // ==============================================================
    // 上次停留的位置
    // ==============================================================

    private void restoreSelection() {
        ClientRenderConfig config = ClientRenderConfig.get();

        this.selectedMain = mainCategoryByName(config.getLastCategory());
        if (!FirstCategoryType.visibleValues().contains(selectedMain)) {
            this.selectedMain = FirstCategoryType.OPTIONS;
        }
        this.selectedTabId = config.getLastSubCategory();
    }

    private void rememberSelection() {
        ClientRenderConfig config = ClientRenderConfig.get();
        config.setLastCategory(selectedMain.name());
        config.setLastSubCategory(selectedTabId == null ? "" : selectedTabId);
        config.save();
    }

    /**
     * 按枚举名找一级分类；名字对不上（或为空）就回到「配置项」
     */
    private static FirstCategoryType mainCategoryByName(String name) {
        if (name != null && !name.isEmpty()) {
            for (FirstCategoryType constant : FirstCategoryType.values()) {
                if (constant.name().equals(name)) {
                    return constant;
                }
            }
        }
        return FirstCategoryType.OPTIONS;
    }

    // ==============================================================
    // 分类 / 行
    // ==============================================================

    private UiTabsSource currentTab() {
        if (tabs.isEmpty()) {
            return null;
        }
        if (selectedTabId != null) {
            for (UiTabsSource tab : tabs) {
                if (tab.id().equals(selectedTabId)) {
                    return tab;
                }
            }
        }
        return tabs.getFirst();
    }

    /**
     * 当前分类下按搜索词过滤后的行
     */
    private List<ConfigRow> filterRows(List<ConfigRow> rows) {
        if (searchQuery.isBlank()) {
            return rows;
        }
        String query = searchQuery.trim().toLowerCase(Locale.ROOT);
        List<ConfigRow> matched = new ArrayList<>();
        for (ConfigRow row : rows) {
            if (row.getDisplayName().getString().toLowerCase(Locale.ROOT).contains(query)
                    || row.getDisplayInfo().getString().toLowerCase(Locale.ROOT).contains(query)) {
                matched.add(row);
            }
        }
        return matched;
    }

    // ==============================================================
    // 布局计算
    // ==============================================================

    private int contentX() {
        return SIDEBAR_X + SIDEBAR_WIDTH + CONTENT_GAP;
    }

    private int contentRight() {
        return this.width - MARGIN;
    }

    private int rowWidth() {
        return Math.max(0, contentRight() - contentX());
    }

    /**
     * 内容行的右边界（需要滚动时给滚动条让出一列）
     */
    private int rowsRight() {
        return page.rowsRight();
    }

    /**
     * 带「重置」按钮的行专用：值控件和齿轮都要给右边的重置按钮让位。
     */
    private int coloredValueX() {
        return resetX() - WIDGET_GAP - VALUE_WIDTH;
    }

    /**
     * 带重置按钮的行里，齿轮再往左一格
     */
    private int gearWithResetX() {
        return coloredValueX() - WIDGET_GAP - GEAR_SIZE;
    }


    private int resetX() {
        return rowsRight() - RESET_WIDTH;
    }

    private int keyBindX() {
        return resetX() - WIDGET_GAP - KEYBIND_WIDTH;
    }

    /**
     * 没有快捷键框的行：开关直接排在重置左边
     */
    private int plainSwitchX() {
        return resetX() - WIDGET_GAP - SWITCH_WIDTH;
    }

    private int searchY() {
        // 搜索框放在左侧一级分类栏的最上面（在「配置项」上面），尺寸和一级分类按钮一致
        return SIDEBAR_TOP;
    }

    /**
     * 一级分类按钮从搜索框下面开始排
     */
    private int sidebarEntryY(int index) {
        return SIDEBAR_TOP + SIDEBAR_ENTRY_HEIGHT + index * SIDEBAR_ENTRY_HEIGHT;
    }

    // ---------------- 二级分类的宽度 / 滚动 ----------------

    /**
     * 二级分类是否超过一行能放下的数量
     */
    private boolean subTabScrollingNeeded() {
        return tabs.size() > SUBTAB_MAX_VISIBLE;
    }

    /**
     * 每个二级分类按钮的宽度：按「一行 5 个」算，要滚动时再给末尾两个按钮让位
     */
    private int subTabWidth() {
        int area = rowWidth() - (subTabScrollingNeeded() ? SUBTAB_ARROW_WIDTH * 2 + SUBTAB_GAP * 2 : 0);
        int usable = area - (SUBTAB_MAX_VISIBLE - 1) * SUBTAB_GAP;
        return Math.max(SUBTAB_MIN_WIDTH, usable / SUBTAB_MAX_VISIBLE);
    }

    private int subTabX(int visibleIndex) {
        return contentX() + visibleIndex * (subTabWidth() + SUBTAB_GAP);
    }

    /**
     * 末尾的翻页按钮位置：index 0 = ◀，1 = ▶
     */
    private int subTabArrowX(int index) {
        return contentRight() - SUBTAB_ARROW_WIDTH * (2 - index) - (index == 0 ? SUBTAB_GAP : 0);
    }

    /**
     * 二级分类数量变了之后，保证 subTabScroll 还落在合法范围里（不然会露出空白）
     */
    private void clampSubTabScrollRange() {
        subTabScroll = Math.clamp(subTabScroll, 0, Math.max(0, tabs.size() - SUBTAB_MAX_VISIBLE));
    }

    /**
     * 把当前选中的二级分类滚进可见窗口。
     *
     * <p>只在「刚打开 / 换了一级分类 / 选中的那个没了」时调用。要是每次重建都调，
     * 玩家手动翻页会被立刻拽回来（选中的那个分类必须可见），左右箭头就按不动了。
     */
    private void revealActiveSubTab(UiTabsSource active) {
        int selectedIndex = 0;
        if (active != null) {
            for (int i = 0; i < tabs.size(); i++) {
                if (tabs.get(i) == active) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        if (selectedIndex < subTabScroll) {
            subTabScroll = selectedIndex;
        } else if (selectedIndex >= subTabScroll + SUBTAB_MAX_VISIBLE) {
            subTabScroll = selectedIndex - SUBTAB_MAX_VISIBLE + 1;
        }
        clampSubTabScrollRange();
    }

    /**
     * 当前是第几页（从 0 数）。
     *
     * <p>页是按 {@code SUBTAB_MAX_VISIBLE} 死切的（第 1-5 项、6-10 项…），
     * 所以「当前页」以<b>现在渲染出来的第一项属于哪一页</b>为准：
     * 滚轮滚到显示第 2-6 项时，第一项是第 2 项，那当前就算第 1 页。
     */
    private int currentSubTabPage() {
        return subTabScroll / SUBTAB_MAX_VISIBLE;
    }

    /**
     * 按一整页翻（最右边 ◀ ▶ 按钮用）。
     *
     * <p>目标 = (当前页 ± 1) × 一页的个数，再夹到合法范围里 —— 所以最后一页不满时会贴到底，
     * 不会露出空白。滚轮不走这里，仍然是一格格滚（见 {@link #scrollSubTabs(int)}）。
     */
    private void pageSubTabs(int deltaPages) {
        int target = (currentSubTabPage() + deltaPages) * SUBTAB_MAX_VISIBLE;
        int next = Math.clamp(target, 0, Math.max(0, tabs.size() - SUBTAB_MAX_VISIBLE));
        if (next != subTabScroll) {
            subTabScroll = next;
            rebuildWidgets();
        }
    }

    /**
     * 按一格格滚（鼠标滚轮用）
     */
    private void scrollSubTabs(int delta) {
        int next = Math.clamp(subTabScroll + delta, 0, Math.max(0, tabs.size() - SUBTAB_MAX_VISIBLE));
        if (next != subTabScroll) {
            subTabScroll = next;
            rebuildWidgets();
        }
    }

    // ==============================================================
    // 控件
    // ==============================================================

    @Override
    protected void init() {
        keyBindButtons.clear();
        resetButtons.clear();

        // 先把几何交给列表组件：后面造行控件要用到 rowsRight()（它取决于「需不需要滚动条」）
        page.setGeometry(contentX(), contentRight(), CONTENT_TOP + FIRST_ROW_OFFSET,
                footerButtonY(this.height) - ROW_SCROLL_MARGIN);
        page.setPanel(contentX() - PANEL_PADDING, CONTENT_TOP - PANEL_PADDING,
                Math.min(contentRight() + PANEL_PADDING, this.width - PANEL_RIGHT_MARGIN));

        // 左侧：一级分类（服务器没装本 mod / 自己一条规则都没权限看时不会包含「服务器」）
        // 结果缓存起来：判断「服务器装没装 mod」要查网络通道，没必要每帧都查一遍
        this.visibleMains = FirstCategoryType.visibleValues();
        // 权限可能在开着这个页面的时候被改掉（比如被下了 op）→ 服务端下次同步就一条规则都不发，
        // 「服务器」分类会跟着消失；这时直接退回第一个还能看的分类，别停在一个已经空掉的页面上
        if (!visibleMains.contains(selectedMain)) {
            selectedMain = visibleMains.getFirst();
            selectedTabId = null;
            subTabScroll = 0;
            subTabScrollNeedsReveal = true;
        }
        for (int i = 0; i < visibleMains.size(); i++) {
            FirstCategoryType main = visibleMains.get(i);
            boolean selected = main == selectedMain;
            addRenderableWidget(Button.builder(selectedLabel(main.getDisplayName(), selected), _ -> {
                        if (selectedMain != main) {
                            selectedMain = main;
                            selectedTabId = null;
                            tabs = ConfigTabs.build(main);
                            selectedTabId = tabs.isEmpty() ? null : tabs.getFirst().id();
                            // 换了一级分类：二级分类滚动窗口回到第一页
                            subTabScrollNeedsReveal = true;
                            rememberSelection();
                            rebuildWidgets();
                        }
                    })
                    .bounds(SIDEBAR_X, sidebarEntryY(i), SIDEBAR_WIDTH, WIDGET_HEIGHT)
                    .build());
        }

        // 右上：二级分类（一行最多 5 个，超出的用 ◀ ▶ 整页翻 / 滚轮一格格滚）
        this.tabs = ConfigTabs.build(selectedMain);
        UiTabsSource active = currentTab();
        if (active == null) {
            selectedTabId = null;
        } else if (!active.id().equals(selectedTabId)) {
            // 上次选的那个二级分类现在不在列表里了（刚打开 / 服务端规则变了）→ 回到第一个并把它露出来
            selectedTabId = active.id();
            subTabScrollNeedsReveal = true;
        }
        if (subTabScrollNeedsReveal) {
            revealActiveSubTab(active);
            subTabScrollNeedsReveal = false;
        } else {
            // 正常情况下只做范围修正，别把玩家翻到的位置拽回去
            clampSubTabScrollRange();
        }

        int visibleCount = Math.min(tabs.size() - subTabScroll, SUBTAB_MAX_VISIBLE);
        for (int i = 0; i < visibleCount; i++) {
            UiTabsSource tab = tabs.get(subTabScroll + i);
            boolean selected = tab == active;
            addRenderableWidget(Button.builder(selectedLabel(tab.displayName(), selected), _ -> {
                        if (selectedTabId == null || !selectedTabId.equals(tab.id())) {
                            selectedTabId = tab.id();
                            rememberSelection();
                            rebuildWidgets();
                        }
                    })
                    .bounds(subTabX(i), CONTENT_TOP, subTabWidth(), WIDGET_HEIGHT)
                    .build());
        }

        // 二级分类放不下时，末尾放两个整页翻页按钮（滚轮仍然一格格滚）
        if (subTabScrollingNeeded()) {
            Button previous = Button.builder(Component.literal("◀"), _ -> pageSubTabs(-1))
                    .bounds(subTabArrowX(0), CONTENT_TOP, SUBTAB_ARROW_WIDTH, WIDGET_HEIGHT)
                    .build();
            // 已经在最左边（没法再往左翻）就置灰
            previous.active = subTabScroll > 0;
            addRenderableWidget(previous);

            Button next = Button.builder(Component.literal("▶"), _ -> pageSubTabs(1))
                    .bounds(subTabArrowX(1), CONTENT_TOP, SUBTAB_ARROW_WIDTH, WIDGET_HEIGHT)
                    .build();
            // 右边已经没有更多分类了就置灰
            next.active = subTabScroll + SUBTAB_MAX_VISIBLE < tabs.size();
            addRenderableWidget(next);
        }

        // 搜索框（全局，每个分类页都有）
        initSearchBox();

        // 内容行：一页最多 MAX_ROWS_PER_PAGE 行，塞不下就用滚轮 / 右侧滚动条（带平滑动画）
        // 注意行控件用的是 addWidget（只进 children）：它们统一由 RowListPage#drawRows 画在画布里
        this.renderedRows = filterRows(active == null ? List.of() : active.rows());
        page.clearRows();
        for (int i = 0; i < renderedRows.size(); i++) {
            ConfigRow row = renderedRows.get(i);
            int y = page.rowY(i);
            List<RowListPage.Widget> widgets = new ArrayList<>();
            switch (row.getStyle()) {
                case TOGGLE -> addToggleRow(row, y, widgets);
                case COLOR -> addColorRow(row, y, widgets);
                case SLIDER -> addSliderRow(row, y, widgets);
                case LIST -> addListRow(row, y, widgets);
                case TRIGGER -> addTriggerRow(row, y, widgets);
            }
            page.addRow(widgets);
        }

        // 滚动条放在行控件之后添加，这样它画在行控件上面
        page.installScrollBar(this::addRenderableWidget);

        page.clamp();
        page.updatePositions();

        // 底部：全局重置 + 完成（全都还是默认值时「全局重置」置灰）
        this.globalResetButton = Button.builder(Component.translatable("villagerdetails.ui.reset_all"), _ -> {
                    ClientRenderConfig.get().resetToDefaults();
                    rebuildWidgets();
                })
                .bounds(footerLeftButtonX(this.width), footerButtonY(this.height),
                        FOOTER_BUTTON_WIDTH, WIDGET_HEIGHT)
                .build();
        globalResetButton.active = ClientRenderConfig.get().hasNonDefaultValues();
        addRenderableWidget(globalResetButton);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, _ -> this.onClose())
                .bounds(footerRightButtonX(this.width), footerButtonY(this.height),
                        FOOTER_BUTTON_WIDTH, WIDGET_HEIGHT)
                .build());
    }

    /**
     * 搜索框实例在界面重建时复用，这样输入内容和光标不会丢
     */
    private void initSearchBox() {
        if (searchBox == null) {
            searchBox = new EditBox(this.font, SIDEBAR_X, searchY(), SIDEBAR_WIDTH, WIDGET_HEIGHT,
                    Component.translatable("villagerdetails.ui.search"));
            searchBox.setHint(Component.translatable("villagerdetails.ui.search.hint"));
            searchBox.setResponder(text -> {
                this.searchQuery = text;
                this.pendingRebuild = true;
            });
        }
        // 尺寸/位置 = 一级分类按钮：宽 SIDEBAR_WIDTH、高 WIDGET_HEIGHT、x SIDEBAR_X。
        // 注意：26.2 的 AbstractWidget#setRectangle 参数顺序是 (width, height, x, y)，
        // 用显式 setter 免得再踩这个坑。
        searchBox.setX(SIDEBAR_X);
        searchBox.setY(searchY());
        searchBox.setWidth(SIDEBAR_WIDTH);
        searchBox.setHeight(WIDGET_HEIGHT);
        addRenderableWidget(searchBox);
        if (restoreSearchFocus) {
            restoreSearchFocus = false;
            this.focusAfterInit = searchBox;
        }
    }

    /**
     * 每一行最左边那枚「名称标签」（带描述悬停），所有行共用。
     */
    private OptionLabel labelWidget(ConfigRow row, int y) {
        Component name = row.getDisplayName();
        return OptionLabel.forRow(row, contentX() + ComponentLayout.LABEL_PADDING, y,
                this.font.width(name) + ComponentLayout.LABEL_PADDING * 2, WIDGET_HEIGHT);
    }

    /**
     * 造好「重置」按钮并登记，方便值变化后统一刷新灰 / 亮
     */
    private ResetButton createResetButton(ConfigRow row, int y) {
        ResetButton button = ResetButton.forRow(row, resetX(), y, RESET_WIDTH, WIDGET_HEIGHT, this::rebuildWidgets);
        resetButtons.add(button);
        return button;
    }

    /**
     * 功能开关 / 服务器规则行：标签 … [状态按钮组] [快捷键] [重置]
     *
     * <p>服务器规则用 {@link RuleSwitchGroup}（按 {@code RuleType#getQuickSwitches()} 渲染成一排状态按钮，
     * 显示 / 颜色 / 取值都和 {@code /ec} 一致，没有快捷键框）；本模组的功能开关则是「是/否」按钮 + 快捷键框。
     */
    private void addToggleRow(ConfigRow row, int y, List<RowListPage.Widget> out) {
        out.add(RowListPage.at(addWidget(labelWidget(row, y))));

        List<AbstractWidget> switches = RuleSwitchGroup.build(row, this.font, resetX() - WIDGET_GAP, y, WIDGET_HEIGHT);
        if (switches.isEmpty()) {
            int switchX = row.hasKeyBind() ? keyBindX() - WIDGET_GAP - SWITCH_WIDTH : plainSwitchX();
            switches = List.of(ToggleSwitchButton.create(switchX, y, SWITCH_WIDTH, WIDGET_HEIGHT, row, this::rebuildWidgets));
        }
        for (AbstractWidget widget : switches) {
            out.add(RowListPage.at(addWidget(widget)));
        }

        if (row instanceof ConfigEntryType entry && row.hasKeyBind()) {
            // 绑完（或按 ESC 清空）立刻落盘，别等到关界面才存
            KeyBindButton bindButton = KeyBindButton.create(keyBindX(), y, KEYBIND_WIDTH, WIDGET_HEIGHT,
                    entry, keys -> {
                        row.setKeyBind(keys);
                        row.save();
                    });
            keyBindButtons.add(bindButton);
            out.add(RowListPage.at(addWidget(bindButton)));
        }

        out.add(RowListPage.at(addWidget(createResetButton(row, y))));
    }

    /**
     * 触发样式行：名称 … [触发按钮] [快捷键框] [重置]
     *
     * <p>触发按钮点一下执行 {@link ConfigRow#getAction()}（不保存开/关状态）；
     * 快捷键按下走同一动作；重置只把快捷键恢复默认。
     */
    private void addTriggerRow(ConfigRow row, int y, List<RowListPage.Widget> out) {
        out.add(RowListPage.at(addWidget(labelWidget(row, y))));

        int triggerX = row.hasKeyBind() ? keyBindX() - WIDGET_GAP - SWITCH_WIDTH : plainSwitchX();
        out.add(RowListPage.at(addWidget(Button.builder(
                        Component.translatable("villagerdetails.ui.trigger.run"),
                        _ -> row.getAction().run())
                .bounds(triggerX, y, SWITCH_WIDTH, WIDGET_HEIGHT)
                .build())));

        if (row instanceof ConfigEntryType entry && row.hasKeyBind()) {
            KeyBindButton bindButton = KeyBindButton.create(keyBindX(), y, KEYBIND_WIDTH, WIDGET_HEIGHT,
                    entry, keys -> {
                        row.setKeyBind(keys);
                        row.save();
                    });
            keyBindButtons.add(bindButton);
            out.add(RowListPage.at(addWidget(bindButton)));
        }

        out.add(RowListPage.at(addWidget(createResetButton(row, y))));
    }

    /**
     * 颜色行：标签 色块 [ #RRGGBB ] [重置]
     *
     * <p>点颜色值按钮进详情页（RGB 滑条 + 可输入的色值框）；重置按钮恢复默认色。
     *
     * <p>色块比按钮矮，靠 {@link RowListPage#offset} 往下挪，让两个的中心线对齐 ——
     * 这个偏移必须登记给列表组件，否则滚动重摆时会被摆回行顶（那就变成贴着上边了）。
     */
    private void addColorRow(ConfigRow row, int y, List<RowListPage.Widget> out) {
        out.add(RowListPage.at(addWidget(labelWidget(row, y))));

        if (row instanceof ConfigEntryType) {
            int swatchX = coloredValueX() - WIDGET_GAP - SWATCH_WIDTH;
            out.add(RowListPage.offset(addWidget(new ColorSwatch(swatchX, y + SWATCH_Y_OFFSET,
                    SWATCH_WIDTH, SWATCH_HEIGHT, row::getColor)), SWATCH_Y_OFFSET));

            out.add(RowListPage.at(addWidget(Button.builder(Component.literal(ColorValueBox.hex(row.getColor())),
                            _ -> openDetail(row))
                    .bounds(coloredValueX(), y, VALUE_WIDTH, WIDGET_HEIGHT)
                    .build())));
        }

        out.add(RowListPage.at(addWidget(createResetButton(row, y))));
    }

    /**
     * 滑块行：标签 [⚙] [滑条 / 数值输入框] [重置]
     *
     * <p>按 ⚙ 在「滑条」和「直接填数值」之间切换；重置按钮恢复默认值。
     */
    private void addSliderRow(ConfigRow row, int y, List<RowListPage.Widget> out) {
        out.add(RowListPage.at(addWidget(labelWidget(row, y))));
        boolean editing = numericEditing.contains(row.getRegisterName());

        out.add(RowListPage.at(addWidget(SettingWidgets.gear(gearWithResetX(), y, GEAR_SIZE, WIDGET_HEIGHT, () -> {
            if (numericEditing.contains(row.getRegisterName())) {
                numericEditing.remove(row.getRegisterName());
                row.save();
            } else {
                numericEditing.add(row.getRegisterName());
            }
            rebuildWidgets();
        }))));

        if (editing) {
            EditBox box = SettingWidgets.numberBox(coloredValueX(), y, VALUE_WIDTH, WIDGET_HEIGHT, row, this::refreshResetButtons);
            out.add(RowListPage.at(addWidget(box)));
            this.focusAfterInit = box;
        } else {
            out.add(RowListPage.at(addWidget(new ValueSlider(coloredValueX(), y, VALUE_WIDTH, WIDGET_HEIGHT,
                    row.minNumber(), row.maxNumber(), row.stepNumber(),
                    row.getNumber(), row::setNumber, row::formatNumber, () -> {
                row.save();
                refreshResetButtons();
            }))));
        }

        out.add(RowListPage.at(addWidget(createResetButton(row, y))));
    }

    /**
     * 列表行：标签 [ 值列表 ] [重置]
     *
     * <p>两种列表：
     * <ul>
     *     <li>本机配置项的列表（{@code ConfigEntryType}）：按钮上显示 {@code [a,b]}，点进去增删值</li>
     *     <li>「服务端配置项」的规则数据（{@link ServerRuleDataRow}）：按钮上显示 {@code [id=值,…]}，
     *         点进去逐条配（值是数字还是文本、是纯名单还是键值，全看服务端下发的模板）</li>
     * </ul>
     */
    private void addListRow(ConfigRow row, int y, List<RowListPage.Widget> out) {
        out.add(RowListPage.at(addWidget(labelWidget(row, y))));

        Component label = switch (row) {
            case ServerRuleDataRow dataRow -> dataRow.listDisplay();
            case ConfigEntryType entry -> entry.listDisplay();
            default -> null;
        };
        if (label != null) {
            out.add(RowListPage.at(addWidget(Button.builder(label, _ -> openDetail(row))
                    .bounds(coloredValueX(), y, VALUE_WIDTH, WIDGET_HEIGHT)
                    .build())));
        }

        out.add(RowListPage.at(addWidget(createResetButton(row, y))));
    }

    /**
     * 不重建界面，只刷新所有「重置」按钮的灰 / 亮状态
     */
    private void refreshResetButtons() {
        for (ResetButton button : resetButtons) {
            button.refreshState();
        }
        if (globalResetButton != null) {
            globalResetButton.active = ClientRenderConfig.get().hasNonDefaultValues();
        }
    }

    /**
     * 进详情页：
     * <ul>
     *     <li>颜色项 → RGB 滑条那一页</li>
     *     <li>本机配置项的列表 → {@link ListDetailScreen}（值就是本机的一串字符串）</li>
     *     <li>「服务端配置项」的规则数据 → 统一走 {@link RuleDataDetailScreen}，
     *         由它按服务端下发的模板分成「id + 值」（抗性）和「id + 是/否 + 重置」（纯名单）两种排版</li>
     * </ul>
     *
     * <p>服务端的规则数据<b>不再按「纯名单 / 键值」分成两个页面</b>：纯名单那一套（开关 + 重置）
     * 就在 {@link RuleDataDetailScreen} 里，客户端不写死规则名单。
     */
    private void openDetail(ConfigRow row) {
        Screen detail = switch (row) {
            case ServerRuleDataRow dataRow -> new RuleDataDetailScreen(this, dataRow);
            case ConfigEntryType entry -> entry.getStyle() == StyleType.LIST
                    ? new ListDetailScreen(this, new EntryListSource(entry))
                    : new OptionDetailScreen(this, entry);
            default -> null;
        };
        if (detail != null) {
            this.minecraft.gui.setScreen(detail);
        }
    }

    /**
     * 选中的那项：前面加 {@code "> "}、后面加 {@code " <"}
     */
    private static Component selectedLabel(Component label, boolean selected) {
        return selected ? Component.literal("> ").append(label).append(" <") : label;
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
    public void tick() {
        // 滚动动画在 extractRenderState 里按帧推进（tick 只有 20Hz，放这儿会卡）
        // 搜索词变化 / 服务端规则状态更新 → 重建一次界面
        int version = ServerRuleClientState.getVersion();
        if (pendingRebuild || version != syncVersion) {
            pendingRebuild = false;
            syncVersion = version;
            if (searchBox != null && searchBox.isFocused()) {
                restoreSearchFocus = true;
            }
            rebuildWidgets();
        }
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        // 正在监听快捷键时，先把按键交给它（含 ESC：取消监听且不关闭界面）
        for (KeyBindButton button : keyBindButtons) {
            if (button.isListening()) {
                return button.keyPressed(event);
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(@NonNull KeyEvent event) {
        // 组合键要等所有键都松开才算绑定完成
        for (KeyBindButton button : keyBindButtons) {
            if (button.isListening()) {
                return button.keyReleased(event);
            }
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean doubleClick) {
        // 监听中左键点空白处 = 和 ESC 一样退出监听
        for (KeyBindButton button : keyBindButtons) {
            if (button.isListening() && !button.isMouseOver(event.x(), event.y())) {
                button.cancelListening();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 鼠标在二级分类那一行上时，滚轮翻分类
        if (subTabScrollingNeeded()
                && mouseY >= CONTENT_TOP && mouseY <= CONTENT_TOP + WIDGET_HEIGHT
                && mouseX >= contentX() && mouseX <= contentRight()) {
            scrollSubTabs(scrollY > 0 ? -1 : 1);
            return true;
        }
        // 鼠标在内容区时，滚轮平滑滚动配置项（一格滚 ROW_SCROLL_STEP 行）
        if (page.scrollingNeeded() && page.isMouseOverRows(mouseX, mouseY)) {
            page.scrollBy(scrollY > 0 ? -ROW_SCROLL_STEP : ROW_SCROLL_STEP);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        rememberSelection();
        this.minecraft.gui.setScreen(this.parent);
    }

    // ==============================================================
    // 绘制
    // ==============================================================

    @Override
    public void extractBackground(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        ScreenDecorations.drawSeparators(graphics, this.width, this.height);
    }

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float frameTicks) {
        // 先按这一帧的进度把行摆好，再画面板和控件（这样滚动是逐帧平滑的）
        page.advance(frameTicks);

        drawPanels(graphics);
        ScreenDecorations.drawTitle(graphics, this, this.title);
        // 二级分类和内容之间那条分割线
        ScreenDecorations.drawSeparatorLine(graphics, contentX(), CONTENT_TOP + HEADER_LINE_OFFSET, rowWidth());
        // 一页放满时，面板下面再补一条同款分割线收尾
        if (needsBottomSeparator()) {
            ScreenDecorations.drawSeparatorLine(graphics, contentX(), page.panelBottom(), rowWidth());
        }

        // 行控件自己画在一个「画布」里（见 RowListPage#drawRows）
        page.drawRows(graphics, mouseX, mouseY, frameTicks, null);

        // 其余控件（左栏、二级分类、搜索框、滚动条、页脚按钮）照旧交给原版渲染
        super.extractRenderState(graphics, mouseX, mouseY, frameTicks);
    }

    /**
     * 左侧分类栏的半透明面板（内容区那块由 {@link RowListPage#drawPanel} 画）
     */
    private void drawPanels(GuiGraphicsExtractor graphics) {
        // 左栏：搜索框 + 全部分类按钮（分类列表在 init 时算好，不用每帧去问服务器装没装 mod）
        int sidebarBottom = sidebarEntryY(visibleMains.size() - 1) + WIDGET_HEIGHT + PANEL_PADDING;
        graphics.fill(SIDEBAR_X - PANEL_PADDING, SIDEBAR_TOP - PANEL_PADDING,
                SIDEBAR_X + SIDEBAR_WIDTH + PANEL_PADDING, sidebarBottom, ComponentLayout.PANEL_COLOR);

        page.drawPanel(graphics);
    }

    /**
     * 要不要在面板下面再补一条收尾的分割线。
     *
     * <p>一页放满了（项数 ≥ 一页行数，例如 ≥ 8 条）列表就一直延伸到面板底边，
     * 需要像上面那条一样在末尾收个口；项少的时候面板本来就贴着最后一行结束，不用再加。
     */
    private boolean needsBottomSeparator() {
        return renderedRows.size() >= page.rowsPerPage();
    }
}
