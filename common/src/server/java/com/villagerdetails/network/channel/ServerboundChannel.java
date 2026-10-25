package com.villagerdetails.network.channel;

import com.villagerdetails.network.ServerChannels;
import net.minecraft.resources.Identifier;

/**
 * {@code network.sync.serverbound} 目录的角色接口：服务端侧的 <b>C→S 封装</b>（目前是空目录，预留位）。
 *
 * <p>和 {@link ClientboundChannel} 完全对称：{@link ServerChannels} 扫那个目录时只认实现本接口的类，
 * 调 {@link #register()} 完成初始化（默认实现就是「确认包定义已登记」）。
 *
 * <p>C→S 包正常只要「包定义」（{@code payload.serverbound}）+「收到干什么」（{@code receiver}）
 * 两件东西，所以这个目录现在没有实现；哪天真需要在服务端侧给某个 C→S 包加一层门面，
 * 再往那儿放实现本接口的类，构造函数里把 {@code PayloadId} 传进来即可，自检不用自己写。
 */
public interface ServerboundChannel<P extends SyncPayload<P>> {

    /**
     * 这条封装对应的包定义 id
     */
    PayloadId<P> id();

    /**
     * 初始化：确认自己的包定义已经登记（由 {@link ServerChannels} 扫包时调用）。
     *
     * @throws IllegalStateException 包定义没登记
     */
    default void register() {
        Identifier channelId = id().id();
        if (!PayloadTypes.registerAll().serverbound().contains(channelId)) {
            throw new IllegalStateException(getClass().getSimpleName() + " 的包定义没登记（缺 " + channelId
                    + "）：把对应的 XxxPayload 放进 network.sync.payload.serverbound 并 implements ServerboundPayload");
        }
    }
}
