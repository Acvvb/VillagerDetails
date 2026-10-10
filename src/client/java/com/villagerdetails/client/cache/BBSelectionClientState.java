package com.villagerdetails.client.cache;

import net.minecraft.core.BlockPos;

/**
 * 客户端记录的「本机玩家 BB 选择器的两个角点」。
 * 由服务端通过 {@link com.villagerdetails.network.sync.payload.clientbound.BBSyncPayload} 推送，
 * 只影响本机渲染。
 *
 * <p>两个角点装在<b>一个不可变快照</b>里整体替换：渲染器拿一次 {@link #get()} 就是同一时刻的
 * pos1 + pos2，不会画出「新 pos1 配旧 pos2」的错框。
 */
public final class BBSelectionClientState {

    /**
     * 空快照（两个角点都是 null）
     */
    public static final Snapshot EMPTY = new Snapshot(null, null);

    private static volatile Snapshot current = EMPTY;

    private BBSelectionClientState() {
    }

    /**
     * 一次选区
     */
    public record Snapshot(BlockPos pos1, BlockPos pos2) {

        public boolean isEmpty() {
            return pos1 == null && pos2 == null;
        }
    }

    public static Snapshot get() {
        return current;
    }

    public static void set(BlockPos pos1, BlockPos pos2) {
        current = new Snapshot(pos1, pos2);
    }

    /**
     * 断线 / 切世界时清空
     */
    public static void clear() {
        current = EMPTY;
    }
}
