package com.villagerdetails.cache;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家「实体 + 方块」选择状态缓存。
 * <p>
 * 每个玩家同一时刻只保存一个选择状态（实体 UUID + 方块坐标）。
 * 绑定触发时通过 {@link #take(UUID)} 原子地「取出并清空」，无论绑定成功与否都不会残留旧状态，
 * 从根上避免「选完实体 → 绑定 → 再选同一实体却再次触发绑定」这类重复绑定问题。
 * </p>
 */
public class EBSelectionStateCache {

    private static final Map<UUID, SelectionState> STATES = new ConcurrentHashMap<>();

    private EBSelectionStateCache() {
    }

    /**
     * 记录玩家选中的实体（保留已选中的方块）。
     */
    public static void selectEntity(UUID playerUuid, UUID entityUuid) {
        STATES.compute(playerUuid, (_, s) ->
                s == null ? new SelectionState(entityUuid, null) : s.withEntity(entityUuid));
    }

    /**
     * 记录玩家选中的方块（保留已选中的实体）。
     */
    public static void selectBlock(UUID playerUuid, BlockPos blockPos) {
        STATES.compute(playerUuid, (_, s) ->
                s == null ? new SelectionState(null, blockPos) : s.withBlock(blockPos));
    }

    /**
     * 玩家是否已同时选中实体和方块（满足绑定条件）。
     */
    public static boolean isComplete(UUID playerUuid) {
        SelectionState state = STATES.get(playerUuid);
        return state != null && state.isComplete();
    }

    /**
     * 原子地取出并清空该玩家的选择状态。
     * <p>
     * 这是绑定流程的入口：一旦取出，该玩家的缓存即为空，
     * 后续任何「再次选中实体」都只会重新开始选择，而不会误触发绑定。
     * </p>
     */
    public static SelectionState take(UUID playerUuid) {
        return STATES.remove(playerUuid);
    }

    /**
     * 清空该玩家的选择状态。
     */
    public static void clear(UUID playerUuid) {
        STATES.remove(playerUuid);
    }

    /**
     * 一次选择：实体 UUID + 方块坐标，两者齐全即视为选择完成。
     */
    public record SelectionState(UUID entityUuid, BlockPos blockPos) {

        public SelectionState withEntity(UUID uuid) {
            return new SelectionState(uuid, blockPos);
        }

        public SelectionState withBlock(BlockPos pos) {
            return new SelectionState(entityUuid, pos);
        }

        public boolean isComplete() {
            return entityUuid != null && blockPos != null;
        }
    }
}
