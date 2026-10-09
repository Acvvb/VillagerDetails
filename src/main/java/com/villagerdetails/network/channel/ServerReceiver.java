package com.villagerdetails.network.channel;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 同步通道的<b>服务端收包</b>能力。
 *
 * <p>用法：在 {@code com.villagerdetails.network.sync.receiver} 包里写一个实现本接口的类，
 * 它会被 {@code ServerChannels} 自动扫到并注册，不用再到别处登记一行。
 *
 * <p>「切回服务端主线程」这件事在 {@link #registerServerReceiver()} 里做一次，
 * 实现类只写「收到包之后干什么」。
 *
 * <p>和 {@code SyncChannel} 分开放的原因：一个功能如果要同时收发两种方向的包，
 * 就分别在 {@code clientbound} / {@code serverbound} 目录下写通道、
 * 在这里写收包处理，三块各管一件事，不会挤在一个类里。
 *
 * @param <P> 收的包类型
 */
public interface ServerReceiver<P extends SyncPayload<P>> {

    /** 收的是哪个包（一般是 {@code XxxPayload.ID}） */
    PayloadId<P> id();

    /** 注册收包（自动切回服务端主线程再交给 {@link #handle}） */
    default void registerServerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(id().type(),
                (payload, context) -> context.server().execute(() -> handle(context.player(), payload)));
    }

    /** 服务端收到这个包时干什么 */
    void handle(ServerPlayer player, P payload);
}
