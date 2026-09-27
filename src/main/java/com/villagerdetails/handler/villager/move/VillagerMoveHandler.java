package com.villagerdetails.handler.villager.move;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;
import com.villagerdetails.cache.ModMemories;
import com.villagerdetails.mixin.BrainInterfaceMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class VillagerMoveHandler {

    private VillagerMoveHandler() {}

    /** 已经注册过 GO_TO_DESTINATION activity 的村民（弱引用，随实体 GC 自动清理） */
    private static final Set<Villager> REGISTERED_VILLAGERS =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * 命令村民走到指定位置。
     */
    public static void sendTo(Villager villager, BlockPos rawTarget) {
        if (!(villager.level() instanceof ServerLevel level)) return;

        // ★ 归一化：把传入坐标转成真正可站立的位置
        BlockPos destination = StoodFinder.find(level, rawTarget);
        if (destination == null) {
            // 目标附近没有可站立位置，直接放弃
            return;
        }
        MoveToDestination.resetTimer(villager);
        Brain<Villager> brain = villager.getBrain();
        if (REGISTERED_VILLAGERS.add(villager)) {
            brain.addActivity(
                    ModActivities.GO_TO_DESTINATION,
                    ImmutableList.of(Pair.of(0, new MoveToDestination())),
                    ImmutableSet.of(Pair.of(ModMemories.DESTINATION, MemoryStatus.VALUE_PRESENT)),
                    ImmutableSet.of()
            );
        }
        // 用归一化后的坐标
        brain.setMemory(ModMemories.DESTINATION, destination);
        brain.setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(destination, 0.5F, 0));   // closeEnoughDist 保持 2
        ((BrainInterfaceMixin) brain).invokeSetActiveActivity(ModActivities.GO_TO_DESTINATION);
    }
}