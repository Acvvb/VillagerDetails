package com.villagerdetails.event;

import com.villagerdetails.handler.binding.bbselection.SelectionInteractionHandler;
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

    private static boolean onLeftClickBlock(Level level, Player player, BlockPos blockPos, BlockState blockState, @Nullable BlockEntity blockEntity) {
        if (!isEnableOfListener(BLOCK_RANGE_SELECTION)) return true; // 未启用时放行，正常破坏
        if (!(player instanceof ServerPlayer sp)) return true;
        // 调用原有的左键处理逻辑，返回 false 表示取消破坏
        return !SelectionInteractionHandler.onLeftClickBlock(sp, blockPos);
    }

    private static InteractionResult onRightClickBlock(Player player, Level level, InteractionHand interactionHand, BlockHitResult blockHitResult) {
        if (!isEnableOfListener(BLOCK_RANGE_SELECTION)) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (interactionHand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (blockHitResult.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
        if (SelectionInteractionHandler.onRightClickBlock(sp, blockHitResult.getBlockPos())) {
            return InteractionResult.SUCCESS; // 取消原右键行为
        }
        return InteractionResult.PASS;
    }
}