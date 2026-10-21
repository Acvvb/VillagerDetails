package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.channel.SyncSubscriptions;
import com.villagerdetails.network.sync.payload.serverbound.SyncSubscriptionPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端发来的通用订阅开关：{@code on} = 恢复发送，{@code off} = 这条通道别再发给我。
 *
 * <p>只写 {@link SyncSubscriptions}，具体哪条通道要不要响应由各通道自己决定
 * （{@code SyncChannel#canSendTo} 已经统一查这里了）。
 *
 * <p>放在 {@code network.sync.receiver} 包下 = 自动注册。
 */
public final class SyncSubscriptionReceiver implements ServerReceiver<SyncSubscriptionPayload> {

    public static final SyncSubscriptionReceiver INSTANCE = new SyncSubscriptionReceiver();

    private SyncSubscriptionReceiver() {
    }

    @Override
    public PayloadId<SyncSubscriptionPayload> id() {
        return SyncSubscriptionPayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, SyncSubscriptionPayload payload) {
        if (player == null || payload == null) {
            return;
        }
        Identifier channel = payload.channelId();
        if (channel == null) {
            // 非法通道 id：不记、也不回，客户端继续收包（保持「没静音」的默认行为）
            return;
        }
        SyncSubscriptions.setMuted(player, channel, !payload.enabled());
    }
}
