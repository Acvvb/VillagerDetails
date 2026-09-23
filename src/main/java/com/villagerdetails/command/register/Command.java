package com.villagerdetails.command.register;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.command.register.server.RegisterServer;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public class Command {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 1. 根节点
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("command");

        // 2. 遍历枚举，动态挂载“需要命令”的规则子命令
        for (RuleType ruleType : RuleType.values()) {
            RegisterServer registerServer = ruleType.getCommandObject();
            if (registerServer == null) {
                continue;
            }

            // 将“规则是否开启”统一放到 requires 中：
            // - 关闭时，Brigadier 在补全与解析阶段都会跳过该节点 → 无法补全 + 无法执行
            // - requires 在每次补全/执行时都会重新求值，因此支持运行时动态开关，无需重新注册命令
            LiteralArgumentBuilder<CommandSourceStack> subCommand = registerServer.register()
                    .requires(source -> RuleCache.isEnabled(ruleType));

            root = root.then(subCommand);
        }

        // 3. 注册
        dispatcher.register(root);
    }
}
