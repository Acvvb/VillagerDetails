package com.villagerdetails.command;

import com.villagerdetails.VillagerDetails;
import net.minecraft.resources.Identifier;

public final class CommandConstants {
    private CommandConstants() {
    }

    public static final String COMMAND_BASE = "/" + VillagerDetails.BASE_COMMAND;

    public static final Identifier PERM_BASE = VillagerDetails.id("c.base");

    /**
     * 改规则被拒时的两条提示（命令与收包端共用，保证同一个非法输入给出同一句话）。
     * {@link #RULE_ERROR_INVALID_OPTION} = 预定义列表里没有这一项。
     */
    public static final String RULE_ERROR_INVALID_STATE = "command.entity_binder.error.invalid_state";
    public static final String RULE_ERROR_INVALID_OPTION = "command.entity_binder.error.invalid_option";

}
