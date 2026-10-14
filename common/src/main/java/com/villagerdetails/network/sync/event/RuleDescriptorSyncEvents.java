package com.villagerdetails.network.sync.event;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.SyncEvents;
import com.villagerdetails.network.sync.clientbound.RuleDescriptorSyncChannel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * 描述符同步（{@link RuleDescriptorSyncChannel}）的事件接线：
 * 玩家加入 / 断开、任意规则变化、定期复查权限。
 *
 * <p>放在 {@code network.sync.event} 包下 = 自动注册。
 *
 * <p>进服时推的那一份用服务端当前语言（客户端还没打招呼）；客户端随即发
 * {@code rule_descriptor_hello}，带上自己的界面语言，服务端再推一份语言正确的 ——
 * 内容变了才会真发，所以正常情况下就是两次包（一次兜底、一次按语言）。
 */
public final class RuleDescriptorSyncEvents implements SyncEvents {

    /**
     * 每隔多少 tick 复查一次权限。
     *
     * <p>玩家被 {@code /op} / {@code /deop}、或者权限插件改了权限时没有任何事件可听，
     * 只能自己定期比一次。
     */
    private static final int PERMISSION_RECHECK_TICKS = 20;

    public static final RuleDescriptorSyncEvents INSTANCE = new RuleDescriptorSyncEvents();

    private RuleDescriptorSyncEvents() {
    }

    @Override
    public void registerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, _, _) -> {
            // 刚进来的先忘掉旧内容（语言留着：客户端可能已经打过招呼了），保证一定会推一份
            RuleDescriptorSyncChannel.INSTANCE.forget(handler.getPlayer());
            RuleDescriptorSyncChannel.INSTANCE.syncTo(handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) ->
                RuleDescriptorSyncChannel.INSTANCE.release(handler.getPlayer()));

        // 任意规则状态变化（开 / 关、权限等级、语言…）→ 广播
        RuleCache.addListener((_, _, _) -> RuleDescriptorSyncChannel.INSTANCE.broadcastAll());

        // 定期复查权限：被下 op / 权限被改时，客户端那两个分类跟着出现或消失
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PERMISSION_RECHECK_TICKS != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                RuleDescriptorSyncChannel.INSTANCE.syncTo(player);
            }
        });
    }
}
