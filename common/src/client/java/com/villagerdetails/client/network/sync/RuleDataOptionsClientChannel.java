package com.villagerdetails.client.network.sync;

import com.villagerdetails.client.cache.impl.RuleAuxCache;
import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.sync.payload.clientbound.RuleDataOptionsPayload;
import net.minecraft.client.Minecraft;

/**
 * 逐条配置的「可选 id + 原版值」清单的客户端侧：收包写进 {@link RuleAuxCache}。
 *
 * <p>不 bumpVersion：详情页在玩家打字 / 点「+」时按需读缓存，响应晚到一步也只是这次不给补全，
 * 不值得为此重建父界面（那会把详情页顶掉）。
 */
public final class RuleDataOptionsClientChannel implements ClientReceiver<RuleDataOptionsPayload> {

    public static final RuleDataOptionsClientChannel INSTANCE = new RuleDataOptionsClientChannel();

    private RuleDataOptionsClientChannel() {
    }

    @Override
    public PayloadId<RuleDataOptionsPayload> id() {
        return RuleDataOptionsPayload.ID;
    }

    @Override
    public void handleClient(Minecraft client, RuleDataOptionsPayload payload) {
        RuleAuxCache cache = RuleAuxCache.INSTANCE;
        cache.put(payload.registerName(), cache.of(payload.tooMany(), payload.options()));
    }
}
