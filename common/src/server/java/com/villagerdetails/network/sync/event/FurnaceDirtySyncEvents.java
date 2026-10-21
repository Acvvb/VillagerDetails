package com.villagerdetails.network.sync.event;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.config.impl.section.item.FurnaceDirtyHighlightConfig;
import com.villagerdetails.handler.block.furnaceDirty.FurnaceDirtyScanner;
import com.villagerdetails.network.channel.SyncEvents;
import com.villagerdetails.network.sync.clientbound.FurnaceDirtySyncChannel;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 熔炉脏东西高亮的服务端接线：周期扫描 + 全量下发 + 规则开关响应。
 *
 * <p>放在 {@code network.sync.event} 包下 = 自动注册。
 *
 * <p>规则关闭时这里就 {@code return}：<b>不扫描、不发包</b>。
 * 开→关的那一下会补发一次空列表，让客户端立刻清掉高亮；关→开立刻扫一轮，
 * 不用等下一个周期。
 *
 * <p>客户端如果自己关掉了显示，会通过通用的 {@code sync_subscription} 包把这条通道静音，
 * {@link FurnaceDirtySyncChannel#canSendTo} 返回 false，这里连扫描都跳过。
 */
public final class FurnaceDirtySyncEvents implements SyncEvents {

    public static final FurnaceDirtySyncEvents INSTANCE = new FurnaceDirtySyncEvents();

    private FurnaceDirtySyncEvents() {
    }

    @Override
    public void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RuleType rule = RuleType.FURNACE_DIRTY_HIGHLIGHT;
            if (!RuleCache.isEnabled(rule)) {
                return;
            }
            // 间隔本身可配；配置里夹过区间，不会出现 0 或负数
            if (server.getTickCount() % FurnaceDirtyHighlightConfig.scanIntervalTicks() != 0) {
                return;
            }
            syncAll(server, true);
        });

        // 规则 开→关：补一包空列表；关→开：立刻扫一轮
        RuleCache.addListener((type, _, _) -> {
            if (type != RuleType.FURNACE_DIRTY_HIGHLIGHT) {
                return;
            }
            MinecraftServer server = RuleCache.getServer();
            if (server == null) {
                return;
            }
            syncAll(server, RuleCache.isEnabled(type));
        });
    }

    /**
     * 给所有在线玩家各推一份「他自己附近」的列表。
     *
     * @param enabled false = 规则刚被关掉，只发空列表清场，不扫描
     */
    private static void syncAll(MinecraftServer server, boolean enabled) {
        int radius = FurnaceDirtyHighlightConfig.scanRadius();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // 没装 mod / 客户端把这条通道静音了 → 跳过（静音时连扫描都省掉）
            if (!FurnaceDirtySyncChannel.INSTANCE.canSendTo(player)) {
                continue;
            }
            List<BlockPos> dirty = enabled
                    ? FurnaceDirtyScanner.scan(player.level(), player.blockPosition(), radius)
                    : List.of();
            FurnaceDirtySyncChannel.INSTANCE.syncTo(player, dirty);
        }
    }
}
