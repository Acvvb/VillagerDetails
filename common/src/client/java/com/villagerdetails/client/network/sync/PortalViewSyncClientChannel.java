package com.villagerdetails.client.network.sync;

import com.villagerdetails.client.cache.impl.PortalViewClientState;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.sync.payload.clientbound.PortalViewSyncPayload;
import net.minecraft.client.Minecraft;

/**
 * 收到服务端推来的「视图高亮区域」→ 写进 {@link PortalViewClientState}，渲染器每帧读它。
 *
 * <p>服务端对一条绑定的两侧各发一个包，这里就按维度分别记下来（同维度覆盖旧的），
 * 玩家进到哪个维度渲染器就画哪一侧。收到空包（三字段 null）即清空所有维度。
 */
public final class PortalViewSyncClientChannel implements ClientReceiver<PortalViewSyncPayload> {

    public static final PortalViewSyncClientChannel INSTANCE = new PortalViewSyncClientChannel();

    private PortalViewSyncClientChannel() {
    }

    @Override
    public PayloadId<PortalViewSyncPayload> id() {
        return PortalViewSyncPayload.ID;
    }

    @Override
    public void handleClient(Minecraft client, PortalViewSyncPayload payload) {
        if (payload.dimension() == null) {
            // 空包 = 服务端要清空高亮：两个维度一起清，不能只清玩家当前所在的那个
            PortalViewClientState.INSTANCE.clear();
        } else {
            PortalViewClientState.INSTANCE.set(payload.dimension(), payload.min(), payload.max());
        }
    }
}
