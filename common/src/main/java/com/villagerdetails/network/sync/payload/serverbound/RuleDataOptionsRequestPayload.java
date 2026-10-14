package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import com.villagerdetails.network.sync.payload.clientbound.RuleDataOptionsPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 客户端 → 服务端：要某条逐条配置规则的「可选 id + 原版值」清单。
 *
 * <p>客户端现在不认识 {@code minecraft:block} 这类语义了，所以补全候选和「新增一条时预填的原版值」
 * 都得问服务端要；打开某条规则的详情页时发一次，服务端回 {@link RuleDataOptionsPayload}。
 *
 * <p>包 id：{@code villagerdetails:rule_data_options_request}
 */
public final class RuleDataOptionsRequestPayload extends SyncPayload<RuleDataOptionsRequestPayload> {

    public static final PayloadId<RuleDataOptionsRequestPayload> ID =
            PayloadId.of("rule_data_options_request");

    public static final StreamCodec<RegistryFriendlyByteBuf, RuleDataOptionsRequestPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.STRING, RuleDataOptionsRequestPayload::registerName,
                    RuleDataOptionsRequestPayload::new);

    private final String registerName;

    public RuleDataOptionsRequestPayload(String registerName) {
        super(ID);
        this.registerName = registerName == null ? "" : registerName;
    }

    public String registerName() {
        return registerName;
    }
}
