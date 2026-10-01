package com.villagerdetails.network;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.BlockCollectableConfig;
import com.villagerdetails.config.impl.BlockMiningResistanceConfig;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 方块规则的客户端同步：玩家加入时下发一次，规则开闭或配置变化时广播给所有在线玩家。
 * <p>
 * 未安装本 mod 的客户端收不到该 payload，{@link ServerPlayNetworking#canSend} 会返回 false，
 * 服务端据此让这类玩家走原版逻辑。
 */
public final class BlockRuleSyncHandler {

    private BlockRuleSyncHandler() {
    }

    public static void register() {
        // 玩家加入时同步当前配置
        ServerPlayConnectionEvents.JOIN.register((handler, _, _) ->
                syncTo(handler.getPlayer()));

        // 规则开/关变化时广播
        RuleCache.addListener((type, _, _) -> {
            if (type != RuleType.BLOCK_MINING_RESISTANCE && type != RuleType.BLOCK_COLLECTABLE) {
                return;
            }
            broadcast();
        });

        // 规则开关变化时刷新在线玩家的命令树，让 /ec c 子命令的补全即时更新（不用重进服务器）
        RuleCache.addListener((type, _, _) -> {
            MinecraftServer server = RuleCache.getServer();
            if (server == null) return;
            if (type.getCommandObject() == null) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                server.getCommands().sendCommands(player);
            }
        });
    }

    public static void syncTo(ServerPlayer player) {
        if (player == null) return;
        if (!ServerPlayNetworking.canSend(player, BlockRuleSyncPayload.TYPE)) return;

        boolean resistanceEnabled = RuleCache.isEnabled(RuleType.BLOCK_MINING_RESISTANCE);
        boolean collectableEnabled = RuleCache.isEnabled(RuleType.BLOCK_COLLECTABLE);
        String resistance = BlockMiningResistanceConfig.toSyncString();
        String collectable = BlockCollectableConfig.toSyncString();

        ServerPlayNetworking.send(player,
                new BlockRuleSyncPayload(resistanceEnabled, collectableEnabled, resistance, collectable));
    }

    public static void broadcast() {
        MinecraftServer server = RuleCache.getServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncTo(player);
        }
    }
}
