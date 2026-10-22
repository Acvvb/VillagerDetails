package com.villagerdetails.client.cache.impl;

import com.villagerdetails.client.cache.base.ClientSessionState;
import com.villagerdetails.client.cache.base.ClientState;
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
public final class FurnaceDirtyClientState implements ClientSessionState {

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code FurnaceDirtyClientState.INSTANCE.xxx()}
     */
    public static final FurnaceDirtyClientState INSTANCE = new FurnaceDirtyClientState();

    /**
     * 一份完整快照：维度 + 坐标集合
     *
     * @param dimension 这份列表属于哪个维度（{@code minecraft:overworld} 这种文本）
     * @param positions 脏熔炉坐标
     */
    public record Snapshot(String dimension, Set<BlockPos> positions) {

        /**
         * 空快照（没有脏熔炉）。
         *
         * <p><b>必须放在这里，不能提到外层类去</b>：上面的 {@link #INSTANCE} 在类初始化时就会
         * 构造实例、执行 {@code state = new ClientState<>(Snapshot.EMPTY)}；如果 EMPTY 是外层的
         * 静态字段且声明在 INSTANCE 之后，那一刻它还只是 null（静态字段按声明顺序初始化），
         * 于是缓存里装进一个 null，渲染器读出来直接 NPE。
         * 放在 record 里，它的初始化走 Snapshot 自己的 {@code <clinit>}，与外层字段顺序无关。
         */
        public static final Snapshot EMPTY = new Snapshot("", Set.of());

        public boolean isEmpty() {
            return positions.isEmpty();
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
     * 用服务端这一轮的全量结果整份替换；空列表就是清空
     */
    public void replace(String dimension, List<BlockPos> positions) {
        String dim = dimension == null ? "" : dimension;
        if (positions == null || positions.isEmpty()) {
            state.set(new Snapshot(dim, Set.of()));
            return;
        }
        state.set(new Snapshot(dim, Set.copyOf(new HashSet<>(positions))));
    }

    /**
     * 清空高亮（<b>本机视图操作</b>：用户本地关了开关、或服务端回了一个空包）。
     *
     * <p>刻意和 {@link #clearSession()} 分开：那个是「断线清会话」，语义上是两件事，
     * 将来会话结束时若还要顺手做别的收尾，改那边不会连带影响这里的业务清除。
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
}
