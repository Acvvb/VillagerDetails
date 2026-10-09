package com.villagerdetails.network.sync;

import com.villagerdetails.network.channel.SyncChannel;

/**
 * S→C：把「本机玩家当前选中了什么」推给客户端，用于客户端高亮。
 * 只影响本机渲染，不参与任何服务端逻辑。
 */
public class EBSyncChannel extends SyncChannel<EBSyncPayload> {

    public static final EBSyncChannel INSTANCE = new EBSyncChannel();

    protected EBSyncChannel() {
        super(EBSyncPayload.ID, EBSyncPayload.CODEC);
    }
}