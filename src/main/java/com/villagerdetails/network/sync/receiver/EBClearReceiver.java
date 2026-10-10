package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.cache.EBSelectionStateCache;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.sync.clientbound.EBSyncChannel;
import com.villagerdetails.network.sync.payload.serverbound.EBClearPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端请求清空 EB 选区：清掉服务端的权威缓存，再把「已经空了」的最新状态推回去。
 *
 * <p>客户端不本地清空 —— 高亮消失只靠这次 {@link EBSyncChannel#syncTo} 下发（两个字段都是 null）
 * 触发，和「选中实体 / 方块后推同步」走的是同一条回写链路。
 *
 * <p>放在 {@code network.sync.receiver} 包下 = 自动注册，切回主线程由 {@link ServerReceiver} 基类兜底。
 */
public final class EBClearReceiver implements ServerReceiver<EBClearPayload> {

    public static final EBClearReceiver INSTANCE = new EBClearReceiver();

    private EBClearReceiver() {
    }

    @Override
    public PayloadId<EBClearPayload> id() {
        return EBClearPayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, EBClearPayload payload) {
        EBSelectionStateCache.clear(player.getUUID());
        EBSyncChannel.INSTANCE.syncTo(player);
    }
}