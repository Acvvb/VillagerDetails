package com.villagerdetails.network.sync.clientbound;

import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.RuleDataOptionsPayload;
import com.villagerdetails.rule.registry.RuleDescriptorBuilder;
import net.minecraft.server.level.ServerPlayer;

/**
 * 逐条配置的「可选 id + 原版值」清单：服务端 → 客户端，按需应答。
 *
 * <p>这是<b>全量</b>清单（整个注册表）：补全候选、id 合法性判断、新增一条时的预填都靠它。
 * 只在打开某条规则的详情页时发一次，所以没必要做内容去重（同一份清单重发也只是浪费一点流量）。
 * 只含<b>配置过的</b> id、只用于「重置到默认值」，两者别混用。
 *
 * <p>包 id：{@code villagerdetails:rule_data_options}
 */
public class RuleDataOptionsChannel extends SyncChannel<RuleDataOptionsPayload> {

    public static final RuleDataOptionsChannel INSTANCE = new RuleDataOptionsChannel();

    protected RuleDataOptionsChannel() {
        super(RuleDataOptionsPayload.ID);
    }

    /**
     * 把某条规则的 id 清单发给这个玩家；这条规则不支持逐条配置就什么都不发
     */
    public void sendOptions(ServerPlayer player, String registerName) {
        RuleDataOptionsPayload payload = RuleDescriptorBuilder.buildOptions(registerName);
        if (payload != null) {
            sendTo(player, payload);
        }
    }
}
