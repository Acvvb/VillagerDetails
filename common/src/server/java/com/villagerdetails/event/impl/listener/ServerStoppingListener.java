package com.villagerdetails.event.impl.listener;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.base.AbstractWorldConfig;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停服收尾（mem 状态清理）。
 *
 * <p><b>真正的配置落盘不在这里</b>：它由 {@code ServerThreadShutdownTask}
 * 登记的 pre-shutdown 钩子完成（序列化入队 → 排干 → 才关池），本类跑在那之后。
 *
 * <p>本类只负责最后的“扫地出门”：
 * <ol>
 *     <li>{@link AbstractWorldConfig#resetAll()}：把四个配置的生命周期状态一起复位
 *         （摘掉 server 引用 + 清空数据 + 清脏标记）。</li>
 *     <li>{@link RuleCache#clearServer()}：清理规则缓存里的 server 引用。</li>
 * </ol>
 */
public class ServerStoppingListener implements ServerLifecycleEvents.ServerStopping, ServerEventRegisterServer {

    private static final Logger log = LoggerFactory.getLogger("VillagerDetails/ServerStopping");

    @Override
    public void register() {
        ServerLifecycleEvents.SERVER_STOPPING.register(new ServerStoppingListener());
    }

    @Override
    public void onServerStopping(net.minecraft.server.@NonNull MinecraftServer server) {
        // 停服后静态 server / 数据没有任何意义：留着会一直攥着已卸载的 server（单人换存档即泄漏），
        // 而且 server 字段非空时后续任何 flush 还会往已停的存档写文件。
        // 这里直接清空，底层的写盘早已在前置钩子里安全完成了。
        AbstractWorldConfig.resetAll();
        RuleCache.clearServer();

        log.info("世界已卸载，配置内存已清理");
    }
}