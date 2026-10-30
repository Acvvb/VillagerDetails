package com.villagerdetails.network.sync.payload.clientbound;

import com.villagerdetails.network.channel.ClientboundPayload;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import com.villagerdetails.network.codec.ModCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 服务端 → 客户端：<b>版本握手的回执</b>。告诉客户端「我是哪个版本」。
 *
 * <p>只在收到客户端的 {@code C2SHandshakePayload} 之后发一次（服务端判定通过时）。
 * 版本门槛完全在服务端一侧把关（{@code NetworkConstants}），客户端收到它只做两件事：
 * 记录对端版本、确认「对面确实是会握手的服务端」。<b>客户端不再据此断开连接</b> ——
 * 以前那份「玩家要求服务端至少 X 版本」的外部配置已废弃。
 *
 * <p>为什么是<b>新包</b>而不是复用已有通道：它是协议级握手的一部分，
 * 对应通道 {@code HandshakeSyncChannel} 实现了 {@code ProtocolChannel}，
 * 所以<b>不受客户端静音影响</b>（静音是「这个功能显示效果我不想看」，不该把版本协商一起关掉）。
 *
 * <p>包 id：{@code villagerdetails:handshake_response}
 */
public final class S2CHandshakeResponsePayload extends SyncPayload<S2CHandshakeResponsePayload>
        implements ClientboundPayload {

    public static final PayloadId<S2CHandshakeResponsePayload> ID = PayloadId.of("handshake_response");

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CHandshakeResponsePayload> CODEC =
            StreamCodec.composite(
                    ModCodecs.STRING, S2CHandshakeResponsePayload::modVersion,
                    S2CHandshakeResponsePayload::new);

    /**
     * 服务端的模组版本原文，例如 {@code 1.1.0+26.2}
     */
    private final String modVersion;

    public S2CHandshakeResponsePayload(String modVersion) {
        super(ID);
        this.modVersion = modVersion == null ? "" : modVersion;
    }

    public String modVersion() {
        return modVersion;
    }
}
