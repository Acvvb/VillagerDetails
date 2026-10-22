package com.villagerdetails.client.cache.impl;

import com.villagerdetails.client.cache.base.ClientSessionState;
import com.villagerdetails.client.cache.base.ClientState;
import net.minecraft.core.BlockPos;

/**
 * 客户端记录的「本机玩家 BB 选择器的两个角点」。
 * 由服务端通过 {@link com.villagerdetails.network.sync.payload.clientbound.BBSyncPayload} 推送，
 * 只影响本机渲染。
 *
 * <p>两个角点装在<b>一个不可变快照</b>里整体替换：渲染器拿一次 {@link #get()} 就是同一时刻的
 * pos1 + pos2，不会画出「新 pos1 配旧 pos2」的错框。
 */
public final class BBSelectionClientState implements ClientSessionState {

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code BBSelectionClientState.INSTANCE.xxx()}
     */
    public static final BBSelectionClientState INSTANCE = new BBSelectionClientState();

    private final ClientState<Snapshot> state = new ClientState<>(Snapshot.EMPTY);

    private BBSelectionClientState() {
    }

    /**
     * 一次选区
     */
    public record Snapshot(BlockPos pos1, BlockPos pos2) {

        /**
         * 空快照（两个角点都是 null）。
         *
         * <p><b>必须放在这里，不能提到外层类去</b>：上面的 {@link #INSTANCE} 在类初始化时就会
         * 构造实例、执行 {@code state = new ClientState<>(Snapshot.EMPTY)}；如果 EMPTY 是外层的
         * 静态字段且声明在 INSTANCE 之后，那一刻它还只是 null（静态字段按声明顺序初始化），
         * 于是缓存里装进一个 null，渲染器读出来直接 NPE。
         * 放在 record 里，它的初始化走 Snapshot 自己的 {@code <clinit>}，与外层字段顺序无关。
         */
        public static final Snapshot EMPTY = new Snapshot(null, null);

        public boolean isEmpty() {
            return pos1 == null && pos2 == null;
        }
    }

    public Snapshot get() {
        return state.get();
    }

    public void set(BlockPos pos1, BlockPos pos2) {
        state.set(new Snapshot(pos1, pos2));
    }

    /**
     * 断线 / 切世界时清空（由 {@link com.villagerdetails.client.cache.ClientSessionStates} 统一调用）
     */
    @Override
    public void clearSession() {
        state.clear(Snapshot.EMPTY);
    }
}
