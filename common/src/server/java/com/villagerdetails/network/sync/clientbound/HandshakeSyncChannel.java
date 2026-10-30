package com.villagerdetails.network.sync.clientbound;

import com.villagerdetails.network.channel.ProtocolChannel;
import com.villagerdetails.network.channel.SyncChannel;
import com.villagerdetails.network.sync.payload.clientbound.S2CHandshakeResponsePayload;
import com.villagerdetails.util.ModVersion;
import net.minecraft.server.level.ServerPlayer;

/**
 * 版本握手的服务端回执通道：把自己的模组版本回发给客户端，供客户端做「对端够不够新」的判断。
 *
 * <p>放在 {@code network.sync.clientbound} 包下 = 被 {@code ServerChannels} 自动扫到、
 * 自动注册、自动做包定义自检。
 *
 * <p><b>这是全项目第一条协议级通道</b>：实现了 {@link ProtocolChannel} 并且
 * {@link #isProtocolChannel()} 返回 {@code true}，所以
 * {@code SyncChannel.canSendTo} 里那道「客户端把通道静音了就别发」的判断对它<b>不生效</b> ——
 * 玩家可以在界面上关掉各种高亮，但版本协商不归他关。
 * （两者必须一致，否则 {@code SyncChannel.register()} 的启动自检会直接报错。）
 *
 * <p>包 id：{@code villagerdetails:handshake_response}
 */
public final class HandshakeSyncChannel extends SyncChannel<S2CHandshakeResponsePayload> implements ProtocolChannel {

    public static final HandshakeSyncChannel INSTANCE = new HandshakeSyncChannel();

    private HandshakeSyncChannel() {
        super(S2CHandshakeResponsePayload.ID);
    }

    /**
     * 协议通道：免静音（见类注释）
     */
    @Override
    protected boolean isProtocolChannel() {
        return true;
    }

    /**
     * 把服务端版本回发给这个玩家。
     *
     * <p>走 {@code sendTo} 而不是直接发：那里统一做了 {@code canSend} 能力探测 ——
     * 老客户端（不认识这个包）根本收不到，也不会因为「未知 Payload」崩掉。
     */
    public void respond(ServerPlayer player) {
        sendTo(player, new S2CHandshakeResponsePayload(ModVersion.current()));
    }
}
