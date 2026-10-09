package com.villagerdetails.network.channel;

import com.villagerdetails.cache.RuleCache;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * 一条同步通道的<b>服务端侧发送 API</b>：发包 / 广播 / 判对方装没装。
 *
 * <p>包类型注册<b>不在这里</b> —— 那是包自己的事，见 {@link ServerChannels}：
 * 包定义（{@code XxxPayload}）放进 {@code network.sync.payload.clientbound} /
 * {@code network.sync.payload.serverbound}，它自己声明的 {@code ID} + {@code CODEC}
 * 就是注册要的全部东西，所以这里不用再抄一遍编解码器。
 *
 * <p>子类（放 {@code network.sync.clientbound}，只负责「这个包怎么发」）只要：
 * 构造里 {@code super(XxxPayload.ID)}，需要的话再写自己的发包方法（例如 {@code sendBed}）。
 *
 * <p>另外两件事各有各的目录，一个类只管一件事：
 * <ul>
 *     <li>「服务端收到这个包干什么」→ {@code network.sync.receiver}（{@link ServerReceiver}）</li>
 *     <li>「事件接线（JOIN 同步、规则监听、周期任务）」→ {@code network.sync.event}（{@link SyncEvents}）</li>
 * </ul>
 *
 * <p><b>客户端侧</b>的收包 / 发包在 {@code client.network.channel.ClientReceiver}（默认方法），
 * 由客户端子类
 * {@code XxxClientChannel extends XxxChannel implements ClientReceiver<XxxPayload>} 混进来。
 */
public abstract class SyncChannel<P extends SyncPayload<P>> {

    private final PayloadId<P> id;

    protected SyncChannel(PayloadId<P> id) {
        this.id = id;
    }

    public final PayloadId<P> id() {
        return id;
    }

    /** 完整包 id，例如 {@code villagerdetails:rule_data} */
    public final Identifier packetId() {
        return id.id();
    }

    // ==============================================================
    // 服务端发包
    // ==============================================================

    /** 发给某个玩家（对方没装本 mod 就跳过） */
    public final void sendTo(ServerPlayer player, P payload) {
        if (canSendTo(player)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /** 广播给所有在线玩家 */
    public final void broadcast(P payload) {
        forEachPlayer(player -> sendTo(player, payload));
    }

    /** 遍历在线玩家（刷新命令树之类的操作用） */
    public final void forEachPlayer(Consumer<ServerPlayer> action) {
        MinecraftServer server = RuleCache.getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            action.accept(player);
        }
    }

    /** 对方客户端装没装本 mod */
    public final boolean canSendTo(ServerPlayer player) {
        return player != null && ServerPlayNetworking.canSend(player, id.type());
    }
}
