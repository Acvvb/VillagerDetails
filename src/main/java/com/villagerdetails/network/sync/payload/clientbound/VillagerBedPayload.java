package com.villagerdetails.network.sync.payload.clientbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 服务端 → 客户端：某个村民当前绑定的床（用于画线）。
 *
 * <p>包 id：{@code villagerdetails:villager_sync}（以前命名空间写错了，第 2 步统一成模组 id）。
 */
public final class VillagerBedPayload extends SyncPayload<VillagerBedPayload> {

    public static final PayloadId<VillagerBedPayload> ID = PayloadId.of("villager_sync");

    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerBedPayload> CODEC =
            StreamCodec.composite(
                    VillagerPacket.PACKET_CODEC, VillagerBedPayload::data,
                    VillagerBedPayload::new);

    private final VillagerPacket data;

    public VillagerBedPayload(VillagerPacket data) {
        super(ID);
        this.data = data;
    }

    public VillagerPacket data() {
        return data;
    }
}
