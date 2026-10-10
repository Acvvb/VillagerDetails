package com.villagerdetails.client.network;

import com.villagerdetails.client.network.sync.*;

/**
 * 所有同步通道的<b>唯一注册入口</b>（客户端侧）。
 *
 * <p>和 {@code ServerChannels} 一一对应：那边扫包注册「包类型 + 服务端收包 + 服务端事件」，
 * 这边只补客户端的收包（客户端通道不在服务端的扫包目录里，所以还是在这里手动登记）。
 * 客户端类都是 {@code XxxClientChannel extends XxxChannel implements ClientReceiver<…>}，
 * 既继承服务端的定义，又白拿客户端的收包 / 发包。
 */
public final class ClientChannels {

    private ClientChannels() {
    }

    /**
     * 注册客户端接收器；客户端初始化时调一次
     */
    public static void registerAll() {
        VillagerBedClientChannel.INSTANCE.registerClientReceiver();
        BlockRuleClientChannel.INSTANCE.registerClientReceiver();
        ServerRuleClientChannel.INSTANCE.registerClientReceiver();
        EBSyncClientChannel.INSTANCE.registerClientReceiver();
        BBSyncClientChannel.INSTANCE.registerClientReceiver();
        PortalViewSyncClientChannel.INSTANCE.registerClientReceiver();

        // 各通道自己的客户端事件（实体卸载清理、断线清空状态）
        VillagerBedClientChannel.INSTANCE.registerClientEvents();
        BlockRuleClientChannel.INSTANCE.registerClientEvents();
        ServerRuleClientChannel.INSTANCE.registerClientEvents();
        EBSyncClientChannel.INSTANCE.registerClientEvents();
        BBSyncClientChannel.INSTANCE.registerClientEvents();
        PortalViewSyncClientChannel.INSTANCE.registerClientEvents();

    }
}
