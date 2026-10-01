package com.villagerdetails.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * 服务端 → 客户端：同步「方块挖掘抗性」与「方块可采集」两条规则的当前配置，
 * 让客户端本地的破坏进度计算与服务端保持一致，避免方块被「挖掉又重现」的抖动。
 */
public record BlockRuleSyncPayload(String miningResistance, String collectable) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockRuleSyncPayload> CODEC =
            StreamCodec.composite(
                    StreamCodec.of(FriendlyByteBuf::writeUtf, FriendlyByteBuf::readUtf),
                    BlockRuleSyncPayload::miningResistance,
                    StreamCodec.of(FriendlyByteBuf::writeUtf, FriendlyByteBuf::readUtf),
                    BlockRuleSyncPayload::collectable,
                    BlockRuleSyncPayload::new
            );

    public static final Identifier PACKET_ID = Identifier.fromNamespaceAndPath("villagerdetails", "block_rule_sync");

    public static final Type<BlockRuleSyncPayload> TYPE = new Type<>(PACKET_ID);

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
