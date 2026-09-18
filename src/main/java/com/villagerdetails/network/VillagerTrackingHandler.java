package com.villagerdetails.network;

import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Objects;

public class VillagerTrackingHandler {

    public static void register() {
        // 玩家开始追踪实体时触发（实体进入视距范围）
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, player) -> {
            if (!(trackedEntity instanceof Villager villager)) {
                return;
            }

            BlockPos bedPos = getBedPos(villager);
            ServerPlayNetworking.send(
                    player,
                    new VillagerBedPayload(new VillagerPacket(villager.getId(), bedPos))
            );
        });
    }

    private static BlockPos getBedPos(Villager villager) {
        // 从村民记忆中获取床的位置
        return Objects.requireNonNull(villager.getBrain()
                .getMemory(MemoryModuleType.HOME)
                .orElse(null)).pos();
    }
}