package com.villagerdetails.network.sync.clientbound;

import com.villagerdetails.cache.BBSectionStateCache;
import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.BBSyncPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * S→C：把「本机玩家 BB 选择器的两个角点」推给客户端，用于客户端高亮。
 *
 * <p>放在 {@code network.sync.clientbound} 包下 = 自动注册成 S→C 包类型，不用手动登记。
 */
public class BBSyncChannel extends SyncChannel<BBSyncPayload> {

    public static final BBSyncChannel INSTANCE = new BBSyncChannel();

    protected BBSyncChannel() {
        super(BBSyncPayload.ID);
    }

    public void syncTo(ServerPlayer player) {
        sendTo(player, new BBSyncPayload(
                BBSectionStateCache.getPos1(player),
                BBSectionStateCache.getPos2(player)));
    }
}
