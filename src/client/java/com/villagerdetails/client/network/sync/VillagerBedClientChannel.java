package com.villagerdetails.client.network.sync;

import com.villagerdetails.client.feature.villagerbed.VillagerBedHelper;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.sync.payload.clientbound.VillagerBedPayload;
import com.villagerdetails.network.sync.clientbound.VillagerBedSyncChannel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 村民床同步的客户端侧：收包写缓存 + 实体卸载/断线清理。
 *
 * <p>收包、切线程的代码在 {@link ClientReceiver} 里，这里只写「收到之后干什么」和客户端自己的事件。
 */
public final class VillagerBedClientChannel extends VillagerBedSyncChannel
        implements ClientReceiver<VillagerBedPayload> {

    public static final VillagerBedClientChannel INSTANCE = new VillagerBedClientChannel();

    private VillagerBedClientChannel() {
    }

    @Override
    public void handleClient(Minecraft client, VillagerBedPayload payload) {
        VillagerBedHelper.updateBedPosition(
                payload.data().villagerId(),
                payload.data().bedPos().orElse(null));
    }

    @Override
    public void registerClientEvents() {
        // 实体卸载（走出视距 / 死亡 / 换维度）时清掉对应缓存，
        // 避免实体ID被复用后，给错误的实体连出一条线
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, _) -> {
            if (entity instanceof Villager villager) {
                VillagerBedHelper.removeBedPosition(villager.getId());
            }
        });

        // 断开连接时整体清空
        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> VillagerBedHelper.clear());
    }
}
