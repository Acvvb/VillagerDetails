package com.villagerdetails.command.c.impl.tool;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.villagerdetails.cache.BBSectionStateCache;
import com.villagerdetails.command.c.server.RegisterServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import static com.villagerdetails.handler.binding.bbselection.SelectionInteractionHandler.*;
import static com.villagerdetails.util.SendMessengerUtils.sendOrBroadcast;

public class BBSelectionServerImpl implements RegisterServer {

    public static final BBSelectionServerImpl INSTANCE = new BBSelectionServerImpl();

    private BBSelectionServerImpl() {
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal(BLOCK_BLOCK_SECTION)
                .executes(this::showHelp)
                .then(Commands.literal("pos1")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> setPosWithArgs(ctx, POS1_SETTER)))
                        .executes(ctx -> setPosAtPlayer(ctx, POS1_SETTER)))
                .then(Commands.literal("pos2")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> setPosWithArgs(ctx, POS2_SETTER)))
                        .executes(ctx -> setPosAtPlayer(ctx, POS2_SETTER)))
                .then(Commands.literal("show").executes(this::showSelection))
                .then(Commands.literal("clear").executes(this::clearSelection));
    }

    private int showHelp(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        MutableComponent msg = Component.empty()
                .append(Component.translatable("message.selection.help.header"))
                .append("\n")
                .append(Component.translatable("message.selection.help.pos1"))
                .append("\n")
                .append(Component.translatable("message.selection.help.pos2"))
                .append("\n")
                .append(Component.translatable("message.selection.help.show"))
                .append("\n")
                .append(Component.translatable("message.selection.help.clear"));
        sendOrBroadcast(player, msg);
        return 1;
    }

    private int setPosAtPlayer(CommandContext<CommandSourceStack> context, int posSeter) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        BlockPos pos = player.blockPosition();
        return setPos(player, pos, posSeter);
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
        if (pos1 == null && pos2 == null) {
            sendOrBroadcast(player, Component.translatable("message.selection.no_selection"));
        } else {
            showPos(player, pos1, POS1_SETTER);
            showPos(player, pos2, POS2_SETTER);
        }
        return 1;
    }

    private int clearSelection(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;
        BBSectionStateCache.clearSelection(player);
        sendOrBroadcast(player, Component.translatable("message.selection.cleared"));
        return 1;
    }

    //工具方法
    private int setPos(ServerPlayer player, BlockPos pos, int posSeter) {
        if (posSeter == POS1_SETTER) {
            BBSectionStateCache.setPos1(player, pos);
        } else if (posSeter == POS2_SETTER) {
            BBSectionStateCache.setPos2(player, pos);
        }
        showPosSuccessMessage(player, pos, posSeter);
        return 1;
    }

    private void showPos(ServerPlayer player, BlockPos pos, int posSeter) {
        if (pos != null) {
            showPosSuccessMessage(player, pos, posSeter);
        } else {
            sendOrBroadcast(player, Component.translatable("message.selection.pos_not_set", posSeter));
        }
    }

}