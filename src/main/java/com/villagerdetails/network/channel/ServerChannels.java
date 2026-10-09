package com.villagerdetails.network.channel;

import com.villagerdetails.network.sync.*;

/**
 * 所有同步通道的<b>唯一注册入口</b>（服务端侧）。
 *
 * <p>「这个模组一共开了哪些包、往哪边发」在这一个文件里看得全。
 * 每个包的类型注册只允许一次，所以别再在别处调 {@code PayloadTypeRegistry}。
 *
 * <table>
 *     <tr><th>通道</th><th>方向</th><th>用途</th></tr>
 *     <tr><td>{@link VillagerBedSyncChannel}</td><td>S→C</td><td>村民绑定的床</td></tr>
 *     <tr><td>{@link BlockRuleSyncChannel}</td><td>S→C</td><td>逐条配置规则的数据（带列表形态标识）</td></tr>
 *     <tr><td>{@link ServerRuleSyncChannel}</td><td>S→C</td><td>规则状态（按权限过滤）</td></tr>
 *     <tr><td>{@link ServerRuleRequestChannel}</td><td>C→S</td><td>请求同步规则状态</td></tr>
 *     <tr><td>{@link ServerRuleSetChannel}</td><td>C→S</td><td>改规则状态</td></tr>
 *     <tr><td>{@link RuleDataChannel}</td><td>C→S</td><td>改逐条配置数据</td></tr>
 * </table>
 */
public final class ServerChannels {

    private ServerChannels() {
    }

    /**
     * 注册全部包类型、服务端接收器和事件；模组初始化时调一次
     */
    public static void registerAll() {
        // 服务端 → 客户端
        VillagerBedSyncChannel.INSTANCE.registerClientbound();
        BlockRuleSyncChannel.INSTANCE.registerClientbound();
        ServerRuleSyncChannel.INSTANCE.registerClientbound();
        EBSyncChannel.INSTANCE.registerClientbound();
        BBSyncChannel.INSTANCE.registerClientbound();

        // 客户端 → 服务端
        ServerRuleRequestChannel.INSTANCE.registerServerbound();
        ServerRuleSetChannel.INSTANCE.registerServerbound();
        RuleDataChannel.INSTANCE.registerServerbound();

        // 服务端收包
        ServerRuleRequestChannel.INSTANCE.registerServerReceiver();
        ServerRuleSetChannel.INSTANCE.registerServerReceiver();
        RuleDataChannel.INSTANCE.registerServerReceiver();

        // 事件（玩家开始追踪实体、玩家加入同步、规则监听、周期复查）
        VillagerBedSyncChannel.INSTANCE.registerEvents();
        BlockRuleSyncChannel.INSTANCE.registerEvents();
        ServerRuleSyncChannel.INSTANCE.registerEvents();
    }
}
