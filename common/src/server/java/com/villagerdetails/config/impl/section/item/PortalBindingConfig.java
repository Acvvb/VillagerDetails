package com.villagerdetails.config.impl.section.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import com.villagerdetails.pojo.dto.PortalBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 传送门绑定的持久化。
 *
 * <p>存进统一配置文件 {@code <world>/villagerdetails/config.json} 的 {@value #SECTION} 节。
 * 注意和 {@code RuleConfig} 那份 {@code villager_details_binding.json} 不是一回事：
 * 那边存的是<b>规则开关</b>，这里存的是<b>坐标数据</b>。
 *
 * <p>数据源就在这个类里（不是内存里另存一份），所以「改过但忘了落盘」不存在：
 * 指令 {@code /ec PortalBinding bind} 走 {@link #add}，加完立刻写文件。
 *
 * <p>一条绑定就是<b>一个对象</b>：键是两端的维度 id，值是该维度里的坐标区域
 * （{@code max} 省略时按单格区域算，{@code min}/{@code max} 顺序写反了会被归一化）：
 * <pre>{@code
 * "PortalBinding": {
 *   "bindings": [
 *     {
 *       "minecraft:overworld": { "min": [100, 64, 200], "max": [110, 70, 210] },
 *       "minecraft:the_nether":  { "min": [12, 60, 25],  "max": [14, 62, 27] }
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>因为两端是对称的（互相传送到对方区域中心），所以不区分先后，也就没有 a / b 这种侧名。
 * 代价是「同一维度之间」的绑定表达不了 —— 指令那边也造不出这种绑定，真出现会在 {@link #add} 被挡下。
 */
public final class PortalBindingConfig implements ReloadableConfig {

    public static final PortalBindingConfig INSTANCE = new PortalBindingConfig();

    private static final Logger LOGGER = LogManager.getLogger(PortalBindingConfig.class);

    /**
     * 统一配置文件里的节名
     */
    public static final String SECTION = "PortalBinding";

    /**
     * 节里真正装绑定的字段（外面套一层，以后要加元数据不用挪数据）
     */
    private static final String BINDINGS_KEY = "bindings";

    private final List<PortalBinding> bindings = new CopyOnWriteArrayList<>();

    private PortalBindingConfig() {
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

    public List<PortalBinding> snapshot() {
        return List.copyOf(bindings);
    }

    /**
     * 加一条并立刻落盘。
     *
     * <p>已经有一模一样的（{@link PortalBinding#of} 会归一化角点顺序，所以角点反着填也算同一条）
     * 就什么都不做，免得重复 {@code bind} 把文件写出一堆重复项。
     *
     * @return 真的新增了返回 true
     */
    public boolean add(PortalBinding binding) {
        if (binding.dimA().equals(binding.dimB())) {
            // 一条绑定靠「两个维度键」表达两端，同维度之间的绑定这种格式装不下
            LOGGER.warn("[{}] 两端维度相同（{}），存不进配置文件，已忽略",
                    SECTION, binding.dimA().identifier());
            return false;
        }
        if (bindings.contains(binding)) {
            return false;
        }
        bindings.add(binding);
        save();
        LOGGER.info("[{}] 已写入第 {} 条绑定：{}", SECTION, bindings.size(), describe(binding));
        return true;
    }

    /**
     * 按下标取一条；越界返回 {@code null}
     */
    public PortalBinding get(int index) {
        return (index >= 0 && index < bindings.size()) ? bindings.get(index) : null;
    }

    /**
     * 按下标删除一条并立刻落盘；越界返回 {@code null}
     */
    public PortalBinding removeAt(int index) {
        if (index < 0 || index >= bindings.size()) {
            return null;
        }
        PortalBinding removed = bindings.remove(index);
        save();
        LOGGER.info("[{}] 已删除第 {} 条绑定：{}", SECTION, index + 1, describe(removed));
        return removed;
    }

    /**
     * 清空全部绑定并落盘
     */
    public void clear() {
        if (bindings.isEmpty()) {
            return;
        }
        int removed = bindings.size();
        bindings.clear();
        save();
        LOGGER.info("[{}] 已清空 {} 条绑定", SECTION, removed);
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
        bindings.clear();

        JsonObject section = UnifiedConfigFile.getSection(SECTION);
        JsonElement element = section.get(BINDINGS_KEY);
        int skipped = 0;
        if (element != null && element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                PortalBinding binding = fromJson(entry);
                if (binding == null) {
                    skipped++;
                    continue;
                }
                bindings.add(binding);
            }
        }

        section.add(BINDINGS_KEY, toJsonArray());

        if (skipped > 0) {
            LOGGER.warn("[{}] 有 {} 条记录读不懂，已跳过；落盘后文件里只剩能用的", SECTION, skipped);
        }
        LOGGER.info("[{}] 已加载 {} 项", SECTION, bindings.size());
    }

    private void save() {
        UnifiedConfigFile.getSection(SECTION).add(BINDINGS_KEY, toJsonArray());
        UnifiedConfigFile.save();
    }

    private JsonArray toJsonArray() {
        JsonArray array = new JsonArray();
        for (PortalBinding binding : bindings) {
            array.add(toJson(binding));
        }
        return array;
    }

    // ------------------------------------------------------------------
    //  JSON ↔ record
    // ------------------------------------------------------------------

    private static JsonObject toJson(PortalBinding binding) {
        JsonObject obj = new JsonObject();
        obj.add(binding.dimA().identifier().toString(), regionToJson(binding.minA(), binding.maxA()));
        obj.add(binding.dimB().identifier().toString(), regionToJson(binding.minB(), binding.maxB()));
        return obj;
    }

    /**
     * 一侧的区域：{@code {"min": [x,y,z], "max": [x,y,z]}}；它属于哪个维度写在上一层的键上。
     */
    private static JsonObject regionToJson(BlockPos min, BlockPos max) {
        JsonObject region = new JsonObject();
        region.add("min", posToJson(min));
        region.add("max", posToJson(max));
        return region;
    }

    private static JsonArray posToJson(BlockPos pos) {
        JsonArray array = new JsonArray(3);
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    /**
     * 一个对象 = 一条绑定：遍历它那两个「维度 → 区域」条目。
     *
     * <p>必须正好两条、且两条都读得懂 —— 少一条宁可整条丢掉，也不留半个绑定在内存里。
     */
    private static PortalBinding fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        Side first = null;
        Side second = null;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            Side side = sideFromJson(entry.getKey(), entry.getValue());
            if (side == null) {
                return null;
            }
            if (first == null) {
                first = side;
            } else if (second == null) {
                second = side;
            } else {
                // 超过两端，不是一条合法绑定
                return null;
            }
        }
        if (first == null || second == null) {
            return null;
        }
        // 交给 of() 归一化，手写的 min/max 顺序乱了也没关系
        return PortalBinding.of(first.dimension(), first.min(), first.max(),
                second.dimension(), second.min(), second.max());
    }

    private static Side sideFromJson(String dimensionKey, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        ResourceKey<Level> dimension = dimensionFromJson(dimensionKey);
        if (dimension == null) {
            return null;
        }
        JsonObject region = element.getAsJsonObject();
        BlockPos min = posFromJson(region.get("min"));
        if (min == null) {
            return null;
        }
        BlockPos max = posFromJson(region.get("max"));
        // max 省略 → 单格区域
        return new Side(dimension, min, max == null ? min : max);
    }

    private static ResourceKey<Level> dimensionFromJson(String key) {
        Identifier id = Identifier.tryParse(key);
        return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
    }

    private static BlockPos posFromJson(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() < 3) {
            return null;
        }
        try {
            return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
        } catch (RuntimeException e) {
            // 写成非数字之类：当这条坏了
            return null;
        }
    }

    /**
     * 解析中间态：一侧的维度 + 两个角点
     */
    private record Side(ResourceKey<Level> dimension, BlockPos min, BlockPos max) {
    }

    private static String describe(PortalBinding binding) {
        return binding.dimA().identifier() + " " + format(binding.minA()) + "~" + format(binding.maxA())
                + " <-> " + binding.dimB().identifier() + " " + format(binding.minB()) + "~" + format(binding.maxB());
    }

    private static String format(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
