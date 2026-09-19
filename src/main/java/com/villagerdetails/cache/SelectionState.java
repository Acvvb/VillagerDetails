package com.villagerdetails.cache;

import java.util.UUID;

/**
 * 暂存玩家选中的村民UUID
 * 因为Fabric事件是静态的，用静态变量跨事件传递数据
 */
public class SelectionState {
    // key: 玩家UUID, value: 选中的村民UUID
    private static final java.util.Map<UUID, UUID> selectedVillagers = new java.util.HashMap<>();

    public static void setSelectedVillager(UUID playerUuid, UUID villagerUuid) {
        selectedVillagers.put(playerUuid, villagerUuid);
    }

    public static UUID getSelectedVillager(UUID playerUuid) {
        return selectedVillagers.get(playerUuid);
    }

    public static void clearSelectedVillager(UUID playerUuid) {
        selectedVillagers.remove(playerUuid);
    }
}