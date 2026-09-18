package com.villagerdetails.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;


// 1. 定义 record + 实现 CustomPacketPayload
public record VillagerBedPayload(VillagerPacket data) implements CustomPacketPayload {
    // 2. 用 StreamCodec.composite 自动生成编解码器
    public static final StreamCodec<RegistryFriendlyByteBuf, VillagerBedPayload> CODEC =
            StreamCodec.composite(
                    VillagerPacket.PACKET_CODEC,
                    VillagerBedPayload::data,
                    VillagerBedPayload::new
            );

    // 3. 定义 PACKET_ID
    public static final Identifier PACKET_ID = Identifier.fromNamespaceAndPath("villager_bed_sync", "villager_sync");

    // 4. 定义 TYPE
    public static final Type<VillagerBedPayload> TYPE = new Type<>(PACKET_ID);

    // 5. 实现接口方法
    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}