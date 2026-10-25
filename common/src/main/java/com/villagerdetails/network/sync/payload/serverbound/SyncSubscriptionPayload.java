package com.villagerdetails.network.sync.payload.serverbound;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerboundPayload;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * 客户端 → 服务端：<b>通用</b>的「某条 S→C 通道还要不要给我发」订阅开关。
 *
 * <p>不是给某一条业务写的：{@code channel} 就是目标包自己的 id
 * （例如 {@code villagerdetails:furnace_dirty_sync}），服务端按 id 记下这个玩家的静音集合，
 * 以后任何「客户端能单方面关掉本地显示」的功能都可以直接复用这一个包，不用再各写一个。
 *
 * <p>语义：
 * <ul>
 *     <li>{@code enabled = false}：别再给我发这条通道的包（服务端可以顺带省掉扫描/组包）</li>
 *     <li>{@code enabled = true}：恢复发送（客户端重新打开显示时发一次）</li>
 * </ul>
 *
 * <p>服务端只认 {@link Identifier#tryParse} 能解析的 id；解析失败的包直接忽略。
 * 玩家退出服务器时服务端会丢掉整条记录，下次进服默认「要发」——客户端如果仍然关着显示，
 * 收到第一包后会自己再发一次 off。
 *
 * <p>包 id：{@code villagerdetails:sync_subscription}
 */
public final class SyncSubscriptionPayload extends SyncPayload<SyncSubscriptionPayload> implements ServerboundPayload {

    public static final PayloadId<SyncSubscriptionPayload> ID = PayloadId.of("sync_subscription");

    /**
     * 用字符串承载通道 id：非法文本在服务端 {@code tryParse} 时被丢弃，
     * 而不是在解码阶段直接抛异常把整包废掉。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncSubscriptionPayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.STRING, SyncSubscriptionPayload::channel,
                    ModCodecs.BOOL, SyncSubscriptionPayload::enabled,
                    SyncSubscriptionPayload::new);

    private final String channel;
    private final boolean enabled;

    public SyncSubscriptionPayload(Identifier channel, boolean enabled) {
        this(channel == null ? "" : channel.toString(), enabled);
    }

    public SyncSubscriptionPayload(String channel, boolean enabled) {
        super(ID);
        this.channel = channel == null ? "" : channel;
        this.enabled = enabled;
    }

    /**
     * 原始字符串形式的通道 id（编解码用）
     */
    public String channel() {
        return channel;
    }

    /**
     * 解析后的通道 id；文本非法时返回 {@code null}
     */
    public Identifier channelId() {
        return Identifier.tryParse(channel);
    }

    /**
     * true = 恢复发送，false = 静音
     */
    public boolean enabled() {
        return enabled;
    }
}
