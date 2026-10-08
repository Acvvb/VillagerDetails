package com.villagerdetails.network.channel;

import com.villagerdetails.cache.RuleCache;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * 一条同步通道的<b>服务端侧</b>骨架：包类型注册、收包（自动切主线程）、发包 / 广播。
 *
 * <p>子类（每条同步一个，放 {@code network.sync}）只要：
 * <ol>
 *     <li>构造里 {@code super(XxxPayload.ID, XxxPayload.CODEC)}</li>
 *     <li>需要的话重写 {@link #handleServer}（服务端收到这个包干什么）</li>
 *     <li>需要的话重写 {@link #registerEvents}（JOIN 同步、规则监听、周期任务这类事件注册）</li>
 * </ol>
 * 其余「切线程、判 canSend、遍历在线玩家」这些重复动作都在基类里。
 *
 * <p><b>客户端侧</b>的收包 / 发包在 {@code client.network.channel.ClientReceiver}（默认方法），
 * 由客户端子类
 * {@code XxxClientChannel extends XxxChannel implements ClientReceiver<XxxPayload>} 混进来。
 */
public abstract class SyncChannel<P extends SyncPayload<P>> {

    private final PayloadId<P> id;
    private final StreamCodec<? super RegistryFriendlyByteBuf, P> codec;

    protected SyncChannel(PayloadId<P> id, StreamCodec<? super RegistryFriendlyByteBuf, P> codec) {
        this.id = id;
        this.codec = codec;
    }

    public final PayloadId<P> id() {
        return id;
    }

    /** 完整包 id，例如 {@code villagerdetails:rule_data} */
    public final Identifier packetId() {
        return id.id();
    }

    public final StreamCodec<? super RegistryFriendlyByteBuf, P> codec() {
        return codec;
    }

    // ==============================================================
    // 注册（由 ServerChannels / ClientChannels 统一调用，保证只注册一次）
    // ==============================================================

    /** 服务端 → 客户端方向 */
    public final void registerClientbound() {
        PayloadTypeRegistry.clientboundPlay().register(id.type(), codec);
    }

    /** 客户端 → 服务端方向 */
    public final void registerServerbound() {
        PayloadTypeRegistry.serverboundPlay().register(id.type(), codec);
    }

    /** 服务端收包（自动切回服务端主线程再交给 {@link #handleServer}） */
    public final void registerServerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(id.type(),
                (payload, context) -> context.server().execute(() -> handleServer(context.player(), payload)));
    }

    /** 事件类注册（JOIN 同步、规则监听、周期任务）；子类按需重写 */
    public void registerEvents() {
    }

    // ==============================================================
    // 钩子
    // ==============================================================

    /** 服务端收到这个包时干什么；不需要收的通道不用管 */
    protected void handleServer(ServerPlayer player, P payload) {
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
