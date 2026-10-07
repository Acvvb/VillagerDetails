package com.villagerdetails.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

/**
 * 客户端 → 服务端：请求把当前规则状态同步一份回来（打开配置界面时用）。
 */
public record ServerRuleRequestPayload() implements CustomPacketPayload {

    public static final ServerRuleRequestPayload INSTANCE = new ServerRuleRequestPayload();

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerRuleRequestPayload> CODEC =
            StreamCodec.unit(INSTANCE);

    public static final Identifier PACKET_ID = Identifier.fromNamespaceAndPath("villagerdetails", "server_rule_request");

    public static final Type<ServerRuleRequestPayload> TYPE = new Type<>(PACKET_ID);

    @Override
    public @NonNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
