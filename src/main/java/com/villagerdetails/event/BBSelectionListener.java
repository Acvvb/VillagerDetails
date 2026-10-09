package com.villagerdetails.event;

import com.villagerdetails.cache.BBSectionStateCache;
import com.villagerdetails.handler.binding.bbselection.SelectionInteractionHandler;
import com.villagerdetails.network.sync.BBSyncChannel;
import com.villagerdetails.network.sync.BBSyncPayload;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

import static com.villagerdetails.cache.RuleCache.isEnableOfListener;
import static com.villagerdetails.event.type.ListenerType.BLOCK_RANGE_SELECTION;

/**
 * 选区事件注册器
 */
public class BBSelectionListener {

    public static void init() {
        PlayerBlockBreakEvents.BEFORE.register(BBSelectionListener::onLeftClickBlock);
        UseBlockCallback.EVENT.register(BBSelectionListener::onRightClickBlock);
    }

    private static boolean onLeftClickBlock(Level level, Player player, BlockPos blockPos,
                                            BlockState blockState, @Nullable BlockEntity blockEntity) {
        if (!(player instanceof ServerPlayer sp)) return true;
        if (!isEnableOfListener(sp, BLOCK_RANGE_SELECTION)) return true;
        boolean handled = SelectionInteractionHandler.onLeftClickBlock(sp, blockPos);
        if (handled) {
            syncTo(sp);          // ← 状态变了，推给客户端
        }
        return !handled;
    }

    private static InteractionResult onRightClickBlock(Player player, Level level,
                                                       InteractionHand interactionHand,
                                                       BlockHitResult blockHitResult) {
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (!isEnableOfListener(sp, BLOCK_RANGE_SELECTION)) return InteractionResult.PASS;
        if (interactionHand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (blockHitResult.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
        if (SelectionInteractionHandler.onRightClickBlock(sp, blockHitResult.getBlockPos())) {
            syncTo(sp);          // ← 状态变了，推给客户端
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /**
     * 把该玩家当前的 pos1 / pos2 推给客户端（用于客户端高亮）。
     *
     * <p>从 {@link BBSectionStateCache} 读，不接收外部变量 ——
     * {@code SelectionInteractionHandler} 已经把值写进去了。
     */
    private static void syncTo(ServerPlayer player) {
        BBSyncChannel.INSTANCE.sendTo(player, new BBSyncPayload(
                BBSectionStateCache.getPos1(player),
                BBSectionStateCache.getPos2(player)));
    }
}