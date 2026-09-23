package com.villagerdetails.event.type;

public enum ListenerType {

    ENTITY_BLOCK_SELECTION(1,"实体绑定选区工具"),
    BLOCK_RANGE_SELECTION(2,"选区工具")
    ;

    private final int id;
    private final String name;

    ListenerType(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
