
package com.villagerdetails.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.NonNull;

import java.util.Optional;

public record VillagerPacket(int villagerId, Optional<BlockPos> bedPos) {
    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerPacket> PACKET_CODEC = StreamCodec.composite(
            // 字段1：村民ID
            StreamCodec.of(FriendlyByteBuf::writeVarInt, FriendlyByteBuf::readVarInt),
            VillagerPacket::villagerId,

            // 字段2：床坐标（Optional 包装，null 时不崩溃）
            new StreamCodec<>() {
                @Override
                public @NonNull Optional<BlockPos> decode(@NonNull RegistryFriendlyByteBuf buf) {
                    boolean present = buf.readBoolean();
                    return present ? Optional.of(BlockPos.STREAM_CODEC.decode(buf)) : Optional.empty();
                }

                @Override
                public void encode(@NonNull RegistryFriendlyByteBuf buf, @NonNull Optional<BlockPos> pos) {
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