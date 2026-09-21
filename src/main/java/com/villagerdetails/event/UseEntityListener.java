package com.villagerdetails.event;

import com.villagerdetails.cache.SelectionState;
import com.villagerdetails.event.type.BindingType;
import com.villagerdetails.handler.BindHandler;
import com.villagerdetails.util.SendMessengerUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import org.apache.logging.log4j.LogManager;

import java.util.UUID;

public class UseEntityListener {

    public static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.PASS;
        BindingType type = BindingToolValidator.isHoldingAnyTool(player,hand,entity);
        if (type == null) return InteractionResult.PASS;
        UUID playerUuid = player.getUUID();
        UUID entityUUID = entity.getUUID();
        SelectionState.setSelectedEntity(playerUuid, entityUUID);
        if (SelectionState.isEnd(playerUuid)) {
            BindHandler.chooseUtil(level, player, hand);
        }else {
            String entityName = Component.translatable(entity.getType().getDescriptionId()).getString();
            SendMessengerUtils.sendOrBroadcastActionBar(
                    (ServerPlayer) player,
                    Component.translatable("msg.entity.select.success", entityName,entityUUID.toString())
            );
            LogManager.getLogger(UseEntityListener.class).debug("玩家 {} 选中了实体({}): {}", playerUuid, entityName, entityUUID);
        }
        return InteractionResult.SUCCESS;
    }

}
