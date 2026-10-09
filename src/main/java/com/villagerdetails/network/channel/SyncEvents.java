package com.villagerdetails.network.channel;

/**
 * 同步相关的事件注册（玩家开始追踪实体、玩家加入同步、规则监听、周期复查）。
 *
 * <p>用法：在 {@code com.villagerdetails.network.sync.event} 包里写一个实现本接口的类，
 * 它会被 {@code ServerChannels} 自动扫到并注册，不用再到别处登记一行。
 *
 * <p>刻意和 {@code SyncChannel} 分开：通道只管「包类型 + 编解码 + 收发」，
 * 事件这种「服务器生命周期 / 玩家进出 / tick」的接线单独一个类，
 * 找起来直观，也不会在集成服里被注册两遍。
 */
public interface SyncEvents {

    /** 注册自己关心的事件；模组初始化时调一次 */
    void registerEvents();
}
