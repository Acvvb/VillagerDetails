package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerboundPayload;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 客户端 → 服务端：<b>「我还要不要能用『坐在楼梯 / 半砖上』这个功能」</b>。
 *
 * <p>和「玩家头顶乘坐」的同意包正好反过来：这里<b>默认是开</b>的。
 * <ul>
 *     <li>装了本 mod 的玩家：客户端配置里有开关（{@code PlayerBlockSitting}，默认开），
 *         关掉就发 {@code enabled = false}；</li>
 *     <li><b>没装本 mod 的玩家永远不发这个包，服务端一律当「开」</b> —— 也就是说他们照样能坐，
 *         这正是我们想要的：坐个楼梯不会影响别人，没必要默认禁掉。</li>
 * </ul>
 *
 * <p>服务端只在收到 {@code false} 时记住「这个人不想坐」，玩家退出时丢掉记录，
 * 下次进服由客户端重新上报。包 id：{@code villagerdetails:player_block_sitting_enabled}
 */
public final class PlayerBlockSittingEnabledPayload extends SyncPayload<PlayerBlockSittingEnabledPayload> implements ServerboundPayload {

    public static final PayloadId<PlayerBlockSittingEnabledPayload> ID =
            PayloadId.of("player_block_sitting_enabled");

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerBlockSittingEnabledPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.BOOL, PlayerBlockSittingEnabledPayload::enabled,
                    PlayerBlockSittingEnabledPayload::new);

    private final boolean enabled;

    public PlayerBlockSittingEnabledPayload(boolean enabled) {
        super(ID);
        this.enabled = enabled;
    }

    /**
     * true = 我还能坐；false = 别让我坐
     */
    public boolean enabled() {
        return enabled;
    }
}
