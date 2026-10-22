package com.villagerdetails.client.cache.impl;

import com.villagerdetails.client.cache.base.ClientSessionState;
import com.villagerdetails.client.cache.base.ClientState;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * 客户端记录的「本机玩家当前选中了什么」（实体 + 方块）。
 *
 * <p>数据由服务端通过 {@code EBSyncPayload} 推送，只影响本机渲染，不参与任何服务端逻辑。
 *
 * <p>状态用<b>一个不可变快照</b>承载：读侧拿一次 {@link #get()} 就拿到同一时刻的实体和方块，
 * 不会出现「读实体时是新的、读方块时还是上一份」的错配（渲染器每帧要同时用这两个字段）。
 */
public final class EBSelectionClientState implements ClientSessionState {

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code EBSelectionClientState.INSTANCE.xxx()}
     */
    public static final EBSelectionClientState INSTANCE = new EBSelectionClientState();

    /**
     * 空快照（两个字段都是 null）
     */
    public static final Snapshot EMPTY = new Snapshot(null, null);

    private final ClientState<Snapshot> state = new ClientState<>(EMPTY);

    private EBSelectionClientState() {
    }

    /**
     * 一次选中状态
     */
    public record Snapshot(UUID entity, BlockPos block) {

        public boolean isEmpty() {
            return entity == null && block == null;
        }
    }

    public Snapshot get() {
        return state.get();
    }

    public void set(UUID entity, BlockPos block) {
        state.set(new Snapshot(entity, block));
    }

    /**
     * 断线 / 切世界时清空（由 {@link com.villagerdetails.client.cache.ClientSessionStates} 统一调用）
     */
    @Override
    public void clearSession() {
        state.clear(EMPTY);
    }
}
