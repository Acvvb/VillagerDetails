package com.villagerdetails.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * 客户端 → 服务端：把某条规则设成指定状态（等价于 {@code /ec <规则> <状态>}）。
 *
 * @param registerName 规则注册名
 * @param state        目标状态字符串（false / true / ops / master / admin / owner / none / 自定义值，
 *                     多选时是逗号分隔的列表）
 * @param reset        true 表示「恢复默认」，此时忽略 {@code state}
 */
public record ServerRuleSetPayload(String registerName, String state, boolean reset) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerRuleSetPayload> CODEC =
            StreamCodec.composite(
                    StreamCodec.of(FriendlyByteBuf::writeUtf, FriendlyByteBuf::readUtf),
                    ServerRuleSetPayload::registerName,
                    StreamCodec.of(FriendlyByteBuf::writeUtf, FriendlyByteBuf::readUtf),
                    ServerRuleSetPayload::state,
                    StreamCodec.of(FriendlyByteBuf::writeBoolean, FriendlyByteBuf::readBoolean),
                    ServerRuleSetPayload::reset,
                    ServerRuleSetPayload::new);

    public static final Identifier PACKET_ID = Identifier.fromNamespaceAndPath("villagerdetails", "server_rule_set");

    public static final Type<ServerRuleSetPayload> TYPE = new Type<>(PACKET_ID);

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
