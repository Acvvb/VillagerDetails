package com.villagerdetails.client.setting.type;

import com.mojang.blaze3d.platform.InputConstants;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.client.setting.row.ListPreview;
import com.villagerdetails.client.setting.type.server.BindingServer;
import com.villagerdetails.client.setting.type.style.StyleType;
import com.villagerdetails.client.setting.util.ColorUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 所有配置项。写法参考 {@link com.villagerdetails.rule.type.RuleType}：
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

    NEAR_COLOR("NearColor",
            "villagerdetails.ui.option.near_color", "villagerdetails.ui.option.near_color.desc",
            FirstCategoryType.OPTIONS, SecondCategoryType.OPTION_COLOR, "#54E36B", "",
            BindingServer.color()),

    FAR_COLOR("FarColor",
            "villagerdetails.ui.option.far_color", "villagerdetails.ui.option.far_color.desc",
            FirstCategoryType.OPTIONS, SecondCategoryType.OPTION_COLOR, "#FF4D4D", "",
            BindingServer.color()),

    SELECTED_ENTITY_COLOR("SelectedEntityColor",
            "villagerdetails.ui.option.selected_entity_color",
            "villagerdetails.ui.option.selected_entity_color.desc",
            FirstCategoryType.OPTIONS, SecondCategoryType.OPTION_COLOR, "#FFD54F", "",
            BindingServer.color()),

    SELECTED_BLOCK_COLOR("SelectedBlockColor",
            "villagerdetails.ui.option.selected_block_color",
            "villagerdetails.ui.option.selected_block_color.desc",
            FirstCategoryType.OPTIONS, SecondCategoryType.OPTION_COLOR, "#4FC3F7", "",
            BindingServer.color()),

    BB_POS1_COLOR("BBPos1Color",
            "villagerdetails.ui.option.bb_pos1_color", "villagerdetails.ui.option.bb_pos1_color.desc",
            FirstCategoryType.OPTIONS, SecondCategoryType.OPTION_COLOR, "#4FC3F7", "",
            BindingServer.color()),

    BB_POS2_COLOR("BBPos2Color",
            "villagerdetails.ui.option.bb_pos2_color", "villagerdetails.ui.option.bb_pos2_color.desc",
            FirstCategoryType.OPTIONS, SecondCategoryType.OPTION_COLOR, "#FFD54F", "",
            BindingServer.color()),

    BB_BOX_COLOR("BBBoxColor",
            "villagerdetails.ui.option.bb_box_color", "villagerdetails.ui.option.bb_box_color.desc",
            FirstCategoryType.OPTIONS, SecondCategoryType.OPTION_COLOR, "#9C27B0", "",
            BindingServer.color()),

    // ---------------- 配置项 / 滑块 ----------------

    LINE_WIDTH("LineWidth",
            "villagerdetails.ui.option.line_width", "villagerdetails.ui.option.line_width.desc",
            FirstCategoryType.OPTIONS, SecondCategoryType.OPTION_SLIDER, "4.0", "",
            BindingServer.slider(1.0D, 10.0D, 0.5D)),

    FAR_DISTANCE("FarDistance",
            "villagerdetails.ui.option.far_distance", "villagerdetails.ui.option.far_distance.desc",
            FirstCategoryType.OPTIONS, SecondCategoryType.OPTION_SLIDER, "5.0", "",
            BindingServer.slider(0.0D, 64.0D, 0.5D)),

    // ---------------- 配置项 / 列表 ----------------

    BED_LIST("BedList",
            "villagerdetails.ui.option.bed_list", "villagerdetails.ui.option.bed_list.desc",
            FirstCategoryType.OPTIONS, SecondCategoryType.OPTION_LIST, "", "",
            BindingServer.list()),

    // ---------------- 功能开关 / 村民 ----------------

    RENDER_BED_LINE("RenderBedLine",
            "villagerdetails.ui.option.render_bed_line", "villagerdetails.ui.option.render_bed_line.desc",
            FirstCategoryType.TOGGLES, SecondCategoryType.TOGGLE_VILLAGER, "false", "",
            BindingServer.toggle()),

    RENDER_SELECTION_HIGHLIGHT("RenderSelectionHighlight",
            "villagerdetails.ui.option.render_selection_highlight",
            "villagerdetails.ui.option.render_selection_highlight.desc",
            FirstCategoryType.TOGGLES, SecondCategoryType.TOGGLE_VILLAGER, "true", "",
            BindingServer.toggle()),

    RENDER_BB_HIGHLIGHT("RenderBBHighlight",
            "villagerdetails.ui.option.render_bb_highlight",
            "villagerdetails.ui.option.render_bb_highlight.desc",
            FirstCategoryType.TOGGLES, SecondCategoryType.TOGGLE_VILLAGER, "true", "",
            BindingServer.toggle()),
    ;

    private final String registerName;
    private final String displayName;
    private final String displayInfo;
    private final FirstCategoryType firstCategoryType;
    private final SecondCategoryType secondCategoryType;
    private final String state;
    private final String defaultKeyBind;
    private final BindingServer bindingServer;

    ConfigEntryType(String registerName, String displayName, String displayInfo,
                    FirstCategoryType firstCategoryType, SecondCategoryType secondCategoryType,
                    String state, String defaultKeyBind, BindingServer bindingServer) {
        this.registerName = registerName;
        this.displayName = displayName;
        this.displayInfo = displayInfo;
        this.firstCategoryType = firstCategoryType;
        this.secondCategoryType = secondCategoryType;
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

    public FirstCategoryType getMainCategory() {
        return firstCategoryType;
    }

    public SecondCategoryType getSubCategory() {
        return secondCategoryType;
    }

    /**
     * 行样式：由自己的 {@link BindingServer} 声明，不再跟随二级分类
     */
    @Override
    public StyleType getStyle() {
        return bindingServer.style();
    }

    /**
     * 只有开关样式才有快捷键绑定框
     */
    @Override
    public boolean hasKeyBind() {
        return bindingServer.style() == StyleType.TOGGLE;
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
     * 滑条的显示文本，例如「粗细：4.0」（翻译键 = 显示名 + {@code .value}）
     */
    @Override
    public Component formatNumber(double value) {
        return Component.translatable(displayName + ".value",
                String.format(Locale.ROOT, "%.1f", value));
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
            text.append(InputConstants.Type.KEYSYM.getOrCreate(keys[i]).getDisplayName());
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