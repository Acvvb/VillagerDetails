package com.villagerdetails.client.network.sync.event;

import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.sync.payload.clientbound.S2CHandshakeResponsePayload;
import com.villagerdetails.network.sync.payload.serverbound.C2SHandshakePayload;
import com.villagerdetails.util.ModVersion;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端侧的版本握手：进服时报上自己的版本，收到服务端回执后记录「对面确实是会握手的服务端」。
 *
 * <p>放在 {@code client.network.sync.event} 包下 = 被 {@code ClientChannels} 自动扫到、自动注册。
 *
 * <p>两个方向各一半：
 * <ul>
 *     <li><b>发</b>（JOIN → {@link C2SHandshakePayload}）：把本机版本报给服务端，
 *         由服务端的 {@code NetworkConstants} 决定要不要踢我；</li>
 *     <li><b>收</b>（{@link S2CHandshakeResponsePayload}）：只做记录，<b>不再做本地比对</b>。</li>
 * </ul>
 *
 * <p><b>为什么客户端不再主动断开</b>：以前有一份本机的
 * {@code minAcceptedServerVersion} 配置，可以让玩家要求「服务端必须够新」。
 * 那份外部配置已经废弃（协议兼容改成编译期常量、由服务端单方面把关），
 * 客户端再留一票否决权只会制造「同一对客户端+服务端，别人能进我进不去」的困惑。
 * 现在客户端只如实上报 + 确认对端握手完成，版本门槛全部由服务端决定。
 *
 * <p>泛型参数是<b>收</b>的那个包，所以 {@code ClientReceiver} 的默认
 * {@code registerClientReceiver()} 正好给 {@code handshake_response} 注册收包端，
 * 收包回调也已经切回客户端主线程。
 */
public final class HandshakeClientChannel implements ClientReceiver<S2CHandshakeResponsePayload> {

    private static final Logger log = LogManager.getLogger(HandshakeClientChannel.class);

    public static final HandshakeClientChannel INSTANCE = new HandshakeClientChannel();

    /**
     * 本次会话里服务端回执过来的版本；没收到过（原版服 / 老服务端 / 还没握手完）是空串。
     *
     * <p>这就是「服务端已完成握手」的标记，供后续按对端版本做降级的地方查询。
     * 断线时不需要专门清：下次进服会重新握手，没回执就一直是空串。
     */
    private static volatile String serverVersion = "";

    private HandshakeClientChannel() {
    }

    @Override
    public PayloadId<S2CHandshakeResponsePayload> id() {
        return S2CHandshakeResponsePayload.ID;
    }

    /**
     * 进服即握手：把本机的模组版本原文报给服务端。
     *
     * <p>服务端没装模组时 {@link ClientReceiver#send} 里的 {@code canSend} 直接返回 false，
     * 这里静默跳过 —— 单人 / 原版服上不会因为这条包报任何错。
     */
    @Override
    public void registerClientEvents() {
        ClientPlayConnectionEvents.JOIN.register((_, _, _) -> announce());
    }

    private void announce() {
        // 进新服先把上一次的回执忘掉，避免拿着旧服务端的版本骗后来者
        serverVersion = "";
        ClientReceiver.send(new C2SHandshakePayload(ModVersion.current()));
    }

    /**
     * 收到服务端版本回执：记录并确认握手完成。
     *
     * <p>已经在客户端主线程（{@code ClientReceiver.registerClientReceiver} 的默认实现切过线程）。
     * 这里<b>不断开连接</b> —— 兼容性判定完全由服务端负责。
     */
    @Override
    public void handleClient(Minecraft client, S2CHandshakeResponsePayload payload) {
        if (payload == null) {
            return;
        }
        serverVersion = payload.modVersion();
        log.debug("握手完成：服务端版本 {}", serverVersion);
    }

    /**
     * 服务端回执过来的版本；空串表示对端不是「会握手的服务端」（原版 / 老版本），
     * 或者握手还没完成。
     */
    public static String serverVersion() {
        return serverVersion;
    }

    /**
     * 服务端有没有正常回过包（= 对端装了带握手的本模组）
     */
    public static boolean handshakeCompleted() {
        return !serverVersion.isEmpty();
    }
}
