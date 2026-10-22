package com.villagerdetails.client.network;

import com.villagerdetails.client.network.channel.ClientReceiver;
import com.villagerdetails.network.channel.ChannelScanner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 所有同步通道的<b>唯一注册入口</b>（客户端侧）。
 *
 * <p>不手写通道清单，而是扫 {@link #SYNC_PACKAGE} 下的 {@link ClientReceiver} 实现，
 * 逐个调 {@link ClientReceiver#registerClientReceiver()} 与 {@link ClientReceiver#registerClientEvents()}。
 * 加新通道<b>不用改这个文件</b>。
 *
 * <p>扫包 / 实例化 / 失败汇总复用 main 的 {@link ChannelScanner}，和 {@code ServerChannels} 同一套底座。
 *
 * <p>包<b>类型</b>注册在 main 的 {@code PayloadTypes}（两端共用），这里不重复做。
 *
 * <p>被扫到的类要能被实例化：给一个 {@code public static final Xxx INSTANCE}（本项目统一写法），
 * 或者一个无参构造（私有也行，底座会 setAccessible）。
 */
public final class ClientChannels {

    private static final Logger LOGGER = LogManager.getLogger(ClientChannels.class);

    /** 客户端通道所在的包（含子包，所以 .event 下的通道也会被扫到） */
    private static final String SYNC_PACKAGE = "com.villagerdetails.client.network.sync";

    private ClientChannels() {
    }

    /**
     * 扫包注册全部客户端通道；客户端初始化时调一次。
     */
    public static void registerAll() {
        List<ChannelScanner.Failure> failures = new ArrayList<>();

        int channels = ChannelScanner.forEach(SYNC_PACKAGE, ClientChannels.class, ClientReceiver.class,
                channel -> registerChannel(channel, failures), failures);

        ChannelScanner.checkNotEmpty(channels, SYNC_PACKAGE, "通道发现", failures);

        LOGGER.info("客户端同步扫包完成：注册 {} 个通道", channels);

        ChannelScanner.reportFailures(LOGGER, "客户端同步注册", failures);
    }

    /**
     * 一个通道的注册：收包和客户端事件<b>分别</b> try-catch，
     * 收包挂了不影响事件，反之亦然（和服务端分阶段注册的语义一致）。
     */
    @SuppressWarnings("rawtypes")
    private static void registerChannel(ClientReceiver channel, List<ChannelScanner.Failure> failures) {
        String name = channel.getClass().getName();
        try {
            channel.registerClientReceiver();
        } catch (Throwable t) {
            failures.add(new ChannelScanner.Failure("客户端收包", name, "注册失败，这个包会收不到", t));
        }
        try {
            channel.registerClientEvents();
        } catch (Throwable t) {
            failures.add(new ChannelScanner.Failure("客户端事件", name, "注册失败，相关接线不会生效", t));
        }
    }
}
