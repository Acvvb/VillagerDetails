package com.villagerdetails.event.impl.register;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.cache.impl.TradeItemCompletionCache;
import com.villagerdetails.cache.impl.VillagerTradeCache;
import com.villagerdetails.config.impl.VillagerTradeCacheConfig;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.handler.entity.villager.trade.VillagerTradePipeline;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 「村民交易查询」的事件接线（放在 {@code event.impl.register} 包下，
 * 由 {@code ServerEventManager} 扫包自动注册，不用改任何清单）。
 *
 * <p>只做这几件事：
 * <ol>
 *     <li>{@code ENTITY_UNLOAD} → {@link VillagerTradePipeline#onEntityUnload}
 *         （<b>实际抓取入口</b>：区块卸载时实体被移出追踪的那一刻，交易表仍然可读）；</li>
 *     <li>{@code CHUNK_UNLOAD} → {@link VillagerTradePipeline#onChunkUnload}
 *         （整区块兜底扫描，见方法注释）；</li>
 *     <li>{@code AFTER_DEATH} → {@link VillagerTradePipeline#onLivingDeath}
 *         （主线程清缓存）；</li>
 *     <li>规则被关掉 → 先落盘再清内存，规则重新打开时把磁盘上的索引读回来；</li>
 *     <li>{@code END_SERVER_TICK} → 清「打开交易界面」抓取的防抖表、
 *         按 10 分钟节奏把墓碑 / 快照时刻表的大清理丢给线程池
 *         （{@link VillagerTradeCache#tickPrune(long)}）。</li>
 * </ol>
 *
 * <p><b>不在这里管落盘节奏</b>：索引的 {@code markDirty → flush} 由
 * {@code AbstractWorldConfig} 的 tick 驱动统一接线（它同时驱动另外三个配置），
 * 本类只管「什么时候产生 / 丢弃数据」。
 *
 * <p>停服清理由 {@link VillagerTradeCache} 自己实现 {@code SessionState} 完成
 * （它在 {@code cache.impl} 包里，{@code ServerSessionStates} 会扫到）：
 * {@code clearSession()} 会先 {@code flush()} 再清内存，所以最后一分钟的交易不会丢；
 * 而更早的那道 pre-shutdown 钩子（{@code VillagerDetailsServer}）已经把它提交并等它落地过。
 */
public final class VillagerTradeSearchEvents implements ServerEventRegisterServer {

    public static final VillagerTradeSearchEvents INSTANCE = new VillagerTradeSearchEvents();

    private static final Logger log = LogManager.getLogger(VillagerTradeSearchEvents.class);

    /**
     * 当前服务器实例（规则重新打开时要靠它把磁盘索引读回来）。
     */
    private volatile MinecraftServer server;

    private VillagerTradeSearchEvents() {
    }

    @Override
    public void register() {

        ServerEntityEvents.ENTITY_UNLOAD.register(VillagerTradePipeline::onEntityUnload);

        ServerChunkEvents.CHUNK_UNLOAD.register(VillagerTradePipeline::onChunkUnload);

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, _) -> VillagerTradePipeline.onLivingDeath(entity));

        RuleCache.addListener(this::onRuleChanged);

        ServerLifecycleEvents.SERVER_STARTED.register(startedServer -> {
            server = startedServer;
            VillagerTradeCacheConfig.INSTANCE.ensureLoaded(startedServer);
            TradeItemCompletionCache.refresh(startedServer);
        });
        // 数据包重载后交易注册表整体换了一份：必须重新扫，否则补全里还是旧数据包的物品。
        // 重载失败时保持上一次的结果（注册表里多半还是旧内容，空掉反而更糟）。
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((reloadedServer, _, success) -> {
            if (!success) {
                log.warn("数据包重载失败，交易补全缓存保持上一次的结果");
                return;
            }
            TradeItemCompletionCache.refresh(reloadedServer);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerLevel overworld = server.overworld();
            long tick = overworld.getGameTime();
            VillagerTradeCache.tickPrune(tick);
        });
    }

    /**
     * 规则开关变化：
     * <ul>
     *     <li><b>关掉</b> → 先落盘再清内存（{@code clearSession} 里做），磁盘文件保留；</li>
     *     <li><b>打开</b> → 内存是空的，把磁盘上的索引读回来，免得「关一下再开」白丢整份数据。</li>
     * </ul>
     */
    private void onRuleChanged(RuleType type, String oldValue, String newValue) {
        if (type != RuleType.VILLAGER_TRADE_SEARCH) {
            return;
        }
        if (RuleCache.isEnabled(type)) {
            reloadFromDiskIfEmpty();
            return;
        }
        VillagerTradeCache.INSTANCE.clearSession();
    }

    private void reloadFromDiskIfEmpty() {
        MinecraftServer currentServer = server;
        if (currentServer == null || VillagerTradeCache.INSTANCE.size() > 0) {
            return;
        }
        try {
            VillagerTradeCacheConfig.INSTANCE.reloadFromDisk(currentServer);
        } catch (Throwable t) {
            log.warn("重新加载村民交易索引失败（内存保持为空）", t);
        }
    }
}
