package com.villagerdetails.event;

import com.villagerdetails.cache.SelectionState;
import com.villagerdetails.event.type.BindingType;
import com.villagerdetails.util.SendMessengerUtils;
import com.villagerdetails.util.entity.VillagerBindingUtils;
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

import java.util.UUID;

public class UseBlockListener {

    public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.PASS;

        UUID playerUuid = player.getUUID();
        UUID entityUuid = SelectionState.getSelectedVillager(playerUuid);
        if (entityUuid == null) return InteractionResult.PASS;

        BindingType type = BindingType.isHoldingAnyTool(player,hand,level.getEntity(entityUuid));
        if (type == null) return InteractionResult.PASS;

        BlockPos clickedPos = hitResult.getBlockPos();
        player.getItemInHand(hand).shrink(1);

        ServerLevel serverLevel = (ServerLevel) level;
        ServerPlayer operator = (ServerPlayer) player;

        if (chooseUtil(serverLevel, operator, entityUuid, clickedPos, type)) {
            SendMessengerUtils.sendOrBroadcastActionBar(operator,
                    Component.translatable("msg.villager.bind.success",
                            entityUuid.toString(),
                            Component.translatable(type.getI18nPrefix()),
                            clickedPos.toShortString())
            );
        }
        SelectionState.clearSelectedVillager(player.getUUID());
        return InteractionResult.SUCCESS;
    }

    private static boolean chooseUtil(ServerLevel level, ServerPlayer operator, UUID entityUuid, BlockPos targetPos, BindingType type) {
        Entity entity = level.getEntity(entityUuid);
        if (entity instanceof Villager) {
            return VillagerBindingUtils.bindVillager(level, operator, entityUuid, targetPos, type);
        }
        return false;
    }

}
