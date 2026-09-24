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
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("command");
        for (RuleType ruleType : RuleType.values()) {
            RegisterServer registerServer = ruleType.getCommandObject();
            if (registerServer == null) continue;
            LiteralArgumentBuilder<CommandSourceStack> subCommand = registerServer.register()
                    .requires(_ -> RuleCache.isEnabled(ruleType));
            root = root.then(subCommand);
        }
        dispatcher.register(root);
    }
}
