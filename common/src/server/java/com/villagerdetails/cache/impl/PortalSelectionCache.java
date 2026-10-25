package com.villagerdetails.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送门绑定的临时选区缓存：每个玩家分别记录主世界选区与地狱选区，直到 bind 成功后清空。
 *
 * <p>生命周期（玩家退出 / 停服）由 {@code ServerSessionStates} 扫包接管。
 */
public final class PortalSelectionCache implements SessionState {

    public static final PortalSelectionCache INSTANCE = new PortalSelectionCache();

    private static final Map<UUID, Region> OVERWORLD = new ConcurrentHashMap<>();
    private static final Map<UUID, Region> NETHER = new ConcurrentHashMap<>();

    private PortalSelectionCache() {
    }

    public static void setOverworld(UUID uuid, BlockPos pos1, BlockPos pos2) {
        OVERWORLD.put(uuid, new Region(pos1, pos2));
    }

    public static void setNether(UUID uuid, BlockPos pos1, BlockPos pos2) {
        NETHER.put(uuid, new Region(pos1, pos2));
    }

    public static Region getOverworld(UUID uuid) {
        return OVERWORLD.get(uuid);
    }

    public static Region getNether(UUID uuid) {
        return NETHER.get(uuid);
    }

    public static void clear(UUID uuid) {
        OVERWORLD.remove(uuid);
        NETHER.remove(uuid);
    }

    @Override
    public void clearPlayer(UUID playerUuid) {
        clear(playerUuid);
    }

    @Override
    public void clearSession() {
        OVERWORLD.clear();
        NETHER.clear();
    }

    /**
     * 一块由两个对角坐标定义的区域
     */
    public record Region(BlockPos pos1, BlockPos pos2) {
    }
}
