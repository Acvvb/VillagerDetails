package com.villagerdetails.client.setting.type;

import com.villagerdetails.client.setting.rule.ServerRuleHandler;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 一级分类（界面左侧那一列）。
 *
 * <p>翻译键：{@code villagerdetails.ui.category.<key>}
 */
public enum FirstCategoryType {

    /**
     * 配置项：颜色 / 滑块 / 列表
     */
    OPTIONS("options"),

    /**
     * 功能开关：一行一个功能，带重置按钮、规则名、快捷键绑定、是非开关
     */
    TOGGLES("toggles"),

    /**
     * 服务器规则：直接用服务端的 RuleType 分类；服务器没装本 mod 时这一项不显示
     */
    SERVER("server"),

    /**
     * 服务器配置项：只放「需要逐条配置」的那几条规则
     * （无挤压、方块挖掘抗性、方块可采集、方块爆炸抗性）。
     *
     * <p>还要求：有权限（服务端下发了这条规则）<b>并且</b>规则是开着的；
     * 一条能配的都没有时这个分类整个不显示。
     */
    SERVER_CONFIG("server_config");

    private final String key;

    FirstCategoryType(String key) {
        this.key = key;
    }

    public String getRegisterName() {
        return key;
    }

    public Component getDisplayName() {
        return Component.translatable("villagerdetails.ui.category." + key);
    }

    /**
     * 属于该一级分类的二级分类（按枚举声明顺序）
     */
    public List<SecondCategoryType> subCategories() {
        return Arrays.stream(SecondCategoryType.values())
                .filter(sub -> sub.getMainCategory() == this)
                .toList();
    }

    /**
     * 当前实际可见的一级分类。
     *
     * <p>「服务器」要同时满足：服务器装了本 mod，并且这个玩家至少收到了一条规则描述符。
     * 玩家被下 op 之后服务端就不再下发任何描述符，这个分类会跟着整个消失。
     *
     * <p>「服务器配置项」在上述基础上还要求：服务端下发的描述符里至少有一条带逐条配置模板
     * 并且是开着的。
     */
    public static List<FirstCategoryType> visibleValues() {
        boolean serverVisible = ServerRuleHandler.INSTANCE.isServerCategoryVisible();
        boolean configVisible = ServerRuleHandler.INSTANCE.hasConfigurableRules();
        return Arrays.stream(values())
                .filter(main -> switch (main) {
                    case SERVER -> serverVisible;
                    case SERVER_CONFIG -> configVisible;
                    default -> true;
                })
                .toList();
    }
}
