package com.villagerdetails.client.network.sync.event;

import com.villagerdetails.client.cache.impl.WorkstationClientState;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.sync.payload.clientbound.WorkstationPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 村民工作站点同步的客户端侧：收包写缓存 + 实体卸载清理。
 *
 * <p>收包、切线程的代码在 {@link ClientReceiver} 的默认方法里，
 * 这里只写「收到之后干什么」和客户端自己的事件。
 *
 * <p>放在 {@code client.network.sync.event} 包下 = 自动注册，不用改 {@code ClientChannels}。
 */
public final class WorkstationClientChannel implements ClientReceiver<WorkstationPayload> {

    public static final WorkstationClientChannel INSTANCE = new WorkstationClientChannel();

    private WorkstationClientChannel() {
    }

    @Override
    public PayloadId<WorkstationPayload> id() {
        return WorkstationPayload.ID;
    }

    @Override
    public void handleClient(Minecraft client, WorkstationPayload payload) {
        WorkstationClientState.INSTANCE.updateWorkstation(
                payload.data().villagerId(),
                payload.data().workstationPos().orElse(null));
    }

    @Override
    public void registerClientEvents() {
        // 实体卸载（走出视距 / 死亡 / 换维度）时清掉对应缓存，
        // 避免实体ID被复用后，给错误的实体连出一条线
        // （断线整体清空在 ClientSessionStates 里统一接线，这里不再各注册一份）
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, _) -> {
            if (entity instanceof Villager villager) {
                WorkstationClientState.INSTANCE.removeWorkstation(villager.getId());
            }
        });
    }
}
