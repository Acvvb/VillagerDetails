package com.villagerdetails.config.impl.section.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import com.villagerdetails.config.impl.util.BindingJson;
import com.villagerdetails.pojo.dto.PortalPiglinNoSpawnRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 「禁用地狱门刷僵尸猪灵」的区域表（绑定数据的落盘方）。
 *
 * <p>存进统一配置文件 {@code <world>/villagerdetails/config.json} 的 {@value #SECTION} 节，
 * 和规则开关（{@code villager_details_binding.json}）不是一回事。
 *
 * <p>数据源就在这个类里（不是内存里另存一份），所以「绑定成功」和「写进文件」是同一件事：
 * 指令 {@code /ec c PortalPiglinNoSpawn bind} 走 {@link #add}，加完立刻写文件。
 *
 * <pre>{@code
 * "PortalPiglinNoSpawn": {
 *   "regions": [
 *     { "dimension": "minecraft:overworld", "min": [100, 64, 200], "max": [105, 70, 205] }
 *   ]
 * }
 * }</pre>
 *
 * <p>一条记录只有一个维度键，因为「地狱门刷猪灵」是单个方块随机刻时的独立行为，不需要成对。
 * {@code max} 省略时按单格区域算；{@code min}/{@code max} 写反了会被 {@link PortalPiglinNoSpawnRegion#of}
 * 归一化；维度或坐标读不懂就整条跳过（不会留半个区域在内存里）。
 */
public final class PortalPiglinNoSpawnConfig implements ReloadableConfig {

    public static final PortalPiglinNoSpawnConfig INSTANCE = new PortalPiglinNoSpawnConfig();

    private static final Logger LOGGER = LogManager.getLogger(PortalPiglinNoSpawnConfig.class);

    /**
     * 统一配置文件里的节名
     */
    public static final String SECTION = "PortalPiglinNoSpawn";

    /**
     * 节里装区域的字段（外面套一层，以后要加元数据不用挪数据）
     */
    private static final String REGIONS_KEY = "regions";

    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_MIN = "min";
    private static final String KEY_MAX = "max";

    private final List<PortalPiglinNoSpawnRegion> regions = new CopyOnWriteArrayList<>();

    private PortalPiglinNoSpawnConfig() {
    }

    @Override
    public String configName() {
        return SECTION;
    }

    /**
     * 从统一配置的内存 root 重新读一遍；服务器启动 / 重载时由 {@link UnifiedConfigFile} 驱动。
     */
    @Override
    public void reload(MinecraftServer server) {
        load();
    }

    // ------------------------------------------------------------------
    //  查询 / 修改
    // ------------------------------------------------------------------

    public List<PortalPiglinNoSpawnRegion> snapshot() {
        return List.copyOf(regions);
    }

    /**
     * 按下标取一条；越界返回 {@code null}
     */
    public PortalPiglinNoSpawnRegion get(int index) {
        return (index >= 0 && index < regions.size()) ? regions.get(index) : null;
    }

    /**
     * 这个维度里的这个方块坐标是否落在任意一条绑定区域内。
     *
     * <p>随机刻每跳一次就会问一次，所以先比维度再比坐标；区域表通常是空的或者只有几条，
     * 遍历一遍就够便宜了。
     */
    public boolean contains(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension == null || pos == null || regions.isEmpty()) {
            return false;
        }
        for (PortalPiglinNoSpawnRegion region : regions) {
            if (dimension.equals(region.dimension()) && region.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 加一条并立刻落盘。
     *
     * <p>已经有一模一样的（角点反着填也算同一条，record 的相等性已经覆盖归一化后的结果）
     * 就什么都不做，免得重复 {@code bind} 把文件写出一堆重复项。
     *
     * @return 真的新增了返回 {@code true}
     */
    public boolean add(PortalPiglinNoSpawnRegion region) {
        if (region == null || regions.contains(region)) {
            return false;
        }
        regions.add(region);
        save();
        LOGGER.info("[{}] 已写入第 {} 个区域：{}", SECTION, regions.size(), describe(region));
        return true;
    }

    /**
     * 按下标删除一条并立刻落盘；越界返回 {@code null}
     */
    public PortalPiglinNoSpawnRegion removeAt(int index) {
        if (index < 0 || index >= regions.size()) {
            return null;
        }
        PortalPiglinNoSpawnRegion removed = regions.remove(index);
        save();
        LOGGER.info("[{}] 已删除第 {} 个区域：{}", SECTION, index + 1, describe(removed));
        return removed;
    }

    /**
     * 清空全部区域并落盘
     */
    public void clear() {
        if (regions.isEmpty()) {
            return;
        }
        int removed = regions.size();
        regions.clear();
        save();
        LOGGER.info("[{}] 已清空 {} 个区域", SECTION, removed);
    }

    // ------------------------------------------------------------------
    //  文件读写
    // ------------------------------------------------------------------

    /**
     * 从内存 root 读。
     *
     * <p>解析完顺手把规范化后的内容写回 root，父类收尾那次 {@code save()} 会一起落盘 ——
     * 于是手改潦草 / 写坏的条目会被清理成标准格式，没绑过时也会留下一个空骨架给用户照着填。
     */
    private void load() {
        regions.clear();

        JsonObject section = UnifiedConfigFile.getSection(SECTION);
        JsonElement element = section.get(REGIONS_KEY);
        int skipped = 0;
        if (element != null && element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                PortalPiglinNoSpawnRegion region = fromJson(entry);
                if (region == null) {
                    skipped++;
                    continue;
                }
                if (!regions.contains(region)) {
                    regions.add(region);
                }
            }
        }

        section.add(REGIONS_KEY, toJsonArray());

        if (skipped > 0) {
            LOGGER.warn("[{}] 有 {} 条记录读不懂，已跳过；落盘后文件里只剩能用的", SECTION, skipped);
        }
        LOGGER.info("[{}] 已加载 {} 个区域", SECTION, regions.size());
    }

    private void save() {
        UnifiedConfigFile.getSection(SECTION).add(REGIONS_KEY, toJsonArray());
        UnifiedConfigFile.save();
    }

    private JsonArray toJsonArray() {
        JsonArray array = new JsonArray();
        for (PortalPiglinNoSpawnRegion region : regions) {
            array.add(toJson(region));
        }
        return array;
    }

    // ------------------------------------------------------------------
    //  JSON ↔ record
    // ------------------------------------------------------------------

    private static JsonObject toJson(PortalPiglinNoSpawnRegion region) {
        JsonObject obj = new JsonObject();
        obj.addProperty(KEY_DIMENSION, region.dimension().identifier().toString());
        obj.add(KEY_MIN, BindingJson.posToJson(region.min()));
        obj.add(KEY_MAX, BindingJson.posToJson(region.max()));
        return obj;
    }

    /**
     * 一条记录 = 维度 + 两个角点；维度 / 坐标读不懂就整条丢掉
     */
    private static PortalPiglinNoSpawnRegion fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();

        ResourceKey<Level> dimension = BindingJson.dimensionFromJson(obj.get(KEY_DIMENSION));
        if (dimension == null) {
            return null;
        }
        BlockPos min = BindingJson.posFromJson(obj.get(KEY_MIN));
        if (min == null) {
            return null;
        }
        BlockPos max = BindingJson.posFromJson(obj.get(KEY_MAX));
        // max 省略 → 单格区域
        return PortalPiglinNoSpawnRegion.of(dimension, min, max == null ? min : max);
    }

    private static String describe(PortalPiglinNoSpawnRegion region) {
        return region.dimension().identifier() + " " + format(region.min()) + "~" + format(region.max());
    }

    private static String format(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
