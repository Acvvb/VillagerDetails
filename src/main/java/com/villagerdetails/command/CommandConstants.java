package com.villagerdetails.command;

import com.villagerdetails.VillagerDetails;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.function.Predicate;

public final class CommandConstants {
    private CommandConstants() {}

    public final static String COMMAND_BASE = "/" + VillagerDetails.BAST_COMMAND;

    public static final Identifier PERM_BASE = VillagerDetails.id("c.base");

    /**
     * 「逐条配置」类规则的命令统一要求最高权限（OWNERS）才能调用。
     *
     * <p>注意和界面那一侧的区别：设置界面走的是 {@code /ec} 那条总权限
     * （见 {@code RuleCache#lacksRuleCommandPermission}），命令行这里是硬要求，两者刻意不同。
     */
    public static final Predicate<CommandSourceStack> OWNER_REQUIREMENT =
            src -> src.checkPermission(PERM_BASE, PermissionLevel.OWNERS);

}
