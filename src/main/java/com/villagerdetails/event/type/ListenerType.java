package com.villagerdetails.event.type;

public enum ListenerType {

    NONE("空"),
    ENTITY_BLOCK_SELECTION("实体绑定选区工具"),
    BLOCK_RANGE_SELECTION("选区工具")
    ;

    private final int id;
    private final String name;

    ListenerType(String name) {
        this.id = this.ordinal();
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
