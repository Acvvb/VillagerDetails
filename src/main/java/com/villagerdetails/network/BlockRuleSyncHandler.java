package com.villagerdetails.network;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 方块规则的客户端同步：玩家加入时下发一次，规则变更时广播给所有在线玩家。
 * <p>
 * 未安装本 mod 的客户端收不到该 payload，{@link ServerPlayNetworking#canSend} 会返回 false，
 * 服务端据此让这类玩家走原版逻辑。
 */
public final class BlockRuleSyncHandler {

    private BlockRuleSyncHandler() {
    }

    public static void register() {
        // 玩家加入时同步当前配置
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                syncTo(handler.getPlayer()));

        // 规则变化时广播给所有在线（装了 mod 的）玩家
        RuleCache.addListener((type, oldValue, newValue) -> {
            if (type != RuleType.BLOCK_MINING_RESISTANCE && type != RuleType.BLOCK_COLLECTABLE) {
                return;
            }
            MinecraftServer server = RuleCache.getServer();
            if (server == null) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                syncTo(player);
            }
        });
    }

    public static void syncTo(ServerPlayer player) {
        if (player == null) return;
        if (!ServerPlayNetworking.canSend(player, BlockRuleSyncPayload.TYPE)) return;

        String resistance = RuleCache.getState(RuleType.BLOCK_MINING_RESISTANCE);
        String collectable = RuleCache.getState(RuleType.BLOCK_COLLECTABLE);
        ServerPlayNetworking.send(player, new BlockRuleSyncPayload(resistance, collectable));
    }
}
