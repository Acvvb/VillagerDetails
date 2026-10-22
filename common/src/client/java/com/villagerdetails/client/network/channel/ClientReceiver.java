package com.villagerdetails.client.network.channel;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 同步通道的<b>客户端侧</b>能力（默认方法）。
 *
 * <p>用法：客户端的通道类直接 {@code implements ClientReceiver<XxxPayload>}，
 * 并实现 {@link #id()} 返回包定义里的 {@code XxxPayload.ID}。
 *
 * <p>三端分离之后客户端<b>不再继承</b>服务端的发包通道（{@code network.sync.clientbound} 在
 * server 源集），客户端只依赖「包定义 + 收包接口 + 包类型注册」这些 main 里的契约。
 * 由 {@code ClientChannels} 统一注册一次。
 */
public interface ClientReceiver<P extends SyncPayload<P>> {

    /** 这条通道的身份证（父类 SyncChannel 已经实现） */
    PayloadId<P> id();

    /** 客户端收包（自动切回客户端主线程再交给 {@link #handleClient}） */
    default void registerClientReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(id().type(),
                (payload, context) -> context.client().execute(() -> handleClient(context.client(), payload)));
    }

    /** 客户端收到这个包时干什么；只发不收的通道不用管 */
    default void handleClient(Minecraft client, P payload) {
    }

    /**
     * 客户端自己的事件注册（实体卸载清理、tick 检查、JOIN 打招呼……）。
     *
     * <p><b>断线清理不在这里</b>：那是 {@code ClientSessionStates} 统一接管的
     * （{@code VillagerDetailsClient} 里唯一的一处 DISCONNECT 回调），通道自己别各注册一份。
     *
     * <p>刻意和「服务端事件」分开：服务端那边的事件接线在
     * {@code network.sync.event}（{@code SyncEvents}），收包在 {@code network.sync.receiver}
     * （{@code ServerReceiver}）；客户端子类继承的是同一个通道类，混在一起会在集成服里被注册两遍。
     */
    default void registerClientEvents() {
    }

    /**
     * 给「只有静态发送方法」的场景用：不持有通道实例也能发（拿包的 type 直接判 + 发）。
     */
    static boolean send(CustomPacketPayload payload) {
        if (!ClientPlayNetworking.canSend(payload.type())) {
            return false;
        }
        ClientPlayNetworking.send(payload);
        return true;
    }

    /** 服务器装没装本 mod（按包类型判断） */
    static boolean canSend(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }
}
