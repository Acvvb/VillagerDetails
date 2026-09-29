package com.villagerdetails.rule.type;

public enum RuleCategoryType {

    MOD_SETTING("Setting", "设置"),
    VILLAGER("villager" ,"村民"),
    ENTITY("entity","实体"),
    MONSTER("monster","怪物"),
    ANIMAL("animal","动物")


    ;


    private final int id;

    private final String registerName;

    private final String displayName;

    RuleCategoryType(String registerName, String displayName) {
        this.id = this.ordinal();
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

    public int getId() {
        return id;
    }

    public String getRegisterName() {
        return registerName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
