package com.villagerdetails.client.network.sync;

import com.villagerdetails.client.cache.BBSelectionClientState;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.sync.BBSyncChannel;
import com.villagerdetails.network.sync.BBSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * 收到服务端同步的 BB 选择状态 → 写客户端缓存，渲染器每帧读缓存。
 */
public final class BBSyncClientChannel extends BBSyncChannel implements ClientReceiver<BBSyncPayload> {

    public static final BBSyncClientChannel INSTANCE = new BBSyncClientChannel();

    private BBSyncClientChannel() {
    }

    @Override
    public void handleClient(Minecraft client, BBSyncPayload payload) {
        BBSelectionClientState.set(payload.pos1(), payload.pos2());
    }

    @Override
    public void registerClientEvents() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                BBSelectionClientState.clear());
    }
}