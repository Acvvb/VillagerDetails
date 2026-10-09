package com.villagerdetails.client.setting.type;

import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 二级分类（界面右上那一排横向按钮）。
 *
 * <p>只声明「属于哪个一级分类」，不再携带行样式 ——
 * 样式由每个 {@link ConfigEntryType} 自己声明。
 *
 * <p>翻译键：{@code villagerdetails.ui.subcategory.<key>}
 */
public enum SecondCategoryType {

    // ---------------- 配置项 ----------------

    OPTION_COLOR("options.color", FirstCategoryType.OPTIONS),
    OPTION_SLIDER("options.slider", FirstCategoryType.OPTIONS),
    OPTION_LIST("options.list", FirstCategoryType.OPTIONS),

    // ---------------- 功能开关 ----------------

    TOGGLE_VILLAGER("toggles.villager", FirstCategoryType.TOGGLES);

    private final String key;
    private final FirstCategoryType firstCategoryType;

    SecondCategoryType(String key, FirstCategoryType firstCategoryType) {
        this.key = key;
        this.firstCategoryType = firstCategoryType;
    }

    public String getRegisterName() {
        return key;
    }

    public FirstCategoryType getMainCategory() {
        return firstCategoryType;
    }

    public Component getDisplayName() {
        return Component.translatable("villagerdetails.ui.subcategory." + key);
    }

    /**
     * 该二级分类下的配置项（按枚举声明顺序）
     */
    public List<ConfigEntryType> options() {
        return Arrays.stream(ConfigEntryType.values())
                .filter(entry -> entry.getSubCategory() == this)
                .toList();
    }
}