package com.villagerdetails.rule.type;

import com.villagerdetails.lang.ServerTranslations;
import net.minecraft.network.chat.Component;

public enum RuleCategoryType {

    MOD_SETTING("Setting", "rule.category.mod_setting"),
    VILLAGER("villager", "rule.category.villager"),
    ENTITY("entity", "rule.category.entity"),
    MONSTER("monster", "rule.category.monster"),
    ANIMAL("animal", "rule.category.animal"),
    PLANT("Plant", "rule.category.plant"),
    BLOCK("block", "rule.category.block"),
    ;


    private final String registerName;

    private final String displayName;

    RuleCategoryType(String registerName, String displayName) {
        this.registerName = registerName;
        this.displayName = displayName;
    }

    public static RuleCategoryType getTypeByRegisterName(String categoryName) {
        if (categoryName == null) return null;
        for (RuleCategoryType ruleCategoryType : RuleCategoryType.values()) {
            if (ruleCategoryType.getRegisterName().equals(categoryName)) {
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
}
