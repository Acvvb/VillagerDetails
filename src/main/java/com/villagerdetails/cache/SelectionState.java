package com.villagerdetails.cache;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 暂存玩家选中的村民UUID
 * 因为Fabric事件是静态的，用静态变量跨事件传递数据
 */
public class SelectionState {
    // key: 玩家UUID, value: 选中的村民UUID
    private static final java.util.Map<UUID, UUID> selectedVillagers = new java.util.HashMap<>();
    private static final Map<UUID, BlockPos> selectedWorkBlocks = new HashMap<>();

    public static void setSelectedEntity(UUID playerUuid, UUID villagerUuid) {
        selectedVillagers.put(playerUuid, villagerUuid);
    }

    public static BlockPos getSelectedWorkBlock(UUID villagerUuid) {
        return selectedWorkBlocks.get(villagerUuid);
    }

    public static UUID getSelectedVillager(UUID playerUuid) {
        return selectedVillagers.get(playerUuid);
    }

    public static void clearSelectedVillager(UUID playerUuid) {
        selectedVillagers.remove(playerUuid);
    }

    public static void setSelectedWorkBlock(UUID villagerUuid, BlockPos workBlockPos) {
        selectedWorkBlocks.put(villagerUuid, workBlockPos);
    }
}