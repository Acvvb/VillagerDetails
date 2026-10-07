package com.villagerdetails.client.setting.type;

import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 二级分类（界面右上那一排横向按钮）。
 *
 * <p>每个二级分类自己带一个 {@link Style}，配置项的行样式就跟着它所在的位置走 ——
 * 也就是说配置项枚举里只需要声明「我属于哪一级、哪二级」，样式不用另外写。
 *
 * <p>翻译键：{@code villagerdetails.ui.subcategory.<key>}
 */
public enum SubCategory {

    // ---------------- 配置项 ----------------

    /**
     * 颜色
     */
    OPTION_COLOR("options.color", MainCategory.OPTIONS, Style.COLOR),

    /**
     * 滑块
     */
    OPTION_SLIDER("options.slider", MainCategory.OPTIONS, Style.SLIDER),

    /**
     * 列表（暂未实现具体渲染）
     */
    OPTION_LIST("options.list", MainCategory.OPTIONS, Style.LIST),

    // ---------------- 功能开关 ----------------

    /**
     * 功能开关 / 村民
     */
    TOGGLE_VILLAGER("toggles.villager", MainCategory.TOGGLES, Style.TOGGLE);

    /**
     * 行样式（跟随二级分类）
     */
    public enum Style {
        /**
         * 重置按钮 + 规则名 + 快捷键绑定 + 是/否
         */
        TOGGLE,
        /**
         * 齿轮 + 颜色预览块 + 颜色按钮
         */
        COLOR,
        /**
         * 齿轮 + 滑条（齿轮可切成立即输入数值的文本框）
         */
        SLIDER,
        /**
         * 占位：只有一个按钮
         */
        LIST
    }

    private final String key;
    private final MainCategory mainCategory;
    private final Style style;

    SubCategory(String key, MainCategory mainCategory, Style style) {
        this.key = key;
        this.mainCategory = mainCategory;
        this.style = style;
    }

    public String getRegisterName() {
        return key;
    }

    public MainCategory getMainCategory() {
        return mainCategory;
    }

    public Style getStyle() {
        return style;
    }

    public Component getDisplayName() {
        return Component.translatable("villagerdetails.ui.subcategory." + key);
    }

    /**
     * 该二级分类下的配置项（按枚举声明顺序）
     */
    public List<ConfigEntry> options() {
        return Arrays.stream(ConfigEntry.values())
                .filter(entry -> entry.getSubCategory() == this)
                .toList();
    }
}
