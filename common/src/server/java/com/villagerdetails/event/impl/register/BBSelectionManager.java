package com.villagerdetails.event.impl.register;

import com.villagerdetails.cache.impl.BBSectionStateCache;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.handler.binding.bbselection.SelectionInteractionHandler;
import com.villagerdetails.network.sync.clientbound.BBSyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.BBSyncPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static com.villagerdetails.cache.RuleCache.isEnableOfListener;
import static com.villagerdetails.event.type.ListenerType.BLOCK_RANGE_SELECTION;

/**
 * 选区事件注册器。
 *
 * <p>除了「用拴绳设点」的两条交互，这里还负责一条会话卫生规则：
 * <b>玩家换了维度，旧选区直接作废</b>。维度的记录在 {@link BBSectionStateCache} 里
 * （设点时记下玩家当时所在维度），每 tick 巡检一次，清掉后顺手把空选区推给客户端，
 * 免得客户端的高亮框一直挂在旧维度上。
 */
public class BBSelectionManager implements ServerEventRegisterServer {

    @Override
    public void register() {
        AttackBlockCallback.EVENT.register(BBSelectionManager::onAttackBlock);
        UseBlockCallback.EVENT.register(BBSelectionManager::onRightClickBlock);
        ServerTickEvents.END_SERVER_TICK.register(BBSelectionManager::onEndServerTick);
    }

    /**
     * 每 tick 巡检一次：玩家换过维度就把旧选区清掉，并同步给客户端
     */
    private static void onEndServerTick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (BBSectionStateCache.clearIfDimensionChanged(player)) {
                syncTo(player);
            }
        }
    }

    private static InteractionResult onAttackBlock(Player player, Level level, InteractionHand hand, BlockPos pos, Direction direction) {
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (sp.isSpectator()) return InteractionResult.PASS;
        if (!isEnableOfListener(sp, BLOCK_RANGE_SELECTION)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        boolean handled = SelectionInteractionHandler.onLeftClickBlock(sp, pos);
        if (handled) {
            syncTo(sp);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult onRightClickBlock(Player player, Level level, InteractionHand interactionHand, BlockHitResult blockHitResult) {
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (!isEnableOfListener(sp, BLOCK_RANGE_SELECTION)) return InteractionResult.PASS;
        if (interactionHand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (blockHitResult.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
        if (SelectionInteractionHandler.onRightClickBlock(sp, blockHitResult.getBlockPos())) {
            syncTo(sp);
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