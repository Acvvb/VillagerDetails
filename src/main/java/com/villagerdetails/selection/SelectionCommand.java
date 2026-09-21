package com.villagerdetails.selection;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

/**
 * 选区管理命令
 * <p>
 * /villagerdetails selection pos1  - 将玩家当前所在方块设为 pos1
 * /villagerdetails selection pos2  - 将玩家当前所在方块设为 pos2
 * /villagerdetails selection show  - 显示当前选区信息
 * /villagerdetails selection clear - 清除当前选区
 * </p>
 */
public class SelectionCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> selection = Commands.literal("selection")
                .then(Commands.literal("pos1").executes(SelectionCommand::setPos1))
                .then(Commands.literal("pos2").executes(SelectionCommand::setPos2))
                .then(Commands.literal("show").executes(SelectionCommand::showSelection))
                .then(Commands.literal("clear").executes(SelectionCommand::clearSelection));

        dispatcher.register(selection);
    }

    private static int setPos1(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        BlockPos pos = player.blockPosition();
        SelectionManager.setPos1(player, pos);
        player.sendSystemMessage(Component.translatable(
                "message.selection.pos1_set",
                pos.getX(), pos.getY(), pos.getZ()
        ));
        return 1;
    }

    private static int setPos2(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        BlockPos pos = player.blockPosition();
        SelectionManager.setPos2(player, pos);
        player.sendSystemMessage(Component.translatable(
                "message.selection.pos2_set",
                pos.getX(), pos.getY(), pos.getZ()
        ));
        return 1;
    }

    private static int showSelection(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        BlockPos pos1 = SelectionManager.getPos1(player);
        BlockPos pos2 = SelectionManager.getPos2(player);

        player.sendSystemMessage(Component.literal("§6========== 当前选区 =========="));
        if (pos1 == null && pos2 == null) {
            player.sendSystemMessage(Component.literal("§c尚未设置任何选点"));
        } else {
            if (pos1 != null) {
                player.sendSystemMessage(Component.translatable(
                        "message.selection.pos1_set",
                        pos1.getX(), pos1.getY(), pos1.getZ()
                ));
            } else {
                player.sendSystemMessage(Component.translatable("message.selection.pos1_not_set"));
            }
            if (pos2 != null) {
                player.sendSystemMessage(Component.translatable(
                        "message.selection.pos2_set",
                        pos2.getX(), pos2.getY(), pos2.getZ()
                ));
            } else {
                player.sendSystemMessage(Component.translatable("message.selection.pos2_not_set"));
            }
        }
        player.sendSystemMessage(Component.literal("§6================================"));
        return 1;
    }

    private static int clearSelection(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return 0;

        SelectionManager.clearSelection(player);
        player.sendSystemMessage(Component.literal("§a选区已清除"));
        return 1;
    }
}