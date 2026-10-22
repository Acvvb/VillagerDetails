package com.villagerdetails.client.setting.type;

import com.villagerdetails.client.network.requests.ClientRequests;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.client.setting.row.ListPreview;
import com.villagerdetails.client.setting.type.server.BindingServer;
import com.villagerdetails.client.setting.type.style.StyleType;
import com.villagerdetails.client.setting.util.ColorUtil;
import com.villagerdetails.client.setting.util.InputCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 所有配置项。写法参考
 * <b>显示名称、显示描述、默认值、行样式</b>都在枚举常量上声明，不再散落在各处。
 *
 * <p>每个常量依次声明：
 * <ol>
 *     <li>{@code registerName}：注册名（配置里存值、快捷键等用它）</li>
 *     <li>{@code displayName}：显示名称的翻译键</li>
 *     <li>{@code displayInfo}：显示描述的翻译键</li>
 *     <li>{@code firstCategoryType} / {@code secondCategoryType}：所属一级 / 二级分类（只管「在哪一栏」）</li>
 *     <li>{@code state}：默认值 —— 开关写 {@code true/false}；滑块写数值；颜色写 {@code #RRGGBB}；列表留空</li>
 *     <li>{@code defaultKeyBind}：默认快捷键（{@code "88+67"} 这种按键码组合，空串 = 未绑定）</li>
 *     <li>{@code bindingServer}：行样式 + 数值范围（由 {@link BindingServer} 提供）</li>
 * </ol>
 *
 * <p>枚举自己实现 {@link ConfigRow}，配置界面直接把它当行用 ——
 * 不需要再套一层「把 entry 转成 row」的转发包装。
 *
 * <p>运行时值一律按 {@code registerName} 存进 {@link ClientRenderConfig} 的通用 map，
 * 所以本枚举里没有任何 {@code switch(this)}：新增一个配置项 = 加一行常量。
 */
public enum ConfigEntryType implements ConfigRow {

    // ---------------- 配置项 / 颜色 ----------------

    /**
     * 村民「近处」状态的颜色（距离玩家近时用的那档）
     */
    NEAR_COLOR("NearColor",
            "villagerdetails.ui.option.near_color", "villagerdetails.ui.option.near_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#54E36B", "",
            BindingServer.color()),

    /**
     * 村民「远处」状态的颜色（超出近处距离后用的那档）
     */
    FAR_COLOR("FarColor",
            "villagerdetails.ui.option.far_color", "villagerdetails.ui.option.far_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#FF4D4D", "",
            BindingServer.color()),

    /**
     * 当前选中实体的描边颜色
     */
    SELECTED_ENTITY_COLOR("SelectedEntityColor",
            "villagerdetails.ui.option.selected_entity_color",
            "villagerdetails.ui.option.selected_entity_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#FFD54F", "",
            BindingServer.color()),

    /**
     * 当前选中方块的描边颜色
     */
    SELECTED_BLOCK_COLOR("SelectedBlockColor",
            "villagerdetails.ui.option.selected_block_color",
            "villagerdetails.ui.option.selected_block_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#4FC3F7", "",
            BindingServer.color()),

    /**
     * BB（两点选区）第一个角点的颜色
     */
    BB_POS1_COLOR("BBPos1Color",
            "villagerdetails.ui.option.bb_pos1_color", "villagerdetails.ui.option.bb_pos1_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#4FC3F7", "",
            BindingServer.color()),

    /**
     * BB（两点选区）第二个角点的颜色
     */
    BB_POS2_COLOR("BBPos2Color",
            "villagerdetails.ui.option.bb_pos2_color", "villagerdetails.ui.option.bb_pos2_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#FFD54F", "",
            BindingServer.color()),

    /**
     * BB 盒子（由两个角点围成的区域）的描边颜色
     */
    BB_BOX_COLOR("BBBoxColor",
            "villagerdetails.ui.option.bb_box_color", "villagerdetails.ui.option.bb_box_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#9C27B0", "",
            BindingServer.color()),

    /**
     * 传送门绑定「高亮」时主世界那侧的颜色。
     * 和服务端推下来的两个维度一一对应，所以主世界 / 地狱各占一个配置项。
     */
    PORTAL_VIEW_OW_COLOR("PortalViewOwColor",
            "villagerdetails.ui.option.portal_view_ow_color", "villagerdetails.ui.option.portal_view_ow_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#4FC3F7", "",
            BindingServer.color()),

    /**
     * 传送门绑定「高亮」时地狱那侧的颜色
     */
    PORTAL_VIEW_NE_COLOR("PortalViewNeColor",
            "villagerdetails.ui.option.portal_view_ne_color", "villagerdetails.ui.option.portal_view_ne_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#FF4D4D", "",
            BindingServer.color()),

    /**
     * 熔炉脏高亮：输入槽里有烧不了的东西
     */
    FURNACE_DIRTY_COLOR("FurnaceDirtyColor",
            "villagerdetails.ui.option.furnace_dirty_color",
            "villagerdetails.ui.option.furnace_dirty_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#FF4D4D", "",
            BindingServer.color()),

    /**
     * 「村民 → 工作站点」连线的颜色
     */
    WORKSTATION_LINE_COLOR("WorkstationLineColor",
            "villagerdetails.ui.option.workstation_line_color",
            "villagerdetails.ui.option.workstation_line_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#FFAA00", "",
            BindingServer.color()),

    /**
     * 被绑定方块（床 / 工作站点）上那个单方块高亮框的颜色。
     *
     * <p>刻意独立于连线颜色：连线表达「谁连着谁」，这个框只是把方块本身标出来，
     * 和 BB 选区里选中单个方块时的那个小方框是同一种画法。
     */
    BOUND_BLOCK_BOX_COLOR("BoundBlockBoxColor",
            "villagerdetails.ui.option.bound_block_box_color",
            "villagerdetails.ui.option.bound_block_box_color.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_COLOR), "#FFFFFF", "",
            BindingServer.color()),

    // ---------------- 配置项 / 滑块 ----------------

    /**
     * 普通连线（村民到床等）的线宽
     */
    LINE_WIDTH("LineWidth",
            "villagerdetails.ui.option.line_width", "villagerdetails.ui.option.line_width.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_SLIDER), "4.0", "",
            BindingServer.slider(1.0D, 10.0D, 0.5D)),

    /**
     * 村民距离超过多少格算「远处」（切换成远处颜色）
     */
    FAR_DISTANCE("FarDistance",
            "villagerdetails.ui.option.far_distance", "villagerdetails.ui.option.far_distance.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_SLIDER), "5.0", "",
            BindingServer.slider(0.0D, 64.0D, 0.5D)),

    /**
     * BB 选区高亮的线宽
     */
    BB_HIGHLIGHT_LINE_WIDTH("BBHighlightLineWidth",
            "villagerdetails.ui.option.bb_highlight_line_width",
            "villagerdetails.ui.option.bb_highlight_line_width.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_SLIDER), "2.0", "",
            BindingServer.slider(1.0D, 10.0D, 0.5D)),

    /**
     * EB（实体边界）高亮的线宽
     */
    EB_HIGHLIGHT_LINE_WIDTH("EBHighlightLineWidth",
            "villagerdetails.ui.option.eb_highlight_line_width",
            "villagerdetails.ui.option.eb_highlight_line_width.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_SLIDER), "2.0", "",
            BindingServer.slider(1.0D, 10.0D, 0.5D)),

    /**
     * 传送门绑定高亮的线宽
     */
    PORTAL_HIGHLIGHT_LINE_WIDTH("PortalHighlightLineWidth",
            "villagerdetails.ui.option.portal_highlight_line_width",
            "villagerdetails.ui.option.portal_highlight_line_width.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_SLIDER), "2.0", "",
            BindingServer.slider(1.0D, 10.0D, 0.5D)),

    /**
     * 高亮区域相对方块边界向外扩出的距离（格），避免描边和方块面重合
     */
    HIGHLIGHT_REGION_OUTSET("HighlightRegionOutset",
            "villagerdetails.ui.option.highlight_region_outset",
            "villagerdetails.ui.option.highlight_region_outset.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_SLIDER), "0.15", "",
            BindingServer.slider(0.0D, 0.5D, 0.05D)),

    /**
     * 熔炉脏高亮的线宽
     */
    FURNACE_DIRTY_LINE_WIDTH("FurnaceDirtyLineWidth",
            "villagerdetails.ui.option.furnace_dirty_line_width",
            "villagerdetails.ui.option.furnace_dirty_line_width.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_SLIDER), "2.0", "",
            BindingServer.slider(1.0D, 10.0D, 0.5D)),

    /**
     * 被绑定方块（床 / 工作站点）高亮框的线宽。
     *
     * <p>和 {@link #LINE_WIDTH}（连线粗细）分开：框是「标出方块」，通常要比线细一点，
     * 不然一眼看过去全是框。
     */
    BOUND_BLOCK_BOX_LINE_WIDTH("BoundBlockBoxLineWidth",
            "villagerdetails.ui.option.bound_block_box_line_width",
            "villagerdetails.ui.option.bound_block_box_line_width.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_SLIDER), "1.5", "",
            BindingServer.slider(1.0D, 10.0D, 0.5D)),

    // ---------------- 配置项 / 列表 ----------------

    /**
     * 床列表：记录哪些坐标放过床，用于画床连线
     */
    BED_LIST("BedList",
            "villagerdetails.ui.option.bed_list", "villagerdetails.ui.option.bed_list.desc",
            List.of(FirstCategoryType.OPTIONS), List.of(SecondCategoryType.OPTION_LIST), "", "",
            BindingServer.list()),

    // ---------------- 功能开关 / 村民 ----------------

    /**
     * 是否渲染「村民 → 床」的连线
     */
    RENDER_BED_LINE("RenderBedLine",
            "villagerdetails.ui.option.render_bed_line", "villagerdetails.ui.option.render_bed_line.desc",
            List.of(FirstCategoryType.TOGGLES), List.of(SecondCategoryType.TOGGLE_VILLAGER,SecondCategoryType.TOGGLE_SWITCH), "false", "",
            BindingServer.toggle()),

    /**
     * 是否渲染「村民 → 工作站点」的连线
     */
    RENDER_WORKSTATION_LINE("RenderWorkstationLine",
            "villagerdetails.ui.option.render_workstation_line",
            "villagerdetails.ui.option.render_workstation_line.desc",
            List.of(FirstCategoryType.TOGGLES), List.of(SecondCategoryType.TOGGLE_VILLAGER,SecondCategoryType.TOGGLE_SWITCH), "false", "",
            BindingServer.toggle()),

    /**
     * 是否渲染选中实体 / 方块的描边高亮
     */
    RENDER_SELECTION_HIGHLIGHT("RenderSelectionHighlight",
            "villagerdetails.ui.option.render_selection_highlight",
            "villagerdetails.ui.option.render_selection_highlight.desc",
            List.of(FirstCategoryType.TOGGLES), List.of(SecondCategoryType.TOGGLE_VILLAGER,SecondCategoryType.TOGGLE_SWITCH), "false", "",
            BindingServer.toggle()),

    /**
     * 是否渲染 BB 选区（两点 + 盒子）的高亮
     */
    RENDER_BB_HIGHLIGHT("RenderBBHighlight",
            "villagerdetails.ui.option.render_bb_highlight",
            "villagerdetails.ui.option.render_bb_highlight.desc",
            List.of(FirstCategoryType.TOGGLES), List.of(SecondCategoryType.TOGGLE_VILLAGER,SecondCategoryType.TOGGLE_SWITCH), "false", "",
            BindingServer.toggle()),

    /**
     * 熔炉脏高亮的本地显示开关。
     *
     * <p>关掉不只是不画：客户端会通过通用的 {@code sync_subscription} 包告诉服务端
     * 「这条通道别发给我」，服务端随即停止扫描和发包；重新打开时再通知一次，恢复同步。
     */
    RENDER_FURNACE_DIRTY("RenderFurnaceDirty",
            "villagerdetails.ui.option.render_furnace_dirty",
            "villagerdetails.ui.option.render_furnace_dirty.desc",
            List.of(FirstCategoryType.TOGGLES), List.of(SecondCategoryType.TOGGLE_ALL,SecondCategoryType.TOGGLE_SWITCH), "false", "",
            BindingServer.toggle()),

    // ---------------- 功能触发 / 选区 ----------------

    /**
     * 清除 BB 选区（按钮 / 快捷键触发，向服务端发清除请求）
     */
    CLEAR_BB_SELECTION("ClearBBSelection",
            "villagerdetails.ui.option.clear_bb_selection",
            "villagerdetails.ui.option.clear_bb_selection.desc",
            List.of(FirstCategoryType.TOGGLES), List.of(SecondCategoryType.TOGGLE_VILLAGER, SecondCategoryType.TOGGLE_SELECTION), "", "",
            BindingServer.trigger(ClientRequests.INSTANCE::sendBBClear)),

    /**
     * 清除 EB（实体边界）选区
     */
    CLEAR_EB_SELECTION("ClearEBSelection",
            "villagerdetails.ui.option.clear_eb_selection",
            "villagerdetails.ui.option.clear_eb_selection.desc",
            List.of(FirstCategoryType.TOGGLES), List.of(SecondCategoryType.TOGGLE_VILLAGER, SecondCategoryType.TOGGLE_SELECTION), "", "",
            BindingServer.trigger(ClientRequests.INSTANCE::sendEBClear)),

    /**
     * 清除传送门视图（取消当前高亮的传送门绑定）
     */
    CLEAR_PORTAL_VIEW("ClearPortalView",
            "villagerdetails.ui.option.clear_portal_view",
            "villagerdetails.ui.option.clear_portal_view.desc",
            List.of(FirstCategoryType.TOGGLES), List.of(SecondCategoryType.TOGGLE_SELECTION), "", "",
            BindingServer.trigger(ClientRequests.INSTANCE::sendPortalViewClear)),

    ;

    /** 注册名：配置里存值、快捷键映射、NBT/JSON 键都用它，改名等于换配置项 */
    private final String registerName;

    /** 显示名称的翻译键（如 {@code villagerdetails.ui.option.near_color}） */
    private final String displayName;

    /** 显示描述的翻译键（悬停提示 / 详情页用） */
    private final String displayInfo;

    /**
     * 所属一级分类（可属于多个）。
     *
     * <p>只决定「显示在哪一栏」，不影响行样式；行样式由 {@link #bindingServer} 决定。
     */
    private final List<FirstCategoryType> firstCategoryTypes;

    /**
     * 所属二级分类（可属于多个）。
     *
     * <p>同上，只决定「显示在哪一栏」；一个配置项可以同时挂在多个二级分类下
     * （例如 {@code CLEAR_BB_SELECTION} 同时属于村民和选区两组）。
     */
    private final List<SecondCategoryType> secondCategoryTypes;

    /**
     * 默认值原文（字符串形式，和 {@code RuleType} 的 state 同义）。
     *
     * <ul>
     *     <li>开关：{@code "true"} / {@code "false"}</li>
     *     <li>滑块：数值原文，如 {@code "4.0"}</li>
     *     <li>颜色：{@code "#RRGGBB"}</li>
     *     <li>列表：空串</li>
     *     <li>触发按钮：空串</li>
     * </ul>
     *
     * <p>注意这是<b>默认值</b>，不是运行时值；运行时值一律按 {@link #registerName}
     * 存在 {@code ClientRenderConfig} 的通用 map 里。
     */
    private final String state;

    /**
     * 默认快捷键原文：GLFW keycode 用 {@code +} 连接，如 {@code "88+67"}；空串 = 未绑定。
     *
     * <p>解析由 {@code ClientRenderConfig.parseKeys} 负责；只有
     * {@link StyleType#TOGGLE} / {@link StyleType#TRIGGER} 两类行才会用到。
     */
    private final String defaultKeyBind;

    /**
     * 行样式 + 数值范围 + 触发动作的载体。
     *
     * <p>由 {@link BindingServer} 的工厂方法（{@code color()} / {@code slider(min,max,step)} /
     * {@code toggle()} / {@code trigger(action)} / {@code list()}）构造，
     * {@link #getStyle()}、{@link #minNumber()}、{@link #maxNumber()}、{@link #stepNumber()}、
     * {@link #getAction()} 全部转发给它。
     */
    private final BindingServer bindingServer;

    /**
     * @param registerName       注册名，见 {@link #registerName}
     * @param displayName        显示名称翻译键，见 {@link #displayName}
     * @param displayInfo        显示描述翻译键，见 {@link #displayInfo}
     * @param firstCategoryTypes 一级分类列表，见 {@link #firstCategoryTypes}
     * @param secondCategoryTypes 二级分类列表，见 {@link #secondCategoryTypes}
     * @param state              默认值原文，见 {@link #state}
     * @param defaultKeyBind     默认快捷键原文，见 {@link #defaultKeyBind}
     * @param bindingServer      行样式与数值范围，见 {@link #bindingServer}
     */
    ConfigEntryType(String registerName, String displayName, String displayInfo,
                    List<FirstCategoryType> firstCategoryTypes, List<SecondCategoryType> secondCategoryTypes,
                    String state, String defaultKeyBind, BindingServer bindingServer) {
        this.registerName = registerName;
        this.displayName = displayName;
        this.displayInfo = displayInfo;
        this.firstCategoryTypes = firstCategoryTypes;
        this.secondCategoryTypes = secondCategoryTypes;
        this.state = state;
        this.defaultKeyBind = defaultKeyBind;
        this.bindingServer = bindingServer;
    }

    // ==============================================================
    // 信息显示（显示名 / 显示描述）
    // ==============================================================

    @Override
    public String getRegisterName() {
        return registerName;
    }

    public String getDisplayNameKey() {
        return displayName;
    }

    public String getDisplayInfoKey() {
        return displayInfo;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(displayName);
    }

    @Override
    public Component getDisplayInfo() {
        return Component.translatable(displayInfo);
    }

    public List<FirstCategoryType> getMainCategories() {
        return firstCategoryTypes;
    }

    public List<SecondCategoryType> getSubCategories() {
        return secondCategoryTypes;
    }

    /**
     * 行样式：由自己的 {@link BindingServer} 声明，不再跟随二级分类
     */
    @Override
    public StyleType getStyle() {
        return bindingServer.style();
    }

    /**
     * 开关样式和触发样式都有快捷键绑定框
     */
    @Override
    public boolean hasKeyBind() {
        StyleType style = bindingServer.style();
        return style == StyleType.TOGGLE || style == StyleType.TRIGGER;
    }

    /**
     * 触发按钮点击 / 快捷键按下时执行的动作（由 {@link BindingServer} 携带）
     */
    @Override
    public Runnable getAction() {
        return bindingServer.action();
    }

    // ==============================================================
    // 默认值（全部在枚举常量上声明）
    // ==============================================================

    /**
     * 默认值原文（和 RuleType 的 state 一个意思）
     */
    public String getState() {
        return state;
    }

    /**
     * 默认快捷键原文
     */
    public String getDefaultKeyBind() {
        return defaultKeyBind;
    }

    public boolean defaultToggle() {
        return Boolean.parseBoolean(state);
    }

    public double defaultNumber() {
        try {
            return Double.parseDouble(state);
        } catch (NumberFormatException e) {
            return minNumber();
        }
    }

    public int[] defaultKeyBindKeys() {
        return ClientRenderConfig.parseKeys(defaultKeyBind);
    }

    // ==============================================================
    // 取值 / 赋值 —— 全部走 config 的通用 map，不再 switch(this)
    // ==============================================================

    @Override
    public boolean getToggle() {
        return getToggle(ClientRenderConfig.get());
    }

    public boolean getToggle(ClientRenderConfig config) {
        return Boolean.parseBoolean(config.getRaw(registerName));
    }

    @Override
    public void setToggle(boolean value) {
        setToggle(ClientRenderConfig.get(), value);
    }

    public void setToggle(ClientRenderConfig config, boolean value) {
        config.setRaw(registerName, Boolean.toString(value));
    }

    @Override
    public double getNumber() {
        return getNumber(ClientRenderConfig.get());
    }

    public double getNumber(ClientRenderConfig config) {
        try {
            return Double.parseDouble(config.getRaw(registerName));
        } catch (NumberFormatException e) {
            return defaultNumber();
        }
    }

    @Override
    public void setNumber(double value) {
        setNumber(ClientRenderConfig.get(), value);
    }

    public void setNumber(ClientRenderConfig config, double value) {
        config.setRaw(registerName, Double.toString(value));
    }

    @Override
    public double minNumber() {
        return bindingServer.minNumber();
    }

    @Override
    public double maxNumber() {
        return bindingServer.maxNumber();
    }

    @Override
    public double stepNumber() {
        return bindingServer.stepNumber();
    }

    /**
     * 滑条的显示文本，例如「粗细：4.0」（翻译键 = 显示名 + {@code .value}）。
     *
     * <p>小数位跟着步进走：步进小于 0.1（如外扩的 0.05）时用两位小数，
     * 否则 0.15 会被 {@code %.1f} 显示成 0.2。
     */
    @Override
    public Component formatNumber(double value) {
        int decimals = stepNumber() < 0.1D ? 2 : 1;
        return Component.translatable(displayName + ".value",
                String.format(Locale.ROOT, "%." + decimals + "f", value));
    }

    @Override
    public int getColor() {
        return getColor(ClientRenderConfig.get());
    }

    public int getColor(ClientRenderConfig config) {
        return ColorUtil.parseRgb(config.getRaw(registerName));
    }

    public void setColor(int rgb) {
        setColor(ClientRenderConfig.get(), rgb);
    }

    public void setColor(ClientRenderConfig config, int rgb) {
        config.setRaw(registerName, ColorUtil.formatRgb(rgb));
    }

    // ==============================================================
    // 快捷键绑定（开关样式用）
    // ==============================================================

    /**
     * 绑定的按键组合（GLFW keycode 数组），空数组表示未绑定
     */
    @Override
    public int[] getKeyBind() {
        return getKeyBind(ClientRenderConfig.get());
    }

    public int[] getKeyBind(ClientRenderConfig config) {
        return config.getKeyBind(registerName);
    }

    @Override
    public void setKeyBind(int[] keys) {
        setKeyBind(ClientRenderConfig.get(), keys);
    }

    public void setKeyBind(ClientRenderConfig config, int[] keys) {
        config.setKeyBind(registerName, keys);
    }

    public void clearKeyBind() {
        ClientRenderConfig config = ClientRenderConfig.get();
        config.clearKeyBind(registerName);
        config.save();
    }

    /**
     * 把按键组合显示成 {@code X + C}；空组合显示「未绑定」
     */
    public static Component displayKeys(int[] keys) {
        if (keys == null || keys.length == 0) {
            return Component.translatable("villagerdetails.ui.keybind.empty");
        }
        MutableComponent text = Component.empty();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                text.append(Component.literal(" + "));
            }
            text.append(InputCompat.keyboard().getOrCreate(keys[i]).getDisplayName());
        }
        return text;
    }

    // ==============================================================
    // 列表值（列表类配置项用，例如床列表）
    // ==============================================================

    /**
     * 完整的值列表（只读快照）
     */
    public List<String> getList() {
        return ClientRenderConfig.get().getList(registerName);
    }

    /**
     * 整份替换这一项的值列表（列表详情页按「完成」时调；空列表 = 清空）
     */
    public void setList(List<String> values) {
        setList(ClientRenderConfig.get(), values);
    }

    public void setList(ClientRenderConfig config, List<String> values) {
        config.setList(registerName, values);
    }

    /**
     * 行上那个按钮显示的文字，例如 {@code [a,b]}（最多预览两个，还有更多就补个 {@code …}）
     */
    public Component listDisplay() {
        return ListPreview.of(getList());
    }

    // ==============================================================
    // 重置 / 落盘
    // ==============================================================

    /**
     * 把这一项恢复成枚举里声明的默认值（含默认快捷键），不落盘
     */
    public void applyDefaultTo(ClientRenderConfig config) {
        switch (getStyle()) {
            case TOGGLE -> setToggle(config, defaultToggle());
            case COLOR -> setColor(config, ColorUtil.parseRgb(state));
            case SLIDER -> setNumber(config, defaultNumber());
            case LIST -> setList(config, List.of());
        }
        setKeyBind(config, defaultKeyBindKeys());
    }

    /**
     * 把当前值夹回合法范围（写回 config，不落盘）
     */
    public void clampInto(ClientRenderConfig config) {
        switch (getStyle()) {
            case SLIDER -> setNumber(config, Math.clamp(getNumber(config), minNumber(), maxNumber()));
            case COLOR -> setColor(config, getColor(config));
            default -> {
            }
        }
    }

    /**
     * 当前值是否就是枚举里声明的默认值（是的话重置按钮置灰）
     */
    @Override
    public boolean isDefault() {
        return switch (getStyle()) {
            // 开关项还要看快捷键：快捷键不是默认值（绑了键）时也算可重置
            case TOGGLE -> getToggle() == defaultToggle()
                    && Arrays.equals(getKeyBind(), defaultKeyBindKeys());
            case SLIDER -> Math.abs(getNumber() - defaultNumber()) < 1.0E-6D;
            case COLOR -> getColor() == ColorUtil.parseRgb(state);
            // 列表默认就是空的
            case LIST -> getList().isEmpty();
            case TRIGGER -> Arrays.equals(getKeyBind(), defaultKeyBindKeys());
        };
    }

    /**
     * 单行「重置」按钮：恢复默认值 + 快捷键清空，并落盘
     */
    @Override
    public void reset() {
        ClientRenderConfig config = ClientRenderConfig.get();
        applyDefaultTo(config);
        config.save();
    }

    /**
     * 本机配置项一改就要落盘（和右上角的功能开关一致，不等关界面）
     */
    @Override
    public void save() {
        ClientRenderConfig.get().save();
    }
}
