package com.villagerdetails.network.sync.payload.clientbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * 服务端 → 客户端：玩家附近所有「脏熔炉」的坐标，<b>全量</b>下发。
 *
 * <p>每个周期整份替换客户端的本地表；空列表表示「附近没有脏熔炉 / 规则已关闭」，
 * 客户端收到后清空高亮即可，不需要额外的清除包。
 *
 * <p>包 id：{@code villagerdetails:furnace_dirty_sync}
 */
public final class FurnaceDirtySyncPayload extends SyncPayload<FurnaceDirtySyncPayload> {

    public static final PayloadId<FurnaceDirtySyncPayload> ID = PayloadId.of("furnace_dirty_sync");

    public static final StreamCodec<RegistryFriendlyByteBuf, FurnaceDirtySyncPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.STRING, FurnaceDirtySyncPayload::dimension,
                    ModCodecs.listOf(BlockPos.STREAM_CODEC), FurnaceDirtySyncPayload::positions,
                    FurnaceDirtySyncPayload::new);

    /**
     * 这份列表属于哪个维度（{@code minecraft:overworld} 这种文本）
     */
    private final String dimension;

    /**
     * 脏熔炉坐标（只装坐标，客户端统一用一种颜色画）
     */
    private final List<BlockPos> positions;

    public FurnaceDirtySyncPayload(String dimension, List<BlockPos> positions) {
        super(ID);
        this.dimension = dimension == null ? "" : dimension;
        this.positions = List.copyOf(positions);
    }

    /**
     * 维度 id 文本；客户端拿它和当前维度比对，传送维度后不会把上一维度的框画出来
     */
    public String dimension() {
        return dimension;
    }

    public List<BlockPos> positions() {
        return positions;
    }
}
