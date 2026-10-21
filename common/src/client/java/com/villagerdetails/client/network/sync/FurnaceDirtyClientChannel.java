package com.villagerdetails.client.network.sync;

import com.villagerdetails.client.cache.FurnaceDirtyClientState;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.sync.payload.clientbound.FurnaceDirtySyncPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * 熔炉脏高亮的客户端侧：收包 → 落本地表；本地显示开关关着时，反过来告诉服务端别发了。
 *
 * <p>「服务端发、客户端画」是主链路；额外的一条是<b>客户端单方面关显示</b>：
 * <ul>
 *     <li>开关变化：每 tick 比对一次，变了就发通用的 {@code sync_subscription}（on / off），
 *         服务端收到后决定还发不发；</li>
 *     <li>开关关着却仍然收到包（比如刚进服、服务端还不知道）：清空本地表，并立刻回一个 off；</li>
 *     <li>断线：本地表和「已通知状态」都由 {@code ClientSessionStates} 统一清掉，
 *         下次进服默认重新订阅。</li>
 * </ul>
 */
public final class FurnaceDirtyClientChannel implements ClientReceiver<FurnaceDirtySyncPayload> {

    public static final FurnaceDirtyClientChannel INSTANCE = new FurnaceDirtyClientChannel();

    private FurnaceDirtyClientChannel() {
    }

    @Override
    public PayloadId<FurnaceDirtySyncPayload> id() {
        return FurnaceDirtySyncPayload.ID;
    }

    @Override
    public void registerClientEvents() {
        // 开关一变就通知服务端；没连服务器时不发（主菜单里 tick 也在跑）
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getConnection() == null) {
                return;
            }
            if (!enabled()) {
                // 本地关了：立刻把缓存清掉。
                // 静音之后服务端不会再发包，只靠「收到包时清」永远等不到下一包，旧的框会一直画着。
                if (!FurnaceDirtyClientState.snapshot().isEmpty()) {
                    FurnaceDirtyClientState.clear();
                }
            }
            syncSubscription();
        });
    }

    @Override
    public void handleClient(Minecraft client, FurnaceDirtySyncPayload payload) {
        if (!enabled()) {
            // 本地显示是关的，但服务端还在发：清掉本地表，并回一个「这条通道别发了」
            FurnaceDirtyClientState.clear();
            syncSubscription();
            return;
        }
        FurnaceDirtyClientState.replace(payload.dimension(), payload.positions());
    }

    /**
     * 把本地显示开关的目标状态同步给服务端（重复状态是空操作，见 {@link SyncSubscriptionClient}）
     */
    private static void syncSubscription() {
        SyncSubscriptionClient.sync(FurnaceDirtySyncPayload.ID, enabled());
    }

    /**
     * 本地显示开关（客户端配置项，默认开）
     */
    private static boolean enabled() {
        return ConfigEntryType.RENDER_FURNACE_DIRTY.getToggle();
    }
}
