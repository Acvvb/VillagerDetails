package com.villagerdetails.cache;


import com.villagerdetails.VillagerDetails;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.Optional;

public final class ModMemories {

    private ModMemories() {}

    /**
     * 村民当前被强制前往的目的地。
     * 存在 = 有强制移动任务；不存在 = 自由状态。
     */
    public static final MemoryModuleType<BlockPos> DESTINATION =
            new MemoryModuleType<>(Optional.of(BlockPos.CODEC));

    public static void register() {
        Registry.register(
                BuiltInRegistries.MEMORY_MODULE_TYPE,
                VillagerDetails.id("destination"),
                DESTINATION
        );
    }
}