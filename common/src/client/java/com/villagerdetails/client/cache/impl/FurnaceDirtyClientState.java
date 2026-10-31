package com.villagerdetails.client.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.client.cache.base.ClientState;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户端记录的「当前要高亮的脏熔炉」。
 *
 * <p><b>按维度分桶</b>：{@code dimension id → Set<BlockPos>}。服务端的同步包都带维度，
 * 客户端只往对应维度里加 / 删；CLEAR 也只清对应维度（{@link #clear(String)}）。
 * 于是「换维度时的清空包晚到一两帧」不会把新维度的数据一起抹掉 —— 两个维度互不干扰。
 *
 * <p>服务端按区块增量同步：进入视距 ADD、离开视距 REMOVE、规则关闭时发 CLEAR。
 * 所以每个维度的表<b>只包含玩家当前视距内</b>的坐标，玩家飞多远都不会膨胀。
 *
 * <p>用<b>不可变快照</b>承载（和 {@link PortalViewClientState} 一个思路）：
 * 每次更新整体换引用，渲染线程读到的永远是某个完整版本，不会读到一半新一半旧。
 * 空集合的维度不保留，快照里不会有「空桶」越积越多。
 */
public final class FurnaceDirtyClientState implements SessionState {

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code FurnaceDirtyClientState.INSTANCE.xxx()}
     */
    public static final FurnaceDirtyClientState INSTANCE = new FurnaceDirtyClientState();

    /**
     * 一份完整快照：维度 id → 该维度的脏熔炉坐标。
     *
     * @param byDimension 只含非空维度；{@code Map.of()} 表示一个都没有
     */
    public record Snapshot(Map<String, Set<BlockPos>> byDimension) {

        /**
         * 空快照。
         *
         * <p><b>必须放在这里，不能提到外层类去</b>：上面的 {@link #INSTANCE} 在类初始化时就会
         * 构造实例、执行 {@code state = new ClientState<>(Snapshot.EMPTY)}；如果 EMPTY 是外层的
         * 静态字段且声明在 INSTANCE 之后，那一刻它还只是 null（静态字段按声明顺序初始化），
         * 于是缓存里装进一个 null，渲染器读出来直接 NPE。
         * 放在 record 里，它的初始化走 Snapshot 自己的 {@code <clinit>}，与外层字段顺序无关。
         */
        public static final Snapshot EMPTY = new Snapshot(Map.of());

        public boolean isEmpty() {
            return byDimension.isEmpty();
        }

        /**
         * 某个维度当前要画的坐标；没有就是空集合（不返回 null）
         */
        public Set<BlockPos> positions(String dimension) {
            if (dimension == null) {
                return Set.of();
            }
            Set<BlockPos> positions = byDimension.get(dimension);
            return positions == null ? Set.of() : positions;
        }
    }

    // 泛型容器接管了 volatile 和线程安全的读写
    private final ClientState<Snapshot> state = new ClientState<>(Snapshot.EMPTY);

    private FurnaceDirtyClientState() {
    }

    /**
     * 渲染器每帧取一次快照；拿到的是一份不可变数据，可以放心遍历
     */
    public Snapshot snapshot() {
        return state.get();
    }

    /**
     * 某个维度当前要画的坐标（渲染器每帧用；不构造快照、直接拿不可变集合）
     */
    public Set<BlockPos> positions(String dimension) {
        return state.get().positions(dimension);
    }

    /**
     * 增量新增（服务端 {@code isAdd=true}），只影响这一维度。
     */
    public void addAll(String dimension, List<BlockPos> additions) {
        if (additions == null || additions.isEmpty()) {
            return;
        }
        String dim = normalize(dimension);
        Map<String, Set<BlockPos>> next = mutable(state.get());
        Set<BlockPos> bucket = new HashSet<>(next.getOrDefault(dim, Set.of()));
        bucket.addAll(additions);
        next.put(dim, Set.copyOf(bucket));
        state.set(new Snapshot(Map.copyOf(next)));
    }

    /**
     * 增量移除（服务端 {@code isAdd=false} 且列表非空），只影响这一维度。
     *
     * <p>这一维度没有数据时什么都不做（例如清空包已经先到、REMOVE 后到）。
     */
    public void removeAll(String dimension, List<BlockPos> removals) {
        if (removals == null || removals.isEmpty()) {
            return;
        }
        String dim = normalize(dimension);
        Map<String, Set<BlockPos>> next = mutable(state.get());
        Set<BlockPos> bucket = next.get(dim);
        if (bucket == null) {
            return;
        }
        Set<BlockPos> remaining = new HashSet<>(bucket);
        remaining.removeAll(removals);
        if (remaining.isEmpty()) {
            next.remove(dim);
        } else {
            next.put(dim, Set.copyOf(remaining));
        }
        state.set(new Snapshot(Map.copyOf(next)));
    }

    /**
     * 清空<b>指定维度</b>（服务端 {@code isAdd=false} + 空列表的 CLEAR 包带的就是它）。
     *
     * <p>只清这一维度：换维度时旧维度的清空包即使晚到，也不会把新维度刚收到的数据抹掉。
     */
    public void clear(String dimension) {
        String dim = normalize(dimension);
        Map<String, Set<BlockPos>> next = mutable(state.get());
        if (next.remove(dim) == null) {
            return; // 该维度本来就没数据：不动快照，避免无意义地换引用
        }
        state.set(new Snapshot(Map.copyOf(next)));
    }

    /**
     * 清空所有维度（本机视图操作：用户本地关了开关；断线时也走这条）。
     */
    public void clear() {
        state.clear(Snapshot.EMPTY);
    }

    /**
     * 断线 / 切世界时清空（由 {@link com.villagerdetails.client.cache.ClientSessionStates} 统一调用）
     */
    @Override
    public void clearSession() {
        clear();
    }

    private static Map<String, Set<BlockPos>> mutable(Snapshot snapshot) {
        return new HashMap<>(snapshot.byDimension());
    }

    private static String normalize(String dimension) {
        return dimension == null ? "" : dimension;
    }
}
