package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 客户端 → 服务端：把某条规则设成指定状态（等价于 {@code /ec <规则> <状态>}）。
 *
 * <p>包 id：{@code villagerdetails:server_rule_set}
 */
public final class ServerRuleSetPayload extends SyncPayload<ServerRuleSetPayload> {

    public static final PayloadId<ServerRuleSetPayload> ID = PayloadId.of("server_rule_set");

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerRuleSetPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.STRING, ServerRuleSetPayload::registerName,
                    ModCodecs.STRING, ServerRuleSetPayload::state,
                    ModCodecs.BOOL, ServerRuleSetPayload::reset,
                    ServerRuleSetPayload::new);

    private final String registerName;
    private final String state;
    private final boolean reset;

    /**
     * @param registerName 规则注册名
     * @param state        目标状态字符串（false / true / ops / master / admin / owner / none / 自定义值，
     *                     多选时是逗号分隔的列表）
     * @param reset        true 表示「恢复默认」，此时忽略 {@code state}
     */
    public ServerRuleSetPayload(String registerName, String state, boolean reset) {
        super(ID);
        this.registerName = registerName;
        this.state = state;
        this.reset = reset;
    }

    public String registerName() {
        return registerName;
    }

    public String state() {
        return state;
    }

    public boolean reset() {
        return reset;
    }
}
