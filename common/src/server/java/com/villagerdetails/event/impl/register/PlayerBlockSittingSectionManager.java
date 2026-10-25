package com.villagerdetails.event.impl.register;

import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.handler.player.blockSitting.PlayerBlockSittingHandler;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 「坐在楼梯 / 半砖上」的事件入口。
 *
 * <p>这里只做两件事：
 * <ol>
 *     <li>把事件注册到 {@link PlayerBlockSittingHandler}；</li>
 *     <li>做「端侧 / 手 / 空手」这类不需要业务知识的校验，通过后把上下文
 *         原样交给 {@link PlayerBlockSittingHandler#trySit}。</li>
 * </ol>
 *
 * <p>业务判定（能不能坐、规则开关、玩家偏好、生成座位、巡检收尾）一律在
 * {@link PlayerBlockSittingHandler} 里。
 */
public final class PlayerBlockSittingSectionManager implements ServerEventRegisterServer {

    private PlayerBlockSittingSectionManager() {
    }

    @Override
    public void register() {
        UseBlockCallback.EVENT.register(PlayerBlockSittingSectionManager::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(PlayerBlockSittingHandler::onEndServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register((MinecraftServer _) -> PlayerBlockSittingHandler.clearState());
    }

    public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
                                               BlockHitResult hitResult) {
        // 1. 客户端拦截：阻止坐标异常时发送导致的 "build.tooHigh"
        if (level.isClientSide()) {
            if (level.isOutsideBuildHeight(hitResult.getBlockPos())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }

        // 2. 基础类型 / 状态校验（与业务无关，留在入口）
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer rider)) return InteractionResult.PASS;
        if (!rider.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;

        // 3. 委托业务
        return PlayerBlockSittingHandler.trySit(rider, level, hitResult);
    }
}