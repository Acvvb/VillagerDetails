package com.villagerdetails.event;

import com.villagerdetails.config.WorldBindingConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerStoppingListener implements ServerLifecycleEvents.ServerStopping {

    private static final Logger LOGGER = LoggerFactory.getLogger("VillagerDetails/ServerStopping");

    @Override
    public void onServerStopping(net.minecraft.server.@NonNull MinecraftServer server) {
        // 确保全局配置被标记为 dirty，触发一次保存
        WorldBindingConfig config = WorldBindingConfig.getOrCreate(server);
        config.setDirty();
        LOGGER.info("世界已卸载，配置已保存");
    }
}
