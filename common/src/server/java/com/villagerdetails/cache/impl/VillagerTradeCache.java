package com.villagerdetails.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.config.impl.VillagerTradeStorage;
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
 * 在定时任务 / 停服时异步完成。之所以不做成「两份 map」，是为了不出现
 * 「查到的和存下的不是同一份」。
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
     * 存入一只村民的快照，并拒绝「比死亡记录还旧」或「比已存快照还旧」的迟到写入。
     *
     * <p>三条拒绝规则（顺序无关，但都要在 {@code store().put} 之前）：
     * <ol>
     *     <li>快照早于最近一次整份清空（{@link #clearedAt}）→ 清理之后才跑完的作废数据；</li>
     *     <li>快照早于村民死亡时刻（{@link #REMOVED_AT}）→ 墓碑挡住迟到写，避免把死掉的村民塞回来；</li>
     *     <li>快照不严格新于已存的快照（{@link #LAST_SNAPSHOT}）→ 双入口 + 多 worker 乱序时，
     *         不让旧坐标 / 旧交易表覆盖新的。</li>
     * </ol>
     *
     * @param info          已解析完的快照
     * @param snapshotNanos 主线程抓快照那一刻的 {@link System#nanoTime()}
     */
    public void put(VillagerTradeInfo info, long snapshotNanos) {
        if (info == null) {
            return;
        }
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
        // 同一只村民的两份快照可能乱序到：只有严格更新的那一份才允许写（原子抢占）
        if (!claimSnapshot(uuid, snapshotNanos)) {
            log.debug("丢弃乱序到达的旧快照：uuid={}, snapshot={}，已存={}",
                    uuid, snapshotNanos, LAST_SNAPSHOT.get(uuid));
            return;
        }
        store().put(uuid, info);
        // 标脏：真正的写盘交给定时任务 / 停服兜底，这里绝不做 I/O
        VillagerTradeStorage.INSTANCE.markDirty();
        if (removedAt != null) {
            // 已经写进了更新的数据，墓碑没用了
            REMOVED_AT.remove(uuid, removedAt);
        }
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
     * 按关键词搜索全部索引。
     *
     * <p>结果排序固定为「维度 id → 坐标 → UUID」，同一份缓存每次搜索的顺序都一样，
     * 玩家点聊天栏里的第 N 条永远对得上。
     *
     * @param keyword 已 trim 的原始关键词；空串直接返回空列表（不把整个缓存倒出来）
     */
    public List<VillagerTradeInfo> search(String keyword) {
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }

        List<VillagerTradeInfo> matches = new ArrayList<>();
        for (VillagerTradeInfo info : store().values()) {
            if (info.matches(needle)) {
                matches.add(info);
            }
        }
        matches.sort(Comparator
                .comparing((VillagerTradeInfo info) -> info.dimension().identifier().toString())
                .thenComparingLong(info -> info.pos().asLong())
                .thenComparing(VillagerTradeInfo::uuid));
        return matches;
    }

    // ============================================================
    // 生命周期
    // ============================================================

    @Override
    public void clearSession() {
        int cached = store().size();
        int tombstones = REMOVED_AT.size();

        // 会话结束：内存里可能还有「没赶上定时任务」的改动，先同步写一次再清
        try {
            VillagerTradeStorage.INSTANCE.flushSync();
        } catch (Throwable t) {
            log.warn("停服落盘村民交易索引失败，本次改动可能丢失", t);
        }

        clearedAt = System.nanoTime();
        REMOVED_AT.clear();
        LAST_SNAPSHOT.clear();
        VillagerTradeStorage.INSTANCE.clearMemory();
        if (cached > 0 || tombstones > 0) {
            log.info("村民交易缓存已清空（快照 {} 条，墓碑 {} 条；磁盘文件保留）", cached, tombstones);
        }
    }
}
