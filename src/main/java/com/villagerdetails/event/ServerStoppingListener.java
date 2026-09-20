package com.villagerdetails.event;

import com.villagerdetails.config.WorldBindingConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerStoppingListener implements ServerLifecycleEvents.ServerStopping {

    private static final Logger LOGGER = LoggerFactory.getLogger("VillagerDetails/ServerStopping");

    @Override
    public void onServerStopping(net.minecraft.server.MinecraftServer server) {
        // 服务器关闭前，确保所有世界的配置都已标记为 dirty 以触发保存
        for (var level : server.getAllLevels()) {
            if (level.isClientSide()) continue;

            WorldBindingConfig config = WorldBindingConfig.getOrCreate(level);
            config.setDirty();

            LOGGER.info("世界已卸载，配置已保存");
        }
    }
}