package com.villagerdetails.command.register;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.villagerdetails.command.register.server.RegisterServer;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public class Command {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 1. 构建根节点（不再需要 argument + suggests）
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("command");

        // 2. 遍历枚举，动态挂载已启用的子命令
        for (RuleType ruleType : RuleType.values()) {
            RegisterServer registerServer = ruleType.getCommandObject();
            if (registerServer != null) {
                // ✅ 将启用校验放到 requires 中，Brigadier 会自动处理客户端/服务端同步
                root = root.then(registerServer.register());
            }
        }

        // 3. 注册
        dispatcher.register(root);
    }
}