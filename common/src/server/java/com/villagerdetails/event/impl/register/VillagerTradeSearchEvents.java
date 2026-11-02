package com.villagerdetails.event.impl.register;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.cache.impl.VillagerTradeCache;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.handler.entity.villager.trade.TradeItemNameResolver;
import com.villagerdetails.handler.entity.villager.trade.VillagerTradePipeline;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
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
 *     <li>规则被关掉 → 整份清空缓存，规则关着时不占内存。</li>
 * </ol>
 *
 * <p>停服清理由 {@link VillagerTradeCache} 自己实现 {@code SessionState} 完成
 * （它在 {@code cache.impl} 包里，{@code ServerSessionStates} 会扫到）。
 */
public final class VillagerTradeSearchEvents implements ServerEventRegisterServer {

    public static final VillagerTradeSearchEvents INSTANCE = new VillagerTradeSearchEvents();

    private static final Logger log = LogManager.getLogger(VillagerTradeSearchEvents.class);

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
        RuleCache.addListener(VillagerTradeSearchEvents::onRuleChanged);

        log.info("村民交易查询已接入：ENTITY_UNLOAD / CHUNK_UNLOAD 抓快照，AFTER_DEATH 清缓存");
    }

    /**
     * 规则关闭：缓存立刻作废（数据本来就不落盘，下次开启靠区块卸载重新积累）。
     *
     * <p>开启时不做任何事 —— 新数据会随着之后的区块卸载自然进来。
     */
    private static void onRuleChanged(RuleType type, String oldValue, String newValue) {
        if (type != RuleType.VILLAGER_TRADE_SEARCH) {
            return;
        }
        if (RuleCache.isEnabled(type)) {
            return;
        }
        VillagerTradeCache.INSTANCE.clearSession();
    }
}
