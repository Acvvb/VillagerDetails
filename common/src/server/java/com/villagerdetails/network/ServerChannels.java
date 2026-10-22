package com.villagerdetails.network;

import com.villagerdetails.network.channel.*;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ServerChannels {

    private static final Logger LOGGER = LogManager.getLogger(ServerChannels.class);
    private static final String SYNC_PACKAGE = "com.villagerdetails.network.sync";

    public static final String CLIENTBOUND_PACKAGE = SYNC_PACKAGE + ".clientbound";
    public static final String SERVERBOUND_PACKAGE = SYNC_PACKAGE + ".serverbound";
    public static final String RECEIVER_PACKAGE     = SYNC_PACKAGE + ".receiver";
    public static final String EVENT_PACKAGE        = SYNC_PACKAGE + ".event";
    public static final String OTHER_PACKAGE        = SYNC_PACKAGE + ".other";

    private ServerChannels() {
    }

    public static void registerAll() {
        List<ChannelScanner.Failure> failures = new ArrayList<>();

        com.villagerdetails.network.channel.PayloadTypes.Registered payloads =
                com.villagerdetails.network.channel.PayloadTypes.registerAll();
        Set<Identifier> clientboundIds = payloads.clientbound();
        Set<Identifier> serverboundIds = payloads.serverbound();

        int receivers = ChannelScanner.forEach(RECEIVER_PACKAGE, ServerChannels.class, ServerReceiver.class,
                ServerReceiver<?>::registerServerReceiver, failures);
        int events = ChannelScanner.forEach(EVENT_PACKAGE, ServerChannels.class, SyncEvents.class,
                SyncEvents::registerEvents, failures);
        int others = ChannelScanner.forEach(OTHER_PACKAGE, ServerChannels.class, SyncRegistrar.class,
                SyncRegistrar::register, failures);

        checkChannels(CLIENTBOUND_PACKAGE, clientboundIds, failures);
        checkChannels(SERVERBOUND_PACKAGE, serverboundIds, failures);

        if (clientboundIds.isEmpty() && serverboundIds.isEmpty()) {
            failures.add(new ChannelScanner.Failure("包类型", SYNC_PACKAGE,
                    "一个同步包都没扫到！检查 " + SYNC_PACKAGE + " 下的子包有没有被打进包里", null));
        }

        LOGGER.info("同步扫包注册完成：S→C 包 {} 个、C→S 包 {} 个、服务端收包 {} 个、事件 {} 个、兜底 {} 个",
                clientboundIds.size(), serverboundIds.size(), receivers, events, others);

        ChannelScanner.reportFailures(LOGGER, "同步注册", failures);
    }

    private static void checkChannels(String channelPackage, Set<Identifier> registeredIds,
                                      List<ChannelScanner.Failure> failures) {
        ChannelScanner.forEach(channelPackage, ServerChannels.class, SyncChannel.class, channel -> {
            Identifier id = channel.id().id();
            if (!registeredIds.contains(id)) {
                failures.add(new ChannelScanner.Failure("通道体检", channel.getClass().getName(),
                        "对应的包定义没放进 payload 目录，这个包不会生效（缺 " + id + "）", null));
            }
        }, failures);
    }
}
