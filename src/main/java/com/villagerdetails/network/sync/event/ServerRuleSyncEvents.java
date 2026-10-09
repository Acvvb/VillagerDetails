package com.villagerdetails.network.sync.event;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.SyncEvents;
import com.villagerdetails.network.sync.clientbound.ServerRuleSyncChannel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * 规则状态同步（{@link ServerRuleSyncChannel}）的事件：玩家加入 / 断开、规则变化、定期复查权限。
 *
 * <p>放在 {@code network.sync.event} 包下 = 自动注册，不用手动登记。
 */
public final class ServerRuleSyncEvents implements SyncEvents {

    /**
     * 每隔多少 tick 复查一次权限。
     *
     * <p>玩家被 {@code /op} / {@code /deop}、或者权限插件改了权限时没有任何事件可听，
     * 只能自己定期比一次。
     */
    private static final int PERMISSION_RECHECK_TICKS = 20;

    public static final ServerRuleSyncEvents INSTANCE = new ServerRuleSyncEvents();

    private ServerRuleSyncEvents() {
    }

    @Override
    public void registerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, _, _) -> {
            // 刚进来的先忘掉旧记录，保证一定会推一份
            ServerRuleSyncChannel.INSTANCE.forgetLastSynced(handler.getPlayer());
            ServerRuleSyncChannel.INSTANCE.syncTo(handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) ->
                ServerRuleSyncChannel.INSTANCE.forgetLastSynced(handler.getPlayer()));

        // 任意规则状态变化 → 广播
        RuleCache.addListener((_, _, _) -> ServerRuleSyncChannel.INSTANCE.broadcastAll());

        // 定期复查权限：被下 op / 权限被改时，客户端那边会跟着刷新
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERMISSION_RECHECK_TICKS != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerRuleSyncChannel.INSTANCE.syncTo(player);
            }
        });
    }
}
