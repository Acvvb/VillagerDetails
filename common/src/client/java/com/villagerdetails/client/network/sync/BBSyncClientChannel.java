package com.villagerdetails.client.network.sync;

import com.villagerdetails.client.cache.impl.BBSelectionClientState;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.sync.payload.clientbound.BBSyncPayload;
import net.minecraft.client.Minecraft;

/**
 * 收到服务端同步的 BB 选择状态 → 写客户端缓存，渲染器每帧读缓存。
 *
 * <p>客户端只依赖「包定义 + 收包接口」，<b>不继承</b>服务端那条发包通道
 * （{@code network.sync.clientbound} 属于 server 源集）。
 */
public final class BBSyncClientChannel implements ClientReceiver<BBSyncPayload> {

    public static final BBSyncClientChannel INSTANCE = new BBSyncClientChannel();

    private BBSyncClientChannel() {
    }

    @Override
    public PayloadId<BBSyncPayload> id() {
        return BBSyncPayload.ID;
    }

    @Override
    public void handleClient(Minecraft client, BBSyncPayload payload) {
        BBSelectionClientState.INSTANCE.set(payload.pos1(), payload.pos2());
    }
}
