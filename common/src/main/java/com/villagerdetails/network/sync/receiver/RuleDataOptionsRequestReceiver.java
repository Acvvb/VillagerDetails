package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.sync.clientbound.RuleDataOptionsChannel;
import com.villagerdetails.network.sync.payload.serverbound.RuleDataOptionsRequestPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端要某条规则的「可选 id + 原版值」清单：核对权限后回一份。
 *
 * <p>放在 {@code network.sync.receiver} 包下 = 自动注册。
 */
public final class RuleDataOptionsRequestReceiver implements ServerReceiver<RuleDataOptionsRequestPayload> {

    public static final RuleDataOptionsRequestReceiver INSTANCE = new RuleDataOptionsRequestReceiver();

    private RuleDataOptionsRequestReceiver() {
    }

    @Override
    public PayloadId<RuleDataOptionsRequestPayload> id() {
        return RuleDataOptionsRequestPayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, RuleDataOptionsRequestPayload payload) {
        if (player == null || RuleCache.lacksRuleCommandPermission(player)) {
            return;
        }
        RuleDataOptionsChannel.INSTANCE.sendOptions(player, payload.registerName());
    }
}
