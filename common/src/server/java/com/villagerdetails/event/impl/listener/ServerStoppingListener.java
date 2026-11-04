package com.villagerdetails.event.impl.listener;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.RuleConfig;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.util.io.AsyncConfigManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停服收尾（mem 状态清理）。
 *
 * <p><b>真正的配置落盘不在这里</b>：它由 {@code VillagerDetailsServer} 登记的
 * pre-shutdown 钩子完成（序列化入队 → 排干 → 才关池），本类跑在那之后。
 *
 * <p>这里保留两道<b>兜底</b>：
 * <ol>
 *     <li>{@link UnifiedConfigFile#flush()} / {@link RuleConfig#onServerStopping}：万一哪天
 *         pre-shutdown 钩子没登记上，配置也不至于烂在内存里；</li>
 *     <li>{@link AsyncConfigManager#flushAndShutdown}：兜底入队的内容也要真正写到磁盘上 ——
 *         正常路径上排干已经发生过，这里是空操作（没有待写内容时立即返回）。</li>
 * </ol>
 */
public class ServerStoppingListener implements ServerLifecycleEvents.ServerStopping, ServerEventRegisterServer {

    private static final Logger log = LoggerFactory.getLogger("VillagerDetails/ServerStopping");

    /**
     * 兜底排干的等待上限（毫秒）。正常路径上没有待写内容，立即返回。
     */
    private static final long FALLBACK_DRAIN_MS = 2000L;

    /** 由统一注册类调用，外部不直接碰 SERVER_STOPPING.register */
    @Override
    public void register() {
        ServerLifecycleEvents.SERVER_STOPPING.register(new ServerStoppingListener());
    }

    @Override
    public void onServerStopping(net.minecraft.server.@NonNull MinecraftServer server) {
        // 兜底 1：把统一配置里还没落盘的改动序列化入队（正常路径上 pre-shutdown 钩子已经做过一次）
        // 必须在 reset() 之前 —— reset() 会把静态 server 置空，之后就找不到存档路径了。
        UnifiedConfigFile.INSTANCE.flush();
        // 兜底 2：规则状态（RuleConfig 是「停机时才刷盘」的）
        RuleConfig.onServerStopping(server);
        // 兜底 3：把上面可能入队的内容真正写到磁盘。正常路径上此时队列已空，是空操作。
        AsyncConfigManager.flushAndShutdown(FALLBACK_DRAIN_MS);

        RuleCache.clearServer();
        // 停服后静态 root / server 没有任何意义：留着会一直攥着已卸载的 server（单人换存档即泄漏），
        // 而且 server 字段非空时后续任何 flush 还会往已停的存档写文件
        UnifiedConfigFile.reset();
        log.info("世界已卸载，配置已保存");
    }
}
