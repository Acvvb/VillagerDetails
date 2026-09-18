package com.villagerdetails.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record VillagerPacket(int villagerId, BlockPos bedPos) {
    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerPacket> PACKET_CODEC = StreamCodec.composite(
            // 字段1：村民ID
            StreamCodec.of(FriendlyByteBuf::writeVarInt, FriendlyByteBuf::readVarInt),
            VillagerPacket::villagerId,

            // 字段2：床坐标
            BlockPos.STREAM_CODEC,
            VillagerPacket::bedPos,

            // 构造函数
            VillagerPacket::new
    );
}