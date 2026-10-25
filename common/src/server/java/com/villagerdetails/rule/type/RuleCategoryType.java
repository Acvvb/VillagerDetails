package com.villagerdetails.rule.type;

import com.villagerdetails.lang.ServerTranslations;
import net.minecraft.network.chat.Component;

public enum RuleCategoryType {

    MOD_SETTING("Setting", "rule.category.mod_setting"),
    BLOCK("block", "rule.category.block"),
    PLANT("Plant", "rule.category.plant"),
    ENTITY("entity", "rule.category.entity"),
    VILLAGER("villager", "rule.category.villager"),
    MONSTER("monster", "rule.category.monster"),
    ANIMAL("animal", "rule.category.animal"),
    PLAYER("player", "rule.category.player"),
    COMMAND("command", "rule.category.command"),
    FIX("fix", "rule.category.fix")
    ;


    private final String registerName;

    private final String displayName;

    RuleCategoryType(String registerName, String displayName) {
        this.registerName = registerName;
        this.displayName = displayName;
    }

    /**
     * 按注册名找分类（{@code /ec list <分类>} 用）。
     *
     * <p>除了精确匹配，还额外容忍两种写法 —— 分类是给人敲的，{@code /ec list players}
     * 这种复数和大小写写错不该直接报「未知分类」：
     * <ol>
     *     <li>忽略大小写（{@code Player} / {@code plant} 都行）；</li>
     *     <li>末尾多一个 {@code s}（{@code players} → {@code player}）。</li>
     * </ol>
     * 顺序是先精确、再宽松，所以永远优先认注册名本身。
     */
    public static RuleCategoryType getTypeByRegisterName(String categoryName) {
        if (categoryName == null) return null;
        String name = categoryName.trim();
        if (name.isEmpty()) return null;

        for (RuleCategoryType ruleCategoryType : RuleCategoryType.values()) {
            if (ruleCategoryType.getRegisterName().equals(name)) {
                return ruleCategoryType;
            }
        }
        for (RuleCategoryType ruleCategoryType : RuleCategoryType.values()) {
            String registerName = ruleCategoryType.getRegisterName();
            if (registerName.equalsIgnoreCase(name) || (registerName + "s").equalsIgnoreCase(name)) {
                return ruleCategoryType;
            }
        }
        return null;
    }

    public String getRegisterName() {
        return registerName;
    }

    public String getDisplayName() {
        return ServerTranslations.resolve(Component.translatable(displayName));
    }

    /** 显示名的翻译键（客户端界面用） */
    public String getDisplayNameKey() {
        return displayName;
    }
}
