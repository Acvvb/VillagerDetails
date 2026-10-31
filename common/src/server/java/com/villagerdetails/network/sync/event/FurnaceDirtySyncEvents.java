package com.villagerdetails.network.sync.event;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.handler.block.furnaceDirty.FurnaceDirtyTracker;
import com.villagerdetails.network.channel.SyncEvents;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

/**
 * 脏熔炉高亮的服务端接线：把「每 tick 的视距增量同步」和「规则开关 / 下线 / 停服」接到
 * {@link FurnaceDirtyTracker}。
 *
 * <p>放在 {@code network.sync.event} 包下 = 自动注册。
 *
 * <p>扫描 / 发包的具体时机全部在 Tracker 里（区块粒度增量），这里只做三件事：
 * <ul>
 *     <li>每 tick 驱动一次 —— Tracker 自己判断「跨区块了没有」「到重扫间隔了没有」，没变化时几乎零开销；</li>
 *     <li>规则开 / 关 —— 关掉时清表并让客户端清空，打开时丢掉视距记录、下个 tick 重新补发；</li>
 *     <li>玩家下线 / 停服 —— 清掉该玩家的视距记录与全局静态表，防内存泄漏。</li>
 * </ul>
 *
 * <p>客户端如果自己关掉了显示，会通过通用的 {@code sync_subscription} 包把这条通道静音，
 * {@code FurnaceDirtySyncChannel#canSendTo} 返回 false，Tracker 会直接丢掉该玩家的视距记录
 * （恢复订阅后当成新玩家整批补发），连扫描都省掉。
 */
public final class FurnaceDirtySyncEvents implements SyncEvents {

    public static final FurnaceDirtySyncEvents INSTANCE = new FurnaceDirtySyncEvents();

    private FurnaceDirtySyncEvents() {
    }

    @Override
    public void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(FurnaceDirtyTracker::tick);

        // 规则 开→关：清表 + 让客户端清空；关→开：丢弃视距记录，下个 tick 重新扫一遍再 ADD
        RuleCache.addListener((type, _, _) -> {
            if (type != RuleType.FURNACE_DIRTY_HIGHLIGHT) {
                return;
            }
            MinecraftServer server = RuleCache.getServer();
            if (server == null) {
                return;
            }
            FurnaceDirtyTracker.onRuleChanged(server, RuleCache.isEnabled(type));
        });

        // 下线：丢掉这个玩家的视距记录（否则 VIEWS 会一直留着他）
        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) ->
                FurnaceDirtyTracker.onPlayerLeave(handler.getPlayer()));

        // 停服：静态表全部清掉
        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> FurnaceDirtyTracker.reset());
    }
}
