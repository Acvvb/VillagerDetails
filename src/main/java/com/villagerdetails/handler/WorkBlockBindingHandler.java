package com.villagerdetails.handler;

import com.villagerdetails.cache.SelectionState;
import com.villagerdetails.util.BindingToolUtils;
import com.villagerdetails.util.SendMessengerUtils;
import com.villagerdetails.util.VillagerWorkBlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;


import java.util.UUID;

public class WorkBlockBindingHandler {
    private static final String TOOL_NAME = "workblock";
    private static final String PREFIX = "msg.villager_bed.work_block";

    // ==================== 右键村民：选中村民 ====================
    public static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity,EntityHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.PASS;
        if (!(entity instanceof Villager villager)) return InteractionResult.PASS;
        if (BindingToolUtils.isNotHoldingTool(player, hand, TOOL_NAME)) return InteractionResult.PASS;

        UUID playerUuid = player.getUUID();
        UUID villagerUuid = villager.getUUID();
        SelectionState.setSelectedVillager(playerUuid, villagerUuid);

        SendMessengerUtils.sendOrBroadcastActionBar(
                (ServerPlayer) player,
                Component.translatable(PREFIX + ".select.success", villagerUuid.toString().substring(0, 8))
        );
        return InteractionResult.SUCCESS;
    }

    // ==================== 右键方块：绑定工作方块 ====================
    public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.PASS;
        if (BindingToolUtils.isNotHoldingTool(player, hand, TOOL_NAME)) return InteractionResult.PASS;

        UUID playerUuid = player.getUUID();
        UUID villagerUuid = SelectionState.getSelectedVillager(playerUuid);
        if (villagerUuid == null) return InteractionResult.PASS;

        BlockPos clickedPos = hitResult.getBlockPos();
        player.getItemInHand(hand).shrink(1);

        ServerLevel serverLevel = (ServerLevel) level;
        ServerPlayer operator = (ServerPlayer) player;
        boolean success = VillagerWorkBlockUtils.bindVillagerWorkBlock(serverLevel, operator, villagerUuid, clickedPos);

        if (success) {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable(PREFIX + ".bind.success",
                            villagerUuid.toString().substring(0, 8), clickedPos.toShortString())
            );
        } else {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable(PREFIX + ".bind.fail")
            );
        }

        SelectionState.clearSelectedVillager(playerUuid);
        return InteractionResult.SUCCESS;
    }
}