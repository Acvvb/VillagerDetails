package com.villagerdetails.command.c.server;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

public interface RegisterServer {

    String BLOCK_BLOCK_SECTION = "selection";

    /**
     * 构建命令树（每个实现类自行处理 requires）
     */
    LiteralArgumentBuilder<CommandSourceStack> register();
}