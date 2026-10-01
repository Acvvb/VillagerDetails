package com.villagerdetails.event.type;

import com.villagerdetails.command.c.impl.tool.BBSelectionServerImpl;
import com.villagerdetails.command.c.server.RegisterServer;
import com.villagerdetails.command.c.server.RegisterTypeServer;

public enum ListenerType implements RegisterTypeServer {

    ENTITY_BLOCK_SELECTION("实体绑定选区工具", null),
    BLOCK_RANGE_SELECTION("选区工具", BBSelectionServerImpl.INSTANCE);

    private final int id;
    private final String name;
    private final RegisterServer commandObject;

    ListenerType(String name, RegisterServer registerServer) {
        this.commandObject = registerServer;
        this.id = this.ordinal();
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }


    public RegisterServer getCommandObject() {
        return commandObject;
    }
}
