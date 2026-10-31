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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * （{@code max} 省略时按单格区域算，{@code min}/{@code max} 顺序写反了会被归一化）。
 * 每侧还可以带一个可选的 {@code portal} 字段，记录该侧「已解析出的门底坐标」缓存，
 * 用于传送时跳过 33×33×33 的方块扫描；顶层还有一个 {@code id}：<b>自增数字 ID</b>，
 * 指令里 {@code view <id>} / {@code delete <id>} / {@code refresh <id>} 用的就是它。
 * <pre>{@code
 * "PortalBinding": {
 *   "nextId": 2,
 *   "bindings": [
 *     {
 *       "id": "1",
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
 * <p><b>ID 是不回头的自增数</b>：删掉 {@code #2} 之后，{@code #3} 仍然是 {@code #3}，
 * 新绑的拿到的是 {@code 最大 ID + 1}。旧存档（没有 {@code id} 字段）在读入时按文件里的
 * 顺序补号，并把计数器推到最大号之后，见 {@link #load()}。
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

    /**
     * 一条绑定的自增数字 ID 字段（顶层，和两个维度键并列）。
     *
     * <p>读的时候要显式跳过它：{@link #fromJson} 是按「对象里的每个键都是一个维度」
     * 来认两端的，多出来一个 {@code id} 会被当成读不懂的维度。
     */
    private static final String KEY_ID = "id";

    /**
     * 节里的 ID 计数器（和 {@code bindings} 平级的元数据）。
     *
     * <p>光靠「已加载的最大 ID + 1」是不够的：删掉编号最大的那条再重启，
     * 最大 ID 就退回去了，新绑的会拿到一个<b>曾经发出去过</b>的号。
     * 把计数器本身存下来，ID 才是真正「只增不减、删除不回收」。
     */
    private static final String KEY_NEXT_ID = "nextId";

    /** 区域里记录「已解析门底坐标」的可选字段名。 */
    private static final String PORTAL_KEY = "portal";

    private final List<PortalBinding> bindings = new CopyOnWriteArrayList<>();

    /**
     * 下一条绑定的自增数字 ID。
     *
     * <p>只增不减：删掉条目<b>不回收</b>号，所以「按钮上的 ID 指向了别人」永远不会发生
     * —— 删掉的号就彻底空了，不会再有新绑定顶上去。
     *
     * <p>所有增删都发生在服务端主线程（指令执行 / 配置重载），读方（{@code show}、
     * Tab 补全）也在主线程，所以普通 {@code int} 就够，不需要原子类。
     */
    private int nextId = 1;

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
     * 全部 ID，按当前列表顺序；只给 Tab 补全用（命令层一敲 Tab 就取一次最新快照）。
     */
    public List<String> getIds() {
        List<String> ids = new ArrayList<>(bindings.size());
        for (PortalBinding binding : bindings) {
            if (binding.id() != null) {
                ids.add(binding.id());
            }
        }
        return ids;
    }

    /**
     * 按 ID 取一条；找不到返回 {@code null}。
     *
     * <p>ID 是<b>字符串</b>（{@code "1"}、{@code "2"}…），指令参数直接拿它比，
     * 不做数字解析：这样手改配置文件写成非数字的 ID 也照样能查到，不会因为
     * {@code Integer.parseInt} 抛异常而炸掉指令。
     */
    public @Nullable PortalBinding getById(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (PortalBinding binding : bindings) {
            if (id.equals(binding.id())) {
                return binding;
            }
        }
        return null;
    }

    /**
     * 按 ID 删除一条并立刻落盘；找不到返回 {@code null}。
     *
     * <p>ID 只增不回退：删掉的号不会分给别人，所以「别人手里那个 ID 突然指到新条目」
     * 这种情况从根上不存在，不需要再做列表版本校验。
     */
    public @Nullable PortalBinding removeById(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (int i = 0; i < bindings.size(); i++) {
            PortalBinding binding = bindings.get(i);
            if (!id.equals(binding.id())) {
                continue;
            }
            PortalBinding removed = bindings.remove(i);
            save();
            log.info("[{}] 已删除 #{} 绑定：{}", SECTION, id, describe(removed));
            return removed;
        }
        return null;
    }

    /**
     * 加一条并立刻落盘，ID 由本类分配（{@code 最大 ID + 1}）。
     *
     * <p>已经有一模一样的（{@link PortalBinding#of} 会归一化角点顺序，所以角点反着填也算同一条）
     * 就什么都不做，免得重复 {@code bind} 把文件写出一堆重复项。
     * 去重只看坐标：{@link PortalBinding#equals} 不比较 ID。
     *
     * <p><b>不按坐标值判「占位」</b>：{@code (0,0,0)} 在超平坦 / 自定义维度里是合法位置，
     * 「未设置」的判定由 {@code PortalSelectionCache} 的 {@code null} 负责。
     * 这里只拦结构上不可能的数据：{@code null}、两端维度相同。
     *
     * @return 真的新增了返回 true；非法 / 重复 / 同维度一律 false
     */
    public boolean add(PortalBinding binding) {
        if (binding == null) {
            log.warn("[{}] add(null)，已忽略", SECTION);
            return false;
        }
        if (binding.dimA().equals(binding.dimB())) {
            // 一条绑定靠「两个维度键」表达两端，同维度之间的绑定这种格式装不下
            log.warn("[{}] 两端维度相同（{}），存不进配置文件，已忽略",
                    SECTION, binding.dimA().identifier());
            return false;
        }
        if (bindings.contains(binding)) {
            return false;
        }
        PortalBinding stored = binding.withId(allocateId());
        bindings.add(stored);
        save();
        log.info("[{}] 已写入 #{} 绑定：{}", SECTION, stored.id(), describe(stored));
        return true;
    }

    /**
     * 按下标取一条；越界返回 {@code null}。
     *
     * <p>只给「内部先按 equals 定位、再取最新副本」这类流程用；
     * 指令一律走 {@link #getById}，序号不再是对外的标识。
     */
    public PortalBinding get(int index) {
        return (index >= 0 && index < bindings.size()) ? bindings.get(index) : null;
    }

    /**
     * 清空全部绑定并落盘。
     *
     * <p>{@link #nextId} <b>不</b>重置：清空之后新绑的还是接着最大的号往上走，
     * 和「删除不回收号」是同一个约定。
     */
    public void clear() {
        if (bindings.isEmpty()) {
            return;
        }
        int removed = bindings.size();
        bindings.clear();
        save();
        log.info("[{}] 已清空 {} 条绑定", SECTION, removed);
    }

    /**
     * 回写某侧「已解析门底坐标」缓存并立刻落盘。
     *
     * <p>定位依赖 {@link PortalBinding#equals} —— 它<b>只比较 6 个原始字段、忽略 ID 与缓存</b>，
     * 所以传入的 {@code binding} 即使是「扫描前拿到的旧副本」，也能精确命中原条目。
     *
     * <p>门被拆后缓存失效无需主动监听：传送时验证 {@code getBlockState(cached)} 不是门，
     * 会走扫描，扫描成功后再次调用本方法把新坐标覆盖回来。
     *
     * @param binding   要更新的绑定（equals 匹配，ID / 缓存字段不参与）
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
     * @param binding 要更新的绑定（equals 匹配，ID / 缓存字段不参与）
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
        // withPortalA/withPortalB 会保留 ID，别用 new PortalBinding(...) 把 ID 丢了
        bindings.set(idx, old.withPortalA(null).withPortalB(null));
        save();
        log.debug("[{}] 已清空缓存：{}", SECTION, describe(binding));
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
     *
     * <p><b>旧存档的 ID 迁移</b>（这里也是「不会重号」的全部保证）：
     * <ol>
     *     <li>第一遍只<b>认领</b>文件里已经写了的 ID，且必须「不重复」——
     *         手改出两条 {@code "id": "3"} 时只有第一条认领成功，
     *         第二条视为「没有 ID」，不会出现两个 {@code #3}；</li>
     *     <li>第二遍给「没 ID / ID 重复」的条目补号，起点是<b>已认领数字 ID 的最大值 + 1</b>，
     *         逐个自增并跳过已被占用的号，所以补出来的号必然比文件里任何数字 ID 都大，
     *         不可能撞上已认领的；</li>
     *     <li>{@link #nextId} 取「全部数字 ID 的最大值 + 1」与「文件里记的 {@code nextId}」
     *         两者中的较大者 —— 后者保证「删掉最大的号再重启」也不会把那个号重新发出去；
     *         之后 {@link #add} 只会更大，删除也不回收，所以新 ID 永远不会复用旧号。</li>
     * </ol>
     * 非数字的 ID（手改出来的 {@code "abc"}）不参与「最大值」计算，但同样会被保留 ——
     * 指令按字符串比较，照样能查到。
     */
    private void load() {
        bindings.clear();
        nextId = 1;

        JsonObject section = UnifiedConfigFile.getSection(SECTION);
        JsonElement element = section.get(BINDINGS_KEY);
        int skipped = 0;
        int duplicated = 0;
        int oversized = 0;
        int assigned = 0;

        List<PortalBinding> parsed = new ArrayList<>();
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
                if (parsed.contains(binding)) {
                    duplicated++;
                    continue;
                }
                parsed.add(binding);
            }
        }

        // ---- 第一遍：认领文件里已写且不重复的 ID ----
        List<PortalBinding> withIds = new ArrayList<>(parsed.size());
        Set<String> usedIds = new HashSet<>();
        int maxNumericId = 0;
        for (PortalBinding binding : parsed) {
            String id = binding.id();
            if (id != null && usedIds.add(id)) {
                withIds.add(binding);
                maxNumericId = Math.max(maxNumericId, numericOrZero(id));
            } else {
                withIds.add(binding.withId(null));
                assigned++;
            }
        }

        // ---- 第二遍：给缺失 / 重复的补号，从最大数字 ID + 1 起 ----
        int cursor = maxNumericId + 1;
        for (int i = 0; i < withIds.size(); i++) {
            PortalBinding binding = withIds.get(i);
            if (binding.id() != null) {
                continue;
            }
            String id;
            do {
                id = String.valueOf(cursor++);
            } while (usedIds.contains(id));
            usedIds.add(id);
            withIds.set(i, binding.withId(id));
        }

        // ---- 落进列表，并让计数器停在「最大号」和「文件里记的计数器」两者之后 ----
        for (PortalBinding binding : withIds) {
            if (!binding.withinSizeLimits()) {
                oversized++;
            }
            bindings.add(binding);
            maxNumericId = Math.max(maxNumericId, numericOrZero(binding.id()));
        }
        // 取两者较大值：文件里记的 nextId 更大 = 曾经删过最大的号，不能退回去重发
        nextId = Math.max(maxNumericId + 1, storedNextId(section));

        // 写回规范化结果：旧存档在这里就被补上 id 了（父类收尾会落盘）
        section.add(BINDINGS_KEY, toJsonArray());
        section.addProperty(KEY_NEXT_ID, nextId);

        if (skipped > 0) {
            log.warn("[{}] 有 {} 条记录读不懂，已跳过；落盘后文件里只剩能用的", SECTION, skipped);
        }
        if (duplicated > 0) {
            log.warn("[{}] 有 {} 条重复记录，已合并；落盘后文件里每条绑定只留一份", SECTION, duplicated);
        }
        if (assigned > 0) {
            log.info("[{}] 有 {} 条记录没有 ID（旧存档 / 手改重复），已按顺序补号，下一个 ID 是 {}",
                    SECTION, assigned, nextId);
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

    /**
     * 分配下一个 ID。
     *
     * <p>{@code nextId} 只会单调递增，正常情况下不会撞号；这里的 {@code while} 只是
     * 防手改配置写出 {@code "999999"} 之外的意外情况（比如非数字 ID 恰好等于下一个数字）。
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
        section.add(BINDINGS_KEY, toJsonArray());
        // 计数器一起落盘：否则「删掉最大的号 → 重启」会让那个号被重新发出去
        section.addProperty(KEY_NEXT_ID, nextId);
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
        if (binding.id() != null) {
            obj.addProperty(KEY_ID, binding.id());
        }
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
     * 一个对象 = 一条绑定：{@code id} 之外的每个键都是一个「维度 → 区域」条目。
     *
     * <p>必须正好两条、且两条都读得懂 —— 少一条宁可整条丢掉，也不留半个绑定在内存里。
     * {@code id} 是元数据，遍历时要显式跳过，否则会被当成读不懂的维度。
     *
     * <p>两个维度键的读取顺序即 A / B 的顺序；Gson 的 {@code JsonObject} 保插入顺序，
     * 而 {@link #toJson} 固定先写 A 后写 B，所以「写入-读取」两侧顺序自洽，
     * {@code portalA} / {@code portalB} 不会错位。
     *
     * <p>没有 {@code id}（旧存档）时返回 {@code id == null}，由 {@link #load} 统一补号。
     */
    private static PortalBinding fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        String id = idFromJson(obj.get(KEY_ID));

        Side first = null;
        Side second = null;
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (KEY_ID.equals(entry.getKey())) {
                continue; // 元数据字段，不是维度
            }
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
        // 先 of() 归一化（min/max 乱序也能修），再挂上 ID 与两侧缓存
        PortalBinding normalized = PortalBinding.of(
                first.dimension(), first.min(), first.max(),
                second.dimension(), second.min(), second.max());
        return normalized
                .withId(id)
                .withPortalA(first.portal())
                .withPortalB(second.portal());
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
        return "#" + binding.id() + " "
                + binding.dimA().identifier() + " " + format(binding.minA()) + "~" + format(binding.maxA())
                + " <-> " + binding.dimB().identifier() + " " + format(binding.minB()) + "~" + format(binding.maxB())
                + (binding.portalA() != null || binding.portalB() != null
                ? " [cached A=" + format(binding.portalA()) + ", B=" + format(binding.portalB()) + "]"
                : "");
    }

    private static String format(@Nullable BlockPos pos) {
        return pos == null ? "null" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
