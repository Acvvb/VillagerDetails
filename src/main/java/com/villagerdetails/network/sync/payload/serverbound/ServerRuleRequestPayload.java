package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 客户端 → 服务端：请求把当前规则状态同步回来（打开配置界面时用）。
 *
 * <p>没有字段，所以编解码用 {@code StreamCodec.unit}。
 * 包 id：{@code villagerdetails:server_rule_request}
 */
public final class ServerRuleRequestPayload extends SyncPayload<ServerRuleRequestPayload> {

    public static final PayloadId<ServerRuleRequestPayload> ID = PayloadId.of("server_rule_request");

    public static final ServerRuleRequestPayload INSTANCE = new ServerRuleRequestPayload();

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerRuleRequestPayload> CODEC =
            StreamCodec.unit(INSTANCE);

    private ServerRuleRequestPayload() {
        super(ID);
    }
}
