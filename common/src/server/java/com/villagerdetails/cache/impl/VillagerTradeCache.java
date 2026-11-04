package com.villagerdetails.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.config.impl.VillagerTradeStorage;
import com.villagerdetails.pojo.dto.TradeItemInfo;
import com.villagerdetails.pojo.dto.VillagerTradeInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全存档「已锁定交易」的村民交易索引（<b>内存是唯一数据源</b>）。
 *
 * <p>写入方只有一类：区块卸载（{@code ENTITY_UNLOAD} 主入口 + {@code CHUNK_UNLOAD} 兜底）时
 * 主线程抓快照 → 丢进服务端线程池 → 异步解析完 {@link #put}。
 * 读取方也只有一条：{@code /ec c TradeSearch <关键词>} 直接遍历这里，<b>不再碰世界</b>，
 * 所以查询是 O(缓存条目数)，不会因为全图村民卡主线程。
 *
 * <p><b>数据现在会落盘</b>：内存容器由 {@link VillagerTradeStorage} 持有
 * （{@code <存档>/villagerdetails/villager_trade_cache.json}），这里只做一层代理 ——
 * 读直接穿透，写顺手 {@link VillagerTradeStorage#markDirty()}，真正的 I/O 由存储类
 * 在落盘周期（{@code AbstractWorldConfig#tickAll} 统一驱动）/ 停服时异步完成。
 * 之所以不做成「两份 map」，是为了不出现「查到的和存下的不是同一份」。
 *
 * <p><b>为什么还要一张墓碑表</b>：区块卸载在主线程提交异步任务，村民完全可能在任务真正执行前
 * 就死了 —— 死亡回调在主线程先 {@link #remove}，随后迟到的异步任务如果直接 {@code put}，
 * 就会把死掉的村民重新塞回缓存。{@link #put(VillagerTradeInfo, long)} 用「快照时间戳 &gt; 移除时间戳」
 * 拦住这种迟到写入；时间戳取 {@link System#nanoTime()}（单调递增，跨线程可比）。
 * 墓碑表<b>不落盘</b>：重启后重新记录即可，存下去只会变成垃圾数据。
 *
 * <p><b>快照之间也要比新旧</b>：两个抓取入口 + 2~4 条 worker，同一只村民的两份快照完全可能
 * 乱序到达。{@link #LAST_SNAPSHOT} 只放行「严格更新」的那一份，否则旧坐标 / 旧交易表会
 * 盖掉刚写进去的新数据（时间戳同样取 {@code nanoTime}）。
 *
 * <p><b>清空与写入互斥</b>：{@link #clearSession()} 清内存的一瞬间，可能还有在途的
 * {@link #put} 已经通过前置检查、但还没写进内存。两者靠 {@link #EPOCH} 纪元 +
 * {@link #SESSION_LOCK} 互斥：迟到的那一条会看到纪元变化而放弃，不会在清空之后
 * 凭空多出一条、再被下一次落盘拿去覆盖整份文件。
 */
public final class VillagerTradeCache implements SessionState {

    public static final VillagerTradeCache INSTANCE = new VillagerTradeCache();

    private static final Logger log = LogManager.getLogger(VillagerTradeCache.class);

    /**
     * 墓碑：村民 UUID → 死亡（移除）时刻的 {@link System#nanoTime()}。
     *
     * <p>只用于挡住「提交在前、死亡在后、执行在最后」的迟到异步任务。
     * 数量上限 = 本会话内死掉的村民数，停服时整张表清空。
     */
    private static final Map<UUID, Long> REMOVED_AT = new ConcurrentHashMap<>();

    /**
     * 每个村民「已经写进索引的最新快照时刻」：村民 UUID → {@link System#nanoTime()}。
     *
     * <p><b>为什么要它</b>：抓快照有 {@code ENTITY_UNLOAD} 与 {@code CHUNK_UNLOAD} 两个入口，
     * 而池子有 2~4 条 worker —— 同一只村民的两份快照完全可能<b>乱序到达</b>。
     * 直接 {@code store().put(uuid, info)} 是「谁最后跑谁赢」，旧坐标 / 旧交易表会盖掉新的。
     * 所以只有「严格更新」的那一份才允许写：{@code snapshotNanos > LAST_SNAPSHOT[uuid]}。
     *
     * <p>写索引同时清墓碑的逻辑不变：能走到写入，就说明这份数据比死亡记录还新。
     *
     * <p>数量上限 = 本会话内进过索引的村民数（和主缓存同量级），停服 / 关规则时整张表清空。
     */
    private static final Map<UUID, Long> LAST_SNAPSHOT = new ConcurrentHashMap<>();

    /**
     * 最近一次整份清空（停服 / 规则关闭）的时刻。
     *
     * <p>清空之后才执行完的异步任务，如果快照是在清空<b>之前</b>抓的，也要丢掉，
     * 否则「规则关了 / 停服了，缓存又冒出来一条」。
     */
    private static volatile long clearedAt = Long.MIN_VALUE;

    /**
     * 清理纪元：{@link #clearSession()} 每清一次 +1，{@link #put} 在真正写进内存之前比对它。
     *
     * <p>为什么 {@link #clearedAt} 不够：它是「先检查、后写入」的典型 check-then-act ——
     * 一条在途的 {@code put} 完全可能在上面的检查通过之后、{@code store().put()} 之前被挂起，
     * 等 {@link #clearSession()} 清完内存才继续执行，于是清空之后又冒出一条并标脏，
     * 下一次 {@code flush} 就拿这份「接近空」的内存覆盖整份落盘文件（规则关一下再开 = 索引全丢）。
     *
     * <p>纪元只在 {@link #SESSION_LOCK} 里读写，「比较纪元 + 写内存」和「推进纪元 + 清内存」
     * 因此是互斥的，那条迟到任务要么看到纪元变化而放弃，要么真的发生在清空之后（那是新数据，该留）。
     */
    private static final AtomicLong EPOCH = new AtomicLong();

    /**
     * 串行化「清空内存」与「写入内存」这两件互斥的事（见 {@link #EPOCH}）。
     *
     * <p>只保护很短的一段临界区（几次 map 操作 + 两个原子写），不和 {@code writeLock}（磁盘写）
     * 嵌套，所以不会和存储类的写盘互锁。
     */
    private static final Object SESSION_LOCK = new Object();

    /**
     * 墓碑 / 快照时刻表的清理阈值：条目数到这个量级就做一次过期清理。
     *
     * <p>两张表都是「每个村民一条」，正常规模下和索引同量级；但在刷怪塔 / 村民牧场里，
     * 村民不断生成又死去，墓碑<b>只增不减</b>，单个会话能涨到几十万条 UUID。
     */
    private static final int STALE_ENTRY_PRUNE_THRESHOLD = 512;

    /**
     * 过期时长：超过它还没被用到的墓碑 / 快照时刻<b>一定</b>没用了。
     *
     * <p>在途快照从「主线程抓取」到「worker 执行 put」只有几秒（队列 64 条、worker 2~4 条），
     * 所以 10 分钟前的记录不可能再参与任何比较。
     */
    private static final long STALE_ENTRY_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);

    private VillagerTradeCache() {
    }

    /**
     * 内存缓存本体（存储类持有）。这里冗余一份引用，只是为了让读路径少一层跳转。
     */
    private static ConcurrentHashMap<UUID, VillagerTradeInfo> store() {
        return VillagerTradeStorage.INSTANCE.cache();
    }

    // ============================================================
    // 写
    // ============================================================

    /**
     * 存入 / 覆盖一只村民的快照（没有快照时间戳，等价于「当前时刻抓的」）。
     *
     * <p>给测试或将来其它同步抓取路径用；正常异步管线走
     * {@link #put(VillagerTradeInfo, long)}。
     */
    public void put(VillagerTradeInfo info) {
        put(info, System.nanoTime());
    }

    /**
     * 存入一只村民的快照，并拒绝所有已经作废的迟到写入。
     *
     * <p>四条拒绝规则（前三条都要在 {@code store().put} 之前判掉）：
     * <ol>
     *     <li>快照早于最近一次整份清空（{@link #clearedAt}）→ 清理之后才跑完的作废数据；</li>
     *     <li>快照早于村民死亡时刻（{@link #REMOVED_AT}）→ 墓碑挡住迟到写，避免把死掉的村民塞回来；</li>
     *     <li>快照不严格新于已存的快照（{@link #LAST_SNAPSHOT}）→ 双入口 + 多 worker 乱序时，
     *         不让旧坐标 / 旧交易表覆盖新的；</li>
     *     <li>进锁后发现清理纪元（{@link #EPOCH}）变了 → 清空就发生在本次检查与写入之间，
     *         写下去会在清空之后凭空多出一条，下一次落盘还会用它覆盖整份文件。</li>
     * </ol>
     *
     * <p>第 4 条必须和「写内存」放在同一个 {@link #SESSION_LOCK} 临界区里，否则挡住它的
     * 仍然只是一个 check-then-act 窗口。
     *
     * @param info          已解析完的快照
     * @param snapshotNanos 主线程抓快照那一刻的 {@link System#nanoTime()}
     */
    public void put(VillagerTradeInfo info, long snapshotNanos) {
        if (info == null) {
            return;
        }
        // 纪元在入口处读一次：之后任何一次 clearSession 都会让它对不上
        long epoch = EPOCH.get();
        // 快照比最近一次整份清空还早：说明清理之后才执行完，属于已经作废的数据
        if (snapshotNanos <= clearedAt) {
            return;
        }
        UUID uuid = info.uuid();
        Long removedAt = REMOVED_AT.get(uuid);
        if (removedAt != null && removedAt >= snapshotNanos) {
            // 快照比村民死亡还早：这是一份已经没有意义的旧数据，直接丢掉
            return;
        }
        synchronized (SESSION_LOCK) {
            if (EPOCH.get() != epoch) {
                // 检查之后、写入之前发生了整份清空：这份快照属于上一个会话，不能再写进去
                log.debug("丢弃清空前的迟到快照：uuid={}, snapshot={}", uuid, snapshotNanos);
                return;
            }
            // 同一只村民的两份快照可能乱序到：只有严格更新的那一份才允许写（原子抢占）
            if (!claimSnapshot(uuid, snapshotNanos)) {
                log.debug("丢弃乱序到达的旧快照：uuid={}, snapshot={}，已存={}",
                        uuid, snapshotNanos, LAST_SNAPSHOT.get(uuid));
                return;
            }
            store().put(uuid, info);
            // 标脏：真正的写盘交给落盘周期 / 停服兜底，这里绝不做 I/O
            VillagerTradeStorage.INSTANCE.markDirty();
        }
        if (removedAt != null) {
            // 已经写进了更新的数据，墓碑没用了
            REMOVED_AT.remove(uuid, removedAt);
        }
        pruneStaleEntries();
        log.debug("村民交易快照已缓存：uuid={}, dimension={}, pos={}, offers={}",
                uuid, info.dimension().identifier(), info.pos(), info.offers().size());
    }

    /**
     * 抢占「这只村民的最新快照时刻」：成功返回 {@code true}（本次快照确实更新）。
     *
     * <p>用 {@link ConcurrentHashMap#compute} 而不是「先 get 再 put」：后者在两条 worker
     * 同时提交时都能通过判断，最后写进去的还是可能更旧的那一份。{@code compute} 对同一
     * key 是原子的，比较和替换在同一个原子操作里完成。
     */
    private static boolean claimSnapshot(UUID uuid, long snapshotNanos) {
        boolean[] claimed = {false};
        LAST_SNAPSHOT.compute(uuid, (_, previous) -> {
            if (previous == null || snapshotNanos > previous) {
                claimed[0] = true;
                return snapshotNanos;
            }
            return previous;
        });
        return claimed[0];
    }

    /**
     * 清理两张过期表（墓碑 / 快照时刻）：条目太多时才真正遍历，平时只是一次 {@code size()} 比较。
     *
     * <p>两者都按 {@link #STALE_ENTRY_TTL_NANOS} 判定过期。清掉的都是「很久以前」的记录，
     * 而任何还可能在途的快照都比它们新得多，所以清理不会放行一条本该被拒的迟到写入。
     */
    private static void pruneStaleEntries() {
        pruneStaleEntries(REMOVED_AT);
        pruneStaleEntries(LAST_SNAPSHOT);
    }

    private static void pruneStaleEntries(Map<UUID, Long> table) {
        if (table.size() < STALE_ENTRY_PRUNE_THRESHOLD) {
            return;
        }
        long cutoff = System.nanoTime() - STALE_ENTRY_TTL_NANOS;
        table.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    /**
     * 村民死亡：主线程立刻摘掉它的缓存，并留下墓碑挡住迟到的异步写入。
     */
    public void remove(UUID uuid) {
        if (uuid == null) {
            return;
        }
        boolean existed = store().remove(uuid) != null;
        REMOVED_AT.put(uuid, System.nanoTime());
        if (existed) {
            VillagerTradeStorage.INSTANCE.markDirty();
        }
        // 墓碑只在「还有在途快照可能到达」的窗口内有意义，过期就清掉，别让刷怪塔把表撑爆
        pruneStaleEntries();
    }

    // ============================================================
    // 读
    // ============================================================

    public VillagerTradeInfo get(UUID uuid) {
        return uuid == null ? null : store().get(uuid);
    }

    /**
     * 索引里的条目数（查空提示用）。
     */
    public int size() {
        return store().size();
    }

    /**
     * 一条搜索结果：命中的村民 + <b>命中的那几笔交易</b>（不是它的全部交易）。
     *
     * <p>以前只返回村民，展示时会把整张交易表打出来 —— 搜「绿宝石」结果二十行全是
     * 没命中的泥土 / 面包。现在把「哪几笔命中」一起带出去，聊天栏只打命中的那几条。
     *
     * @param info          村民信息（UUID / 坐标 / 维度）
     * @param matchedOffers 命中的交易，保持村民交易表里的原始顺序
     */
    public record SearchResult(VillagerTradeInfo info, List<TradeItemInfo> matchedOffers) {
        public SearchResult {
            matchedOffers = matchedOffers == null ? List.of() : List.copyOf(matchedOffers);
        }
    }

    /**
     * 附魔书的物品 id：这类交易归 {@code book} 子命令，{@code item} 子命令里要排除。
     */
    private static final String ENCHANTED_BOOK_ID = "minecraft:enchanted_book";

    /**
     * 按关键词搜索<b>普通物品</b>交易（{@code /ec c TradeSearch item}）。
     *
     * <p>匹配范围：
     * <ol>
     *     <li>{@link VillagerTradeInfo#matchesLocation}：UUID / 维度 / 坐标命中 → 该村民的
     *         普通物品交易全算命中（玩家找的就是这只村民）；</li>
     *     <li>{@code price1Id} / {@code price2Id} / {@code resultItemId} 的<b>原始 id</b>
     *         （{@code stone}、{@code minecraft:bookshelf}）；</li>
     *     <li><b>别名表兜底</b>：把 id 过一遍 {@link IdTranslationConfig#displayNameOf}，
     *         用中文 / 自定叫法做包含匹配 —— 中文玩家搜「绿宝石」「书架」也能命中。</li>
     * </ol>
     *
     * <p><b>附魔书交易整笔跳过</b>：它属于 {@link #searchBook}。否则搜「绿宝石」会同时搜出
     * 「57 绿宝石 + 1 书 -> 1 附魔书」，把物品结果淹掉。
     *
     * @param keyword 已 trim 的原始关键词；空串直接返回空列表（不把整个缓存倒出来）
     */
    public List<SearchResult> searchItem(String keyword) {
        String needle = normalize(keyword);
        if (needle.isEmpty()) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>();
        for (VillagerTradeInfo info : store().values()) {
            boolean wholeVillager = info.matchesLocation(needle);
            List<TradeItemInfo> matched = new ArrayList<>();
            for (TradeItemInfo offer : info.offers()) {
                if (isEnchantedBookOffer(offer)) {
                    continue;
                }
                if (wholeVillager || matchesItem(offer, needle)) {
                    matched.add(offer);
                }
            }
            if (!matched.isEmpty()) {
                results.add(new SearchResult(info, matched));
            }
        }
        sort(results);
        return results;
    }

    /**
     * 按关键词搜索<b>附魔书</b>交易（{@code /ec c TradeSearch book}）。
     *
     * <p>只认「结果是附魔书」的那几笔交易，匹配点只有<b>附魔</b>：
     * <ul>
     *     <li>附魔 id 包含关键词（{@code mending} 天然包含在 {@code minecraft:mending} 里）；</li>
     *     <li>或附魔的中文叫法（{@code 经验修补}）包含关键词。</li>
     * </ul>
     *
     * <p>按 UUID / 维度 / 坐标搜时，列出这只村民的<b>全部附魔书交易</b>（玩家找的是村民）。
     *
     * @param keyword 已 trim 的原始关键词；空串直接返回空列表
     */
    public List<SearchResult> searchBook(String keyword) {
        String needle = normalize(keyword);
        if (needle.isEmpty()) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>();
        for (VillagerTradeInfo info : store().values()) {
            boolean wholeVillager = info.matchesLocation(needle);
            List<TradeItemInfo> matched = new ArrayList<>();
            for (TradeItemInfo offer : info.offers()) {
                if (!isEnchantedBookOffer(offer)) {
                    continue;
                }
                if (wholeVillager || matchesEnchantment(offer, needle)) {
                    matched.add(offer);
                }
            }
            if (!matched.isEmpty()) {
                results.add(new SearchResult(info, matched));
            }
        }
        sort(results);
        return results;
    }

    /**
     * 这笔交易卖的是不是附魔书（决定归 {@code item} 还是 {@code book}）。
     */
    private static boolean isEnchantedBookOffer(TradeItemInfo offer) {
        return offer != null && ENCHANTED_BOOK_ID.equals(offer.resultItemId());
    }

    /**
     * 普通物品匹配：结果物 / 两样价格物的 id 或别名。
     */
    private static boolean matchesItem(TradeItemInfo offer, String needle) {
        return idOrAlias(offer.resultItemId(), needle)
                || idOrAlias(offer.price1Id(), needle)
                || idOrAlias(offer.price2Id(), needle);
    }

    /**
     * 附魔匹配：只看 {@link TradeItemInfo#enchantments()}，id 或中文叫法。
     */
    private static boolean matchesEnchantment(TradeItemInfo offer, String needle) {
        for (TradeItemInfo.EnchantmentEntry enchantment : offer.enchantments()) {
            if (enchantment.id().contains(needle) || aliasContains(enchantment.id(), needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * id 直接包含关键词，或它的别名（{@code trade_index.json} + 内置兜底）包含关键词。
     */
    private static boolean idOrAlias(String idText, String needle) {
        if (idText == null || idText.isBlank()) {
            return false;
        }
        return idText.contains(needle) || aliasContains(idText, needle);
    }

    /**
     * {@link IdTranslationConfig#displayNameOf} 的优先级是「{@code trade_index.json} →
     * 内置兜底」，正是玩家手工配中文名的那张表。
     */
    private static boolean aliasContains(String idText, String needle) {
        if (idText == null || idText.isBlank()) {
            return false;
        }
        String name = IdTranslationConfig.displayNameOf(idText);
        return name != null && name.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String normalize(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 结果排序固定为「维度 id → 坐标 → UUID」，同一份缓存每次搜索的顺序都一样，
     * 玩家点聊天栏里的第 N 条永远对得上。
     */
    private static void sort(List<SearchResult> results) {
        results.sort(Comparator
                .comparing((SearchResult result) -> result.info().dimension().identifier().toString())
                .thenComparingLong(result -> result.info().pos().asLong())
                .thenComparing(result -> result.info().uuid()));
    }

    // ============================================================
    // 生命周期
    // ============================================================

    /**
     * 会话结束：<b>先把没落盘的改动提交出去，再清内存</b>（停服 / 规则关闭都会走这里）。
     *
     * <p>提交本身是异步的（{@code flush()} 只做「主线程序列化 + 入队」，字节写在单写者线程上）；
     * 停服那条路径上，{@code VillagerDetailsServer} 的 pre-shutdown 钩子已经先提交并<b>等它落地</b>过，
     * 走到这里时队列已空，是一次空操作。
     *
     * <p>失败时不清内存：以前失败只打 WARN 就继续清空，磁盘满 / 文件被占（Windows 上很常见）时
     * 等于把唯一的一份索引直接扔掉。现在只记 ERROR 并保留内存，下一次会话还有机会写出去。
     *
     * <p>清空与「在途 {@code put}」的互斥靠 {@link #EPOCH}：推进纪元与清内存在
     * {@link #SESSION_LOCK} 里一次做完，已经在 {@link #put} 里通过前置检查的迟到任务
     * 会在锁内看到纪元变化而放弃，不会在清空之后又冒出来一条。
     */
    @Override
    public void clearSession() {
        int cached = store().size();
        int tombstones = REMOVED_AT.size();

        // 会话结束：内存里可能还有「没赶上落盘周期」的改动，先提交一次再清
        try {
            VillagerTradeStorage.INSTANCE.flush();
        } catch (Throwable t) {
            // 保留内存数据：清掉就真的没了（磁盘上那份可能还是上一次的旧内容）
            log.error("提交村民交易索引落盘失败：保留内存数据，本次不清理、不推进清理纪元", t);
            return;
        }

        synchronized (SESSION_LOCK) {
            // 先推进纪元再清内存：在途 put 的比较与写入都在同一把锁里，顺序天然成立
            EPOCH.incrementAndGet();
            clearedAt = System.nanoTime();
            REMOVED_AT.clear();
            LAST_SNAPSHOT.clear();
            VillagerTradeStorage.INSTANCE.clearMemory();
        }
        if (cached > 0 || tombstones > 0) {
            log.info("村民交易缓存已清空（快照 {} 条，墓碑 {} 条；磁盘文件保留）", cached, tombstones);
        }
    }
}
