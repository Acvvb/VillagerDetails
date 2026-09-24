package com.villagerdetails.rule.type;

public enum RuleCategoryType {

    VILLAGER(1,"villager" ,"村民");

    private final int id;

    private final String registerName;

    private final String displayName;

    RuleCategoryType(int id, String registerName, String displayName) {
        this.id = id;
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
