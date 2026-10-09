package com.villagerdetails.network.sync;

import com.villagerdetails.network.channel.SyncChannel;

/**
 * S→C：把「本机玩家 BB 选择器的两个角点」推给客户端，用于客户端高亮。
 */
public class BBSyncChannel extends SyncChannel<BBSyncPayload> {

    public static final BBSyncChannel INSTANCE = new BBSyncChannel();

    protected BBSyncChannel() {
        super(BBSyncPayload.ID, BBSyncPayload.CODEC);
    }
}