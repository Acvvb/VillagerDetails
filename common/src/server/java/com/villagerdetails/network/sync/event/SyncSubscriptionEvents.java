package com.villagerdetails.network.sync.event;

import com.villagerdetails.network.channel.SyncEvents;
import com.villagerdetails.network.channel.SyncSubscriptions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * 通用订阅记录（{@link SyncSubscriptions}）的生命周期清理。
 *
 * <p>玩家退出时丢掉他的整条静音记录：下次进服默认「要发」，客户端如果仍然关着显示，
 * 收到第一包后会自己再发一次 off —— 不需要服务端持久化任何「这个玩家关过显示」的记忆。
 *
 * <p>放在 {@code network.sync.event} 包下 = 自动注册。
 */
public final class SyncSubscriptionEvents implements SyncEvents {

    public static final SyncSubscriptionEvents INSTANCE = new SyncSubscriptionEvents();

    private SyncSubscriptionEvents() {
    }

    @Override
    public void registerEvents() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) ->
                SyncSubscriptions.clear(handler.getPlayer()));
        // 服务器停止：整份清空，免得内存里留下上一局的记录
        ServerLifecycleEvents.SERVER_STOPPING.register(_ ->
                SyncSubscriptions.clearAll());
    }
}
