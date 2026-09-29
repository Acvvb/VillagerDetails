package com.villagerdetails.event;

import com.villagerdetails.config.impl.RuleConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerStoppingListener implements ServerLifecycleEvents.ServerStopping {

    private static final Logger LOGGER = LoggerFactory.getLogger("VillagerDetails/ServerStopping");

    @Override
    public void onServerStopping(net.minecraft.server.@NonNull MinecraftServer server) {
        RuleConfig.onServerStopping(server);
        LOGGER.info("世界已卸载，配置已保存");
    }
}