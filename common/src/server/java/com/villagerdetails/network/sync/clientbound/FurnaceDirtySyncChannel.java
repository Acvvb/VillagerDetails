package com.villagerdetails.network.sync.clientbound;

import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.FurnaceDirtySyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * S→C：把「玩家附近的脏熔炉」整份推给客户端做高亮。
 *
 * <p>放在 {@code network.sync.clientbound} 包下 = 自动登记成 S→C 包类型。
 * 周期的扫描 / 开关接线在 {@code network.sync.event.FurnaceDirtySyncEvents}。
 *
 * <p>客户端可以单方面把这条通道静音（通用的 {@code sync_subscription} 包），
 * 静音后 {@link #canSendTo} 直接返回 false，调用方连扫描都会跳过。
 *
 * <p>包 id：{@code villagerdetails:furnace_dirty_sync}
 */
public class FurnaceDirtySyncChannel extends SyncChannel<FurnaceDirtySyncPayload> {

    public static final FurnaceDirtySyncChannel INSTANCE = new FurnaceDirtySyncChannel();

    protected FurnaceDirtySyncChannel() {
        super(FurnaceDirtySyncPayload.ID);
    }

    /**
     * 全量推一次；空列表等于让客户端清空高亮
     */
    public void syncTo(ServerPlayer player, List<BlockPos> dirty) {
        if (!canSendTo(player)) {
            return;
        }
        String dimension = player.level().dimension().identifier().toString();
        sendTo(player, new FurnaceDirtySyncPayload(dimension, dirty));
    }
}
