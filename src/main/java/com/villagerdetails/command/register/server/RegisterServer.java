package com.villagerdetails.command.register.server;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;

public interface RegisterServer {

    /**
     * 获取该命令对应的 RuleType
     */
    RuleType getRuleType();

    /**
     * 构建命令树（每个实现类自行处理 requires）
     */
    LiteralArgumentBuilder<CommandSourceStack> register();
}