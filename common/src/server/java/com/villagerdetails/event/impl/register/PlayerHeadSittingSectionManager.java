package com.villagerdetails.event.impl.register;

import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.handler.player.headSitting.PlayerHeadSittingHandler;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/**
 * 「玩家头顶乘坐」的事件入口。
 *
 * <p>这里只负责：
 * <ol>
 *     <li>把事件注册到 {@link PlayerHeadSittingHandler}；</li>
 *     <li>做「端侧 / 手 / 目标类型 / 空手」这类与业务无关的校验；</li>
 *     <li>通过后把上下文交给 {@link PlayerHeadSittingHandler#trySit}。</li>
 * </ol>
 *
 * <p>规则开关、同意判定、叠罗汉、防穿模、巡检踢人全部在 Handler 里。
 */
public final class PlayerHeadSittingSectionManager implements ServerEventRegisterServer {

    private PlayerHeadSittingSectionManager() {
    }

    @Override
    public void register() {
        UseEntityCallback.EVENT.register(PlayerHeadSittingSectionManager::onUseEntity);
        ServerTickEvents.END_SERVER_TICK.register(PlayerHeadSittingHandler::onEndServerTick);
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(
                (player, _, _) -> PlayerHeadSittingHandler.onPlayerChangeLevel(player));
    }

    public static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand,
                                                Entity entity, EntityHitResult hitResult) {
        // 只在逻辑服务端判定；客户端不预测
        if (level.isClientSide()) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer rider)) return InteractionResult.PASS;
        if (!(entity instanceof ServerPlayer target)) return InteractionResult.PASS;

        // 空手才是「要坐上去」的手势，手里有东西交给原版
        if (!rider.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;

        return PlayerHeadSittingHandler.trySit(rider, target, hand);
    }
}