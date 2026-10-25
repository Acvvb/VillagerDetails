package com.villagerdetails.client.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.client.cache.base.ClientMapState;
import net.minecraft.core.BlockPos;

/**
 * 客户端记录的「村民绑定的工作站点」缓存。
 *
 * <p>数据由服务端逐个村民推下来（{@code workstation_sync}），客户端只负责记和查 ——
 * 不判断「这个方块是不是工作站点」，那是服务端的记忆说了算。
 *
 * <p>村民实体 ID 在换世界 / 重进服之后会被复用，所以断线必须整体清空
 * （{@link #clearSession()} 由扫包框架自动调用，和 ED/BB 那几份一样）。
 */
public final class WorkstationClientState implements SessionState {

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code WorkstationClientState.INSTANCE.xxx()}
     */
    public static final WorkstationClientState INSTANCE = new WorkstationClientState();

    /**
     * 村民实体 ID → 工作站点坐标。
     *
     * <p>用 {@link ClientMapState}（不可变 Map 整体替换）而不是自己开 HashMap：
     * 渲染线程每帧要查它，写入则发生在收包和实体卸载时，快照式读写不需要再加锁。
     */
    private final ClientMapState<Integer, BlockPos> state = new ClientMapState<>();

    private WorkstationClientState() {
    }

    /**
     * 取某个村民的工作站点；没有就是 {@code null}（渲染器据此跳过）
     */
    public BlockPos getWorkstation(int villagerId) {
        return state.get(villagerId);
    }

    /**
     * 收到服务端同步时更新缓存；{@code pos == null} 表示这个村民现在没有工作站点，直接删掉记录
     */
    public void updateWorkstation(int villagerId, BlockPos pos) {
        if (pos == null) {
            state.remove(villagerId);
        } else {
            state.put(villagerId, pos);
        }
    }

    /**
     * 村民卸载（走出视距 / 死亡 / 换维度）时清掉这条记录，
     * 避免实体 ID 被复用后给错误的村民连出一条线。
     */
    public void removeWorkstation(int villagerId) {
        state.remove(villagerId);
    }

    /**
     * 断线 / 切换世界时整体清空（由 {@link com.villagerdetails.client.cache.ClientSessionStates} 统一调用）
     */
    @Override
    public void clearSession() {
        state.clear();
    }
}
