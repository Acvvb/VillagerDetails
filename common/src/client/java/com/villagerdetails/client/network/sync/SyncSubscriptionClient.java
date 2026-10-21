package com.villagerdetails.client.network.sync;

import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.sync.payload.serverbound.SyncSubscriptionPayload;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端侧的「通道订阅」通用助手：把「这条通道要不要发给我」告诉服务端。
 *
 * <p>和 {@code SyncSubscriptionPayload} 配套：任何功能只要有一个客户端开关，
 * 就在开关变化时调 {@link #sync}，服务端那边 {@code SyncChannel#canSendTo} 会自动生效，
 * 不需要为每个功能再写一套 C→S 协议。
 *
 * <p>去重：只在「目标状态和上次已通知的不一样」时才发，避免每 tick 刷屏。
 * 没发成功（例如对面是原版客户端 / 还没连上）时不记状态，下个 tick 会重试。
 *
 * <p>断线时由 {@link com.villagerdetails.client.cache.ClientSessionStates} 调 {@link #clearSession()}，
 * 免得下次进服误以为已经通知过。
 */
public final class SyncSubscriptionClient {

    /**
     * 通道 id → 上次已成功通知服务端的状态
     *
     * <p>只在客户端主线程读写（收包回调、tick 回调都切回客户端线程了）
     */
    private static final Map<Identifier, Boolean> NOTIFIED = new HashMap<>();

    private SyncSubscriptionClient() {
    }

    /**
     * 告诉服务端：这条通道要（{@code true}）或者不要（{@code false}）继续发给我。
     *
     * <p>重复调用同样的状态是空操作。
     */
    public static void sync(PayloadId<?> channel, boolean enabled) {
        if (channel == null) {
            return;
        }
        Identifier id = channel.id();
        Boolean notified = NOTIFIED.get(id);
        if (notified != null && notified == enabled) {
            return;
        }
        if (ClientReceiver.send(new SyncSubscriptionPayload(id, enabled))) {
            NOTIFIED.put(id, enabled);
        }
    }

    /**
     * 断线：忘掉「已通知过」的记录（服务端那边也会在玩家退出时丢掉记录）
     */
    public static void clearSession() {
        NOTIFIED.clear();
    }
}
