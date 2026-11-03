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
 * <p>写入方只有一条：区块卸载时主线程抓快照 → 丢进服务端线程池 → 异步解析完 {@link #put}。
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
     * 存入一只村民的快照，并拒绝「比死亡记录还旧」的迟到写入。
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
        VillagerTradeStorage.INSTANCE.clearMemory();
        if (cached > 0 || tombstones > 0) {
            log.info("村民交易缓存已清空（快照 {} 条，墓碑 {} 条；磁盘文件保留）", cached, tombstones);
        }
    }
}
