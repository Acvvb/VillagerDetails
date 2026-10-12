package com.villagerdetails.network.sync.payload.clientbound;

import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.Optional;

/**
 * 村民床同步的内容载体（{@link VillagerBedPayload} 的第二个字段）。
 *
 * <p>Optional 的编解码以前是手写的开关，现在用 {@link ModCodecs#optionalOf}。
 * 和另外两个内部小结构一样，缓冲类型用 {@link FriendlyByteBuf} 就够
 * （外面的大包再套成 {@code RegistryFriendlyByteBuf}）。
 */
public record VillagerPacket(int villagerId, Optional<BlockPos> bedPos) {

    public static final StreamCodec<FriendlyByteBuf, VillagerPacket> PACKET_CODEC =
            StreamCodec.composite(
                    ModCodecs.VAR_INT, VillagerPacket::villagerId,
                    ModCodecs.optionalOf(BlockPos.STREAM_CODEC), VillagerPacket::bedPos,
                    VillagerPacket::new);
}
