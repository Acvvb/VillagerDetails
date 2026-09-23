package com.villagerdetails.rule.type;

public enum RuleCategoryType {

    VILLAGER(1,"村民");

    private final int id;

    private final String displayName;

    RuleCategoryType(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public int getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }
}
