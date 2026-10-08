package com.villagerdetails.client.network.channel;

import com.villagerdetails.network.channel.PayloadId;
import com.villagerdetails.network.channel.SyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 同步通道的<b>客户端侧</b>能力（默认方法）。
 *
 * <p>用法：客户端的通道类
 * {@code XxxClientChannel extends XxxChannel implements ClientReceiver<XxxPayload>}，
 * 于是既继承服务端那套（id / codec / 广播），又白拿这里的「收包 + 发包到服务端」。
 * 由 {@code ClientChannels} 统一注册一次。
 *
 * <p>{@link #id()} 由父类 {@code SyncChannel} 提供。
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
     * 客户端自己的事件注册（实体卸载清理、断线清状态）。
     *
     * <p>刻意和父类的 {@code SyncChannel.registerEvents()}（JOIN 同步、规则监听、周期任务，
     * 都是<b>服务端</b>的事）分开：客户端子类继承的是同一个通道类，混在一起会在集成服里被注册两遍。
     */
    default void registerClientEvents() {
    }

    /** 客户端发给服务端（自带「服务器装没装本 mod」判断） */
    default boolean sendToServer(P payload) {
        if (!canSendToServer()) {
            return false;
        }
        ClientPlayNetworking.send(payload);
        return true;
    }

    /** 服务器装没装本 mod（能收我们的包） */
    default boolean canSendToServer() {
        return ClientPlayNetworking.canSend(id().type());
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
