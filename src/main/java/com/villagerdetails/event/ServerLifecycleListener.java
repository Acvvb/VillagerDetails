package com.villagerdetails.event;

import com.villagerdetails.config.WorldBindingConfig;
import com.villagerdetails.permission.BindingTypeSwitch;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerLifecycleListener implements ServerLifecycleEvents.ServerStarted {

    private static final Logger LOGGER = LoggerFactory.getLogger("VillagerDetails/ServerLifecycle");

    @Override
    public void onServerStarted(net.minecraft.server.MinecraftServer server) {
        // 服务器启动后，遍历所有已加载的世界并同步配置
        for (var level : server.getAllLevels()) {
            if (level.isClientSide()) continue;

            WorldBindingConfig config = WorldBindingConfig.getOrCreate(level);
            BindingTypeSwitch.setWorldConfig(config);
            config.syncToSwitch();
            LOGGER.info("世界已加载，配置已同步");
        }
    }
}