package com.villagerdetails.config.impl.section.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.config.ReloadableConfig;
import com.villagerdetails.config.impl.UnifiedConfigFile;
import com.villagerdetails.config.impl.util.BindingJson;
import com.villagerdetails.pojo.dto.PortalBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
 * （{@code max} 省略时按单格区域算，{@code min}/{@code max} 顺序写反了会被归一化）。
 * 每侧还可以带一个可选的 {@code portal} 字段，记录该侧「已解析出的门底坐标」缓存，
 * 用于传送时跳过 33×33×33 的方块扫描：
 * <pre>{@code
 * "PortalBinding": {
 *   "bindings": [
 *     {
 *       "minecraft:overworld": {
 *         "min": [100, 64, 200], "max": [110, 70, 210],
 *         "portal": [105, 64, 205]
 *       },
 *       "minecraft:the_nether": {
 *         "min": [12, 60, 25], "max": [14, 62, 27],
 *         "portal": [13, 60, 26]
 *       }
 *     }
 *   ]
 * }
 * }</pre>
 * {@code portal} 缺失 = 尚未解析；传送时走扫描，扫描成功后会由
 * {@link #updateCachedPortal} 回写。门被拆后缓存失效，传送时验证失败会自动重扫重写，
 * 所以这里存的是<b>快路径缓存</b>，不是唯一真相。
 *
 * <p>因为两端是对称的（互相传送到对方区域中心），所以不区分先后，也就没有 a / b 这种侧名。
 * 代价是「同一维度之间」的绑定表达不了 —— 指令那边也造不出这种绑定，真出现会在 {@link #add} 被挡下。
 */
public final class PortalBindingConfig implements ReloadableConfig {

    public static final PortalBindingConfig INSTANCE = new PortalBindingConfig();

    private static final Logger log = LogManager.getLogger(PortalBindingConfig.class);

    /**
     * 统一配置文件里的节名
     */
    public static final String SECTION = "PortalBinding";

    /**
     * 节里真正装绑定的字段（外面套一层，以后要加元数据不用挪数据）
     */
    private static final String BINDINGS_KEY = "bindings";

    /** 区域里记录「已解析门底坐标」的可选字段名。 */
    private static final String PORTAL_KEY = "portal";

    private final List<PortalBinding> bindings = new CopyOnWriteArrayList<>();

    /**
     * 列表的「结构版本」：<b>只</b>在增 / 删 / 清空 / 重新加载时自增，
     * 缓存回写（{@code portalA}/{@code portalB}）不算 —— 那不会让序号错位。
     *
     * <p>{@code show} 会把当时的版本写进每个条目的 [刷新] / [删除] 按钮里，
     * 点按钮时带回来校验：版本对不上就说明「你看到列表之后又有人增删过」，
     * 此时序号已经指向别的绑定了，直接拒绝执行，让玩家重新 {@code show}，
     * 免得刷错门、或者删掉一条完全不相干的绑定。
     *
     * <p>所有增删都发生在服务端主线程，读方也在主线程；这里用 {@link AtomicInteger} 而不是
     * {@code volatile int}：{@code version++} 是「读-改-写」，写在 volatile 上不是原子操作
     * （静态检查会直接报「volatile 字段上的非原子操作」），换成原子自增既消掉告警，语义也更准。
     */
    private final AtomicInteger structureVersion = new AtomicInteger();

    private PortalBindingConfig() {
    }

    @Override
    public String configName() {
        return SECTION;
    }

    /**
     * 当前列表结构版本（见 {@link #structureVersion}）。
     *
     * <p>只在 {@code show} 渲染按钮时读取，命令执行时拿它和按钮带回来的值比对。
     */
    public int structureVersion() {
        return structureVersion.get();
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
            log.warn("[{}] 两端维度相同（{}），存不进配置文件，已忽略",
                    SECTION, binding.dimA().identifier());
            return false;
        }
        if (bindings.contains(binding)) {
            return false;
        }
        bindings.add(binding);
        structureVersion.incrementAndGet();
        save();
        log.info("[{}] 已写入第 {} 条绑定：{}", SECTION, bindings.size(), describe(binding));
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
        structureVersion.incrementAndGet();
        save();
        log.info("[{}] 已删除第 {} 条绑定：{}", SECTION, index + 1, describe(removed));
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
        structureVersion.incrementAndGet();
        save();
        log.info("[{}] 已清空 {} 条绑定", SECTION, removed);
    }

    /**
     * 回写某侧「已解析门底坐标」缓存并立刻落盘。
     *
     * <p>定位依赖 {@link PortalBinding#equals} —— 它<b>只比较 6 个原始字段、忽略缓存</b>，
     * 所以传入的 {@code binding} 即使是「扫描前拿到的旧副本」，也能精确命中原条目。
     *
     * <p>门被拆后缓存失效无需主动监听：传送时验证 {@code getBlockState(cached)} 不是门，
     * 会走扫描，扫描成功后再次调用本方法把新坐标覆盖回来。
     *
     * @param binding   要更新的绑定（equals 匹配，缓存字段不参与）
     * @param targetIsB true 更新 {@code portalB}（目标在 B 侧），false 更新 {@code portalA}
     * @param portalPos 解析出的门底坐标（{@code bottomOfPortal} 的结果）
     * @return 真的改动了返回 true；找不到 / 已是该值返回 false
     */
    public boolean updateCachedPortal(PortalBinding binding, boolean targetIsB, BlockPos portalPos) {
        if (binding == null || portalPos == null) {
            return false;
        }
        int idx = bindings.indexOf(binding);
        if (idx < 0) {
            // 正常流程不该出现：binding 被删除、或 equals 被改坏
            log.warn("[{}] 找不到要回写缓存的绑定：{}", SECTION, binding);
            return false;
        }
        PortalBinding old = bindings.get(idx);
        BlockPos oldCached = targetIsB ? old.portalB() : old.portalA();
        if (Objects.equals(portalPos, oldCached)) {
            // 已是该值：不落盘，避免每次传送都触发一次 IO
            return false;
        }
        PortalBinding updated = targetIsB ? old.withPortalB(portalPos) : old.withPortalA(portalPos);
        bindings.set(idx, updated);
        save();
        log.debug("[{}] 回写缓存（{}）：{}", SECTION,
                targetIsB ? "B 侧" : "A 侧", format(portalPos));
        return true;
    }

    /**
     * 一次性覆盖某条绑定<b>两侧</b>的缓存门坐标并落盘（{@code refresh} 专用）。
     *
     * <p>等价于「先 {@link #clearCachedPortals} 清空、再对两侧各调一次
     * {@link #updateCachedPortal}」，但中间不会落盘：整份配置文件只写一次。
     * 刷新一次要连续改三条记录（清缓存 + A 侧 + B 侧），走老路子会连写三次文件。
     *
     * <p>语义与刷新一致：<b>完全覆盖</b>，{@code portalA} / {@code portalB} 传 {@code null}
     * 就表示该侧「区域内暂时没门」，缓存被清掉（下次传送懒解析）。
     *
     * @param binding 要更新的绑定（equals 匹配，缓存字段不参与）
     * @param portalA 新的 A 侧门底坐标；{@code null} 表示该侧无缓存
     * @param portalB 新的 B 侧门底坐标；{@code null} 表示该侧无缓存
     * @return 真的改动了返回 true；找不到 / 与现状完全一致返回 false（不落盘）
     */
    public boolean applyCachedPortals(PortalBinding binding,
                                      @Nullable BlockPos portalA, @Nullable BlockPos portalB) {
        if (binding == null) {
            return false;
        }
        int idx = bindings.indexOf(binding);
        if (idx < 0) {
            log.warn("[{}] 找不到要刷新缓存的绑定：{}", SECTION, binding);
            return false;
        }
        PortalBinding old = bindings.get(idx);
        if (Objects.equals(old.portalA(), portalA) && Objects.equals(old.portalB(), portalB)) {
            // 和现状一模一样：不落盘，刷新一次没换门时连 IO 都不做
            return false;
        }
        bindings.set(idx, old.withPortalA(portalA).withPortalB(portalB));
        save();
        log.debug("[{}] 已刷新缓存：{} -> A={}, B={}", SECTION,
                describe(binding), format(portalA), format(portalB));
        return true;
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
        int duplicated = 0;
        int oversized = 0;
        if (element != null && element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                PortalBinding binding = fromJson(entry);
                if (binding == null) {
                    skipped++;
                    continue;
                }
                // 手改文件可能写出两条一模一样的绑定：这里合并掉。
                // 缓存回写 / 刷新 / 清缓存全都按 equals 找第一条，
                // 留两条相等记录会让「第 2 条的缓存」永远改不到（也删不干净）。
                if (bindings.contains(binding)) {
                    duplicated++;
                    continue;
                }
                if (!binding.withinSizeLimits()) {
                    oversized++;
                }
                bindings.add(binding);
            }
        }

        section.add(BINDINGS_KEY, toJsonArray());
        // 重载后序号可能整体变了：旧聊天记录里的按钮一律作废
        structureVersion.incrementAndGet();

        if (skipped > 0) {
            log.warn("[{}] 有 {} 条记录读不懂，已跳过；落盘后文件里只剩能用的", SECTION, skipped);
        }
        if (duplicated > 0) {
            log.warn("[{}] 有 {} 条重复记录，已合并；落盘后文件里每条绑定只留一份", SECTION, duplicated);
        }
        if (oversized > 0) {
            // 命令 set 已经不允许这么大的选区了，这里只可能是手改配置文件塞进来的
            log.warn("[{}] 有 {} 条绑定超出选区上限（{}x{}x{}），已原样保留；"
                            + "刷新 / 传送只会扫描中心附近一片，可能漏掉外围的门",
                    SECTION, oversized, PortalBinding.MAX_SIZE_X, PortalBinding.MAX_SIZE_Y,
                    PortalBinding.MAX_SIZE_Z);
        }
        log.info("[{}] 已加载 {} 项", SECTION, bindings.size());
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
        obj.add(binding.dimA().identifier().toString(),
                regionToJson(binding.minA(), binding.maxA(), binding.portalA()));
        obj.add(binding.dimB().identifier().toString(),
                regionToJson(binding.minB(), binding.maxB(), binding.portalB()));
        return obj;
    }

    /**
     * 一侧的区域：{@code {"min": [x,y,z], "max": [x,y,z], "portal": [x,y,z]?}}；
     * 它属于哪个维度写在上一层的键上。{@code portal} 为 null 时整字段省略，
     * 保持「没解析过就不写」的语义，避免配置文件里出现 {@code null} 占位。
     */
    private static JsonObject regionToJson(BlockPos min, BlockPos max, @Nullable BlockPos portal) {
        JsonObject region = new JsonObject();
        region.add("min", BindingJson.posToJson(min));
        region.add("max", BindingJson.posToJson(max));
        if (portal != null) {
            region.add(PORTAL_KEY, BindingJson.posToJson(portal));
        }
        return region;
    }

    /**
     * 一个对象 = 一条绑定：遍历它那两个「维度 → 区域」条目。
     *
     * <p>必须正好两条、且两条都读得懂 —— 少一条宁可整条丢掉，也不留半个绑定在内存里。
     *
     * <p>两个键的读取顺序即 A / B 的顺序；Gson 的 {@code JsonObject} 保插入顺序，
     * 而 {@link #toJson} 固定先写 A 后写 B，所以「写入-读取」两侧顺序自洽，
     * {@code portalA} / {@code portalB} 不会错位。
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
        // 先 of() 归一化（min/max 乱序也能修），再挂上两侧缓存
        PortalBinding normalized = PortalBinding.of(
                first.dimension(), first.min(), first.max(),
                second.dimension(), second.min(), second.max());
        return normalized
                .withPortalA(first.portal())
                .withPortalB(second.portal());
    }

    private static Side sideFromJson(String dimensionKey, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        ResourceKey<Level> dimension = BindingJson.dimensionFromKey(dimensionKey);
        if (dimension == null) {
            return null;
        }
        JsonObject region = element.getAsJsonObject();
        BlockPos min = BindingJson.posFromJson(region.get("min"));
        if (min == null) {
            return null;
        }
        BlockPos max = BindingJson.posFromJson(region.get("max"));
        // portal 缺失（旧配置 / 未解析）→ null，向后兼容
        BlockPos portal = region.has(PORTAL_KEY) ? BindingJson.posFromJson(region.get(PORTAL_KEY)) : null;
        // max 省略 → 单格区域
        return new Side(dimension, min, max == null ? min : max, portal);
    }

    /**
     * 解析中间态：一侧的维度 + 两个角点 + 可选的门缓存
     */
    private record Side(ResourceKey<Level> dimension, BlockPos min, BlockPos max,
                        @Nullable BlockPos portal) {
    }

    private static String describe(PortalBinding binding) {
        return binding.dimA().identifier() + " " + format(binding.minA()) + "~" + format(binding.maxA())
                + " <-> " + binding.dimB().identifier() + " " + format(binding.minB()) + "~" + format(binding.maxB())
                + (binding.portalA() != null || binding.portalB() != null
                ? " [cached A=" + format(binding.portalA()) + ", B=" + format(binding.portalB()) + "]"
                : "");
    }

    private static String format(@Nullable BlockPos pos) {
        return pos == null ? "null" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * 清空一条绑定两侧的缓存门坐标并落盘。
     *
     * <p>用于 {@code delete} 指令：删记录之前先把「这条绑定的缓存已作废」落一次盘。
     * {@code refresh} 不走这里 —— 它要的是「扫完再一次写回」，走
     * {@link #applyCachedPortals}，整份文件只写一次。
     *
     * <p>如果两侧本来就没缓存，返回 false，不落盘。
     */
    public boolean clearCachedPortals(PortalBinding binding) {
        if (binding == null) return false;
        int idx = bindings.indexOf(binding);
        if (idx < 0) {
            log.warn("[{}] 找不到要清缓存的绑定：{}", SECTION, binding);
            return false;
        }
        PortalBinding old = bindings.get(idx);
        if (old.portalA() == null && old.portalB() == null) {
            return false;
        }
        bindings.set(idx, new PortalBinding(
                old.dimA(), old.minA(), old.maxA(),
                old.dimB(), old.minB(), old.maxB(),
                null, null));
        save();
        log.debug("[{}] 已清空缓存：{}", SECTION, describe(binding));
        return true;
    }
}