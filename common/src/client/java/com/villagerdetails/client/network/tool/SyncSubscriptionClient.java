package com.villagerdetails.client.network.tool;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.client.cache.ClientSessionStates;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.sync.payload.serverbound.SyncSubscriptionPayload;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>断线时由 {@link ClientSessionStates} 调 {@link #clearSession()}，
 * 免得下次进服误以为已经通知过。
 *
 * <p>它<b>不是缓存</b>（没有业务数据，只有「通知过没有」这份记忆），所以类不在
 * {@code cache.impl} 包里，由 {@code ClientSessionStates} 手工登记一行；
 * 但仍然实现 {@link SessionState}，清理入口和其它状态长得一样。
 */
public final class SyncSubscriptionClient implements SessionState {

    /**
     * 单例：调用方统一写 {@code SyncSubscriptionClient.INSTANCE.xxx()}
     */
    public static final SyncSubscriptionClient INSTANCE = new SyncSubscriptionClient();

    /**
     * 通道 id → 上次已成功通知服务端的状态
     *
     * <p><b>必须是并发安全的容器</b>：写入发生在客户端主线程（tick 回调 / 收包回调），
     * 而 {@link #clearSession()} 由 {@code ClientSessionStates.clearAll()} 在<b>断线回调里</b>执行 ——
     * Fabric 的 {@code ClientPlayConnectionEvents.DISCONNECT} 是从
     * {@code ConnectionMixin#disconnectAddon}（netty 线程）同步触发的，所以清空和写入会并发。
     * 普通 {@code HashMap} 在这种交错下可能丢更新甚至把桶链表弄坏，表现为「已通知过」的记录
     * 残留到下一次会话 —— 服务端以为你退订了，通道类功能在新会话里一直收不到包。
     */
    private final Map<Identifier, Boolean> notified = new ConcurrentHashMap<>();

    private SyncSubscriptionClient() {
    }

    /**
     * 告诉服务端：这条通道要（{@code true}）或者不要（{@code false}）继续发给我。
     *
     * <p>重复调用同样的状态是空操作。
     */
    public void sync(PayloadId<?> channel, boolean enabled) {
        if (channel == null) {
            return;
        }
        Identifier id = channel.id();
        Boolean last = notified.get(id);
        if (last != null && last == enabled) {
            return;
        }
        // 发送成功才记录：对面没装 mod / 没连上时不记，下个 tick 会自动重试
        if (ClientReceiver.send(new SyncSubscriptionPayload(id, enabled))) {
            notified.put(id, enabled);
        }
    }

    /**
     * 断线：忘掉「已通知过」的记录（服务端那边也会在玩家退出时丢掉记录）
     */
    @Override
    public void clearSession() {
        notified.clear();
    }
}
