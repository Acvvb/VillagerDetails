package com.villagerdetails.cache.impl;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.concurrent.ServerThreadManager;
import com.villagerdetails.config.impl.VillagerTradeCacheConfig;
import com.villagerdetails.config.impl.TradeIndexConfig;
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
 * <p><b>数据现在会落盘</b>：内存容器由 {@link VillagerTradeCacheConfig} 持有
 * （{@code <存档>/villagerdetails/villager_trade_cache.json}），这里只做一层代理 ——
 * 读直接穿透，写顺手 {@link VillagerTradeCacheConfig#markDirty()}，真正的 I/O 由存储类
 * 在落盘周期（{@code AbstractWorldConfig#tickAll} 统一驱动）/ 停服时异步完成。
 * 之所以不做成「两份 map」，是为了不出现「查到的和存下的不是同一份」。
 *
 * <p><b>为什么还要一张墓碑表</b>：区块卸载在主线程提交异步任务，村民完全可能在任务真正执行前
 * 就死了 —— 死亡回调在主线程先 {@link #remove}，随后迟到的异步任务如果直接 {@code put}，
 * 就会把死掉的村民重新塞回缓存。{@link #put(VillagerTradeInfo, long, long)} 用「快照时间戳 &gt; 移除时间戳」
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
     * 当前清理纪元。
     *
     * <p>抓快照的主线程在<b>提交异步任务之前</b>取一份，worker 执行 {@link #put} 时再比对：
     * 中间要是发生过一次 {@link #clearSession()}（规则关 → 开 / 停服换档），这条快照就是
     * 上一个会话的数据，必须丢掉 —— 否则它可能晚于「清空后重新读盘」落地，把新读回来的
     * 整份索引覆盖成一条陈旧的村民记录（时间戳拦截只能挡住一半，纪元是精确判定）。
     *
     * @return 当前纪元值
     */
    public static long getCurrentEpoch() {
        return EPOCH.get();
    }

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
     * 内存缓存本体（存储类持有），直接返回 {@code ConcurrentHashMap} 活引用。
     *
     * <p><b>为什么给查询侧活引用而不是 List 拷贝</b>：异步查询要遍历整份索引，拷贝一份等于把
     * 「几万条村民 × 每条的交易表」再复制一遍 —— 查询本身是毫秒级，拷贝反而更贵。
     * CHM 的弱一致遍历本来就允许「边遍历边被改」，配合 {@link #removedAtView()} 过滤死者即可。
     *
     * <p>代价是<b>写入必须走</b> {@link #put} / {@link #remove} / {@link #clearSession()}：
     * 直接改这张表既不标脏、也绕过了墓碑与纪元的仲裁。读（搜索）随便用。
     */
    public static Map<UUID, VillagerTradeInfo> store() {
        return VillagerTradeCacheConfig.INSTANCE.cache();
    }

    /**
     * 墓碑表的只读视图：{@code 村民 UUID → 死亡时刻（nanoTime）}。
     *
     * <p>异步查询<b>必须</b>用它做二次过滤：CHM 的弱一致遍历可能返回「几毫秒前刚被
     * {@link #remove} 物理删掉」的那一条（游标已经走过了 bucket，值还是旧的）。
     * 这是正确性保证，不是可选优化 —— 少了它，玩家会查到一只刚被打死的村民。
     *
     * <p>返回活引用只为了省一次拷贝；调用方只允许读（{@code containsKey} / {@code get}）。
     */
    public static Map<UUID, Long> removedAtView() {
        return REMOVED_AT;
    }

    // ============================================================
    // 写
    // ============================================================

    /**
     * 存入 / 覆盖一只村民的快照（没有快照时间戳，等价于「当前时刻抓的」）。
     *
     * <p>给测试或将来其它同步抓取路径用；正常异步管线走
     * {@link #put(VillagerTradeInfo, long, long)}。
     */
    public void put(VillagerTradeInfo info) {
        put(info, System.nanoTime(), getCurrentEpoch());
    }

    /**
     * 存入一只村民的快照（纪元取当前值）。
     *
     * <p>等价于 {@code put(info, snapshotNanos, getCurrentEpoch())}：给「本来就是当前会话抓的」
     * 同步路径用。异步管线必须用三参数版，把主线程提交任务那一刻的纪元一起带进来。
     *
     * @param info          已解析完的快照
     * @param snapshotNanos 主线程抓快照那一刻的 {@link System#nanoTime()}
     */
    public void put(VillagerTradeInfo info, long snapshotNanos) {
        put(info, snapshotNanos, getCurrentEpoch());
    }

    /**
     * 存入一只村民的快照，并拒绝所有已经作废的迟到写入。
     *
     * <p>闸门顺序（数字就是执行顺序）：
     * <ol>
     *     <li><b>闸 1</b>（锁外）{@code snapshotNanos <= clearedAt} → 清理之后才跑完的作废数据；</li>
     *     <li><b>闸 2</b>（锁外）{@code REMOVED_AT[uuid] >= snapshotNanos} → 墓碑挡住「比死亡还早」
     *         的快照，快路径上就把绝大多数迟到写入挡掉；</li>
     *     <li><b>闸 3</b>（锁内）{@link #EPOCH} 与 {@code capturedEpoch} 不一致 → 抓快照之后
     *         世界整份清空过，这份属于上一个会话；</li>
     *     <li><b>闸 4</b>（锁内，<b>新增</b>）二次墓碑检查 —— 兜住的时序是：
     *         <pre>
     *         worker 通过了锁外闸 2（读到「没有墓碑」）
     *           → 主线程 remove 拿到 SESSION_LOCK，立墓碑 + 删索引 + 清快照时刻
     *           → worker 这时才拿到锁（闸 3 的纪元没变，挡不住它）
     *         </pre>
     *         没有闸 4，这台 worker 就会把死掉的村民重新写回索引，而且墓碑还留着 ——
     *         查询侧看到「索引里有、墓碑里也有」，怎么判都是错的。加上闸 4 之后，
     *         {@link #remove} 的三步和这里的写入形成「全有或全无」的互斥：
     *         要么 remove 整体先完成（worker 放弃），要么 worker 整体先完成（remove 再删一遍）；</li>
     *     <li><b>闸 5</b>（锁内）抢占「这只村民的最新快照时刻」，失败说明有更新的快照已经写过。</li>
     * </ol>
     *
     * <p>闸 3～5 必须和 {@code store().put(...)} 在同一个 {@link #SESSION_LOCK} 临界区里，
     * 否则每一条都只是一个 check-then-act 窗口。锁内只做 CHM 操作（微秒级），不碰 IO。
     *
     * @param info          已解析完的快照
     * @param snapshotNanos 主线程抓快照那一刻的 {@link System#nanoTime()}
     * @param capturedEpoch 主线程提交异步任务时抓的纪元（见 {@link #getCurrentEpoch()}）
     */
    public void put(VillagerTradeInfo info, long snapshotNanos, long capturedEpoch) {
        if (info == null) {
            return;
        }
        // 闸 3 的锁外快路径：先便宜地挡掉「抓完快照 → 规则关/开过一轮」的整批任务
        if (capturedEpoch != EPOCH.get()) {
            return;
        }
        // 闸 1：快照比最近一次整份清空还早，属于已经作废的数据
        if (snapshotNanos <= clearedAt) {
            return;
        }
        UUID uuid = info.uuid();
        // 闸 2：锁外快路径 —— 墓碑比快照新，说明这是村民死后才跑完的旧快照
        Long removedAt = REMOVED_AT.get(uuid);
        if (removedAt != null && removedAt >= snapshotNanos) {
            return;
        }
        synchronized (SESSION_LOCK) {
            // 闸 3：锁内比对的是<b>同一个 capturedEpoch</b>（不再另读一次 EPOCH）：
            // 否则「入口读一次、锁内再读一次」会把两次读之间的清空吃掉 —— 第二次读到的是新纪元，
            // 比对自然通过，一条属于上一个会话的快照就这样写进了清空之后的内存。
            if (EPOCH.get() != capturedEpoch) {
                log.debug("丢弃清空前的迟到快照：uuid={}, snapshot={}", uuid, snapshotNanos);
                return;
            }
            // 闸 4：二次墓碑检查 —— 兜住「worker 过了锁外闸 2 → 主线程 remove 拿锁完成 →
            // worker 才拿到锁」这条时序（详见方法注释）。这是 remove 与 put 严格互斥的关键。
            Long lockedRemovedAt = REMOVED_AT.get(uuid);
            if (lockedRemovedAt != null && lockedRemovedAt >= snapshotNanos) {
                log.debug("丢弃死亡之后才落地的快照：uuid={}, snapshot={}", uuid, snapshotNanos);
                return;
            }
            // 闸 5：同一只村民的两份快照可能乱序到，只有严格更新的那一份才允许写
            if (!claimSnapshotLocked(uuid, snapshotNanos)) {
                log.debug("丢弃乱序到达的旧快照：uuid={}, snapshot={}，已存={}",
                        uuid, snapshotNanos, LAST_SNAPSHOT.get(uuid));
                return;
            }
            store().put(uuid, info);
            // 标脏：真正的写盘交给落盘周期 / 停服兜底，这里绝不做 I/O
            VillagerTradeCacheConfig.INSTANCE.markDirty();
        }
        if (removedAt != null) {
            // 已经写进了更新的数据，墓碑没用了。
            // 用 remove(key, oldValue) 做值匹配：万一这段窗口里又发生了一次 remove，
            // 墓碑已经是新值，这里的删除自然失败，不会把新墓碑误删。
            REMOVED_AT.remove(uuid, removedAt);
        }
        // 过期清理不在这里做：那是「遍历整张表」的活，放在 worker 线程上也会拖慢快照写入，
        // 统一交给 tickPrune 按 10 分钟节奏丢进线程池异步完成（见 #tickPrune）。
        log.debug("村民交易快照已缓存：uuid={}, dimension={}, pos={}, offers={}",
                uuid, info.dimension().identifier(), info.pos(), info.offers().size());
    }

    /**
     * 抢占「这只村民的最新快照时刻」：成功返回 {@code true}（本次快照确实更新）。
     *
     * <p><b>调用方必须已持有 {@link #SESSION_LOCK}</b>：它和 {@link #remove} 的
     * 「清 {@code LAST_SNAPSHOT}」互为对方的可见性依赖，锁外调用会让
     * {@link #pruneNowAsync} 的孤儿清理误删「已抢占、还没写索引」的条目。
     *
     * <p>用 {@link ConcurrentHashMap#compute} 而不是「先 get 再 put」：后者在两条 worker
     * 同时提交时都能通过判断，最后写进去的还是可能更旧的那一份。{@code compute} 对同一
     * key 是原子的，比较和替换在同一个原子操作里完成。
     */
    private static boolean claimSnapshotLocked(UUID uuid, long snapshotNanos) {
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
     * 上一次异步清理的 tick（{@code level.getGameTime()}）；{@code < 0} 表示还没清过。
     *
     * <p>只用主线程写（{@link #tickPrune}），{@code volatile} 只是为了让调试 / 其它线程读到最新值。
     */
    private static volatile long lastPruneTick = -1L;

    /**
     * 异步清理的最小间隔：12000 tick（10 分钟，正好等于墓碑 TTL）。
     */
    private static final long PRUNE_INTERVAL_TICKS = 12000L;

    /**
     * 由 {@code END_SERVER_TICK} 调用：把「清墓碑 / 清快照时刻表」按 12000 tick 的节奏
     * 丢给服务端线程池异步执行。
     *
     * <p><b>为什么不在 put / remove 里顺手清</b>：这两条路径分别在 worker 线程和主线程上，
     * 表一大，`removeIf` 的整表遍历就是一次真实卡顿（主线程上尤其明显）。
     * 这里只做「到点没有 + 攒够阈值没有」的廉价判定，真正的遍历在池子里跑。
     *
     * <p>台账都是纯内存的辅助数据，晚清 10 分钟没有任何正确性影响，所以：
     * <ul>
     *     <li>两张表都没到 {@link #STALE_ENTRY_PRUNE_THRESHOLD} 条 → 直接返回；</li>
     *     <li>线程池不可用（停服 / 未初始化）→ 跳过本次，等下一个周期。</li>
     * </ul>
     *
     * @param currentTick 当前世界的 {@code level.getGameTime()}
     */
    public static void tickPrune(long currentTick) {
        long previous = lastPruneTick;
        if (previous < 0 || currentTick < previous) {
            // 首次调用 / 换存档重开服（gameTime 归零）：以当前 tick 为基准，避免「永远不到点」
            lastPruneTick = currentTick;
            return;
        }
        if (currentTick - previous < PRUNE_INTERVAL_TICKS) {
            return;
        }
        // 先记下本轮 tick：即使下面因为池子不可用没提交，也等下一个周期再试，
        // 避免「队列满 → 每 tick 重试 → 每 tick 刷一条 WARN」
        lastPruneTick = currentTick;

        if (REMOVED_AT.size() < STALE_ENTRY_PRUNE_THRESHOLD
                && LAST_SNAPSHOT.size() < STALE_ENTRY_PRUNE_THRESHOLD) {
            return;
        }
        ServerThreadManager.execute(VillagerTradeCache::pruneNowAsync);
    }

    /**
     * 真正干活的清理（在服务端线程池上跑，主线程不再参与遍历）。
     *
     * <p>两张表各自只清「自己的脏数据」：
     * <ul>
     *     <li>{@link #REMOVED_AT}：只按 {@link #STALE_ENTRY_TTL_NANOS} 清。墓碑只在
     *         「还有在途快照可能到达」的窗口内有用，10 分钟前的记录不可能再参与比较。
     *         <b>不要</b>再拿它和索引比对去删死者 —— 死者早已在 {@link #remove} 里被物理删除，
     *         这里再扫一遍索引既慢又是重复劳动；</li>
     *     <li>{@link #LAST_SNAPSHOT}：清「索引里已经没有这只村民」的孤儿条目。它按
     *         「索引里有没有」而不是 TTL 判定，因为村民被 {@link #remove} 物理删除之后，
     *         它的快照时刻就再没有比较对象了 —— 只按 TTL 清会让这张表长期停在索引级规模。</li>
     * </ul>
     *
     * <p><b>{@code LAST_SNAPSHOT} 那一段必须进 {@link #SESSION_LOCK}</b>：{@link #put} 是
     * 「先 {@code claimSnapshotLocked}（写这张表）→ 再 {@code store().put()}（写索引）」两步，
     * 中间有一个「表里有、索引里还没有」的瞬间。若清理恰好插在这个窗口里读到「索引里没有」
     * 把它删掉，一条更旧的快照随后就能 claim 成功、覆盖刚写好的新数据 —— 这正是这张表要挡的事。
     * 清理 10 分钟才跑一次，锁内只做一次 {@code removeIf}，代价可忽略。
     */
    private static void pruneNowAsync() {
        try {
            long now = System.nanoTime();
            // 墓碑：锁外清（CHM 的 removeIf 是并发安全的），不必占着会话锁
            REMOVED_AT.entrySet().removeIf(entry -> now - entry.getValue() > STALE_ENTRY_TTL_NANOS);

            // 孤儿快照时刻：读 store 做比对，必须和 put 的 claim+store 互斥
            synchronized (SESSION_LOCK) {
                Map<UUID, VillagerTradeInfo> cached = store();
                LAST_SNAPSHOT.entrySet().removeIf(entry -> !cached.containsKey(entry.getKey()));
            }
        } catch (Throwable t) {
            // 池子里的异常绝不能冒出去；清理失败只是「下个周期再清」，不影响正确性
            log.warn("异步清理村民交易缓存的两张辅助表失败（下个周期重试）", t);
        }
    }

    /**
     * 村民死亡：把这只村民从索引里<b>物理删除</b>，并留下墓碑挡住迟到的异步写入。
     *
     * <p>三步全部在 {@link #SESSION_LOCK} 内一次做完，<b>顺序不能反</b>：
     * <ol>
     *     <li><b>先立墓碑</b>：只有墓碑先落下来，才能拦住「已经通过锁外闸 2、正排队等锁」的
     *         worker —— 它拿到锁后会在闸 4 看到墓碑而放弃。反过来先删索引，那条 worker 就可能在
     *         删完之后把死掉的村民重新写回索引；</li>
     *     <li><b>再删索引</b>：这才是查询侧看得见的「物理删除」，之后 CHM 遍历再也读不到它；</li>
     *     <li><b>最后清快照占位</b>：这只村民的快照时刻再没有比较对象，留着只会拖大辅助表。</li>
     * </ol>
     *
     * <p><b>为什么三步必须原子</b>：任何中间态都会泄漏给 worker ——
     * 「墓碑已写、索引没删」会让一条本来更新的快照在闸 4 被误杀（数据丢了）；
     * 「索引已删、墓碑没写」则会让迟到 worker 把死村民写回来。锁内只有几次 CHM 操作
     * （微秒级），主线程 tick 感觉不到。
     *
     * <p>标脏放在锁外：落盘由 tick 驱动，锁内不碰 IO（{@code markDirty} 只是置标志 + 入队）。
     */
    public void remove(UUID uuid) {
        if (uuid == null) {
            return;
        }
        synchronized (SESSION_LOCK) {
            REMOVED_AT.put(uuid, System.nanoTime());
            store().remove(uuid);
            LAST_SNAPSHOT.remove(uuid);
        }
        VillagerTradeCacheConfig.INSTANCE.markDirty();
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
     *     <li><b>别名表兜底</b>：把 id 过一遍 {@link TradeIndexConfig#displayNameOf}，
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
     * {@link TradeIndexConfig#displayNameOf} 的优先级是「{@code trade_index.json} →
     * 内置兜底」，正是玩家手工配中文名的那张表。
     */
    private static boolean aliasContains(String idText, String needle) {
        if (idText == null || idText.isBlank()) {
            return false;
        }
        String name = TradeIndexConfig.displayNameOf(idText);
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

        // 锁外先提交落盘：flush() 只做「主线程序列化 + 入队」，字节写在单写者线程上，
        // 所以这一步不阻塞 tick，也不会和 SESSION_LOCK 嵌套。
        // 失败就整轮放弃：内存是唯一数据源，清掉就真的没了（磁盘上那份可能还是上上一次的旧内容）。
        try {
            VillagerTradeCacheConfig.INSTANCE.flush();
        } catch (Throwable t) {
            log.error("提交村民交易索引落盘失败：保留内存数据，本次不清理、不推进清理纪元", t);
            return;
        }

        synchronized (SESSION_LOCK) {
            // 一次做完五件事，任何一件单独做都会留下「清了一半」的中间态：
            EPOCH.incrementAndGet();   // 先把纪元推上去：在途 put 的闸 3 会立刻失效
            store().clear();           // 物理清空索引
            REMOVED_AT.clear();        // 墓碑只对「还有在途快照」有意义，清空后一并作废
            LAST_SNAPSHOT.clear();
            clearedAt = System.nanoTime();   // 兜住「快照比清空还早」的那批任务
            // 清完内存必须顺手清脏标记：否则下一次 tick 会把这张空表序列化出去，
            // 覆盖掉刚才 flush 提交的那份完整索引（等于把磁盘文件清空）。
            VillagerTradeCacheConfig.INSTANCE.clearDirty();
        }
        if (cached > 0 || tombstones > 0) {
            log.info("村民交易缓存已清空（快照 {} 条，墓碑 {} 条；磁盘文件保留）", cached, tombstones);
        }
    }
}
