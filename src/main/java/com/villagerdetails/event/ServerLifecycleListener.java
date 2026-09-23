package com.villagerdetails.event;

import com.villagerdetails.config.WorldBindingConfig;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerLifecycleListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("VillagerDetails/ServerLifecycle");

    public static void onServerStarted(MinecraftServer server) {
        // 服务器启动后，从全局数据存储读取（或创建）配置，并覆盖内存默认开关
        WorldBindingConfig config = WorldBindingConfig.getOrCreate(server);
        config.syncToSwitch();
        LOGGER.info("世界已加载，配置已同步");
    }
}
