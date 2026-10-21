package com.villagerdetails.network.channel;

/**
 * 「只管注册，不管具体实现」的兜底。
 *
 * <p>用法：在 {@code com.villagerdetails.network.sync.other} 包里写一个实现本接口的类，
 * 它会被 {@code ServerChannels} 自动扫到并调用 {@link #register()}；
 * 里面注册什么、怎么注册，全由那个类自己决定。
 *
 * <p>什么时候用它：某段同步相关的注册既不是「包定义」，也不是「服务端收包」，
 * 又算不上「事件接线」，几个目录都对不上号 —— 才放这里。
 * 能对上号的别用兜底：兜底没有编译期约束，用得越多越散。
 */
public interface SyncRegistrar {

    /** 注册自己该注册的东西；模组初始化时调一次 */
    void register();
}
