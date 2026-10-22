package com.villagerdetails.client.network.sync;

import com.villagerdetails.client.cache.impl.EBSelectionClientState;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.sync.payload.clientbound.EBSyncPayload;
import net.minecraft.client.Minecraft;

/**
 * 收到服务端同步的选中状态 → 写进客户端缓存，渲染器每帧读缓存。
 *
 * <p>客户端只依赖「包定义 + 收包接口」，不继承服务端的发包通道。
 */
public final class EBSyncClientChannel implements ClientReceiver<EBSyncPayload> {

    public static final EBSyncClientChannel INSTANCE = new EBSyncClientChannel();

    private EBSyncClientChannel() {
    }

    @Override
    public PayloadId<EBSyncPayload> id() {
        return EBSyncPayload.ID;
    }

    /**
     * 收包已经在客户端主线程，直接写缓存即可。
     */
    @Override
    public void handleClient(Minecraft client, EBSyncPayload payload) {
        EBSelectionClientState.INSTANCE.set(payload.entity(), payload.block());
    }
}
