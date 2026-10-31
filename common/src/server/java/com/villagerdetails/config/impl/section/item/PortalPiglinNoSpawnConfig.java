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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *   "nextId": 2,
 *   "regions": [
 *     { "id": "1", "dimension": "minecraft:overworld", "min": [100, 64, 200], "max": [105, 70, 205] }
 *   ]
 * }
 * }</pre>
 *
 * <p>一条记录只有一个维度键，因为「地狱门刷猪灵」是单个方块随机刻时的独立行为，不需要成对。
 * {@code max} 省略时按单格区域算；{@code min}/{@code max} 写反了会被 {@link PortalPiglinNoSpawnRegion#of}
 * 归一化；维度或坐标读不懂就整条跳过（不会留半个区域在内存里）。
 *
 * <p>{@code id} 和传送门绑定那套是同一个约定：<b>自增数字、只增不减、删除不回收</b>，
 * 所以指令里的 {@code <id>} 永远指向同一条记录，不需要列表版本号。
 * 旧存档（没有 {@code id}）在读入时按顺序补号，见 {@link #load()}。
 */
public final class PortalPiglinNoSpawnConfig implements ReloadableConfig {

    public static final PortalPiglinNoSpawnConfig INSTANCE = new PortalPiglinNoSpawnConfig();

    private static final Logger log = LogManager.getLogger(PortalPiglinNoSpawnConfig.class);

    /**
     * 统一配置文件里的节名
     */
    public static final String SECTION = "PortalPiglinNoSpawn";

    /**
     * 节里装区域的字段（外面套一层，以后要加元数据不用挪数据）
     */
    private static final String REGIONS_KEY = "regions";

    /**
     * 一条区域的自增数字 ID 字段。
     */
    private static final String KEY_ID = "id";

    /**
     * 节里的 ID 计数器（和 {@code regions} 平级的元数据）。
     *
     * <p>光靠「已加载的最大 ID + 1」不够：删掉编号最大的那条再重启，
     * 最大 ID 就退回去了，新绑的会拿到一个<b>曾经发出去过</b>的号。
     * 把计数器本身存下来，ID 才是真正「只增不减、删除不回收」。
     */
    private static final String KEY_NEXT_ID = "nextId";

    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_MIN = "min";
    private static final String KEY_MAX = "max";

    private final List<PortalPiglinNoSpawnRegion> regions = new CopyOnWriteArrayList<>();

    /**
     * 下一条区域的自增数字 ID。
     *
     * <p>只增不减：删掉一个条目<b>不回收</b>它的号，所以已经发出去的 ID 不会指到新条目上。
     * 增删都在服务端主线程，普通 {@code int} 就够。
     */
    private int nextId = 1;

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
     * 全部 ID，按当前列表顺序；只给 Tab 补全用。
     */
    public List<String> getIds() {
        List<String> ids = new ArrayList<>(regions.size());
        for (PortalPiglinNoSpawnRegion region : regions) {
            if (region.id() != null) {
                ids.add(region.id());
            }
        }
        return ids;
    }

    /**
     * 按 ID 取一条；找不到返回 {@code null}。
     */
    public @Nullable PortalPiglinNoSpawnRegion getById(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (PortalPiglinNoSpawnRegion region : regions) {
            if (id.equals(region.id())) {
                return region;
            }
        }
        return null;
    }

    /**
     * 按「维度 + 两个角点」找一条（{@link PortalPiglinNoSpawnRegion#equals} 不比较 ID）。
     *
     * <p>给 {@code bind} 用：刚 {@link #add} 进去的记录带了新分配的 ID，
     * 用它就能把「这条已经绑过、ID 是多少」告诉玩家。
     */
    public @Nullable PortalPiglinNoSpawnRegion findByRegion(PortalPiglinNoSpawnRegion probe) {
        if (probe == null) {
            return null;
        }
        for (PortalPiglinNoSpawnRegion region : regions) {
            if (region.equals(probe)) {
                return region;
            }
        }
        return null;
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
     * 加一条并立刻落盘，ID 由本类分配（{@code 最大 ID + 1}）。
     *
     * <p>已经有一模一样的（角点反着填也算同一条，record 的相等性已经覆盖归一化后的结果，
     * 且<b>不比较 ID</b>）就什么都不做，免得重复 {@code bind} 把文件写出一堆重复项。
     *
     * @return 真的新增了返回 {@code true}
     */
    public boolean add(PortalPiglinNoSpawnRegion region) {
        if (region == null || regions.contains(region)) {
            return false;
        }
        PortalPiglinNoSpawnRegion stored = region.withId(allocateId());
        regions.add(stored);
        save();
        log.info("[{}] 已写入 #{} 区域：{}", SECTION, stored.id(), describe(stored));
        return true;
    }

    /**
     * 按 ID 删除一条并立刻落盘；找不到返回 {@code null}。
     */
    public @Nullable PortalPiglinNoSpawnRegion removeById(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (int i = 0; i < regions.size(); i++) {
            PortalPiglinNoSpawnRegion region = regions.get(i);
            if (!id.equals(region.id())) {
                continue;
            }
            PortalPiglinNoSpawnRegion removed = regions.remove(i);
            save();
            log.info("[{}] 已删除 #{} 区域：{}", SECTION, id, describe(removed));
            return removed;
        }
        return null;
    }

    /**
     * 清空全部区域并落盘。
     *
     * <p>{@link #nextId} <b>不</b>重置：清空之后新绑的还是接着最大的号往上走。
     */
    public void clear() {
        if (regions.isEmpty()) {
            return;
        }
        int removed = regions.size();
        regions.clear();
        save();
        log.info("[{}] 已清空 {} 个区域", SECTION, removed);
    }

    // ------------------------------------------------------------------
    //  文件读写
    // ------------------------------------------------------------------

    /**
     * 从内存 root 读。
     *
     * <p>解析完顺手把规范化后的内容写回 root，父类收尾那次 {@code save()} 会一起落盘 ——
     * 于是手改潦草 / 写坏的条目会被清理成标准格式，没绑过时也会留下一个空骨架给用户照着填。
     *
     * <p><b>旧存档的 ID 迁移</b>与 {@code PortalBindingConfig} 完全同一套：
     * <ol>
     *     <li>第一遍只认领文件里已写、且不重复的 ID（重复的除第一条外都当作「没有 ID」）；</li>
     *     <li>第二遍给没 ID / ID 重复的条目补号，起点是<b>已认领数字 ID 的最大值 + 1</b>，
     *         逐个自增并跳过已占用的号，所以补出来的号必然不与已有号冲突；</li>
     *     <li>{@link #nextId} 取「全部数字 ID 的最大值 + 1」与「文件里记的 {@code nextId}」
     *         两者中的较大者 —— 后者保证「删掉最大的号再重启」也不会把那个号重新发出去；
     *         之后新增只会更大，删除不回收，新 ID 永远不会复用旧号。</li>
     * </ol>
     */
    private void load() {
        regions.clear();
        nextId = 1;

        JsonObject section = UnifiedConfigFile.getSection(SECTION);
        JsonElement element = section.get(REGIONS_KEY);
        int skipped = 0;
        int duplicated = 0;
        int assigned = 0;

        List<PortalPiglinNoSpawnRegion> parsed = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                PortalPiglinNoSpawnRegion region = fromJson(entry);
                if (region == null) {
                    skipped++;
                    continue;
                }
                // 手改出的重复区域合并掉：否则「按 equals 找第一条」永远改不到第二条
                if (parsed.contains(region)) {
                    duplicated++;
                    continue;
                }
                parsed.add(region);
            }
        }

        // ---- 第一遍：认领文件里已写且不重复的 ID ----
        List<PortalPiglinNoSpawnRegion> withIds = new ArrayList<>(parsed.size());
        Set<String> usedIds = new HashSet<>();
        int maxNumericId = 0;
        for (PortalPiglinNoSpawnRegion region : parsed) {
            String id = region.id();
            if (id != null && usedIds.add(id)) {
                withIds.add(region);
                maxNumericId = Math.max(maxNumericId, numericOrZero(id));
            } else {
                withIds.add(region.withId(null));
                assigned++;
            }
        }

        // ---- 第二遍：给缺失 / 重复的补号，从最大数字 ID + 1 起 ----
        int cursor = maxNumericId + 1;
        for (int i = 0; i < withIds.size(); i++) {
            PortalPiglinNoSpawnRegion region = withIds.get(i);
            if (region.id() != null) {
                continue;
            }
            String id;
            do {
                id = String.valueOf(cursor++);
            } while (usedIds.contains(id));
            usedIds.add(id);
            withIds.set(i, region.withId(id));
        }

        // ---- 落进列表，并让计数器停在「最大号」和「文件里记的计数器」两者之后 ----
        for (PortalPiglinNoSpawnRegion region : withIds) {
            regions.add(region);
            maxNumericId = Math.max(maxNumericId, numericOrZero(region.id()));
        }
        // 取两者较大值：文件里记的 nextId 更大 = 曾经删过最大的号，不能退回去重发
        nextId = Math.max(maxNumericId + 1, storedNextId(section));

        // 写回规范化结果：旧存档在这里就被补上 id 了（父类收尾会落盘）
        section.add(REGIONS_KEY, toJsonArray());
        section.addProperty(KEY_NEXT_ID, nextId);

        if (skipped > 0) {
            log.warn("[{}] 有 {} 条记录读不懂，已跳过；落盘后文件里只剩能用的", SECTION, skipped);
        }
        if (duplicated > 0) {
            log.warn("[{}] 有 {} 条重复记录，已合并；落盘后文件里每条区域只留一份", SECTION, duplicated);
        }
        if (assigned > 0) {
            log.info("[{}] 有 {} 条记录没有 ID（旧存档 / 手改重复），已按顺序补号，下一个 ID 是 {}",
                    SECTION, assigned, nextId);
        }
        log.info("[{}] 已加载 {} 个区域", SECTION, regions.size());
    }

    /**
     * 分配下一个 ID（只增不减；{@code while} 只是防御手改配置的意外撞号）。
     */
    private String allocateId() {
        String id;
        do {
            id = String.valueOf(nextId++);
        } while (getById(id) != null);
        return id;
    }

    private void save() {
        JsonObject section = UnifiedConfigFile.getSection(SECTION);
        section.add(REGIONS_KEY, toJsonArray());
        // 计数器一起落盘：否则「删掉最大的号 → 重启」会让那个号被重新发出去
        section.addProperty(KEY_NEXT_ID, nextId);
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
        if (region.id() != null) {
            obj.addProperty(KEY_ID, region.id());
        }
        obj.addProperty(KEY_DIMENSION, region.dimension().identifier().toString());
        obj.add(KEY_MIN, BindingJson.posToJson(region.min()));
        obj.add(KEY_MAX, BindingJson.posToJson(region.max()));
        return obj;
    }

    /**
     * 一条记录 = 可选 ID + 维度 + 两个角点；维度 / 坐标读不懂就整条丢掉。
     *
     * <p>没有 {@code id}（旧存档）时返回 {@code id == null}，由 {@link #load} 统一补号。
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
        PortalPiglinNoSpawnRegion region = PortalPiglinNoSpawnRegion.of(dimension, min, max == null ? min : max);
        return region.withId(idFromJson(obj.get(KEY_ID)));
    }

    /**
     * 读 {@code id} 字段：接受字符串，也接受手改成的数字；空 / 缺失一律返回 {@code null}
     * （表示「还没分配」）。
     */
    private static @Nullable String idFromJson(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String id = element.getAsString();
        return id == null || id.isBlank() ? null : id;
    }

    /**
     * 读文件里记的 ID 计数器；没有 / 读不懂一律按 0（表示「让最大值说了算」）。
     */
    private static int storedNextId(JsonObject section) {
        JsonElement element = section.get(KEY_NEXT_ID);
        if (element == null || !element.isJsonPrimitive()) {
            return 0;
        }
        return numericOrZero(element.getAsString());
    }

    /**
     * 纯数字 ID 转成 int；非数字（手改出来的）返回 0，不参与「最大值」计算。
     */
    private static int numericOrZero(@Nullable String id) {
        if (id == null) {
            return 0;
        }
        try {
            int value = Integer.parseInt(id);
            return Math.max(value, 0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String describe(PortalPiglinNoSpawnRegion region) {
        return "#" + region.id() + " " + region.dimension().identifier()
                + " " + format(region.min()) + "~" + format(region.max());
    }

    private static String format(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
