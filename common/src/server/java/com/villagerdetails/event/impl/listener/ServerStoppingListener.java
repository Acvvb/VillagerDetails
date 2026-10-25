package com.villagerdetails.event.impl.listener;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerStoppingListener implements ServerLifecycleEvents.ServerStopping, ServerEventRegisterServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("VillagerDetails/ServerStopping");

    /** 由统一注册类调用，外部不直接碰 SERVER_STOPPING.register */
    @Override
    public void register() {
        ServerLifecycleEvents.SERVER_STOPPING.register(new ServerStoppingListener());
    }

    @Override
    public void onServerStopping(net.minecraft.server.@NonNull MinecraftServer server) {
        RuleConfig.onServerStopping(server);
        RuleCache.clearServer();
        LOGGER.info("世界已卸载，配置已保存");
    }
}