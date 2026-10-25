package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerboundPayload;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 客户端 → 服务端：<b>「我允许别人坐到我头上吗」</b>。
 *
 * <p>「玩家头顶乘坐」的同意权完全在<b>被坐的那个人</b>自己手里，而且是每个玩家各管各的：
 * 客户端配置里有一个开关（{@code AllowPlayerHeadSitting}），开着就发 {@code allow = true}。
 *
 * <p>服务端记成一张「允许名单」，只在名单里的人能被别人右键坐上去；
 * <b>没装本 mod 的玩家永远不会发这个包，所以默认算不允许</b> —— 这就是防恶意骑人的护栏。
 *
 * <p>玩家退出时服务端丢掉记录（下次进服客户端会自己再发一遍），所以不需要持久化。
 *
 * <p>包 id：{@code villagerdetails:player_head_sitting_allow}
 */
public final class PlayerHeadSittingAllowPayload extends SyncPayload<PlayerHeadSittingAllowPayload> implements ServerboundPayload {

    public static final PayloadId<PlayerHeadSittingAllowPayload> ID =
            PayloadId.of("player_head_sitting_allow");

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerHeadSittingAllowPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.BOOL, PlayerHeadSittingAllowPayload::allow,
                    PlayerHeadSittingAllowPayload::new);

    private final boolean allow;

    public PlayerHeadSittingAllowPayload(boolean allow) {
        super(ID);
        this.allow = allow;
    }

    /**
     * true = 允许别人坐到我头上；false = 不允许
     */
    public boolean allow() {
        return allow;
    }
}
