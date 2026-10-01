package com.villagerdetails.handler.block;

import com.villagerdetails.network.BlockRuleSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 方块规则在「挖掘进度 / 破坏掉落」这类玩家相关调用链里的上下文。
 * <p>
 * {@code getDestroySpeed} 与 {@code getDrops} 本身没有玩家参数，无法判断当前操作者是否装了本 mod。
 * 因此由外层带玩家的方法（{@code getDestroyProgress} / {@code playerDestroy}）在进入时把玩家塞进
 * ThreadLocal，内层据此决定是否对「未安装本 mod 的客户端」回退原版逻辑。
 */
public final class BlockRuleContext {

    private static final ThreadLocal<ServerPlayer> MINING_PLAYER = new ThreadLocal<>();

    private BlockRuleContext() {
    }

    /** 进入一次玩家相关的方块操作，记录当前操作玩家（客户端玩家 / 非玩家传 null）。 */
    public static void begin(ServerPlayer player) {
        MINING_PLAYER.set(player);
    }

    public static void end() {
        MINING_PLAYER.remove();
    }

    /** 当前操作者是否为「未安装本 mod 的客户端」，是则对其走原版逻辑。 */
    public static boolean isVanillaClient() {
        ServerPlayer player = MINING_PLAYER.get();
        return player != null && !ServerPlayNetworking.canSend(player, BlockRuleSyncPayload.TYPE);
    }
}
