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
 *   "scanRadius": 32,
 *   "scanIntervalTicks": 20
 * }
 * </pre>
 *
 * <p>{@code scanRadius}：每个玩家各自扫多大范围（格），夹在 8..64。
 * <p>{@code scanIntervalTicks}：多少 tick 扫一次，夹在 5..100。
 *
 * <p>缺键时自动写回默认值（和 {@code ServerLangConfig} 同一个套路）；
 * 改了值走 {@code /ec reload} 生效，不用重启。
 *
 */
public final class FurnaceDirtyHighlightConfig implements ReloadableConfig {

    public static final FurnaceDirtyHighlightConfig INSTANCE = new FurnaceDirtyHighlightConfig();

    private FurnaceDirtyHighlightConfig() {
    }

    private static final Logger log = LogManager.getLogger(FurnaceDirtyHighlightConfig.class);

    private static final String KEY_SCAN_RADIUS = "scanRadius";
    private static final String KEY_SCAN_INTERVAL = "scanIntervalTicks";

    /**
     * 默认扫描半径（格）
     */
    public static final int DEFAULT_SCAN_RADIUS = 32;

    /**
     * 默认扫描间隔（tick）
     */
    public static final int DEFAULT_SCAN_INTERVAL_TICKS = 20;

    private static final int MIN_SCAN_RADIUS = 8;
    private static final int MAX_SCAN_RADIUS = 64;
    private static final int MIN_SCAN_INTERVAL_TICKS = 5;
    private static final int MAX_SCAN_INTERVAL_TICKS = 100;

    /**
     * 当前扫描半径；服务端主线程读写，客户端不会碰
     */
    private static volatile int scanRadius = DEFAULT_SCAN_RADIUS;

    /**
     * 当前扫描间隔（tick）
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

        int radius = DEFAULT_SCAN_RADIUS;
        if (isNumber(section, KEY_SCAN_RADIUS)) {
            radius = section.get(KEY_SCAN_RADIUS).getAsInt();
        } else {
            section.addProperty(KEY_SCAN_RADIUS, DEFAULT_SCAN_RADIUS);
            dirty = true;
        }

        int interval = DEFAULT_SCAN_INTERVAL_TICKS;
        if (isNumber(section, KEY_SCAN_INTERVAL)) {
            interval = section.get(KEY_SCAN_INTERVAL).getAsInt();
        } else {
            section.addProperty(KEY_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL_TICKS);
            dirty = true;
        }

        scanRadius = Math.clamp(radius, MIN_SCAN_RADIUS, MAX_SCAN_RADIUS);
        scanIntervalTicks = Math.clamp(interval, MIN_SCAN_INTERVAL_TICKS, MAX_SCAN_INTERVAL_TICKS);

        if (dirty) {
            UnifiedConfigFile.save();
        }
        log.info("[{}] scanRadius={}, scanIntervalTicks={}", configName(), scanRadius, scanIntervalTicks);
    }

    /**
     * 扫描半径（格）；保证在合法区间内
     */
    public static int scanRadius() {
        return scanRadius;
    }

    /**
     * 扫描间隔（tick）；保证在合法区间内
     */
    public static int scanIntervalTicks() {
        return scanIntervalTicks;
    }

    private static boolean isNumber(JsonObject section, String key) {
        return section.has(key) && section.get(key).isJsonPrimitive()
                && section.getAsJsonPrimitive(key).isNumber();
    }
}
