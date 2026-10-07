package com.villagerdetails.client.setting.screen;

import com.villagerdetails.client.setting.component.*;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.config.ComponentLayout;
import com.villagerdetails.client.setting.network.ServerRuleClientReceiver;
import com.villagerdetails.client.setting.network.ServerRuleClientState;
import com.villagerdetails.client.setting.row.ClientEntryRow;
import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.client.setting.type.ConfigEntry;
import com.villagerdetails.client.setting.type.ConfigTabs;
import com.villagerdetails.client.setting.type.MainCategory;
import com.villagerdetails.client.setting.type.UiTab;
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

import static com.villagerdetails.client.setting.config.UiLayout.*;

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
 */
public class VillagerBedConfigScreen extends Screen {

    private final Screen parent;

    private MainCategory selectedMain = MainCategory.OPTIONS;
    private String selectedTabId;

    private List<UiTab> tabs = List.of();
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
     * 每一行的控件（重建界面时全部建好，靠滚动偏移决定摆在哪里 / 显不显示）
     */
    private final List<List<RowWidget>> rowWidgets = new ArrayList<>();

    /**
     * 一行里的一个控件，外加它<b>相对行顶的 y 偏移</b>。
     *
     * <p>光记控件是不够的：滚动时每帧都要按行位置重摆一遍，如果一律 {@code setY(行 y)}，
     * 那些故意往下挪过的控件（比如颜色块要垂直居中）就会被摆回行顶。
     * 以后要加别的需要偏移的控件，也在这里给偏移量，别在构造参数里加。
     */
    private record RowWidget(AbstractWidget widget, int yOffset) {
    }

    /**
     * 行内控件：贴着行顶（默认）
     */
    private static RowWidget rowWidget(AbstractWidget widget) {
        return new RowWidget(widget, 0);
    }

    /**
     * 行内控件：相对行顶往下挪 {@code yOffset} 像素
     */
    private static RowWidget rowWidget(AbstractWidget widget, int yOffset) {
        return new RowWidget(widget, yOffset);
    }

    /**
     * 本次重建里创建的「重置」按钮，值变化后统一刷新灰 / 亮
     */
    private final List<ResetButton> resetButtons = new ArrayList<>();

    /**
     * 底部的「全局重置」
     */
    private Button globalResetButton;

    /**
     * 内容行的滚动状态：滚动量单位是「行」，每帧朝目标平滑逼近（和列表详情页共用同一套）
     */
    private final RowScrollState rowScroll = new RowScrollState();

    /**
     * 内容区右侧的滚动条（内容不足一页时整条隐藏，那一列让给行控件）
     */
    private ScrollBar scrollBar;

    /**
     * 本次重建时算好的、实际可见的一级分类（面板高度要用，见 drawPanels）
     */
    private List<MainCategory> visibleMains = List.of();

    private AbstractWidget focusAfterInit;

    public VillagerBedConfigScreen(Screen parent) {
        super(Component.translatable("villagerdetails.ui.title"));
        this.parent = parent;
        this.syncVersion = ServerRuleClientState.getVersion();
        restoreSelection();

        // 打开界面时向服务器要一份最新的规则状态（服务器没装本 mod 时内部会直接忽略）
        ServerRuleClientReceiver.requestSync();
    }

    // ==============================================================
    // 上次停留的位置
    // ==============================================================

