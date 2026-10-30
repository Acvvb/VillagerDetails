package com.villagerdetails.network.sync.receiver;

import com.villagerdetails.cache.impl.PlayerModVersion;
import com.villagerdetails.lang.ServerTranslations;
import com.villagerdetails.network.channel.NetworkConstants;
import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.ServerReceiver;
import com.villagerdetails.network.sync.clientbound.HandshakeSyncChannel;
import com.villagerdetails.network.sync.payload.serverbound.C2SHandshakePayload;
import com.villagerdetails.util.ModVersion;
import com.villagerdetails.util.SendMessengerUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 版本握手的服务端收包端：按 {@link NetworkConstants} 判定客户端版本，通过就回发服务端版本。
 *
 * <p>放在 {@code network.sync.receiver} 包下 = 自动注册。
 *
 * <p><b>策略是编译期常量，不读任何配置文件</b>（见 {@link NetworkConstants} 的类注释：
 * 协议兼容是发布决定，不该让外部 json 改）。这个类只负责执行判定、拼提示、断开。
 *
 * <p>判定顺序：拉黑（优先级最高）→ 版本串合法性 → 下限。**没有最高版本检查**，
 * 高版本客户端连低版本服务端一律放行。
 *
 * <p><b>收到与没收到分别意味着什么</b>：
 * <ul>
 *     <li>收到了 → 对端装了本模组且会用握手包，按常量判定；</li>
 *     <li>没收到 → 要么是原版客户端（没装模组），要么是更早的老客户端（那时还没有这个包）。
 *         两者都不踢：原版客户端本来就能正常玩（所有 S→C 包在发送口都被 {@code canSendTo} 挡住了），
 *         老客户端也按「版本未知」放行。</li>
 * </ul>
 * 换句话说：<b>本协议只做下限 / 黑名单拦截，不做「必须装模组」的强制</b>。
 *
 * <p>判定通过后<b>一定要回发</b> {@code S2CHandshakeResponsePayload}：客户端那半边要靠它
 * 确认「对面确实是个会握手的服务端」。
 *
 * <p>注意这里<b>没有</b>任何 {@code EnvType.CLIENT} 之类的短路口：单机（集成服）里
 * 服务端逻辑照常执行，客户端逻辑在另一侧独立执行，两边互不干扰。
 */
public final class HandshakeReceiver implements ServerReceiver<C2SHandshakePayload> {

    private static final Logger log = LogManager.getLogger(HandshakeReceiver.class);

    public static final HandshakeReceiver INSTANCE = new HandshakeReceiver();

    private HandshakeReceiver() {
    }

    @Override
    public PayloadId<C2SHandshakePayload> id() {
        return C2SHandshakePayload.ID;
    }

    @Override
    public void handle(ServerPlayer player, C2SHandshakePayload payload) {
        if (player == null || payload == null) {
            return;
        }
        String reported = payload.modVersion();

        // 先记：不管版本合不合规都留下记录，方便其它业务查询 / 排查
        PlayerModVersion.INSTANCE.set(player, reported);

        NetworkConstants.Verdict verdict = NetworkConstants.evaluate(reported);
        if (verdict == NetworkConstants.Verdict.OK) {
            log.debug("玩家 {} 的模组版本是 {}，通过（服务端 {}）",
                    player.getName().getString(), reported, ModVersion.current());
            // 回执：客户端据此确认握手完成
            HandshakeSyncChannel.INSTANCE.respond(player);
            return;
        }

        log.warn("玩家 {} 的模组版本是 \"{}\"，判定为 {}（下限 {} / 拉黑 {}），断开连接",
                player.getName().getString(), reported, verdict,
                NetworkConstants.MIN_COMPATIBLE_VERSION,
                NetworkConstants.BLOCKED_VERSIONS);
        disconnect(player, verdict, reported);
    }

    /**
     * 按判定结果挑文案 → 提示 → 断开。
     *
     * <p>文案走 {@code Component.translatable} 并由服务端解析成<b>纯文本</b>再发：
     * 因为对端可能是老版本客户端，它<b>没有</b>对应的 lang key，
     * 直接发 translatable 只会显示成 {@code msg.villager.handshake.*} 这样的键名。
     */
    private void disconnect(ServerPlayer player, NetworkConstants.Verdict verdict, String reportedVersion) {
        String shown = reportedVersion == null || reportedVersion.isBlank() ? "?" : reportedVersion;

        Component reason = switch (verdict) {
            case BLOCKED -> Component.translatable("msg.villager.handshake.blocked", shown);
            case TOO_OLD -> Component.translatable("msg.villager.handshake.too_old",
                    shown, NetworkConstants.MIN_COMPATIBLE_VERSION);
            case UNPARSEABLE -> Component.translatable("msg.villager.handshake.unparseable", shown);
            case OK -> null; // 走不到这里
        };
        if (reason == null) {
            return;
        }

        // 先发一条聊天：留在客户端的聊天记录里，方便玩家截图反馈
        SendMessengerUtils.sendOrBroadcast(player, reason);

        // 再断开：断开界面显示的也是这段文字（已解析成纯文本）
        player.connection.disconnect(Component.literal(ServerTranslations.resolve(reason)));
    }

    // ==============================================================
    // 如果想强制「必须装模组」再往下加
    // ==============================================================
    // 目前没有超时踢人：原版客户端不发这个包是<b>正常</b>的，加了超时等于把原版玩家全踢掉。
    // 要做的话，在 network.sync.event 包里加一个 SyncEvents 实现，注册
    // ServerPlayConnectionEvents.JOIN 记下进服 tick，再用 ServerTickEvents.END_SERVER_TICK 检查
    // 「进服超过 N 秒 且 !PlayerModVersion.INSTANCE.known(player)」→ 同样走 disconnect。
    // 那会改变「支持原版客户端连入」这个既有前提，需要同步更新文档。
}
