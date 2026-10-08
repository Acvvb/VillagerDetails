package com.villagerdetails.network.sync;

import com.villagerdetails.network.channel.SyncChannel;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Optional;

/**
 * 村民床同步：服务端 → 客户端。
 *
 * <p>玩家开始追踪村民（进入视距）时把床坐标发过去；村民 tick 时床变了也发（见 VillagerSyncMixin）。
 *
 * <p>包 id：{@code villagerdetails:villager_sync}
 */
public class VillagerBedSyncChannel extends SyncChannel<VillagerBedPayload> {

    public static final VillagerBedSyncChannel INSTANCE = new VillagerBedSyncChannel();

    protected VillagerBedSyncChannel() {
        super(VillagerBedPayload.ID, VillagerBedPayload.CODEC);
    }

    /**
     * 把这个村民当前绑的床发给某个玩家（{@code bedPos} 为空表示没有床）
     */
    public void sendBed(ServerPlayer player, int villagerId, BlockPos bedPos) {
        sendTo(player, new VillagerBedPayload(new VillagerPacket(villagerId, Optional.ofNullable(bedPos))));
    }

    @Override
    public void registerEvents() {
        // 服务端：玩家开始追踪实体（实体进入视距）时，把这个村民的床发给他
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, player) -> {
            if (trackedEntity instanceof Villager villager) {
                sendBed(player, villager.getId(), bedPosOf(villager));
            }
        });
    }

    /**
     * 从村民大脑的 HOME 记忆里取床坐标；没有返回 null（tick 时判断变没变也用它）
     */
    public static BlockPos bedPosOf(Villager villager) {
        Brain<?> brain = villager.getBrain();
        return brain.getMemory(MemoryModuleType.HOME).map(GlobalPos::pos).orElse(null);
    }
}
