package com.villagerdetails.network.channel;

import com.villagerdetails.network.ServerChannels;
import net.minecraft.resources.Identifier;

/**
 * {@code network.sync.clientbound} 目录的角色接口：<b>一条 S→C 同步通道</b>（负责「这个包怎么发」）。
 *
 * <p>{@link ServerChannels} 扫这个目录时，只认实现本接口的类，然后调 {@link #register()} 完成初始化。
 * 通道本身的发送 API 在 {@link SyncChannel}（本接口的实现基类）。
 *
 * <p>泛型参数是「这条通道发的那个包」：{@code SyncChannel<P> implements ClientboundChannel<P>}，
 * 于是接口里的 {@code id()} 和基类里的 {@code id()} 签名<b>逐字一致</b>，不会出现
 * 「返回值类型不兼容的重复方法」。
 */
public interface ClientboundChannel<P extends SyncPayload<P>> {

    /**
     * 这条通道对应的包定义 id
     */
    PayloadId<P> id();

    /**
     * 初始化：确认自己的包定义已经登记（由 {@link ServerChannels} 扫包时调用）。
     *
     * <p>默认实现就够了，{@link SyncChannel} 的子类不用自己写：
     * 漏放 payload 类 / 忘实现 {@link ClientboundPayload} 会在这里直接报出来，
     * 而不是等运行时发了没人收。
     *
     * @throws IllegalStateException 包定义没登记
     */
    default void register() {
        Identifier channelId = id().id();
        if (!PayloadTypes.registerAll().clientbound().contains(channelId)) {
            throw new IllegalStateException(getClass().getSimpleName() + " 的包定义没登记（缺 " + channelId
                    + "）：把对应的 XxxPayload 放进 network.sync.payload.clientbound 并 implements ClientboundPayload");
        }
    }
}
