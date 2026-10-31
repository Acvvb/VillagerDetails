package com.villagerdetails.network.sync.payload.clientbound;

import com.villagerdetails.network.channel.ClientboundPayload;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * 服务端 → 客户端：脏熔炉的<b>增量</b>同步（ADD / REMOVE）。
 *
 * <p>一次包只描述「这一批坐标要加上」或「这一批坐标要拿掉」：
 * 客户端收到 {@code isAdd=true} 就 {@code addAll}，{@code isAdd=false} 就 {@code removeAll}。
 * 特殊约定：<b>{@code isAdd=false} 且列表为空 = 清空全部</b>
 * （换维度、规则被关掉、客户端重新订阅时用），这样不用再加第三种动作字段。
 *
 * <p><b>为什么不再发全量列表</b>：全量方案下每个玩家每个周期都收一份「自己附近」的完整列表，
 * 数据量随脏熔炉数量增长；而客户端只增不减的写法又要求服务端每次都要发全量才能保证一致。
 * 改成增量 + 区块粒度（进入视距 ADD、离开视距 REMOVE）之后：
 * <ul>
 *     <li>客户端的表只包含<b>当前视距内</b>的坐标，玩家飞多远都不会膨胀；</li>
 *     <li>网络只在「跨区块 / 熔炉状态变化 / 换维度」时发包，频率极低；</li>
 *     <li>客户端不再需要按距离 / 维度过滤（见 {@code FurnaceDirtyHighlightRenderer}）。</li>
 * </ul>
 *
 * <p><b>包 id 是 {@code furnace_dirty_sync_v2}</b>：字段从「维度 + 全量列表」变成
 * 「维度 + 增量标志 + 坐标列表」，旧 id 的解码已经对不上，所以换 id 而不是塞兼容字段。
 * 换 id 之后旧客户端根本不注册这个包，{@code ServerPlayNetworking.canSend} 直接返回 false ——
 * 收不到、也不会因为解码失败掉线。发布时记得把 {@code NetworkConstants.MIN_COMPATIBLE_VERSION}
 * 抬到本版。
 */
public final class FurnaceDirtySyncPayload extends SyncPayload<FurnaceDirtySyncPayload> implements ClientboundPayload {

    public static final PayloadId<FurnaceDirtySyncPayload> ID = PayloadId.of("furnace_dirty_sync_v2");

    public static final StreamCodec<RegistryFriendlyByteBuf, FurnaceDirtySyncPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.STRING, FurnaceDirtySyncPayload::dimension,
                    ModCodecs.BOOL, FurnaceDirtySyncPayload::isAdd,
                    ModCodecs.listOf(BlockPos.STREAM_CODEC), FurnaceDirtySyncPayload::positions,
                    FurnaceDirtySyncPayload::new);

    /**
     * 这份增量属于哪个维度（{@code minecraft:overworld} 这种文本）
     */
    private final String dimension;

    /**
     * {@code true} = 新增（客户端 addAll）；{@code false} = 移除（客户端 removeAll）
     */
    private final boolean add;

    /**
     * 脏熔炉坐标（只装坐标，客户端统一用一种颜色画）
     */
    private final List<BlockPos> positions;

    public FurnaceDirtySyncPayload(String dimension, boolean add, List<BlockPos> positions) {
        super(ID);
        this.dimension = dimension == null ? "" : dimension;
        this.add = add;
        this.positions = positions == null ? List.of() : List.copyOf(positions);
    }

    /**
     * 维度 id 文本；客户端拿它和上一个包的维度比对，换维度时先清掉旧数据
     */
    public String dimension() {
        return dimension;
    }

    /**
     * {@code true} = ADD，{@code false} = REMOVE（列表为空时表示清空全部）
     */
    public boolean isAdd() {
        return add;
    }

    public List<BlockPos> positions() {
        return positions;
    }
}
