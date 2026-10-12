package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.sync.clientbound.PortalViewSyncChannel;
import com.villagerdetails.network.sync.payload.serverbound.PortalViewClearPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端请求清空传送门视图高亮。
 *
 * <p>服务端不做别的，只回一条空包（{@link PortalViewSyncChannel#clear}）——
 * 客户端本地不自己清，高亮消失只靠这条下发触发，和 BB / EB 的清除链路保持一致；
 * 顺带把「服务端侧本来就有、却一直没人调」的 clear 通道接上。
 *
 * <p>放在 {@code network.sync.receiver} 包下 = 自动注册。
 */
public final class PortalViewClearReceiver implements ServerReceiver<PortalViewClearPayload> {

    public static final PortalViewClearReceiver INSTANCE = new PortalViewClearReceiver();

    private PortalViewClearReceiver() {
    }

    @Override
    public PayloadId<PortalViewClearPayload> id() {
        return PortalViewClearPayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, PortalViewClearPayload payload) {
        if (player == null) {
            return;
        }
        PortalViewSyncChannel.INSTANCE.clear(player);
    }
}
