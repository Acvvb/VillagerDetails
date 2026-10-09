package com.villagerdetails.client.cache;

import net.minecraft.core.BlockPos;

/**
 * 客户端记录的「本机玩家 BB 选择器的两个角点」。
 * 由服务端通过 {@link com.villagerdetails.network.sync.payload.clientbound.BBSyncPayload} 推送，
 * 只影响本机渲染。
 */
public final class BBSelectionClientState {

    private static volatile BlockPos pos1;
    private static volatile BlockPos pos2;

    private BBSelectionClientState() {
    }

    public static void set(BlockPos a, BlockPos b) {
        pos1 = a;
        pos2 = b;
    }

    public static BlockPos pos1() {
        return pos1;
    }

    public static BlockPos pos2() {
        return pos2;
    }

    /**
     * 断线 / 切世界时清空
     */
    public static void clear() {
        pos1 = null;
        pos2 = null;
    }
}