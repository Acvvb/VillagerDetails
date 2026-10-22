package com.villagerdetails.client.setting.type;

import com.villagerdetails.client.setting.type.style.StyleType;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * 二级分类（界面右上那一排横向按钮）。
 *
 * <p>只声明「属于哪个一级分类」，不再携带行样式 ——
 * 样式由每个 {@link ConfigEntryType} 自己声明。
 *
 * <p>归属分两种，{@link #entriesOf} 统一处理：
 * <ul>
 *     <li><b>静态归属</b>（{@code OPTION_*} / {@code TOGGLE_VILLAGER} / {@code TOGGLE_SELECTION} /
 *         {@code TOGGLE_SWITCH}）：每个配置项在自己的枚举里声明「我属于哪几栏」，
 *         所以一个配置项可以同时出现在多栏里。新增这样的分类 = 这里加一行常量，
 *         再去 {@link ConfigEntryType} 把该进的项挂上。</li>
 *     <li><b>判断类型</b>（{@code TOGGLE_ALL} / {@code TOGGLE_TRIGGER} /
 *         {@code TOGGLE_ENABLED} / {@code TOGGLE_DISABLED}）：归属不写死在配置项上，
 *         建页签时按谓词现算。新增这样的分类 = 加一行常量 + 一个翻译键，配置项那边不用动。</li>
 * </ul>
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
     * 「全部」二级分类（判断类型）：当前一级分类下的所有配置项，不筛样式、不看状态
     */
    TOGGLE_ALL("toggles.all", FirstCategoryType.TOGGLES, _ -> true),

    /**
     * 「村民」二级分类：只收纳村民相关的功能开关
     */
    TOGGLE_VILLAGER("toggles.villager", FirstCategoryType.TOGGLES),

    /**
     * 「选区」二级分类：只收纳选区（BB / EB / 传送门视图）相关的功能开关
     */
    TOGGLE_SELECTION("toggles.selection", FirstCategoryType.TOGGLES),

    /**
     * 「开关」二级分类：只收纳有开 / 关状态的项（一次性触发按钮不在里面）。
     *
     * <p>归属是<b>静态</b>的 —— 配在 {@link ConfigEntryType} 各自的声明里，和「村民」「选区」一个写法；
     * 只看「是不是开关」、不看当前值是开还是关（要看值请用「开启 / 关闭」两栏）。
     */
    TOGGLE_SWITCH("toggles.switch", FirstCategoryType.TOGGLES),

    /**
     * 「触发器」二级分类（判断类型）：只收一次性触发按钮（点一下执行一次动作，不保留开关状态）
     */
    TOGGLE_TRIGGER("toggles.trigger", FirstCategoryType.TOGGLES, triggerStyle()),

    /**
     * 「开启」二级分类（判断类型）：开关样式的项里，当前处于开启状态的那些
     */
    TOGGLE_ENABLED("toggles.enabled", FirstCategoryType.TOGGLES,
            toggleStyle().and(ConfigEntryType::getToggle)),

    /**
     * 「关闭」二级分类（判断类型）：开关样式的项里，当前处于关闭状态的那些
     */
    TOGGLE_DISABLED("toggles.disabled", FirstCategoryType.TOGGLES,
            toggleStyle().and(ConfigEntryType::getToggle).negate()),

    ;

    private final String key;
    private final FirstCategoryType firstCategoryType;

    /**
     * 判断类型的筛选条件；{@code null} 表示这一栏走「配置项自己声明的静态归属」
     */
    private final Predicate<ConfigEntryType> filter;

    /**
     * 静态归属的二级分类
     */
    SecondCategoryType(String key, FirstCategoryType firstCategoryType) {
        this(key, firstCategoryType, null);
    }

    /**
     * 判断类型的二级分类（按 {@code filter} 现算归属）
     */
    SecondCategoryType(String key, FirstCategoryType firstCategoryType, Predicate<ConfigEntryType> filter) {
        this.key = key;
        this.firstCategoryType = firstCategoryType;
        this.filter = filter;
    }

    // ==============================================================
    // 判断类型的筛选条件
    // ==============================================================

    /**
     * 样式是开关的那些配置项。
     *
     * <p>为什么写成静态方法而不是「一个静态常量谓词」：枚举常量的构造参数是在本类的
     * {@code <clinit>} 里求值的，那时本类的静态字段还没赋值（静态字段按声明顺序初始化），
     * 常量里直接引用一个静态谓词字段会拿到 {@code null}。静态方法不受这个顺序影响。
     */
    private static Predicate<ConfigEntryType> toggleStyle() {
        return entry -> entry.getStyle() == StyleType.TOGGLE;
    }

    /**
     * 样式是一次性触发按钮的那些配置项（同 {@link #toggleStyle()} 的理由，用静态方法）
     */
    private static Predicate<ConfigEntryType> triggerStyle() {
        return entry -> entry.getStyle() == StyleType.TRIGGER;
    }

    // ==============================================================
    // 查询
    // ==============================================================

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
     * 该二级分类在某个一级分类下实际要显示哪些配置项（按枚举声明顺序）。
     *
     * <p>判断类型按谓词现算，所以每次调用都反映<b>当前</b>配置值（「开启 / 关闭」两栏靠这个跟着变）；
     * 静态归属直接返回配置项自己声明的那几栏。
     *
     * @param main 一级分类（只对判断类型有意义：静态归属的项自己就绑死了属于哪一栏）
     */
    public List<ConfigEntryType> entriesOf(FirstCategoryType main) {
        if (filter == null) {
            return Arrays.stream(ConfigEntryType.values())
                    .filter(entry -> entry.getSubCategories().contains(this))
                    .toList();
        }
        return Arrays.stream(ConfigEntryType.values())
                .filter(entry -> entry.getMainCategories().contains(main))
                .filter(filter)
                .toList();
    }
}
