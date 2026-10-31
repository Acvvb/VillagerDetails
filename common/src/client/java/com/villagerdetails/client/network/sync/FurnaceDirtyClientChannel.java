package com.villagerdetails.client.network.sync;

import com.villagerdetails.client.cache.impl.FurnaceDirtyClientState;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.client.network.tool.SyncSubscriptionClient;
import com.villagerdetails.client.setting.type.ConfigEntryType;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.sync.payload.clientbound.FurnaceDirtySyncPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * 熔炉脏高亮的客户端侧：收<b>增量</b>包 → 落本地表；本地显示开关关着时，反过来告诉服务端别发了。
 *
 * <p>包只有三种语义：
 * <ul>
 *     <li>{@code isAdd=true}：把这批坐标加进「这个维度」的本地表；</li>
 *     <li>{@code isAdd=false} 且列表非空：把这批坐标从该维度拿掉；</li>
 *     <li>{@code isAdd=false} 且列表为空：<b>清空该维度</b>（换维度 / 规则关闭 / 重新订阅）。</li>
 * </ul>
 * 服务端按区块增量维护，客户端这里不做任何「距离过滤」——
 * 收到什么就是什么，范围由服务端保证（见 {@code FurnaceDirtyTracker}）。
 *
 * <p>「服务端发、客户端画」是主链路；额外的一条是<b>客户端单方面关显示</b>：
 * <ul>
 *     <li>开关变化：每 tick 比对一次，变了就发通用的 {@code sync_subscription}（on / off），
 *         服务端收到后丢掉该玩家的视距记录、不再发包；</li>
 *     <li>开关关着却仍然收到包（比如刚进服、服务端还不知道）：清空本地表，并立刻回一个 off；</li>
 *     <li>断线：本地表和「已通知状态」都由 {@code ClientSessionStates} 统一清掉，
 *         下次进服默认重新订阅；重新订阅后服务端会当成新玩家整批补发。</li>
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
                if (!FurnaceDirtyClientState.INSTANCE.snapshot().isEmpty()) {
                    FurnaceDirtyClientState.INSTANCE.clear();
                }
            }
            syncSubscription();
        });
    }

    @Override
    public void handleClient(Minecraft client, FurnaceDirtySyncPayload payload) {
        if (!enabled()) {
            // 本地显示是关的，但服务端还在发：清掉本地表，并回一个「这条通道别发了」
            FurnaceDirtyClientState.INSTANCE.clear();
            syncSubscription();
            return;
        }
        if (payload.isAdd()) {
            FurnaceDirtyClientState.INSTANCE.addAll(payload.dimension(), payload.positions());
            return;
        }
        if (payload.positions().isEmpty()) {
            // REMOVE + 空列表 = 清空「这个维度」：换维度、规则被关掉、服务端要求重新同步。
            // 只清对应维度，旧维度的清空包晚到时不会误伤新维度刚收到的数据。
            FurnaceDirtyClientState.INSTANCE.clear(payload.dimension());
            return;
        }
        FurnaceDirtyClientState.INSTANCE.removeAll(payload.dimension(), payload.positions());
    }

    /**
     * 把本地显示开关的目标状态同步给服务端（重复状态是空操作，见 {@link SyncSubscriptionClient}）
     */
    private static void syncSubscription() {
        SyncSubscriptionClient.INSTANCE.sync(FurnaceDirtySyncPayload.ID, enabled());
    }

    /**
     * 本地显示开关（客户端配置项，默认开）
     */
    private static boolean enabled() {
        return ConfigEntryType.RENDER_FURNACE_DIRTY.getToggle();
    }
}
