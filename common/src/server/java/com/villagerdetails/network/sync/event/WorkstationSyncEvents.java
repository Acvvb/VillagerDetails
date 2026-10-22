package com.villagerdetails.network.sync.event;

import com.villagerdetails.network.channel.SyncEvents;
import com.villagerdetails.network.sync.clientbound.WorkstationSyncChannel;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 村民工作站点同步（{@link WorkstationSyncChannel}）的服务端事件。
 *
 * <p>放在 {@code network.sync.event} 包下 = 自动注册，不用手动登记。
 *
 * <p>这里只负责「玩家刚开始追踪某个村民时先推一份」；之后的<b>变化检测</b>
 * （工作站点换了 / 被拆了）在 {@code mixin.villager.sync.VillagerSyncMixin} 里
 * 和床记忆共用同一次 tick 注入，避免再加一个每 tick 的监听。
 */
public final class WorkstationSyncEvents implements SyncEvents {

    public static final WorkstationSyncEvents INSTANCE = new WorkstationSyncEvents();

    private WorkstationSyncEvents() {
    }

    @Override
    public void registerEvents() {
        // 玩家开始追踪实体（实体进入视距）时，把这个村民的工作站点发给他
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, player) -> {
            if (trackedEntity instanceof Villager villager) {
                WorkstationSyncChannel.INSTANCE.sendWorkstation(player, villager.getId(),
                        WorkstationSyncChannel.workstationPosOf(villager));
            }
        });
    }
}
