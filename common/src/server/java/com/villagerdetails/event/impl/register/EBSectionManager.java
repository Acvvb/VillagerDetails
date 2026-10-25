package com.villagerdetails.event.impl.register;

import com.villagerdetails.cache.impl.EBSelectionStateCache;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.handler.binding.BindHandler;
import com.villagerdetails.handler.binding.BindingUtil;
import com.villagerdetails.handler.binding.type.BindingType;
import com.villagerdetails.network.sync.clientbound.EBSyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.EBSyncPayload;
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

public class EBSectionManager implements ServerEventRegisterServer {

    private static final Logger log = LogManager.getLogger(EBSectionManager.class);

    @Override
    public void register() {
        UseEntityCallback.EVENT.register(EBSectionManager::onUseEntity);
        UseBlockCallback.EVENT.register(EBSectionManager::onUseBlock);
    }

    public static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (!isEnableOfListener(serverPlayer, ENTITY_BLOCK_SELECTION)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        // 只有手持正确的工具时才进入选择流程，避免误缓存
        BindingType type = BindingUtil.isHoldingAnyTool(player, hand, entity);
        if (type == null) return InteractionResult.PASS;

        UUID playerUuid = player.getUUID();
        UUID entityUuid = entity.getUUID();
        EBSelectionStateCache.selectEntity(playerUuid, entityUuid);

        // 状态变了，推给客户端（此时方块可能还没选，payload 里对应字段为 null）
        syncTo(serverPlayer, playerUuid);

        // 实体 + 方块都已选中 → 触发绑定
        if (EBSelectionStateCache.isComplete(playerUuid)) {
            BindHandler.chooseUtil(level, player, hand);
            // 按缓存现状推高亮：绑定成功 / 已进入绑定流程 → 缓存空了 → 客户端跟着清空；
            // 校验没过（工具换了、目标不是村民）→ 选区还在 → 客户端继续保持高亮，玩家直接重试即可
            syncTo(serverPlayer, playerUuid);
            return InteractionResult.SUCCESS;
        }

        Component entityName = Component.translatable(entity.getType().getDescriptionId());
        SendMessengerUtils.sendOverlayOrBroadcast(
                serverPlayer,
                Component.translatable("msg.entity.select.success", entityName, entityUuid.toString())
        );
        log.debug("玩家 {} 选中了实体({}): {}", playerUuid, entityName, entityUuid);
        return InteractionResult.SUCCESS;
    }

    public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (!isEnableOfListener(serverPlayer, ENTITY_BLOCK_SELECTION)) return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        // 先校验工具，再缓存方块，避免未拿工具时污染方块缓存
        BindingType type = BindingUtil.isHoldingAnyTool(player, hand, null);
        if (type == null) return InteractionResult.PASS;

        UUID playerUuid = player.getUUID();
        BlockPos clickedPos = hitResult.getBlockPos();
        EBSelectionStateCache.selectBlock(playerUuid, clickedPos);

        // 状态变了，推给客户端（此时实体可能还没选，payload 里对应字段为 null）
        syncTo(serverPlayer, playerUuid);

        // 实体 + 方块都已选中 → 触发绑定
        if (EBSelectionStateCache.isComplete(playerUuid)) {
            BindHandler.chooseUtil(level, player, hand);
            // 按缓存现状推高亮：绑定成功 / 已进入绑定流程 → 缓存空了 → 客户端跟着清空；
            // 校验没过（工具换了、目标不是村民）→ 选区还在 → 客户端继续保持高亮，玩家直接重试即可
            syncTo(serverPlayer, playerUuid);
            return InteractionResult.SUCCESS;
        }

        Component blockName = level.getBlockState(clickedPos).getBlock().getName();
        SendMessengerUtils.sendOverlayOrBroadcast(
                serverPlayer,
                Component.translatable("msg.block.select.success",
                        blockName,
                        "%d, %d, %d".formatted(clickedPos.getX(), clickedPos.getY(), clickedPos.getZ())
                )
        );
        return InteractionResult.SUCCESS;
    }

    /**
     * 把该玩家「当前完整选择状态」推给客户端（用于客户端高亮）。
     *
     * <p>从缓存里读，不接收外部变量 —— {@code selectEntity} / {@code selectBlock}
     * 已经把值写进去了，这里只管读出来发。
     *
     * <p>绑定流程走完也调它（而不是显式发一条 null）：缓存被摘掉时读出来自然就是两个 null，
     * 客户端高亮跟着消失；校验没过、选区还在时，推出的就是当前的选区，高亮不会莫名清空。
     * 一条路径同时覆盖"清空"和"保持"，也就不会出现服务端与客户端不一致的中间态。
     */
    private static void syncTo(ServerPlayer player, UUID playerUuid) {
        EBSyncChannel.INSTANCE.sendTo(player, new EBSyncPayload(
                EBSelectionStateCache.getSelectedEntity(playerUuid),
                EBSelectionStateCache.getSelectedBlock(playerUuid)));
    }
}