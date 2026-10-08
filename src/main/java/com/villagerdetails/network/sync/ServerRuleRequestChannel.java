package com.villagerdetails.network.sync;

import com.villagerdetails.network.channel.SyncChannel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端请求同步规则状态：客户端 → 服务端。
 *
 * <p>收到就直接把这个玩家的规则状态推回去（打开配置界面时用）。
 *
 * <p>包 id：{@code villagerdetails:server_rule_request}
 */
public final class ServerRuleRequestChannel extends SyncChannel<ServerRuleRequestPayload> {

    public static final ServerRuleRequestChannel INSTANCE = new ServerRuleRequestChannel();

    private ServerRuleRequestChannel() {
        super(ServerRuleRequestPayload.ID, ServerRuleRequestPayload.CODEC);
    }

    @Override
    protected void handleServer(ServerPlayer player, ServerRuleRequestPayload payload) {
        ServerRuleSyncChannel.INSTANCE.syncTo(player);
    }
}
