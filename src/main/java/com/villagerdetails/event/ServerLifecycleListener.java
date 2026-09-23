package com.villagerdetails.event;

import com.villagerdetails.config.WorldBindingConfig;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerLifecycleListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("VillagerDetails/ServerLifecycle");

    public static void onServerStarted(MinecraftServer server) {
        // 服务器启动后，遍历所有已加载的世界并同步配置
        for (var level : server.getAllLevels()) {
            if (level.isClientSide()) continue;

            // 从存档中获取（或创建）该世界的配置，而不是 new 一个空对象
            WorldBindingConfig config = WorldBindingConfig.getOrCreate(level);
            // 将存档中的配置同步到 RuleCache（内存开关）
            config.syncToSwitch();
            LOGGER.info("世界已加载，配置已同步");
        }
    }
}