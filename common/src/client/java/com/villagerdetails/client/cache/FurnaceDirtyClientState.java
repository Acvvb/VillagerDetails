package com.villagerdetails.client.cache;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端记录的「当前要高亮的脏熔炉」，由 {@code furnace_dirty_sync} 整份替换。
 *
 * <p>和 {@link PortalViewClientState} 一个思路：整份状态用<b>一个不可变快照</b>承载、
 * 每次更新整体换引用，渲染线程读到的永远是某个完整版本，不会读到一半新一半旧。
 *
 * <p>快照里带维度：玩家传送后，上一维度的列表还在，但渲染器一比对维度就会跳过，
 * 不会在新维度同坐标处冒出「幽灵框」。
 *
 * <p>空表 = 附近没有脏熔炉（或规则已经关了）→ 渲染器直接不画。
 */
public final class FurnaceDirtyClientState {

    /**
     * 一份完整快照：维度 + 坐标集合
     *
     * @param dimension 这份列表属于哪个维度（{@code minecraft:overworld} 这种文本）
     * @param positions 脏熔炉坐标
     */
    public record Snapshot(String dimension, Set<BlockPos> positions) {

        public boolean isEmpty() {
            return positions.isEmpty();
        }
    }

    private static final Snapshot EMPTY = new Snapshot("", Set.of());

    private static volatile Snapshot current = EMPTY;

    private FurnaceDirtyClientState() {
    }

    /**
     * 渲染器每帧取一次快照；拿到的是一份不可变数据，可以放心遍历
     */
    public static Snapshot snapshot() {
        return current;
    }

    /**
     * 用服务端这一轮的全量结果整份替换；空列表就是清空
     */
    public static void replace(String dimension, List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            current = new Snapshot(dimension == null ? "" : dimension, Set.of());
            return;
        }
        current = new Snapshot(dimension == null ? "" : dimension, Set.copyOf(new HashSet<>(positions)));
    }

    /**
     * 清空（断线时由 {@link ClientSessionStates} 统一调）
     */
    public static void clear() {
        current = EMPTY;
    }
}
