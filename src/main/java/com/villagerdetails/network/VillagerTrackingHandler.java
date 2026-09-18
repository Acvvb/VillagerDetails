package com.villagerdetails.network;

import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class VillagerTrackingHandler {

    private static final Logger log = LogManager.getLogger(VillagerTrackingHandler.class);

    public static void register() {
        // 玩家开始追踪实体时触发（实体进入视距范围）
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, player) -> {
            if (!(trackedEntity instanceof Villager villager)) {
                return;
            }

            BlockPos bedPos = getBedPos(villager);
            log.info("UUID:{},bedPos:{}",villager.getUUID(),bedPos);
            if (bedPos != null) {
                ServerPlayNetworking.send(
                        player,
                        new VillagerBedPayload(new VillagerPacket(villager.getId(), Optional.of(bedPos)))
                );
            }
        });

    }

    private static BlockPos getBedPos(Villager villager) {
        // 1. 获取村民的大脑
        Brain<?> brain = villager.getBrain();

        // 2. 从大脑中获取 HOME 记忆（床的位置）
        Optional<GlobalPos> homeMemory = brain.getMemory(MemoryModuleType.HOME);

        // 3. 如果记忆存在，提取坐标；否则返回 null
        if (homeMemory.isPresent()) {
            GlobalPos globalPos = homeMemory.get();
            return globalPos.pos();
        } else {
            return null;
        }
    }
}