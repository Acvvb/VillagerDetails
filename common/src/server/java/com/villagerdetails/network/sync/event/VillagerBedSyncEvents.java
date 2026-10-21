package com.villagerdetails.network.sync.event;

import com.villagerdetails.network.channel.SyncEvents;
import com.villagerdetails.network.sync.clientbound.VillagerBedSyncChannel;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 村民床同步（{@link VillagerBedSyncChannel}）的事件。
 *
 * <p>放在 {@code network.sync.event} 包下 = 自动注册，不用手动登记。
 */
public final class VillagerBedSyncEvents implements SyncEvents {

    public static final VillagerBedSyncEvents INSTANCE = new VillagerBedSyncEvents();

    private VillagerBedSyncEvents() {
    }

    @Override
    public void registerEvents() {
        // 服务端：玩家开始追踪实体（实体进入视距）时，把这个村民的床发给他
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, player) -> {
            if (trackedEntity instanceof Villager villager) {
                VillagerBedSyncChannel.INSTANCE.sendBed(player, villager.getId(),
                        VillagerBedSyncChannel.bedPosOf(villager));
            }
        });
    }
}
