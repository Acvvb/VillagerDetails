package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.cache.impl.PlayerBlockSittingPreference;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.sync.payload.serverbound.PlayerBlockSittingEnabledPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端上报的「我还要不要能坐在楼梯 / 半砖上」，写进 {@link PlayerBlockSittingPreference}。
 *
 * <p>默认是开，所以这里只需要处理「关掉」的情况；玩家退出时记录会被清掉。
 *
 * <p>放在 {@code network.sync.receiver} 包下 = 自动注册。
 */
public final class PlayerBlockSittingEnabledReceiver
        implements ServerReceiver<PlayerBlockSittingEnabledPayload> {

    public static final PlayerBlockSittingEnabledReceiver INSTANCE = new PlayerBlockSittingEnabledReceiver();

    private PlayerBlockSittingEnabledReceiver() {
    }

    @Override
    public PayloadId<PlayerBlockSittingEnabledPayload> id() {
        return PlayerBlockSittingEnabledPayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, PlayerBlockSittingEnabledPayload payload) {
        if (player == null || payload == null) {
            return;
        }
        PlayerBlockSittingPreference.INSTANCE.set(player, payload.enabled());
    }
}
