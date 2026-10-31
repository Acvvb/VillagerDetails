package com.villagerdetails.network.sync.clientbound;

import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.FurnaceDirtySyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * S→C：脏熔炉的<b>增量</b>发包口（ADD / REMOVE）。
 *
 * <p>放在 {@code network.sync.clientbound} 包下 = 自动登记成 S→C 包类型。
 * 什么时候发、发给谁，全部由 {@code FurnaceDirtyTracker} 决定（区块粒度），
 * 这个类只管「怎么把一批坐标发出去」。
 *
 * <p>客户端可以单方面把这条通道静音（通用的 {@code sync_subscription} 包），
 * 静音后 {@link #canSendTo} 直接返回 false，调用方连扫描都会跳过。
 *
 * <p>包 id：{@code villagerdetails:furnace_dirty_sync_v2}
 */
public class FurnaceDirtySyncChannel extends SyncChannel<FurnaceDirtySyncPayload> {

    public static final FurnaceDirtySyncChannel INSTANCE = new FurnaceDirtySyncChannel();

    protected FurnaceDirtySyncChannel() {
        super(FurnaceDirtySyncPayload.ID);
    }

    /**
     * 推一批增量。
     *
     * @param player    目标玩家
     * @param level     这批坐标所属的维度
     * @param add       {@code true} = 新增；{@code false} = 移除
     * @param positions 坐标；{@code add=false} 且为空时表示「清空全部」
     */
    public void send(ServerPlayer player, ServerLevel level, boolean add, List<BlockPos> positions) {
        if (player == null || level == null) {
            return;
        }
        List<BlockPos> batch = positions == null ? List.of() : List.copyOf(positions);
        // 空 ADD 没有意义（客户端 addAll 空集合什么也不做），省一个包
        if (add && batch.isEmpty()) {
            return;
        }
        if (!canSendTo(player)) {
            return;
        }
        sendTo(player, new FurnaceDirtySyncPayload(level.dimension().identifier().toString(), add, batch));
    }

    /**
     * 让客户端把「这个维度」的本地表整个清掉（换维度 / 规则被关掉 / 重新订阅）。
     */
    public void sendClear(ServerPlayer player, String dimension) {
        if (player == null) {
            return;
        }
        if (!canSendTo(player)) {
            return;
        }
        sendTo(player, new FurnaceDirtySyncPayload(dimension, false, List.of()));
    }
}
