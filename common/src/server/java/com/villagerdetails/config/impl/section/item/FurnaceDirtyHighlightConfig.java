package com.villagerdetails.config.impl.section.item;

import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.villagerdetails.rule.type.RuleType.FURNACE_DIRTY_HIGHLIGHT;

/**
 * 熔炉脏东西高亮的<b>服务端</b>参数（每个存档一份，落在
 * {@code <world>/villagerdetails/config.json} 的 {@code FurnaceDirtyHighlight} 节里）。
 *
 * <pre>
 * "FurnaceDirtyHighlight": {
 *   "viewChunkRadius": 0,
 *   "scanIntervalTicks": 20
 * }
 * </pre>
 *
 * <p>{@code viewChunkRadius}：<b>扫描半径（区块）</b>。
 * 如果 ≤ 0，则默认跟随服务端视距（{@code server.properties} 中的 {@code view-distance}）。默认为 0。
 *
 * <p>范围是以玩家所在区块为中心的<b>正方形区块范围</b>：半径 N → (2N+1)×(2N+1) 个区块
 * （例如 N=2 → 5×5）。服务端按它决定「哪些区块算在这个玩家的视距内」：进入视距的区块 ADD、
 * 离开视距的区块 REMOVE，客户端只保留这部分坐标（见 {@code FurnaceDirtyTracker}）。
 *
 * <p>{@code scanIntervalTicks}：多久重扫一遍「当前被视距覆盖的区块」，夹在 5..100。
 * 熔炉状态变化（放进东西 / 烧完）在这个间隔内被发现，并增量推给视距内的玩家。
 *
 * <p>缺键时自动写回默认值（和 {@code ServerLangConfig} 同一个套路）；
 * 改了值走 {@code /ec reload} 生效，不用重启。
 *
 * <p><b>不保留旧键兼容</b>：1.1.0 起由 {@code ConfigMigration} 框架负责旧存档迁移
 * （旧格式低于 1.1.0 会整份重置），这里不再做「旧字段名 → 新字段名」的兼容。
 */
public final class FurnaceDirtyHighlightConfig implements ReloadableConfig {

    public static final FurnaceDirtyHighlightConfig INSTANCE = new FurnaceDirtyHighlightConfig();

    private FurnaceDirtyHighlightConfig() {
    }

    private static final Logger log = LogManager.getLogger(FurnaceDirtyHighlightConfig.class);

    private static final String KEY_VIEW_CHUNK_RADIUS = "viewChunkRadius";
    private static final String KEY_SCAN_INTERVAL = "scanIntervalTicks";

    /**
     * 默认扫描半径（区块）：0 = 跟随服务端视距。
     */
    public static final int DEFAULT_VIEW_CHUNK_RADIUS = 0;

    /**
     * 默认重扫间隔（tick）
     */
    public static final int DEFAULT_SCAN_INTERVAL_TICKS = 20;

    /**
     * 合法区间：0 表示跟随服务端视距；上限对齐原版 {@code view-distance} 的最大值。
     */
    private static final int MIN_VIEW_CHUNK_RADIUS = 0;
    private static final int MAX_VIEW_CHUNK_RADIUS = 32;

    /**
     * 跟随服务端视距、但拿不到服务端实例时使用的兜底半径（区块）：原版 {@code view-distance} 默认值。
     */
    private static final int FOLLOW_VIEW_DISTANCE_FALLBACK = 10;

    private static final int MIN_SCAN_INTERVAL_TICKS = 5;
    private static final int MAX_SCAN_INTERVAL_TICKS = 100;

    /**
     * 当前扫描半径（区块）；≤ 0 表示跟随服务端视距。服务端主线程读写，客户端不会碰。
     */
    private static volatile int viewChunkRadius = DEFAULT_VIEW_CHUNK_RADIUS;

    /**
     * 当前重扫间隔（tick）
     */
    private static volatile int scanIntervalTicks = DEFAULT_SCAN_INTERVAL_TICKS;

    @Override
    public String configName() {
        return FURNACE_DIRTY_HIGHLIGHT.getRegisterName();
    }

    @Override
    public void reload(MinecraftServer server) {
        JsonObject section = UnifiedConfigFile.getSection(configName());
        boolean dirty = false;

        int radius = DEFAULT_VIEW_CHUNK_RADIUS;
        if (isNumber(section, KEY_VIEW_CHUNK_RADIUS)) {
            radius = section.get(KEY_VIEW_CHUNK_RADIUS).getAsInt();
        } else {
            section.addProperty(KEY_VIEW_CHUNK_RADIUS, DEFAULT_VIEW_CHUNK_RADIUS);
            dirty = true;
        }

        int interval = DEFAULT_SCAN_INTERVAL_TICKS;
        if (isNumber(section, KEY_SCAN_INTERVAL)) {
            interval = section.get(KEY_SCAN_INTERVAL).getAsInt();
        } else {
            section.addProperty(KEY_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL_TICKS);
            dirty = true;
        }

        viewChunkRadius = Math.clamp(radius, MIN_VIEW_CHUNK_RADIUS, MAX_VIEW_CHUNK_RADIUS);
        scanIntervalTicks = Math.clamp(interval, MIN_SCAN_INTERVAL_TICKS, MAX_SCAN_INTERVAL_TICKS);

        if (dirty) {
            UnifiedConfigFile.save();
        }

        // 配置 0 = 跟随服务端视距：日志直接打出「实际生效」的区块半径，
        // 免得还得自己去翻 server.properties 才知道真实扫描范围。
        String radiusDesc = viewChunkRadius <= 0
                ? "跟随服务端视距(实际生效: " + effectiveViewChunkRadius(server) + " 区块)"
                : "固定";
        log.info("[{}] viewChunkRadius={}（{}）, scanIntervalTicks={}", configName(), viewChunkRadius,
                radiusDesc, scanIntervalTicks);
    }

    /**
     * 配置里的扫描半径（区块）；≤ 0 表示跟随服务端视距（{@code server.properties} 的 {@code view-distance}）。
     *
     * <p>实际范围是以玩家所在区块为中心、半径 {@link #effectiveViewChunkRadius(MinecraftServer)}
     * 的正方形区块范围。
     */
    public static int viewChunkRadius() {
        return viewChunkRadius;
    }

    /**
     * 实际生效的扫描半径（区块）：配置 &gt; 0 时就是配置值，
     * 配置 ≤ 0 时取服务端视距（{@code view-distance}）。
     *
     * @param server 服务端实例；为 {@code null}（例如配置自检阶段）时按原版默认视距
     *               {@value #FOLLOW_VIEW_DISTANCE_FALLBACK} 兜底
     * @return 半径（区块），至少为 1
     */
    public static int effectiveViewChunkRadius(MinecraftServer server) {
        int configured = viewChunkRadius;
        if (configured > 0) {
            return configured;
        }
        if (server == null) {
            return FOLLOW_VIEW_DISTANCE_FALLBACK;
        }
        return Math.max(1, server.getPlayerList().getViewDistance());
    }

    /**
     * 重扫间隔（tick）；保证在合法区间内
     */
    public static int scanIntervalTicks() {
        return scanIntervalTicks;
    }

    private static boolean isNumber(JsonObject section, String key) {
        return section.has(key) && section.get(key).isJsonPrimitive()
                && section.get(key).getAsJsonPrimitive().isNumber();
    }
}
