package com.villagerdetails.client.setting.type;

import com.mojang.blaze3d.platform.InputConstants;
import com.villagerdetails.client.setting.config.ClientRenderConfig;
import com.villagerdetails.client.setting.row.ConfigRow;
import com.villagerdetails.client.setting.row.ListPreview;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 所有配置项。写法参考 {@link com.villagerdetails.rule.type.RuleType}：
 * <b>显示名称、显示描述、默认值</b>都在枚举常量上声明，不再散落在各处。
 *
 * <p>每个常量依次声明：
 * <ol>
 *     <li>{@code registerName}：注册名（配置里存快捷键等用它）</li>
 *     <li>{@code displayName}：显示名称的翻译键</li>
 *     <li>{@code displayInfo}：显示描述的翻译键</li>
 *     <li>{@code mainCategory} / {@code subCategory}：所属一级 / 二级分类（行样式跟随二级分类）</li>
 *     <li>{@code state}：默认值 —— 开关写 {@code true/false}；滑块写数值；颜色写 {@code #RRGGBB}；列表留空</li>
 *     <li>{@code defaultKeyBind}：默认快捷键（{@code "88+67"} 这种按键码组合，空串 = 未绑定）</li>
 * </ol>
 *
 * <p>枚举自己实现 {@link ConfigRow}，配置界面直接把它当行用 ——
 * 不需要再套一层「把 entry 转成 row」的转发包装。
 */
public enum ConfigEntry implements ConfigRow {

    // ---------------- 配置项 / 颜色 ----------------

    NEAR_COLOR("NearColor",
            "villagerdetails.ui.option.near_color", "villagerdetails.ui.option.near_color.desc",
            MainCategory.OPTIONS, SubCategory.OPTION_COLOR, "#54E36B", ""),

    FAR_COLOR("FarColor",
            "villagerdetails.ui.option.far_color", "villagerdetails.ui.option.far_color.desc",
            MainCategory.OPTIONS, SubCategory.OPTION_COLOR, "#FF4D4D", ""),

    // ---------------- 配置项 / 滑块 ----------------

    LINE_WIDTH("LineWidth",
            "villagerdetails.ui.option.line_width", "villagerdetails.ui.option.line_width.desc",
            MainCategory.OPTIONS, SubCategory.OPTION_SLIDER, "4.0", ""),

    FAR_DISTANCE("FarDistance",
            "villagerdetails.ui.option.far_distance", "villagerdetails.ui.option.far_distance.desc",
            MainCategory.OPTIONS, SubCategory.OPTION_SLIDER, "5.0", ""),

    // ---------------- 配置项 / 列表 ----------------

    BED_LIST("BedList",
            "villagerdetails.ui.option.bed_list", "villagerdetails.ui.option.bed_list.desc",
            MainCategory.OPTIONS, SubCategory.OPTION_LIST, "", ""),

    // ---------------- 功能开关 / 村民 ----------------

    RENDER_BED_LINE("RenderBedLine",
            "villagerdetails.ui.option.render_bed_line", "villagerdetails.ui.option.render_bed_line.desc",
            MainCategory.TOGGLES, SubCategory.TOGGLE_VILLAGER, "false", ""),
    ;

    private final String registerName;
    private final String displayName;
    private final String displayInfo;
    private final MainCategory mainCategory;
    private final SubCategory subCategory;
    private final String state;
    private final String defaultKeyBind;

    ConfigEntry(String registerName, String displayName, String displayInfo,
                MainCategory mainCategory, SubCategory subCategory,
                String state, String defaultKeyBind) {
        this.registerName = registerName;
        this.displayName = displayName;
        this.displayInfo = displayInfo;
        this.mainCategory = mainCategory;
        this.subCategory = subCategory;
        this.state = state;
        this.defaultKeyBind = defaultKeyBind;
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

    public MainCategory getMainCategory() {
        return mainCategory;
    }

    public SubCategory getSubCategory() {
        return subCategory;
    }

    /** 行样式：跟随所在的二级分类 */
    @Override
    public SubCategory.Style getStyle() {
        return subCategory.getStyle();
    }

    /** 只有功能开关有快捷键绑定框 */
    @Override
    public boolean hasKeyBind() {
        return getStyle() == SubCategory.Style.TOGGLE;
    }

    // ==============================================================
    // 默认值（全部在枚举常量上声明）
    // ==============================================================

    /** 默认值原文（和 RuleType 的 state 一个意思） */
    public String getState() {
        return state;
    }

    /** 默认快捷键原文 */
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

    public int defaultColor() {
        String value = state == null ? "" : state.trim();
        if (value.startsWith("#")) {
            try {
                return Integer.parseInt(value.substring(1), 16) & ClientRenderConfig.RGB_MASK;
            } catch (NumberFormatException ignored) {
                // 写坏了就退回白色
            }
        }
        return ClientRenderConfig.RGB_MASK;
    }

    public int[] defaultKeyBindKeys() {
        return ClientRenderConfig.parseKeys(defaultKeyBind);
    }

    // ==============================================================
    // 取值 / 赋值
    // ==============================================================

    @Override
    public boolean getToggle() {
        return getToggle(ClientRenderConfig.get());
    }

    public boolean getToggle(ClientRenderConfig config) {
        return switch (this) {
            case RENDER_BED_LINE -> config.isRenderBedLine();
            default -> false;
        };
    }

    @Override
    public void setToggle(boolean value) {
        setToggle(ClientRenderConfig.get(), value);
    }

    public void setToggle(ClientRenderConfig config, boolean value) {
        switch (this) {
            case RENDER_BED_LINE -> config.setRenderBedLine(value);
            default -> {
            }
        }
    }

    @Override
    public double getNumber() {
        return getNumber(ClientRenderConfig.get());
    }

    public double getNumber(ClientRenderConfig config) {
        return switch (this) {
            case LINE_WIDTH -> config.getLineWidth();
            case FAR_DISTANCE -> config.getFarDistance();
            default -> 0.0D;
        };
    }

    @Override
    public void setNumber(double value) {
        setNumber(ClientRenderConfig.get(), value);
    }

    public void setNumber(ClientRenderConfig config, double value) {
        switch (this) {
            case LINE_WIDTH -> config.setLineWidth((float) value);
            case FAR_DISTANCE -> config.setFarDistance(value);
            default -> {
            }
        }
    }

    @Override
    public double minNumber() {
        return switch (this) {
            case LINE_WIDTH -> ClientRenderConfig.MIN_LINE_WIDTH;
            case FAR_DISTANCE -> ClientRenderConfig.MIN_FAR_DISTANCE;
            default -> 0.0D;
        };
    }

    @Override
    public double maxNumber() {
        return switch (this) {
            case LINE_WIDTH -> ClientRenderConfig.MAX_LINE_WIDTH;
            case FAR_DISTANCE -> ClientRenderConfig.MAX_FAR_DISTANCE;
            default -> 1.0D;
        };
    }

    @Override
    public double stepNumber() {
        return switch (this) {
            case LINE_WIDTH, FAR_DISTANCE -> 0.5D;
            default -> 1.0D;
        };
    }

    /** 滑条的显示文本，例如「粗细：4.0」（翻译键 = 显示名 + {@code .value}） */
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
        return switch (this) {
            case NEAR_COLOR -> config.getNearColor();
            case FAR_COLOR -> config.getFarColor();
            default -> ClientRenderConfig.RGB_MASK;
        };
    }

    public void setColor(int rgb) {
        setColor(ClientRenderConfig.get(), rgb);
    }

    public void setColor(ClientRenderConfig config, int rgb) {
        switch (this) {
            case NEAR_COLOR -> config.setNearColor(rgb);
            case FAR_COLOR -> config.setFarColor(rgb);
            default -> {
            }
        }
    }

    // ==============================================================
    // 快捷键绑定（功能开关用）
    // ==============================================================

    /** 绑定的按键组合（GLFW keycode 数组），空数组表示未绑定 */
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

    /** 把按键组合显示成 {@code X + C}；空组合显示「未绑定」 */
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

    /** 完整的值列表（只读快照） */
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

    /** 把这一项恢复成枚举里声明的默认值（含默认快捷键），不落盘 */
    public void applyDefaultTo(ClientRenderConfig config) {
        switch (getStyle()) {
            case TOGGLE -> setToggle(config, defaultToggle());
            case COLOR -> setColor(config, defaultColor());
            case SLIDER -> setNumber(config, defaultNumber());
            case LIST -> setList(config, List.of());
        }
        setKeyBind(config, defaultKeyBindKeys());
    }

    /** 当前值是否就是枚举里声明的默认值（是的话重置按钮置灰） */
    @Override
    public boolean isDefault() {
        return switch (getStyle()) {
            // 开关项还要看快捷键：快捷键不是默认值（绑了键）时也算可重置
            case TOGGLE -> getToggle() == defaultToggle()
                    && Arrays.equals(getKeyBind(), defaultKeyBindKeys());
            case SLIDER -> Math.abs(getNumber() - defaultNumber()) < 1.0E-6D;
            case COLOR -> getColor() == defaultColor();
            // 列表默认就是空的
            case LIST -> getList().isEmpty();
        };
    }

    /** 单行「重置」按钮：恢复默认值 + 快捷键清空，并落盘 */
    @Override
    public void reset() {
        ClientRenderConfig config = ClientRenderConfig.get();
        applyDefaultTo(config);
        config.save();
    }

    /** 本机配置项一改就要落盘（和右上角的功能开关一致，不等关界面） */
    @Override
    public void save() {
        ClientRenderConfig.get().save();
    }
}
