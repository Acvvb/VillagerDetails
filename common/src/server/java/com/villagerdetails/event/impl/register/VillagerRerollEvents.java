package com.villagerdetails.event.impl.register;

import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.handler.entity.villager.trader.refresh.TradeRerollScheduler;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 命名牌「点名刷取」的分帧调度接线。
 *
 * <p>刷新任务只在 {@code END_SERVER_TICK} 推进：主线程、每 tick 固定预算、
 * 实体每 tick 用 UUID 现查（详见 {@link TradeRerollScheduler}）。
 * 停服时清空队列，避免把任务带到下一个存档 / 下一次启动。
 */
public final class VillagerRerollEvents implements ServerEventRegisterServer {

    public static final VillagerRerollEvents INSTANCE = new VillagerRerollEvents();

    private static final Logger log = LoggerFactory.getLogger(VillagerRerollEvents.class);

    private VillagerRerollEvents() {
    }

    @Override
    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(TradeRerollScheduler::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> TradeRerollScheduler.clear());
        log.info("村民交易分帧刷新已接入：END_SERVER_TICK 推进，每 tick 次数由 VillagerOneTickTraderCount 控制（默认 500）/ 单任务上限 {} 次",
                TradeRerollScheduler.MAX_ATTEMPTS);
    }
}
