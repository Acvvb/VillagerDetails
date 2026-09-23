package com.villagerdetails.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家选区管理器
 * <p>
 * 每个玩家独立维护自己的 pos1 / pos2。
 * </p>
 */
public class BBSectionStateCache {

    private static final Map<UUID, BlockPos> POS1_MAP = new HashMap<>();
    private static final Map<UUID, BlockPos> POS2_MAP = new HashMap<>();

    /**
     * 设置玩家的 pos1
     */
    public static void setPos1(ServerPlayer player, BlockPos pos) {
        POS1_MAP.put(player.getUUID(), pos);
    }

    /**
     * 设置玩家的 pos2
     */
    public static void setPos2(ServerPlayer player, BlockPos pos) {
        POS2_MAP.put(player.getUUID(), pos);
    }

    /**
     * 获取玩家的 pos1
     */
    public static BlockPos getPos1(ServerPlayer player) {
        return POS1_MAP.get(player.getUUID());
    }

    /**
     * 获取玩家的 pos2
     */
    public static BlockPos getPos2(ServerPlayer player) {
        return POS2_MAP.get(player.getUUID());
    }

    /**
     * 玩家是否已有完整选区（pos1 和 pos2 都已设置）
     */
    public static boolean hasSelection(ServerPlayer player) {
        return getPos1(player) != null && getPos2(player) != null;
    }

    /**
     * 清除玩家的选区
     */
    public static void clearSelection(ServerPlayer player) {
        POS1_MAP.remove(player.getUUID());
        POS2_MAP.remove(player.getUUID());
    }

}