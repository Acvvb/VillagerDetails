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
    private static final java.util.Map<UUID, UUID> selectedEntity = new java.util.HashMap<>();
    private static final Map<UUID, BlockPos> selectedBlocks = new HashMap<>();

    public static void setSelectedEntity(UUID playerUuid, UUID villagerUuid) {
        selectedEntity.put(playerUuid, villagerUuid);
    }

    public static BlockPos getSelectedBlock(UUID villagerUuid) {
        return selectedBlocks.get(villagerUuid);
    }

    public static UUID getSelectedEntity(UUID playerUuid) {
        return selectedEntity.get(playerUuid);
    }

    public static void clearSelectedEntity(UUID playerUuid) {
        selectedEntity.remove(playerUuid);
    }

    public static void setSelectedBlock(UUID villagerUuid, BlockPos workBlockPos) {
        selectedBlocks.put(villagerUuid, workBlockPos);
    }

    public static void removeAll(UUID uuid){
        selectedEntity.remove(uuid);
        selectedBlocks.remove(uuid);
    }

    public static boolean isEnd(UUID uuid) {
        return selectedEntity.containsKey(uuid) && selectedBlocks.containsKey(uuid);
    }
}