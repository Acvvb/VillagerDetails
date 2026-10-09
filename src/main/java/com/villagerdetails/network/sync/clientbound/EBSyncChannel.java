package com.villagerdetails.network.sync.clientbound;

import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.EBSyncPayload;

/**
 * S→C：把「本机玩家当前选中了什么」推给客户端，用于客户端高亮。
 * 只影响本机渲染，不参与任何服务端逻辑。
 *
 * <p>放在 {@code network.sync.clientbound} 包下 = 自动注册成 S→C 包类型，不用手动登记。
 */
public class EBSyncChannel extends SyncChannel<EBSyncPayload> {

    public static final EBSyncChannel INSTANCE = new EBSyncChannel();

    protected EBSyncChannel() {
        super(EBSyncPayload.ID);
    }
}
