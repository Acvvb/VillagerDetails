package com.villagerdetails.cache;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家「实体 + 方块」选择状态缓存。
 * <p>
 * 每个玩家同一时刻只保存一个选择状态（实体 UUID + 方块坐标）。
 * 绑定流程分两步取用，别看到缓存就以为"取出来就该清掉"：
 * </p>
 * <ol>
 *     <li>{@link #peek(UUID)} —— 先看一眼做校验（手上工具对不对、目标实体是不是村民）。
 *         校验没过时选区<b>必须留着</b>：玩家换回正确工具再点一次就能接着绑，
 *         而不是白选一遍</li>
 *     <li>{@link #clear(UUID)} —— 校验通过、确实要进入绑定流程了，这时候才摘掉，
 *         避免同一次选中被重复触发绑定</li>
 * </ol>
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
     * 看一眼该玩家当前的选择状态，<b>不动缓存</b>；没选过返回 null。
     *
     * <p>绑定前的校验一律走这里，校验通过、真的要绑了再 {@link #clear(UUID)}。
     */
    public static SelectionState peek(UUID playerUuid) {
        return STATES.get(playerUuid);
    }

    /**
     * 清空该玩家的选择状态。
     *
     * <p>绑定流程里这一步 = "确认消费掉这次选中"：进去之后无论绑定成败都不会再残留，
     * 也就不会出现"选完实体 → 绑定 → 再选同一实体又触发一次绑定"。
     */
    public static void clear(UUID playerUuid) {
        STATES.remove(playerUuid);
    }

    /**
     * 该玩家当前选中的实体 UUID；没选返回 null
     */
    public static UUID getSelectedEntity(UUID playerUuid) {
        SelectionState state = STATES.get(playerUuid);
        return state == null ? null : state.entityUuid();
    }

    /**
     * 该玩家当前选中的方块坐标；没选返回 null
     */
    public static BlockPos getSelectedBlock(UUID playerUuid) {
        SelectionState state = STATES.get(playerUuid);
        return state == null ? null : state.blockPos();
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
