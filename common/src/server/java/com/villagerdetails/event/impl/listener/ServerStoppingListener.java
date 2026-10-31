package com.villagerdetails.event.impl.listener;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerStoppingListener implements ServerLifecycleEvents.ServerStopping, ServerEventRegisterServer {

    private static final Logger log = LoggerFactory.getLogger("VillagerDetails/ServerStopping");

    /** 由统一注册类调用，外部不直接碰 SERVER_STOPPING.register */
    @Override
    public void register() {
        ServerLifecycleEvents.SERVER_STOPPING.register(new ServerStoppingListener());
    }

    @Override
    public void onServerStopping(net.minecraft.server.@NonNull MinecraftServer server) {
        // 先把还没落盘的规则状态写回（RuleConfig 是「停机时才刷盘」的）
        RuleConfig.onServerStopping(server);
        RuleCache.clearServer();
        // 停服后静态 root / server 没有任何意义：留着会一直攥着已卸载的 server（单人换存档即泄漏），
        // 而且 server 字段非空时后续任何 save() 还会往已停的存档写文件
        UnifiedConfigFile.reset();
        log.info("世界已卸载，配置已保存");
    }
}