    private void restoreSelection() {
        ClientRenderConfig config = ClientRenderConfig.get();

        this.selectedMain = mainCategoryByName(config.getLastCategory());
        if (!MainCategory.visibleValues().contains(selectedMain)) {
            this.selectedMain = MainCategory.OPTIONS;
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
    private static MainCategory mainCategoryByName(String name) {
        if (name != null && !name.isEmpty()) {
            for (MainCategory constant : MainCategory.values()) {
                if (constant.name().equals(name)) {
                    return constant;
                }
            }
        }
        return MainCategory.OPTIONS;
    }

    // ==============================================================
    // 分类 / 行
    // ==============================================================

    private UiTab currentTab() {
        if (tabs.isEmpty()) {
            return null;
        }
        if (selectedTabId != null) {
            for (UiTab tab : tabs) {
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
     * 内容行的右边界。
     *
     * <p>需要滚动时会空出右侧滚动条那一列（滚动条宽 + 间距），所以行控件整体左移；
     * 一页放得下时不用滚动条，这一列就让给行控件，行宽和以前一样。
     */
    private int rowsRight() {
        return rowScrollingNeeded() ? contentRight() - SCROLLBAR_WIDTH - SCROLLBAR_GAP : contentRight();
    }

    /**
     * 滚动条所在的 x（贴着内容区右边界，仍在黑色面板里面）
     */
    private int scrollBarX() {
        return contentRight() - SCROLLBAR_WIDTH;
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

    private int rowY(int index) {
        return CONTENT_TOP + FIRST_ROW_OFFSET + index * ROW_HEIGHT;
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
    private void revealActiveSubTab(UiTab active) {
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

    // ---------------- 内容行的分页 / 平滑滚动 ----------------

    /**
     * 第一行内容的位置
     */
    private int rowsTop() {
        return CONTENT_TOP + FIRST_ROW_OFFSET;
    }

    /**
     * 内容区底部：页脚按钮上方留一点间距。只用来算「一页能放几行」，不是绘制边界
     */
    private int rowsBottom() {
        return footerButtonY(this.height) - ROW_SCROLL_MARGIN;
    }

    /**
     * 一页能放几行：受窗口高度和 {@code MAX_ROWS_PER_PAGE}（8 条）双重限制，至少 1 行。
     */
    private int rowsPerPage() {
        // 上限先兜一次底，免得 clamp 的 min > max 抛异常
        return Math.clamp((rowsBottom() - rowsTop()) / ROW_HEIGHT, 1, Math.max(1, MAX_ROWS_PER_PAGE));
    }

    /**
     * 这一页的可见区下边界。
     *
     * <p>它由「一页的行数」决定，所以一屏最多只会完整显示 {@link #rowsPerPage()} 行 ——
     * 窗口再高也不会多显示（多出来的行要滚动才上来），面板也按这个高度收边。
     */
    private int rowsViewportBottom() {
        return rowsTop() + rowsPerPage() * ROW_HEIGHT;
    }

    /**
     * 行控件的「画布」上边界：比第一行的位置再往上让出两行之间的那道空隙。
     *
     * <p>这样滚动时上面那半行能露出来，又不会画到分隔线（在 {@code CONTENT_TOP + HEADER_LINE_OFFSET}）
     * 上面去；停在整行位置时正好没有任何残留的边。
     */
    private int rowsCanvasTop() {
        return rowsTop() - ROW_GAP_Y;
    }

    /**
     * 行控件的「画布」下边界：就是内容面板的底边（一页放满时等于这一页的下边界）
     */
    private int rowsCanvasBottom() {
        return contentPanelBottom();
    }

    /**
     * 内容是否超过一页
     */
    private boolean rowScrollingNeeded() {
        return renderedRows.size() > rowsPerPage();
    }

    private void clampRowScroll() {
        rowScroll.clampTo(renderedRows.size(), rowsPerPage());
    }

    /**
     * 滚动指定行数（滚轮一次 ROW_SCROLL_STEP 行）
     */
    private void scrollRows(int deltaRows) {
        rowScroll.scrollBy(deltaRows, renderedRows.size(), rowsPerPage());
    }

    /**
     * 直接把滚动位置设到某一行，不做平滑动画。
     *
     * <p>拖滚动条 / 点滚动条轨道时用：这时候要求「手到哪、内容到哪」，动画反而会拖后腿。
     */
    private void setRowScroll(double rows) {
        rowScroll.set(rows, renderedRows.size(), rowsPerPage());
        updateRowPositions();
    }

    /**
     * 这一行现在该摆在哪个 y（带滚动偏移）。
     *
     * <p>滚动量的单位是「行」，换算成像素由 {@link RowScrollState#offsetPx(int)} 负责 ——
     * 别在别处直接拿它当像素用，否则滚到底也只能挪动几像素，下面的行永远上不来。
     */
    private float rowDrawY(int index) {
        return rowY(index) - rowScroll.offsetPx(ROW_HEIGHT);
    }

    /**
     * 这一行要不要画。
     *
     * <p>只要和这一页的画布有交集就画 —— 上下两端允许只露半个项，多出来的部分由
     * {@link #drawRows} 里的画布（scissor）裁掉。
     * 停在整行位置时，上下各正好不会有残留的半个项。
     */
    private boolean rowVisible(int index) {
        float y = rowDrawY(index);
        return y + WIDGET_HEIGHT > rowsCanvasTop() && y < rowsCanvasBottom();
    }

    /**
     * 把所有行控件按当前滚动偏移摆好，并把滚出可视区的行藏起来
     */
    private void updateRowPositions() {
        for (int i = 0; i < rowWidgets.size(); i++) {
            int y = Math.round(rowDrawY(i));
            boolean visible = rowVisible(i);
            for (RowWidget entry : rowWidgets.get(i)) {
                // 行顶 + 这个控件自己的偏移（色块之类的居中偏移就靠它保住）
                entry.widget().setY(y + entry.yOffset());
                entry.widget().visible = visible;
            }
        }
        updateScrollBar();
    }

    /**
     * 滚动条的显隐 / 位置。
     *
     * <p>内容超过一页才显示；不足一页时整条隐藏，它那一列由 {@link #rowsRight()} 还给行控件。
     * 注意 26.2 的 {@code setRectangle} 参数顺序是 (width, height, x, y)，所以这里全用显式 setter。
     */
    private void updateScrollBar() {
        if (scrollBar == null) {
            return;
        }
        boolean needed = rowScrollingNeeded();
        scrollBar.visible = needed;
        scrollBar.active = needed;
        scrollBar.setX(scrollBarX());
        scrollBar.setY(rowsTop());
        scrollBar.setWidth(SCROLLBAR_WIDTH);
        // 轨道高度 = 一页的高度，和右边这一列行的高度对齐
        scrollBar.setHeight(Math.max(1, rowsViewportBottom() - rowsTop()));
    }

    /**
     * 平滑滚动：每帧朝目标位置逼近一点。
     *
     * <p>以前这段是放在 {@code tick()} 里的 —— 但 tick 只有 20Hz，而界面是按帧（60~200Hz）画的，
     * 于是滚动位置每秒只变 20 次，看上去就是「一卡一卡」的跳跃。现在改到渲染阶段，
     * 按引擎传进来的「这一帧过去了多少 tick」推进（细节在 {@link RowScrollState#advance}）。
     *
     * @param frameTicks 这一帧经过了多少个 tick（60fps ≈ 0.33，20fps ≈ 1.0）
     */
    private void applyRowScroll(float frameTicks) {
        // 只有真的动了才重摆控件，动画停下来之后每帧什么都不做
        if (rowScroll.advance(frameTicks)) {
            updateRowPositions();
        }
    }

    // ==============================================================
    // 控件
    // ==============================================================

    @Override
    protected void init() {
        keyBindButtons.clear();
        resetButtons.clear();

        // 左侧：一级分类（服务器没装本 mod / 自己一条规则都没权限看时不会包含「服务器」）
        // 结果缓存起来：判断「服务器装没装 mod」要查网络通道，没必要每帧都查一遍
        this.visibleMains = MainCategory.visibleValues();
        // 权限可能在开着这个页面的时候被改掉（比如被下了 op）→ 服务端下次同步就一条规则都不发，
        // 「服务器」分类会跟着消失；这时直接退回第一个还能看的分类，别停在一个已经空掉的页面上
        if (!visibleMains.contains(selectedMain)) {
            selectedMain = visibleMains.getFirst();
            selectedTabId = null;
            subTabScroll = 0;
            subTabScrollNeedsReveal = true;
        }
        for (int i = 0; i < visibleMains.size(); i++) {
            MainCategory main = visibleMains.get(i);
            boolean selected = main == selectedMain;
            addRenderableWidget(Button.builder(selectedLabel(main.getDisplayName(), selected), button -> {
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
        UiTab active = currentTab();
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
            UiTab tab = tabs.get(subTabScroll + i);
            boolean selected = tab == active;
            addRenderableWidget(Button.builder(selectedLabel(tab.displayName(), selected), button -> {
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
            Button previous = Button.builder(Component.literal("◀"), button -> pageSubTabs(-1))
                    .bounds(subTabArrowX(0), CONTENT_TOP, SUBTAB_ARROW_WIDTH, WIDGET_HEIGHT)
                    .build();
            // 已经在最左边（没法再往左翻）就置灰
            previous.active = subTabScroll > 0;
            addRenderableWidget(previous);

            Button next = Button.builder(Component.literal("▶"), button -> pageSubTabs(1))
                    .bounds(subTabArrowX(1), CONTENT_TOP, SUBTAB_ARROW_WIDTH, WIDGET_HEIGHT)
                    .build();
            // 右边已经没有更多分类了就置灰
            next.active = subTabScroll + SUBTAB_MAX_VISIBLE < tabs.size();
            addRenderableWidget(next);
        }

        // 搜索框（全局，每个分类页都有）
        initSearchBox();

        // 内容行：一页最多 MAX_ROWS_PER_PAGE 行，塞不下就用滚轮 / 右侧滚动条（带平滑动画）
        // 注意行控件用的是 addWidget（只进 children）：它们统一由 drawRows 画在画布里
        this.renderedRows = filterRows(active == null ? List.of() : active.rows());
        rowWidgets.clear();
        for (int i = 0; i < renderedRows.size(); i++) {
            ConfigRow row = renderedRows.get(i);
            int y = rowY(i);
            List<RowWidget> widgets = new ArrayList<>();
            switch (row.getStyle()) {
                case TOGGLE -> addToggleRow(row, y, widgets);
                case COLOR -> addColorRow(row, y, widgets);
                case SLIDER -> addSliderRow(row, y, widgets);
                case LIST -> addListRow(row, y, widgets);
            }
            rowWidgets.add(widgets);
        }

        // 滚动条放在行控件之后添加，这样它画在行控件上面
        this.scrollBar = new ScrollBar(scrollBarX(), rowsTop(), SCROLLBAR_WIDTH,
                Math.max(1, rowsViewportBottom() - rowsTop()),
                () -> renderedRows.size(), this::rowsPerPage,
                () -> (double) rowScroll.scroll(), this::setRowScroll);
        addRenderableWidget(scrollBar);

        clampRowScroll();
        updateRowPositions();

        // 底部：全局重置 + 完成（全都还是默认值时「全局重置」置灰）
        this.globalResetButton = Button.builder(Component.translatable("villagerdetails.ui.reset_all"), button -> {
                    ClientRenderConfig.get().resetToDefaults();
                    rebuildWidgets();
                })
                .bounds(footerLeftButtonX(this.width), footerButtonY(this.height),
                        FOOTER_BUTTON_WIDTH, WIDGET_HEIGHT)
                .build();
        globalResetButton.active = ClientRenderConfig.get().hasNonDefaultValues();
        addRenderableWidget(globalResetButton);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
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
    private void addToggleRow(ConfigRow row, int y, List<RowWidget> out) {
        out.add(rowWidget(addWidget(labelWidget(row, y))));

        List<AbstractWidget> switches = RuleSwitchGroup.build(row, this.font, resetX() - WIDGET_GAP, y, WIDGET_HEIGHT);
        if (switches.isEmpty()) {
            int switchX = row.hasKeyBind() ? keyBindX() - WIDGET_GAP - SWITCH_WIDTH : plainSwitchX();
            switches = List.of(ToggleSwitchButton.create(switchX, y, SWITCH_WIDTH, WIDGET_HEIGHT, row, this::rebuildWidgets));
        }
        for (AbstractWidget widget : switches) {
            out.add(rowWidget(addWidget(widget)));
        }

        if (row.hasKeyBind() && row instanceof ClientEntryRow clientRow) {
            // 绑完（或按 ESC 清空）立刻落盘，别等到关界面才存
            KeyBindButton bindButton = KeyBindButton.create(keyBindX(), y, KEYBIND_WIDTH, WIDGET_HEIGHT,
                    clientRow.entry(), keys -> {
                        row.setKeyBind(keys);
                        row.save();
                    });
            keyBindButtons.add(bindButton);
            out.add(rowWidget(addWidget(bindButton)));
        }

        out.add(rowWidget(addWidget(createResetButton(row, y))));
    }

    /**
     * 颜色行：标签 色块 [ #RRGGBB ] [重置]
     *
     * <p>点颜色值按钮进详情页（RGB 滑条 + 可输入的色值框）；重置按钮恢复默认色。
     *
     * <p>色块比按钮矮，靠  往下挪，让两个的中心线对齐 ——
     * 这个偏移必须通过 {@link #rowWidget(AbstractWidget, int)} 登记，
     * 否则滚动重摆时会被摆回行顶（那就变成贴着上边了）。
     */
    private void addColorRow(ConfigRow row, int y, List<RowWidget> out) {
        out.add(rowWidget(addWidget(labelWidget(row, y))));

        if (row instanceof ClientEntryRow(ConfigEntry entry)) {
            int swatchX = coloredValueX() - WIDGET_GAP - SWATCH_WIDTH;
            out.add(rowWidget(addWidget(new ColorSwatch(swatchX, y + SWATCH_Y_OFFSET,
                    SWATCH_WIDTH, SWATCH_HEIGHT, row::getColor)), SWATCH_Y_OFFSET));

            out.add(rowWidget(addWidget(Button.builder(colorHex(row.getColor()),
                            button -> openDetail(entry))
                    .bounds(coloredValueX(), y, VALUE_WIDTH, WIDGET_HEIGHT)
                    .build())));
        }

        out.add(rowWidget(addWidget(createResetButton(row, y))));
    }

    /**
     * 滑块行：标签 [⚙] [滑条 / 数值输入框] [重置]
     *
     * <p>按 ⚙ 在「滑条」和「直接填数值」之间切换；重置按钮恢复默认值。
     */
    private void addSliderRow(ConfigRow row, int y, List<RowWidget> out) {
        out.add(rowWidget(addWidget(labelWidget(row, y))));
        boolean editing = numericEditing.contains(row.getId());

        out.add(rowWidget(addWidget(SettingWidgets.gear(gearWithResetX(), y, GEAR_SIZE, WIDGET_HEIGHT, () -> {
            if (numericEditing.contains(row.getId())) {
                numericEditing.remove(row.getId());
                row.save();
            } else {
                numericEditing.add(row.getId());
            }
            rebuildWidgets();
        }))));

        if (editing) {
            EditBox box = SettingWidgets.numberBox(coloredValueX(), y, VALUE_WIDTH, WIDGET_HEIGHT, row, this::refreshResetButtons);
            out.add(rowWidget(addWidget(box)));
            this.focusAfterInit = box;
        } else {
            out.add(rowWidget(addWidget(new ValueSlider(coloredValueX(), y, VALUE_WIDTH, WIDGET_HEIGHT,
                    row.minNumber(), row.maxNumber(), row.stepNumber(),
                    row.getNumber(), row::setNumber, row::formatNumber, () -> {
                row.save();
                refreshResetButtons();
            }))));
        }

        out.add(rowWidget(addWidget(createResetButton(row, y))));
    }

    /**
     * 列表行：标签 [ 值列表 ] [重置]
     *
     * <p>按钮上显示这一项当前的值，例如 {@code [a,b]}（最多预览两个）、没有值就是 {@code []}；
     * 点进去是列表的详情页（可以增删值）。
     */
    private void addListRow(ConfigRow row, int y, List<RowWidget> out) {
        out.add(rowWidget(addWidget(labelWidget(row, y))));
        if (row instanceof ClientEntryRow clientRow) {
            ConfigEntry entry = clientRow.entry();
            out.add(rowWidget(addWidget(Button.builder(entry.listDisplay(),
                            button -> openDetail(entry))
                    .bounds(coloredValueX(), y, VALUE_WIDTH, WIDGET_HEIGHT)
                    .build())));
        }
        out.add(rowWidget(addWidget(createResetButton(row, y))));
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
     * 进详情页：颜色项看 RGB 滑条那一页，列表项看列表那一页
     */
    private void openDetail(ConfigEntry entry) {
        Screen detail = switch (entry.style()) {
            case LIST -> new ListDetailScreen(this, entry);
            default -> new OptionDetailScreen(this, entry);
        };
        this.minecraft.gui.setScreen(detail);
    }


    private static Component colorHex(int rgb) {
        return Component.literal(String.format(Locale.ROOT, "#%06X", rgb & ClientRenderConfig.RGB_MASK));
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
        if (rowScrollingNeeded()
                && mouseY >= rowsTop() && mouseY <= rowsViewportBottom()
                && mouseX >= contentX() && mouseX <= contentRight()) {
            scrollRows((scrollY > 0 ? -ROW_SCROLL_STEP : ROW_SCROLL_STEP));
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
        applyRowScroll(frameTicks);

        drawPanels(graphics);
        ScreenDecorations.drawTitle(graphics, this, this.title);
        // 二级分类和内容之间那条分割线
        ScreenDecorations.drawSeparatorLine(graphics, contentX(), CONTENT_TOP + HEADER_LINE_OFFSET, rowWidth());
        // 一页放满时，面板下面再补一条同款分割线收尾
        if (needsBottomSeparator()) {
            ScreenDecorations.drawSeparatorLine(graphics, contentX(), contentPanelBottom(), rowWidth());
        }

        // 行控件自己画在一个「画布」里（见 drawRows）
        drawRows(graphics, mouseX, mouseY, frameTicks);

        // 其余控件（左栏、二级分类、搜索框、滚动条、页脚按钮）照旧交给原版渲染
        super.extractRenderState(graphics, mouseX, mouseY, frameTicks);
    }

    /**
     * 把内容行画在「一页」这块画布里。
     *
     * <p>行控件是用 {@code addWidget} 加的（只进 children，不进 renderables），所以不会被
     * {@code super.extractRenderState} 再画一遍，而是统一在这里画：先开 scissor 当画布，
     * 再让每一行自己渲染。滚动时上下两端那半个项照常渲染，超出画布的部分直接被裁掉 ——
     * 看起来就是「列表被一个方框挡住」，而不是整行突然消失。
     */
    private void drawRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float frameTicks) {
        graphics.enableScissor(rowsCanvasLeft(), rowsCanvasTop(), rowsCanvasRight(), rowsCanvasBottom());
        try {
            for (List<RowWidget> row : rowWidgets) {
                for (RowWidget entry : row) {
                    // visible=false 的行会自动跳过（内部判断），这里不用再筛
                    entry.widget().extractRenderState(graphics, mouseX, mouseY, frameTicks);
                }
            }
        } finally {
            graphics.disableScissor();
        }
    }

    /**
     * 左侧分类栏和右侧内容区的半透明面板（纯色块，不依赖材质）
     */
    private void drawPanels(GuiGraphicsExtractor graphics) {
        // 左栏：搜索框 + 全部分类按钮（分类列表在 init 时算好，不用每帧去问服务器装没装 mod）
        int sidebarBottom = sidebarEntryY(visibleMains.size() - 1) + WIDGET_HEIGHT + PANEL_PADDING;
        graphics.fill(SIDEBAR_X - PANEL_PADDING, SIDEBAR_TOP - PANEL_PADDING,
                SIDEBAR_X + SIDEBAR_WIDTH + PANEL_PADDING, sidebarBottom, ComponentLayout.PANEL_COLOR);

        // 内容区：面板高度按「实际画出来的行数」收边（一页最多 rowsPerPage() 行）
        graphics.fill(contentPanelLeft(), CONTENT_TOP - PANEL_PADDING,
                contentPanelRight(), contentPanelBottom(), ComponentLayout.PANEL_COLOR);
    }

    /**
     * 内容面板的下边缘（行数不足一页时就贴着最后一行收边）。
     *
     * <p>一页放满时它正好等于 {@link #rowsViewportBottom()}，也就是行画布的下边界。
     */
    private int contentPanelBottom() {
        // 空列表也要撑出一行的位置，所以下限是 1；上限先兜一次底，免得 clamp 的 min > max 抛异常
        int perPage = Math.max(1, rowsPerPage());
        int shown = Math.clamp(renderedRows.size(), 1, perPage);
        return rowY(shown - 1) + WIDGET_HEIGHT + PANEL_PADDING;
    }

    /**
     * 要不要在面板下面再补一条收尾的分割线。
     *
     * <p>一页放满了（项数 ≥ 一页行数，例如 ≥ 8 条）列表就一直延伸到面板底边，
     * 需要像上面那条一样在末尾收个口；项少的时候面板本来就贴着最后一行结束，不用再加。
     */
    private boolean needsBottomSeparator() {
        return renderedRows.size() >= rowsPerPage();
    }

    /**
     * 内容面板左边缘：行控件画布的左边（比内容区再往外扩一点，和面板对齐）
     */
    private int contentPanelLeft() {
        return contentX() - PANEL_PADDING;
    }

    /**
     * 内容面板右边缘：至少离屏幕右边 PANEL_RIGHT_MARGIN
     */
    private int contentPanelRight() {
        return Math.min(contentRight() + PANEL_PADDING, this.width - PANEL_RIGHT_MARGIN);
    }

    /**
     * 行画布的左边界同上
     */
    private int rowsCanvasLeft() {
        return contentPanelLeft();
    }

    /**
     * 行画布的右边界同上（滚动条在画布外面单独渲染，所以不会被裁）
     */
    private int rowsCanvasRight() {
        return contentPanelRight();
    }
}
