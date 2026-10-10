package com.villagerdetails.network.sync.event;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.SyncEvents;
import com.villagerdetails.network.sync.clientbound.BlockRuleSyncChannel;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

/**
 * 逐条配置同步（{@link BlockRuleSyncChannel}）的事件：玩家加入下发、规则开关变化广播、命令树刷新。
 *
 * <p>放在 {@code network.sync.event} 包下 = 自动注册，不用手动登记。
 */
public final class BlockRuleSyncEvents implements SyncEvents {

    public static final BlockRuleSyncEvents INSTANCE = new BlockRuleSyncEvents();

    private BlockRuleSyncEvents() {
    }

    @Override
    public void registerEvents() {
        // 玩家加入时同步当前配置
        ServerPlayConnectionEvents.JOIN.register((handler, _, _) ->
                BlockRuleSyncChannel.INSTANCE.syncTo(handler.getPlayer()));

        // 这些规则开/关变化时广播
        RuleCache.addListener((type, _, _) -> {
            switch (type) {
                case BLOCK_MINING_RESISTANCE, BLOCK_COLLECTABLE,
                     BLOCK_EXPLOSION_RESISTANCE, VILLAGER_NO_SQUEEZE -> BlockRuleSyncChannel.INSTANCE.broadcastAll();
                default -> {
                }
            }
        });

        // 规则开关变化时刷新在线玩家的命令树，让 /ec c 子命令的补全即时更新（不用重进服务器）
        RuleCache.addListener((type, _, _) -> {
            if (type.getCommandObject() == null) {
                return;
            }
            // 静态引用只读一次并判空：停机放掉 server 之后，lambda 里再读一次就可能拿到 null
            MinecraftServer server = RuleCache.getServer();
            if (server == null) {
                return;
            }
            BlockRuleSyncChannel.INSTANCE.forEachPlayer(player ->
                    server.getCommands().sendCommands(player));
        });
    }
}
