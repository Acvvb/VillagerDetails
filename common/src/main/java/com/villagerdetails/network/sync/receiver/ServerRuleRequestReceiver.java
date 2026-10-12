package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.sync.payload.serverbound.ServerRuleRequestPayload;
import com.villagerdetails.network.sync.clientbound.ServerRuleSyncChannel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端请求同步规则状态：收到就把这个玩家的规则状态推回去（打开配置界面时用）。
 *
 * <p>包的收发定义在 {@code network.sync.serverbound.ServerRuleRequestChannel}，
 * 这里只管收到之后干什么。放在 {@code network.sync.receiver} 包下 = 自动注册。
 */
public final class ServerRuleRequestReceiver implements ServerReceiver<ServerRuleRequestPayload> {

    public static final ServerRuleRequestReceiver INSTANCE = new ServerRuleRequestReceiver();

    private ServerRuleRequestReceiver() {
    }

    @Override
    public PayloadId<ServerRuleRequestPayload> id() {
        return ServerRuleRequestPayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, ServerRuleRequestPayload payload) {
        ServerRuleSyncChannel.INSTANCE.syncTo(player);
    }
}
