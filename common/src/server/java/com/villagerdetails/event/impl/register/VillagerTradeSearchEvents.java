package com.villagerdetails.event.impl.register;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.cache.impl.VillagerTradeCache;
import com.villagerdetails.config.impl.VillagerTradeStorage;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.handler.entity.villager.trade.TradeItemNameResolver;
import com.villagerdetails.handler.entity.villager.trade.VillagerTradePipeline;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
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
 *     <li>{@code SERVER_STARTED} → {@link VillagerTradeStorage#load}
 *         （把上一次会话落盘的索引读回内存）；</li>
 *     <li>{@code END_SERVER_TICK} → 每 {@value #FLUSH_INTERVAL_TICKS} tick 检查一次脏标志，
 *         脏了就把快照丢进服务端线程池异步写盘（<b>主线程不做 I/O</b>）；</li>
 *     <li>规则被关掉 → 先同步落盘再清内存，规则重新打开时把磁盘上的索引读回来。</li>
 * </ol>
 *
 * <p>停服清理由 {@link VillagerTradeCache} 自己实现 {@code SessionState} 完成
 * （它在 {@code cache.impl} 包里，{@code ServerSessionStates} 会扫到）：
 * {@code clearSession()} 会先 {@code flushSync()} 再清内存，所以最后一分钟的交易不会丢。
 */
public final class VillagerTradeSearchEvents implements ServerEventRegisterServer {

    public static final VillagerTradeSearchEvents INSTANCE = new VillagerTradeSearchEvents();

    private static final Logger log = LogManager.getLogger(VillagerTradeSearchEvents.class);

    /**
     * 落盘检查间隔：1200 tick ≈ 1 分钟。
     */
    private static final int FLUSH_INTERVAL_TICKS = 1200;

    /**
     * 距离下一次检查还有多少 tick（只在服务端主线程读写，所以普通 int 就够）。
     */
    private int ticksUntilFlush = FLUSH_INTERVAL_TICKS;

    /**
     * 当前服务器实例（规则重新打开时要靠它把磁盘索引读回来）。
     */
    private volatile MinecraftServer server;

    private VillagerTradeSearchEvents() {
    }

    @Override
    public void register() {
        // 原版 en_us 表在启动期读一次，避免第一次区块卸载在服务端线程上读盘
        TradeItemNameResolver.preload();

        ServerEntityEvents.ENTITY_UNLOAD.register(VillagerTradePipeline::onEntityUnload);
        ServerChunkEvents.CHUNK_UNLOAD.register(VillagerTradePipeline::onChunkUnload);
        ServerLivingEntityEvents.AFTER_DEATH.register(
                (entity, _) -> VillagerTradePipeline.onLivingDeath(entity));
        RuleCache.addListener(this::onRuleChanged);

        // 启动：把上一次会话写下的索引读回内存（读盘失败只 WARN，不影响启动）
        ServerLifecycleEvents.SERVER_STARTED.register(startedServer -> {
            server = startedServer;
            VillagerTradeStorage.INSTANCE.load(startedServer);
        });
        // 定时异步落盘：主线程只做「标脏检查 + 一次 HashMap 拷贝」
        ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);

        log.info("村民交易查询已接入：ENTITY_UNLOAD / CHUNK_UNLOAD 抓快照，AFTER_DEATH 清缓存，"
                + "每 {} tick 异步落盘", FLUSH_INTERVAL_TICKS);
    }

    /**
     * 定时落盘检查。故意用 tick 计数而不是定时器线程：这里只读一个原子标志，
     * 真正的序列化 / 写盘都在服务端线程池里。
     */
    private void onEndServerTick(MinecraftServer currentServer) {
        if (--ticksUntilFlush > 0) {
            return;
        }
        ticksUntilFlush = FLUSH_INTERVAL_TICKS;
        try {
            VillagerTradeStorage.INSTANCE.flushAsync(currentServer);
        } catch (Throwable t) {
            log.warn("村民交易索引定时落盘失败（下一轮重试）", t);
        }
    }

    /**
     * 规则开关变化：
     * <ul>
     *     <li><b>关掉</b> → 先同步落盘再清内存（{@code clearSession} 里做），磁盘文件保留；</li>
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
            VillagerTradeStorage.INSTANCE.load(currentServer);
        } catch (Throwable t) {
            log.warn("重新加载村民交易索引失败（内存保持为空）", t);
        }
    }
}
