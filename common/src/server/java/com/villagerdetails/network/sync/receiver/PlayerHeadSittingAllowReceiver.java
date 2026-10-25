package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.cache.impl.PlayerHeadSittingAllow;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.sync.payload.serverbound.PlayerHeadSittingAllowPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端上报的「允许别人坐到我头上」开关，写进 {@link PlayerHeadSittingAllow} 名单。
 *
 * <p>只看包里的 {@code allow}，其它一律不信：谁能不能被坐完全由对方自己的客户端说了算，
 * 服务端不提供「替别人允许」的入口。
 *
 * <p>放在 {@code network.sync.receiver} 包下 = 自动注册。
 */
public final class PlayerHeadSittingAllowReceiver implements ServerReceiver<PlayerHeadSittingAllowPayload> {

    public static final PlayerHeadSittingAllowReceiver INSTANCE = new PlayerHeadSittingAllowReceiver();

    private PlayerHeadSittingAllowReceiver() {
    }

    @Override
    public PayloadId<PlayerHeadSittingAllowPayload> id() {
        return PlayerHeadSittingAllowPayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, PlayerHeadSittingAllowPayload payload) {
        if (player == null || payload == null) {
            return;
        }
        PlayerHeadSittingAllow.INSTANCE.set(player, payload.allow());
    }
}
