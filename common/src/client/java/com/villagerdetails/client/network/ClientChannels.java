package com.villagerdetails.client.network;

import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.client.network.sync.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * 所有同步通道的<b>唯一注册入口</b>（客户端侧）。
 *
 * <p>和 {@code ServerChannels} 一一对应：那边扫包注册「包类型 + 服务端收包 + 服务端事件」，
 * 这边只补客户端的收包（客户端通道不在服务端的扫包目录里，所以还是在这里登记）。
 * 客户端类都是 {@code XxxClientChannel extends XxxChannel implements ClientReceiver<…>}，
 * 既继承服务端的定义，又白拿客户端的收包 / 发包。
 *
 * <p>登记就是下面这一张 {@link #CHANNELS} 清单：新增通道只加一行，不用再抄两遍调用；
 * 单个通道注册失败只记 ERROR 并继续（少一个包 ≠ 客户端不该启动）。
 */
public final class ClientChannels {

    private static final Logger LOGGER = LogManager.getLogger(ClientChannels.class);

    /**
     * 客户端通道清单（唯一登记点）
     */
    private static final List<ClientReceiver<?>> CHANNELS = List.of(
            VillagerBedClientChannel.INSTANCE,
            BlockRuleClientChannel.INSTANCE,
            RuleDescriptorClientChannel.INSTANCE,
            RuleDataOptionsClientChannel.INSTANCE,
            EBSyncClientChannel.INSTANCE,
            BBSyncClientChannel.INSTANCE,
            PortalViewSyncClientChannel.INSTANCE);

    private ClientChannels() {
    }

    /**
     * 注册客户端接收器与各通道自己的客户端事件；客户端初始化时调一次
     */
    public static void registerAll() {
        for (ClientReceiver<?> channel : CHANNELS) {
            String name = channel.getClass().getName();
            try {
                channel.registerClientReceiver();
            } catch (Throwable t) {
                LOGGER.error("注册客户端收包失败，这个包会收不到：{}", name, t);
            }
            try {
                channel.registerClientEvents();
            } catch (Throwable t) {
                LOGGER.error("注册客户端事件失败，相关接线不会生效：{}", name, t);
            }
        }
    }
}
