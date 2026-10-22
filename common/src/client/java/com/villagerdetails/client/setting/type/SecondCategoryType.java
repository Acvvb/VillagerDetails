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

    /**
     * 颜色类配置项的二级分类（一级 {@code OPTIONS}）
     */
    OPTION_COLOR("options.color", FirstCategoryType.OPTIONS),

    /**
     * 滑块类配置项的二级分类（一级 {@code OPTIONS}）
     */
    OPTION_SLIDER("options.slider", FirstCategoryType.OPTIONS),

    /**
     * 列表类配置项的二级分类（一级 {@code OPTIONS}），如床列表
     */
    OPTION_LIST("options.list", FirstCategoryType.OPTIONS),

    // ---------------- 功能开关 ----------------

    /**
     * 「全部开关」二级分类：收纳所有功能开关，一栏看全
     */
    TOGGLE_ALL("toggles.all", FirstCategoryType.TOGGLES),

    /**
     * 「村民」二级分类：只收纳村民相关的功能开关
     */
    TOGGLE_VILLAGER("toggles.villager", FirstCategoryType.TOGGLES),

    /**
     * 「选区」二级分类：只收纳选区（BB / EB / 传送门视图）相关的功能开关
     */
    TOGGLE_SELECTION("toggles.selection", FirstCategoryType.TOGGLES),

    /**
     * 「触发」二级分类：只收纳一次性触发按钮（点一下执行一次动作，不保留开关状态）
     */
    TOGGLE_TRIGGER("toggles.trigger", FirstCategoryType.TOGGLES),

    /**
     * 「已启用」二级分类：筛选出当前处于开启状态的开关项，便于快速回看 / 关闭
     */
    TOGGLE_ENABLED("toggles.enabled", FirstCategoryType.TOGGLES),

    /**
     * 「已禁用」二级分类：筛选出当前处于关闭状态的开关项，便于批量排查哪些功能被关了
     */
    TOGGLE_DISABLED("toggles.disabled", FirstCategoryType.TOGGLES),

    ;

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
                .filter(entry -> entry.getSubCategories().contains(this))
                .toList();
    }
}
