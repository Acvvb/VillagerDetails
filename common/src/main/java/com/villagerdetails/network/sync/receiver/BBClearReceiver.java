package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.cache.BBSectionStateCache;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.sync.clientbound.BBSyncChannel;
import com.villagerdetails.network.sync.payload.serverbound.BBClearPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端请求清空 BB 选区：清掉服务端的权威缓存，再把「已经空了」的最新状态原样推回去。
 *
 * <p>客户端自己完全不动本地缓存 —— 高亮消失只靠这次  下发
 * （pos1 / pos2 都是 null）来触发，和「左键 / 右键点方块改选区后推同步」走的是同一条回写链路。
 *
 * <p>放在 {@code network.sync.receiver} 包下 = 自动注册，切回服务端主线程由 {@link ServerReceiver} 基类兜底。
 */
public final class BBClearReceiver implements ServerReceiver<BBClearPayload> {

    public static final BBClearReceiver INSTANCE = new BBClearReceiver();

    private BBClearReceiver() {
    }

    @Override
    public PayloadId<BBClearPayload> id() {
        return BBClearPayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, BBClearPayload payload) {
        BBSectionStateCache.clearSelection(player);
        BBSyncChannel.INSTANCE.syncTo(player);
    }
}