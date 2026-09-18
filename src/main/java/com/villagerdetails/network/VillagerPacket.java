
package com.villagerdetails.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.Optional;

public record VillagerPacket(int villagerId, Optional<BlockPos> bedPos) {
    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerPacket> PACKET_CODEC = StreamCodec.composite(
            // 字段1：村民ID
            StreamCodec.of(FriendlyByteBuf::writeVarInt, FriendlyByteBuf::readVarInt),
            VillagerPacket::villagerId,

            // 字段2：床坐标（Optional 包装，null 时不崩溃）
            new StreamCodec<RegistryFriendlyByteBuf, Optional<BlockPos>>() {
                @Override
                public Optional<BlockPos> decode(RegistryFriendlyByteBuf buf) {
                    boolean present = buf.readBoolean();
                    return present ? Optional.of(BlockPos.STREAM_CODEC.decode(buf)) : Optional.empty();
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, Optional<BlockPos> pos) {
                    if (pos.isPresent()) {
                        buf.writeBoolean(true);
                        BlockPos.STREAM_CODEC.encode(buf, pos.get());
                    } else {
                        buf.writeBoolean(false);
                    }
                }
            },
            VillagerPacket::bedPos,

            // 构造函数
            VillagerPacket::new
    );
}