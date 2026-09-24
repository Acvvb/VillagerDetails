package com.villagerdetails.event;

import com.villagerdetails.cache.EBSelectionStateCache;
import com.villagerdetails.handler.binding.BindHandler;
import com.villagerdetails.handler.binding.BindingUtil;
import com.villagerdetails.handler.binding.type.BindingType;
import com.villagerdetails.util.SendMessengerUtils;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

import static com.villagerdetails.cache.RuleCache.isEnableOfListener;
import static com.villagerdetails.event.type.ListenerType.ENTITY_BLOCK_SELECTION;

public class EBSectionManager {

    private static final Logger log = LogManager.getLogger(EBSectionManager.class);

    public static void init() {
        UseEntityCallback.EVENT.register(EBSectionManager::onUseEntity);
        UseBlockCallback.EVENT.register(EBSectionManager::onUseBlock);
    }

    public static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hitResult) {
        if (!isEnableOfListener(ENTITY_BLOCK_SELECTION)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.PASS;
        BindingType type = BindingUtil.isHoldingAnyTool(player,hand,entity);
        if (type == null) return InteractionResult.PASS;
        UUID playerUuid = player.getUUID();
        UUID entityUUID = entity.getUUID();
        EBSelectionStateCache.setSelectedEntity(playerUuid, entityUUID);
        if (EBSelectionStateCache.isEnd(playerUuid)) {
            BindHandler.chooseUtil(level, player, hand);
        }else {
            String entityName = Component.translatable(entity.getType().getDescriptionId()).getString();
            SendMessengerUtils.sendOverlayOrBroadcast(
                    (ServerPlayer) player,
                    Component.translatable("msg.entity.select.success", entityName,entityUUID.toString())
            );
            log.debug("玩家 {} 选中了实体({}): {}", playerUuid, entityName, entityUUID);
        }
        return InteractionResult.SUCCESS;
    }

    public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        if (!isEnableOfListener(ENTITY_BLOCK_SELECTION)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.PASS;
        UUID playerUuid = player.getUUID();
        BlockPos clickedPos = hitResult.getBlockPos();
        ServerPlayer operator = (ServerPlayer) player;
        EBSelectionStateCache.setSelectedBlock(playerUuid,clickedPos);
        BindingType type = BindingUtil.isHoldingAnyTool(player,hand,null);
        if (type == null) return InteractionResult.PASS;
        if (EBSelectionStateCache.isEnd(playerUuid)){
            if (BindHandler.chooseUtil(level, player, hand)) return InteractionResult.SUCCESS;
        }else {
            SendMessengerUtils.sendOverlayOrBroadcast(operator,
                    Component.translatable("msg.block.select.success",
                            level.getBlockState(clickedPos).getBlock().getName(),
                            "%d, %d, %d".formatted(clickedPos.getX(), clickedPos.getY(), clickedPos.getZ())
                    ));
        }
        return InteractionResult.PASS;
    }
}
