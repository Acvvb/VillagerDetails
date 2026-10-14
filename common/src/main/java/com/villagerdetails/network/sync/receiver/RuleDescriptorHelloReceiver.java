package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.channel.SyncProtocol;
import com.villagerdetails.network.sync.clientbound.RuleDescriptorSyncChannel;
import com.villagerdetails.network.sync.payload.serverbound.RuleDescriptorHelloPayload;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端打招呼：核对协议版本、记下它的界面语言，然后按这个语言推一份描述符回去。
 *
 * <p>放在 {@code network.sync.receiver} 包下 = 自动注册。
 *
 * <p>协议版本对不上就只记一条日志、不同步：两边是同一份构建，正常不会发生；
 * 真发生了（混用新旧 jar），发过去也是一堆解不开的字节，不如留一条明确的日志。
 */
public final class RuleDescriptorHelloReceiver implements ServerReceiver<RuleDescriptorHelloPayload> {

    private static final Logger LOGGER = LogManager.getLogger(RuleDescriptorHelloReceiver.class);

    public static final RuleDescriptorHelloReceiver INSTANCE = new RuleDescriptorHelloReceiver();

    private RuleDescriptorHelloReceiver() {
    }

    @Override
    public PayloadId<RuleDescriptorHelloPayload> id() {
        return RuleDescriptorHelloPayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, RuleDescriptorHelloPayload payload) {
        if (player == null) {
            return;
        }
        if (payload.protocol() != SyncProtocol.VERSION) {
            LOGGER.warn("玩家 {} 的同步协议版本是 {}，服务端是 {}，不做规则描述符同步",
                    player.getName().getString(), payload.protocol(), SyncProtocol.VERSION);
            return;
        }

        RuleDescriptorSyncChannel.INSTANCE.setLanguage(player, payload.language());
        RuleDescriptorSyncChannel.INSTANCE.syncTo(player);
    }
}
