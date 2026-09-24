package com.villagerdetails.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家选区管理器（方块选区 pos1 / pos2）。
 * <p>
 * 每个玩家同一时刻只保存一个选区状态（pos1 + pos2），
 * 采用与 {@link EBSelectionStateCache} 一致的单状态 + 并发 Map 机制。
 * </p>
 */
public class BBSectionStateCache {

    private static final Map<UUID, BlockSection> SECTIONS = new ConcurrentHashMap<>();

    private BBSectionStateCache() {
    }

    /**
     * 设置玩家的 pos1（保留已设置的 pos2）
     */
    public static void setPos1(ServerPlayer player, BlockPos pos) {
        SECTIONS.compute(player.getUUID(), (_, s) ->
                s == null ? new BlockSection(pos, null) : s.withPos1(pos));
    }

    /**
     * 设置玩家的 pos2（保留已设置的 pos1）
     */
    public static void setPos2(ServerPlayer player, BlockPos pos) {
        SECTIONS.compute(player.getUUID(), (_, s) ->
                s == null ? new BlockSection(null, pos) : s.withPos2(pos));
    }

    /**
     * 获取玩家的 pos1
     */
    public static BlockPos getPos1(ServerPlayer player) {
        BlockSection section = SECTIONS.get(player.getUUID());
        return section == null ? null : section.pos1();
    }

    /**
     * 获取玩家的 pos2
     */
    public static BlockPos getPos2(ServerPlayer player) {
        BlockSection section = SECTIONS.get(player.getUUID());
        return section == null ? null : section.pos2();
    }

    /**
     * 玩家是否已有完整选区（pos1 和 pos2 都已设置）
     */
    public static boolean hasSelection(ServerPlayer player) {
        BlockSection section = SECTIONS.get(player.getUUID());
        return section != null && section.isComplete();
    }

    /**
     * 清除玩家的选区
     */
    public static void clearSelection(ServerPlayer player) {
        SECTIONS.remove(player.getUUID());
    }

    /**
     * 一次选区：pos1 + pos2，两者齐全即视为选区完成。
     */
    public record BlockSection(BlockPos pos1, BlockPos pos2) {

        public BlockSection withPos1(BlockPos pos) {
            return new BlockSection(pos, pos2);
        }

        public BlockSection withPos2(BlockPos pos) {
            return new BlockSection(pos1, pos);
        }

        public boolean isComplete() {
            return pos1 != null && pos2 != null;
        }
    }
}
