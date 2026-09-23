package com.villagerdetails.command.register.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.cache.BBSectionStateCache;
import com.villagerdetails.command.register.server.RegisterServer;
import com.villagerdetails.handler.binding.entity.villager.VillagerBindServer;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static com.villagerdetails.rule.type.RuleType.BATCH_BED_RESET;
import static com.villagerdetails.util.SendMessengerUtils.sendToPlayer;

public class BBSelectionServerImpl implements RegisterServer {

    private static final String BLOCK_BLOCK_SECTION = "selection";

    private static final int POS1_SETTER = 1;
    private static final int POS2_SETTER = 2;

    @Override
    public RuleType getRuleType() {
        return BATCH_BED_RESET;
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal(BLOCK_BLOCK_SECTION)
                .then(Commands.literal("pos1")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> this.setPosWithArgs(context, POS1_SETTER)))
                        .executes(context -> this.setPosAtPlayer(context, POS1_SETTER)))
                .then(Commands.literal("pos2")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> this.setPosWithArgs(context, POS2_SETTER)))
                        .executes(context -> this.setPosAtPlayer(context, POS2_SETTER)))
                .then(Commands.literal("show").executes(this::showSelection))
                .then(Commands.literal("clear").executes(this::clearSelection));
    }


    private int setPosAtPlayer(CommandContext<CommandSourceStack> context, int posSeter) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        BlockPos pos = player.blockPosition();
        return setPos(player,pos,posSeter);
    }

    private int setPosWithArgs(CommandContext<CommandSourceStack> context, int posSetter) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        return setPos(player, pos, posSetter);
    }

    private int showSelection(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        BlockPos pos1 = BBSectionStateCache.getPos1(player);
        BlockPos pos2 = BBSectionStateCache.getPos2(player);
        player.sendSystemMessage(Component.literal("§6========== 当前选区 =========="));
        if (pos1 == null && pos2 == null) {
            player.sendSystemMessage(Component.literal("§c尚未设置任何选点"));
        } else {
            showPos(player,pos1,POS1_SETTER);
            showPos(player,pos2,POS2_SETTER);
        }
        player.sendSystemMessage(Component.literal("§6================================"));
        return 1;
    }

    private int clearSelection(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        BBSectionStateCache.clearSelection(player);
        player.sendSystemMessage(Component.literal("§a选区已清除"));
        return 1;
    }

    private int bindAreaBeds(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        if (!BBSectionStateCache.hasSelection(player)) {
            player.sendSystemMessage(Component.literal("§c请先用 pos1/pos2 设置选区"));
            return 0;
        }
        BlockPos pos1 = BBSectionStateCache.getPos1(player);
        BlockPos pos2 = BBSectionStateCache.getPos2(player);
        int count = VillagerBindServer.bindAreaBeds(player.level(), player, pos1, pos2);
        player.sendSystemMessage(Component.literal(
                String.format("§a成功绑定 %d 张床", count)
        ));
        return 1;
    }


    //工具方法
    private int setPos(ServerPlayer player, BlockPos pos, int posSeter){
        if (posSeter == POS1_SETTER) {
            BBSectionStateCache.setPos1(player, pos);
        }else if (posSeter == POS2_SETTER) {
            BBSectionStateCache.setPos2(player, pos);
        }
        showPosSuccessMessage(player,pos,posSeter);
        return 1;
    }

    private void showPos(ServerPlayer player, BlockPos pos, int posSeter) {
        if (pos != null) {
            showPosSuccessMessage(player,pos,posSeter);
        } else {
            sendToPlayer(player, Component.translatable("message.selection.pos_not_set",posSeter));
        }
    }

    private void showPosSuccessMessage(ServerPlayer player, BlockPos pos, int posSeter) {
        sendToPlayer(player, Component.translatable(
                "message.selection.pos_set",
                posSeter, pos.getX(), pos.getY(), pos.getZ()
        ));
    }
}