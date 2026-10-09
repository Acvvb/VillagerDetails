package com.villagerdetails.client.network.sync;


import com.villagerdetails.client.cache.EBSelectionClientState;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.sync.payload.clientbound.EBSyncPayload;
import com.villagerdetails.network.sync.clientbound.EBSyncChannel;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * 收到服务端同步的选中状态 → 写进客户端缓存，渲染器每帧读缓存。
 *
 * <p>继承 {@link EBSyncChannel} 拿到 id / codec / 服务端广播能力；
 * 实现 {@link ClientReceiver} 拿到客户端收包能力。
 */
public final class EBSyncClientChannel extends EBSyncChannel implements ClientReceiver<EBSyncPayload> {

    public static final EBSyncClientChannel INSTANCE = new EBSyncClientChannel();

    private EBSyncClientChannel() {
        super();
    }

    /**
     * 收包已经在客户端主线程，直接写缓存即可。
     */
    @Override
    public void handleClient(Minecraft client, EBSyncPayload payload) {
        EBSelectionClientState.set(payload.entity(), payload.block());
    }

    /**
     * 断线时清空选中状态，免得下次进服时残留上一局的高亮。
     */
    @Override
    public void registerClientEvents() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                EBSelectionClientState.clear());
    }
}