package com.villagerdetails.config.impl;

import com.villagerdetails.config.impl.base.AbstractWorldConfig;
import com.villagerdetails.config.impl.data.VillagerTradeIndexJson;
import com.villagerdetails.config.impl.dto.ParsedIndex;
import com.villagerdetails.pojo.dto.VillagerTradeInfo;
import com.villagerdetails.util.json.JsonIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.villagerdetails.config.impl.data.VillagerTradeIndexJson.parse;

/**
 * 村民交易索引的落盘存储（存档内 {@code villagerdetails/villager_trade_cache.json}）。
 *
 * <p>生命周期与 IO 全部来自 {@link AbstractWorldConfig}（和 {@code RuleConfig} 同一套），
 * 本类只回答「写哪个文件、怎么把 JSON 读成缓存、清空成什么样」，外加一个它特有的差异：
 * <b>落盘周期是 1200 tick 而不是每 tick</b>（见 {@link #flushIntervalTicks()}）。
 *
 * <p><b>JSON 长什么样不归本类管</b>：字段结构、容错、旧格式作废全部在
 * {@link VillagerTradeIndexJson}（纯「内存 ⇄ JSON」转换）。本类只负责
 * 「什么时候读、什么时候写、写哪个文件」。
 */
public final class VillagerTradeCacheConfig extends AbstractWorldConfig {

    // 全局唯一实例
    public static final VillagerTradeCacheConfig INSTANCE = new VillagerTradeCacheConfig();

    private static final Logger log = LogManager.getLogger(VillagerTradeCacheConfig.class);

    /** 相对存档根目录的文件位置：{@code <world>/villagerdetails/villager_trade_cache.json}。 */
    private static final String SAVE_PATH = "villagerdetails/villager_trade_cache.json";

    /**
     * 村民 UUID → 交易快照。
     *
     * <p>用 {@link ConcurrentHashMap} 而不是普通 {@code Map}：写方是服务端线程池里解析出来的
     * 异步快照，读方是主线程上的搜索命令，两边不共享锁。
     */
    private final ConcurrentHashMap<UUID, VillagerTradeInfo> cache = new ConcurrentHashMap<>();

    /**
     * 私有构造：全局只允许 {@link #INSTANCE} 存在一个实例。
     *
     * <p>构造时父类会把它登记进静态表，因此 {@link AbstractWorldConfig#tickAll()} /
     * {@link AbstractWorldConfig#flushAll()} / {@link AbstractWorldConfig#resetAll()} 自动覆盖到它。
     */
    private VillagerTradeCacheConfig() {
    }

    // ==============================================================
    // 父类要求的三件事 + 数据
    // ==============================================================

    /**
     * {@inheritDoc}
     *
     * <p>本配置固定写在存档的 {@code villagerdetails/villager_trade_cache.json}。
     */
    @Override
    protected String savePath() {
        return SAVE_PATH;
    }

    /**
     * {@inheritDoc}
     *
     * <p>把整份缓存交给 {@link VillagerTradeIndexJson#encode} 转成 JSON 树；
     * 真正的序列化（Gson）由父类在主线程瞬间完成，所以这里不能返回会变的共享对象。
     */
    @Override
    protected Object getDataToSave() {
        return VillagerTradeIndexJson.encode(cache);
    }

    /**
     * {@inheritDoc}
     *
     * <p>注册名 / {@code /ec c reload <name>} 里用的名字。
     */
    @Override
    public String configName() {
        return "VillagerTradeCacheConfig";
    }

    /**
     * 落盘周期：1200 tick ≈ 1 分钟。
     *
     * <p>只有它需要节流：{@link #getDataToSave()} 要把整份村民索引转成 JSON，
     * 而区块卸载时每个村民都会 {@link #put} 一次 —— 每 tick 都试一次等于每 tick 整份重写。
     *
     * <p>父类的计数器初值是 1，所以开服后<b>第一个 tick</b> 仍会提交一次，
     * 保证文件一定被创建出来，不用先白等一分钟。
     */
    @Override
    protected int flushIntervalTicks() {
        return 200;
    }

    /**
     * 磁盘 → 内存：把上一次会话落盘的索引读回 {@link #cache}。
     *
     * <p>前期失败一律<b>直接返回、不动内存</b>（开服时缓存本来就是空的，等于按空缓存继续），
     * 避免一次坏文件把已经抓到的数据抹掉：
     * <ul>
     *     <li>没有 server（停服后）/ 文件不存在 → 打日志后返回；</li>
     *     <li>读盘抛异常（IO / 权限）→ 记 WARN 后返回；</li>
     *     <li>文件能读通 → 整体替换成文件里的内容；其中坏记录已被
     *         {@link VillagerTradeIndexJson#parse} 逐条跳过并计入 {@code skipped}，
     *         这里据此 {@link #markDirty()}，让下一次落盘把文件重写干净。</li>
     * </ul>
     */
    @Override
    protected void load() {
        Path target = currentFile();
        if (target == null) return;

        if (!Files.exists(target)) {
            log.info("村民交易索引暂无落盘文件：{}", target);
            return;
        }

        ParsedIndex parsed;
        try {
            parsed = parse(JsonIO.readObject(target));
        } catch (Exception e) {
            log.warn("读取村民交易索引失败，本次按空缓存继续（文件：{}）", target, e);
            return;
        }

        cache.clear();
        cache.putAll(parsed.villagers());

        if (parsed.skipped() > 0) {
            markDirty(); // 有坏数据被剔除：标脏，下次 flush 时重写文件
            log.info("村民交易索引里有 {} 条坏记录已被剔除，将在下一次落盘时重写文件", parsed.skipped());
        }
        log.info("已从 {} 加载村民交易索引：{} 只村民", target, cache.size());
    }

    /**
     * {@inheritDoc}
     *
     * <p>只清内存，不碰文件 —— 停服后磁盘上的索引要原样留着给下一次会话读回来。
     */
    @Override
    protected void clearData() {
        cache.clear();
    }

    // ==============================================================
    // 业务方法
    // ==============================================================

    /**
     * 直接暴露底层容器（{@code VillagerTradeCache} 的读写都穿透到这里）。
     *
     * <p>返回的是<b>活引用</b>，调用方对它的修改不经过 {@link #put}，因此不会自动标脏。
     * 新增数据请一律走 {@link #put}。
     */
    public ConcurrentHashMap<UUID, VillagerTradeInfo> cache() {
        return cache;
    }

    /**
     * 更新一只村民的交易快照，并标脏等下一次落盘周期提交。
     *
     * <p><b>只改内存</b>：这里绝不碰磁盘，也不阻塞；真正的字节写入在异步单写者线程上完成。
     *
     * @param uuid 村民 UUID
     * @param info 该村民的交易快照（不可变 DTO，可安全跨线程传递）
     */
    public void put(UUID uuid, VillagerTradeInfo info) {
        cache.put(uuid, info);
        markDirty();
    }

    /**
     * 清空内存并清掉脏标记（规则关闭 / 会话结束时用）。
     *
     * <p>调用方负责先 {@link #flush()}：本方法只负责让「内存里已经没有东西」这件事
     * 不要被当成一次待落盘的改动写出去。
     */
    public void clearMemory() {
        cache.clear();
        clearDirty();
    }
}
