package com.villagerdetails.config.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.villagerdetails.concurrent.ModContext;
import com.villagerdetails.concurrent.ModThreadPool;
import com.villagerdetails.config.impl.util.ConfigPaths;
import com.villagerdetails.util.io.JsonIO;
import com.villagerdetails.pojo.dto.TradeItemInfo;
import com.villagerdetails.pojo.dto.VillagerTradeInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 村民交易索引的<b>落盘存储</b>（存档内 {@code villagerdetails/villager_trade_cache.json}）。
 *
 * <p>和 {@link IdTranslationConfig} 是同一套模式：<b>内存是唯一数据源，磁盘只是它的副本</b>。
 * 区别在于写入者是<b>线程池里的异步解析</b>，所以这里多了三道保护：
 *
 * <ol>
 *     <li><b>标脏而不是立刻写</b>：{@link #markDirty()} 只置一个原子标志（O(1)、无锁）。
 *         区块卸载时每个村民都会 {@code put} 一次，如果每次都碰磁盘，卸载一次区块就是几百次 I/O；</li>
 *     <li><b>定时 / 停服才落盘</b>：{@link #flushAsync(MinecraftServer)} 每 1200 tick 由
 *         {@code VillagerTradeSearchEvents} 调一次，把 {@code new HashMap<>(cache)} 的快照丢进
 *         服务端线程池序列化写盘 —— <b>主线程永远不做 I/O</b>；停服 / 关规则时走
 *         {@link #flushSync()}（那时已经不需要考虑卡顿了，而且线程池可能先被关掉）；</li>
 *     <li><b>代际号防止丢写</b>：写盘期间完全可能又有新的村民进来。写完只在
 *         「代际号没变」时才清脏标志，否则下一次定时任务会把这些新数据再写一遍
 *         —— 用 {@link AtomicBoolean} 一个标志搞不定这件事，所以额外维护
 *         {@link #generation}（每次 {@link #markDirty()} +1）。</li>
 * </ol>
 *
 * <p><b>写盘是原子的</b>（{@link JsonIO#writeObject} 临时文件 + 整体替换），且用
 * {@link #writeLock} 串行化：定时任务和停服兜底可能同时发生，两份 JSON 交叉写会互相截断。
 *
 * <p>读盘失败（文件损坏 / 版本不兼容）只打 WARN 并留空，<b>绝不崩服</b> —— 这是一份缓存，
 * 丢了顶多重新积累。
 */
public final class VillagerTradeStorage {

    private VillagerTradeStorage() {
    }

    public static final VillagerTradeStorage INSTANCE = new VillagerTradeStorage();

    private static final Logger log = LogManager.getLogger(VillagerTradeStorage.class);

    /**
     * 存档内相对路径（和 {@code trade_index.json} 放在同一个目录下）。
     */
    private static final String SAVE_RELATIVE_PATH = "villagerdetails/villager_trade_cache.json";

    /**
     * 文件格式版本。以后字段不兼容时靠它决定「整个丢掉」还是「迁移」。
     */
    private static final int FORMAT_VERSION = 1;

    /**
     * 主缓存：村民 UUID → 交易快照。
     *
     * <p>异步写、主线程读，必须是并发容器；读取不要求强一致（搜索到一份马上要消失的快照也无害）。
     */
    private final ConcurrentHashMap<UUID, VillagerTradeInfo> cache = new ConcurrentHashMap<>();

    /**
     * 有没有「还没写进磁盘」的改动。
     */
    private final AtomicBoolean dirty = new AtomicBoolean();

    /**
     * 写代际：每次 {@link #markDirty()} +1。写盘开始时记下值，写完如果没变才清脏标志。
     */
    private final AtomicLong generation = new AtomicLong();

    /**
     * 串行化写盘（定时任务 / 停服兜底可能撞车）。
     */
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * 目标文件；{@link #load(MinecraftServer)} 之前是 {@code null}（此时不写盘）。
     */
    private volatile Path file;

    // ============================================================
    // 内存缓存
    // ============================================================

    /**
     * 内存缓存本体（<b>唯一数据源</b>）。读操作直接走它，不要拷贝。
     */
    public ConcurrentHashMap<UUID, VillagerTradeInfo> cache() {
        return cache;
    }

    /**
     * 落盘目标文件；还没 {@link #load} 过时返回 {@code null}。
     */
    public Path file() {
        return file;
    }

    // ============================================================
    // 脏标记
    // ============================================================

    public void markDirty() {
        dirty.set(true);
        generation.incrementAndGet();
    }

    public void markClean() {
        dirty.set(false);
    }

    public boolean isDirty() {
        return dirty.get();
    }

    // ============================================================
    // 读盘
    // ============================================================

    /**
     * 服务端启动时调一次：记下文件路径，把上一次会话写下的索引读回内存。
     *
     * <p>读失败 / 结构不对 / 版本不认识 → WARN + 留空缓存（不崩服）。
     */
    public void load(MinecraftServer server) {
        if (server == null) {
            return;
        }
        Path target = ConfigPaths.world(server, SAVE_RELATIVE_PATH);
        file = target;

        if (!Files.exists(target)) {
            log.info("村民交易索引暂无落盘文件（首次运行）：{}", target);
            markClean();
            return;
        }

        Map<UUID, VillagerTradeInfo> loaded;
        try {
            loaded = parse(JsonIO.readObject(target));
        } catch (Exception e) {
            log.warn("读取村民交易索引失败，本次按空缓存继续（文件：{}）", target, e);
            markClean();
            return;
        }

        cache.clear();
        cache.putAll(loaded);
        markClean();
        log.info("已从 {} 加载村民交易索引：{} 只村民", target, cache.size());
    }

    /**
     * 反序列化：{@code villagers} 数组里每一项是一只村民。
     *
     * <p>单条坏数据只跳过自己（并计数），不影响其它条目 —— 手改文件写坏一个 UUID
     * 不该让整个索引作废。
     */
    private static Map<UUID, VillagerTradeInfo> parse(JsonObject root) {
        Map<UUID, VillagerTradeInfo> result = new HashMap<>();

        int format = root.has("format") && root.get("format").isJsonPrimitive()
                ? root.get("format").getAsInt() : FORMAT_VERSION;
        if (format != FORMAT_VERSION) {
            log.warn("村民交易索引格式版本不匹配（文件 {}，期望 {}），本次按空缓存继续",
                    format, FORMAT_VERSION);
            return result;
        }

        JsonElement villagersElement = root.get("villagers");
        if (villagersElement == null || !villagersElement.isJsonArray()) {
            return result;
        }

        int skipped = 0;
        for (JsonElement element : villagersElement.getAsJsonArray()) {
            try {
                VillagerTradeInfo info = parseVillager(element.getAsJsonObject());
                if (info != null) {
                    result.put(info.uuid(), info);
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("村民交易索引里有 {} 条记录解析失败，已跳过（其余 {} 条正常）", skipped, result.size());
        }
        return result;
    }

    private static VillagerTradeInfo parseVillager(JsonObject object) {
        UUID uuid = UUID.fromString(object.get("uuid").getAsString());
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(object.get("dimension").getAsString()));

        JsonArray posArray = object.getAsJsonArray("pos");
        if (posArray == null || posArray.size() < 3) {
            return null;
        }
        BlockPos pos = new BlockPos(posArray.get(0).getAsInt(),
                posArray.get(1).getAsInt(), posArray.get(2).getAsInt());

        List<TradeItemInfo> offers = new ArrayList<>();
        JsonElement offersElement = object.get("offers");
        if (offersElement != null && offersElement.isJsonArray()) {
            for (JsonElement offerElement : offersElement.getAsJsonArray()) {
                TradeItemInfo offer = parseOffer(offerElement.getAsJsonObject());
                if (offer != null) {
                    offers.add(offer);
                }
            }
        }
        if (offers.isEmpty()) {
            return null;
        }
        return new VillagerTradeInfo(uuid, pos, dimension, offers);
    }

    private static TradeItemInfo parseOffer(JsonObject object) {
        String resultItemId = string(object, "result");
        if (resultItemId.isBlank()) {
            return null;
        }
        return new TradeItemInfo(
                resultItemId,
                intValue(object, "resultCount"),
                string(object, "price1"),
                intValue(object, "price1Count"),
                string(object, "price2"),
                intValue(object, "price2Count"),
                string(object, "display"),
                string(object, "search"));
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static int intValue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? 0 : element.getAsInt();
    }

    // ============================================================
    // 写盘
    // ============================================================

    /**
     * 异步落盘：脏了才写，序列化 + I/O 全在服务端线程池里做。
     *
     * <p>由 {@code VillagerTradeSearchEvents} 每 1200 tick 调一次；主线程只做
     * {@code new HashMap<>(cache)} 这一次拷贝（几十上百条记录，微秒级）。
     *
     * <p>线程池不可用（停服过程中）时什么都不做 —— 那时会走 {@link #flushSync()}。
     */
    public void flushAsync(MinecraftServer server) {
        if (!dirty.get() || file == null) {
            return;
        }
        ModThreadPool pool = ModContext.getServerPool();
        if (pool == null || pool.isShutdown()) {
            return;
        }

        Map<UUID, VillagerTradeInfo> snapshot = new HashMap<>(cache);
        long capturedGeneration = generation.get();
        Path target = file;

        pool.execute(() -> writeSnapshot(snapshot, target, capturedGeneration, false));
    }

    /**
     * 同步落盘（停服 / 关规则时兜底）。
     *
     * <p>只在服务端收尾路径调用：那时线程池可能已经关了，而「最后一分钟的交易」必须存下来。
     */
    public void flushSync() {
        if (!dirty.get() || file == null) {
            return;
        }
        writeSnapshot(new HashMap<>(cache), file, generation.get(), true);
    }

    /**
     * 真正写盘：序列化 + {@link JsonIO#writeObject}（原子写）。任何异常只记日志。
     *
     * @param snapshot          写盘用的快照（普通 HashMap，已脱离并发容器）
     * @param target            目标文件
     * @param capturedGeneration 快照对应的写代际；写完代际没变才清脏标志
     * @param sync              {@code true} 表示调用方就在主线程（停服），日志按同步路径打
     */
    private void writeSnapshot(Map<UUID, VillagerTradeInfo> snapshot, Path target,
                               long capturedGeneration, boolean sync) {
        writeLock.lock();
        try {
            JsonObject root = toJson(snapshot);
            JsonIO.writeObject(target, root);

            if (generation.get() == capturedGeneration) {
                markClean();
            }
            log.debug("村民交易索引已落盘（{}）：{} 只村民，{}",
                    sync ? "同步" : "异步", snapshot.size(), target);
        } catch (Exception e) {
            // 写失败不清脏标志：下一轮定时任务会重试
            log.error("写入村民交易索引失败：{}", target, e);
        } finally {
            writeLock.unlock();
        }
    }

    private static JsonObject toJson(Map<UUID, VillagerTradeInfo> snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        root.addProperty("savedAt", System.currentTimeMillis());

        JsonArray villagers = new JsonArray();
        // 顺序固定（UUID 排序），同一个存档每次写出来的文件内容 / diff 都是稳定的
        snapshot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> villagers.add(villagerToJson(entry.getValue())));
        root.add("villagers", villagers);
        return root;
    }

    private static JsonObject villagerToJson(VillagerTradeInfo info) {
        JsonObject object = new JsonObject();
        object.addProperty("uuid", info.uuid().toString());
        object.addProperty("dimension", info.dimension().identifier().toString());

        JsonArray pos = new JsonArray(3);
        pos.add(info.pos().getX());
        pos.add(info.pos().getY());
        pos.add(info.pos().getZ());
        object.add("pos", pos);

        JsonArray offers = new JsonArray();
        for (TradeItemInfo offer : info.offers()) {
            JsonObject item = new JsonObject();
            item.addProperty("result", offer.resultItemId());
            item.addProperty("resultCount", offer.resultCount());
            item.addProperty("price1", offer.price1Id());
            item.addProperty("price1Count", offer.price1Count());
            item.addProperty("price2", offer.price2Id());
            item.addProperty("price2Count", offer.price2Count());
            item.addProperty("display", offer.displayText());
            item.addProperty("search", offer.searchText());
            offers.add(item);
        }
        object.add("offers", offers);
        return object;
    }

    /**
     * 只清内存（停服 / 关规则后在 {@code clearSession} 里调）。磁盘上的文件<b>保留</b>，
     * 下次启动由 {@link #load(MinecraftServer)} 读回来。
     */
    public void clearMemory() {
        cache.clear();
        markClean();
    }
}
