package com.villagerdetails.command;

import com.villagerdetails.VillagerDetails;

public enum SwitchComponentType {

    OP_0("0", SwitchComponentType.INFO, "0"),
    OP_1("1", SwitchComponentType.INFO, "1"),
    OP_2("2", SwitchComponentType.INFO, "2"),
    OP_3("3", SwitchComponentType.INFO, "3"),
    OP_4("4", SwitchComponentType.INFO, "4"),
    DISABLE("关闭", SwitchComponentType.INFO, "false"),
    ENABLE("开启", SwitchComponentType.INFO, "true")
    ;

    public final static String INFO = "点击切换规则状态\n当前状态: %s";
    public final static String COMMAND_BASE = String.format("/%s ", VillagerDetails.MOD_ID);

    private final int id;
    private final String displayName;
    private final String onClickMessage;
    private final String commandStr;



    SwitchComponentType(String displayName, String onClickMessage, String commandStr) {
        this.onClickMessage = onClickMessage;
        this.commandStr = commandStr;
        this.id = this.ordinal();
        this.displayName = displayName;
    }

    public int getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getOnClickMessage() {
        return onClickMessage;
    }

    public String getCommandStr() {
        return commandStr;
    }
}
