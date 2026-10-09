package com.villagerdetails.client.cache;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * 客户端记录的「本机玩家当前选中了什么」。
 * 数据由服务端通过 {@code EBSyncPayload} 推送，只影响本机渲染，不参与任何服务端逻辑。
 */
public final class EBSelectionClientState {

    private static volatile UUID selectedEntity;
    private static volatile BlockPos selectedBlock;

    private EBSelectionClientState() {
    }

    public static void set(UUID entity, BlockPos block) {
        selectedEntity = entity;
        selectedBlock = block;
    }

    public static UUID entity() {
        return selectedEntity;
    }

    public static BlockPos block() {
        return selectedBlock;
    }

    /**
     * 断线 / 切世界时清空
     */
    public static void clear() {
        selectedEntity = null;
        selectedBlock = null;
    }
}