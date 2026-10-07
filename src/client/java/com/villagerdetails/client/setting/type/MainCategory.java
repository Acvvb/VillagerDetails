package com.villagerdetails.client.setting.type;

import com.villagerdetails.client.setting.network.ServerRuleClientState;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 一级分类（界面左侧那一列）。
 *
 * <p>翻译键：{@code villagerdetails.ui.category.<key>}
 */
public enum MainCategory {

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
    SERVER("server");

    private final String key;

    MainCategory(String key) {
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
    public List<SubCategory> subCategories() {
        return Arrays.stream(SubCategory.values())
                .filter(sub -> sub.getMainCategory() == this)
                .toList();
    }

    /**
     * 当前实际可见的一级分类。
     *
     * <p>「服务器」要同时满足：服务器装了本 mod，并且这个玩家至少有一条有权限看的规则。
     * 玩家被下 op 之后服务端就不再下发任何规则，这个分类会跟着整个消失。
     */
    public static List<MainCategory> visibleValues() {
        boolean serverVisible = ServerRuleClientState.isServerCategoryVisible();
        return Arrays.stream(values())
                .filter(main -> main != SERVER || serverVisible)
                .toList();
    }
}
