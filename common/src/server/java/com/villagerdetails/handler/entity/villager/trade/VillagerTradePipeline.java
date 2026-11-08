package com.villagerdetails.handler.entity.villager.trade;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.cache.impl.VillagerTradeCache;
import com.villagerdetails.concurrent.ModContext;
import com.villagerdetails.concurrent.ModThreadPool;
import com.villagerdetails.pojo.dto.TradeItemInfo;
import com.villagerdetails.pojo.dto.VillagerTradeInfo;
import com.villagerdetails.rule.type.RuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 村民交易查询的<b>异步处理管线</b>（主线程只做「读 + 拷数据」，写缓存交给线程池）。
 *
 * <p>两段式：
 * <ol>
 *     <li><b>主线程（实体 / 区块卸载）</b>：只挑「已锁定交易」的村民，
 *         把 UUID / 坐标 / 维度 / <b>每笔交易的结果与价格</b>拷成
 *         {@link TradeItemInfo}（id / 数量 / 可交易次数 / 附魔 id + 等级，全是标量）。
 *         这一步是<b>深拷贝</b>：不把
 *         {@link Villager}、{@link MerchantOffer}、{@link ItemStack} 这些原版对象带进子线程，
 *         避免主线程继续改交易时出现 {@code ConcurrentModificationException}；</li>
 *     <li><b>线程池</b>：把快照包成 {@link VillagerTradeInfo} 写进
 *         {@link VillagerTradeCache}（顺手标脏，真正的落盘由存储类定时做）。</li>
 * </ol>
 *
 * <p><b>不做任何「值不值得收录」的过滤</b>：村民卖什么就收录什么（只跳过没有结果物品的
 * 空交易）。索引的用途是「查谁卖什么」，不该替玩家判断哪些物品值得查 —— 一旦按
 * {@code trade_index.json} 做白名单，书架、地图、命名牌、盔甲、食物这些正常出售交易
 * 会全部被误杀，而「收购绿宝石」方向反倒全都能过，索引最后只剩一堆「X -> 1 绿宝石」。
 * 条目多到撑爆文件属于容量问题，应该用文件体积 / 条数上限去管，不是靠误杀数据解决。
 *
 * <p><b>主线程入口</b>：{@link #onEntityUnload(Entity, ServerLevel)} 是实际生效的那个
 * （原版在区块变成 HIDDEN 时就把实体移出追踪，比 {@code CHUNK_UNLOAD} 更早）；
 * {@link #onChunkUnload(ServerLevel, LevelChunk)} 保留为整区块兜底；
 * {@link #onTradeOpen(Villager)} 是「玩家打开交易界面」的实时补抓（100 tick 防抖），
 * 用来补上「交易刚锁定但区块一直不卸载」的实时性缺口。
 *
 * <p>村民死亡时由 {@code ServerLivingEntityEvents.AFTER_DEATH} 调 {@link #onLivingDeath}，
 * 在主线程立刻摘掉缓存（并留下墓碑挡住迟到的异步写入，见 {@link VillagerTradeCache}）。
 */
public final class VillagerTradePipeline {

    private VillagerTradePipeline() {
    }

    private static final Logger log = LogManager.getLogger(VillagerTradePipeline.class);

    /**
     * 「线程池不可用」只提示一次，避免每次卸载刷屏。
     */
    private static final AtomicBoolean POOL_WARNING = new AtomicBoolean();

    /**
     * 「打开交易界面」抓取的防抖表：村民 UUID → 上次抓取时的 {@code gameTime}。
     *
     * <p>玩家站在村民面前反复右键，每次都会走到 {@code setTradingPlayer}；
     * 这里给每只村民 5 秒（100 tick）冷却，避免同一次交互窗口里反复深拷贝交易表。
     */
    private static final Map<UUID, Long> LAST_CAPTURE_TICKS = new ConcurrentHashMap<>();

    /**
     * 同一村民两次「打开交易界面」抓取之间至少间隔多少 tick（100 tick = 5 秒）。
     */
    private static final long TRADE_OPEN_COOLDOWN_TICKS = 100L;

    // ============================================================
    // 主线程入口
    // ============================================================

    /**
     * 区块卸载：抓取区块内「已锁定交易」村民的字符串快照，丢进服务端线程池。
     *
     * <p>单个村民 / 单个区块出错只跳过自己，<b>绝不让异常冒泡回区块卸载逻辑</b>。
     *
     * <p><b>兜底路径</b>：26.x 的实际卸载顺序是「实体先被移出追踪（触发
     * {@code ServerEntityEvents.ENTITY_UNLOAD}）→ 区块再发 {@code CHUNK_UNLOAD}」，
     * 所以这个事件里通常已经查不到村民了；真正的抓取入口是
     * {@link #onEntityUnload(Entity, ServerLevel)}。这里保留整区块扫描是为了兜住
     * 其它版本 / 其它加载器上「区块卸载时实体还在」的情况，查不到就是空循环，代价很小。
     */
    public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        if (level == null || chunk == null) {
            return;
        }
        // 最便宜的一步放最前面：规则关着就完全不碰实体索引
        if (!RuleCache.isEnabled(RuleType.VILLAGER_TRADE_SEARCH)) {
            return;
        }

        List<Villager> villagers;
        try {
            villagers = level.getEntitiesOfClass(Villager.class, chunkBounds(level, chunk), villager -> !villager.isRemoved());
        } catch (Throwable t) {
            log.warn("区块卸载时枚举村民失败，跳过该区块：dimension={}, chunk=({}, {})",
                    level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z(), t);
            return;
        }

        for (Villager villager : villagers) {
            captureAndSubmit(level, villager);
        }
    }

    /**
     * 实体卸载：区块卸载 / 实体离开当前世界时，逐只抓取「已锁定交易」的村民。
     *
     * <p>为什么还要这个入口：原版 {@code PersistentEntitySectionManager} 在区块变成
     * {@code HIDDEN} 时就把实体从追踪表里摘掉（本事件的注入点正是
     * {@code onTrackingEnd} 的 HEAD），比 {@code CHUNK_UNLOAD} 更早，而且此时
     * {@code villager.isRemoved()} 还是 {@code false}、交易表仍然可读。
     * 这正是「区块卸载时抓快照」想要的那一刻，只是钩子换成了实体级事件。
     *
     * <p><b>只收「卸载」不收「死亡 / 移除」</b>：死亡会先走
     * {@code AFTER_DEATH} → {@link #onLivingDeath} 清缓存，此时实体已经是 removed 状态，
     * 直接跳过；否则会把死掉的村民又抓回缓存里。
     */
    public static void onEntityUnload(Entity entity, ServerLevel level) {
        if (!(entity instanceof Villager villager) || level == null) {
            return;
        }
        // 死亡 / 被移除 / 换维度都会走到这里：它们不是「区块卸载」，不收录
        if (villager.isRemoved() || !villager.isAlive()) {
            return;
        }
        if (!RuleCache.isEnabled(RuleType.VILLAGER_TRADE_SEARCH)) {
            return;
        }
        captureAndSubmit(level, villager);
    }

    /**
     * 单只村民的「主线程抓取 → 线程池解析」公共路径；任何异常只记日志，不向外抛。
     */
    private static void captureAndSubmit(ServerLevel level, Villager villager) {
        if (villager == null || villager.isRemoved()) {
            return;
        }
        try {
            VillagerTradeSnapshot snapshot = VillagerTradeSnapshot.capture(level, villager);
            if (snapshot != null) {
                submit(snapshot);
            }
        } catch (Throwable t) {
            // 实体数据异常（例如交易表被别的模组改坏）：跳过这一只，继续处理其它村民
            log.warn("提取村民交易快照失败，已跳过该村民（不影响区块卸载）", t);
        }
    }

    /**
     * 立刻抓一次这只村民的交易快照（主线程调用）。
     *
     * <p>给「命名牌刷新交易」用：刷新刚把报价表整张换掉，如果等区块卸载再抓，
     * 查询索引里会一直留着旧的一份。抓取规则与卸载路径完全一致
     * （{@code VillagerTradeSnapshot.capture} 自己判断「有没有锁定交易」）。
     */
    public static void captureNow(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        if (!RuleCache.isEnabled(RuleType.VILLAGER_TRADE_SEARCH)) {
            return;
        }
        captureAndSubmit(level, villager);
    }

    /**
     * 玩家<b>打开</b>村民交易界面时顺手抓一次快照（主线程，{@code AbstractVillager#setTradingPlayer}
     * 的注入点调用）。
     *
     * <p><b>为什么需要这个入口</b>：索引原本只在区块卸载（{@code ENTITY_UNLOAD} /
     * {@code CHUNK_UNLOAD}）和命名牌刷新成功（{@link #captureNow}）时更新。村民刚锁定交易、
     * 但区块一直不卸载（玩家就在旁边交易）时，索引要等下一次卸载才更新 —— 打开界面正好是
     * 「交易表此刻一定可读」的时刻，补上这个入口即可解决这种实时性缺口。
     *
     * <p><b>三级守卫</b>：
     * <ol>
     *     <li>规则关闭 → 直接返回（最便宜的一步放最前面）；</li>
     *     <li>不在服务端（客户端预测 / 无世界）→ 直接返回，绝不跨端写缓存；</li>
     *     <li>同一村民 100 tick 内重复打开 → 跳过（玩家狂点右键不会把交易表拷贝刷爆）。</li>
     * </ol>
     * 真正抓取复用 {@link #captureNow}（它自己也会再判一次规则与端，重复判断成本可忽略）。
     *
     * @param villager 刚被打开交易界面的村民；{@code null} 直接返回
     */
    public static void onTradeOpen(Villager villager) {
        if (villager == null || villager.isRemoved()) {
            return;
        }
        // 最便宜的一步放最前面：规则关着就完全不做任何事
        if (!RuleCache.isEnabled(RuleType.VILLAGER_TRADE_SEARCH)) {
            return;
        }
        if (!(villager.level() instanceof ServerLevel level)) {
            // 注入点两端都会触发，这里只允许服务端那份继续
            return;
        }
        long now = level.getGameTime();
        UUID uuid = villager.getUUID();
        Long last = LAST_CAPTURE_TICKS.get(uuid);
        if (last != null && now - last < TRADE_OPEN_COOLDOWN_TICKS) {
            return;
        }
        LAST_CAPTURE_TICKS.put(uuid, now);
        captureNow(villager);
    }

    /**
     * 村民死亡：主线程把这只村民从索引里<b>物理删除</b>（含墓碑与快照占位，见
     * {@link VillagerTradeCache#remove(java.util.UUID)}）。
     *
     * <p>这里只做「转手」一件事：删除的全部并发语义（墓碑先行、三步原子、标脏在锁外）
     * 都由缓存自己保证，调用方再补一遍只会把顺序写错。
     *
     * <p>只认 {@link Villager}；僵尸村民转化之类不是死亡，不走这里
     * （转化后 UUID 不变，但交易已经没了，下次区块卸载自然不会再收录）。
     *
     * <p>参数用 {@link Entity}（而不是 {@code LivingEntity}）：{@code AFTER_DEATH} 给的就是
     * {@code LivingEntity}，但语义上「死亡」只需要一个实体引用，收窄类型对调用方没有好处。
     */
    public static void onLivingDeath(Entity entity) {
        if (!(entity instanceof Villager villager)) {
            return;
        }
        try {
            VillagerTradeCache.INSTANCE.remove(villager.getUUID());
        } catch (Throwable t) {
            // 死亡回调里抛异常会打断后续收尾（掉落 / 进度统计），这里必须吞掉
            log.warn("清理村民交易缓存失败：uuid={}", villager.getUUID(), t);
        }
    }

    // ============================================================
    // 线程池
    // ============================================================

    /**
     * 把一份纯数据快照交给项目的服务端线程池。
     *
     * <p><b>不自己 new Thread / ExecutorService</b>：池子是
     * {@link com.villagerdetails.concurrent.ServerThreadManager} 在服务端初始化时建好的那一个。
     */
    private static void submit(VillagerTradeSnapshot snapshot) {
        ModThreadPool pool = ModContext.getServerPool();
        if (pool == null || pool.isShutdown()) {
            if (POOL_WARNING.compareAndSet(false, true)) {
                // 停服过程中池子可能已经先关掉了，属于正常收尾；但"从未初始化"是真故障
                // —— 快照会被全部丢弃、查询索引永远为空，所以这里用 WARN 且只报一次。
                log.warn("服务端线程池不可用（未初始化 / 已关闭），交易快照被丢弃"
                        + "（若在正常运行期出现，请检查 ServerThreadManager.init 是否调用了 ModContext.setServerPool）");
            }
            return;
        }

        // 纪元必须在主线程、提交任务这一刻抓：worker 执行时如果已经历过一次
        // clearSession（规则关 → 开 / 停服换档），put 里的纪元校验会直接丢掉这条老快照，
        // 避免它覆盖掉「清空之后重新读盘」拿到的新数据。
        long capturedEpoch = VillagerTradeCache.getCurrentEpoch();

        pool.execute(() -> {
            try {
                // 快照本身已经只含不可变标量，这里只是包成缓存 DTO 并写入（顺带标脏）
                VillagerTradeCache.INSTANCE.put(snapshot.toInfo(), snapshot.capturedAt(), capturedEpoch);
            } catch (Throwable t) {
                // 子线程里出的错不能影响池子，也不能把半成品写进缓存
                log.warn("异步解析村民交易失败，已跳过：uuid={}", snapshot.uuid(), t);
            }
        });
    }

    private static AABB chunkBounds(ServerLevel level, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        return new AABB(
                pos.getMinBlockX(), level.getMinY(), pos.getMinBlockZ(),
                // AABB 的上界按「含端点」理解，+1 保证最外一排方块里的实体也在范围里
                pos.getMaxBlockX() + 1.0D, level.getMaxY() + 1.0D, pos.getMaxBlockZ() + 1.0D);
    }

    // ============================================================
    // 主线程抓取的数据快照（全部是不可变基础类型）
    // ============================================================

    /**
     * 一只村民的交易快照。
     *
     * <p>只由 {@link #capture} 在主线程构造；之后在子线程里只读，
     * 因此 <b>不允许</b>出现任何原版对象字段。
     */
    private record VillagerTradeSnapshot(
            UUID uuid,
            BlockPos pos,
            ResourceKey<Level> dimension,
            List<TradeItemInfo> items,
            long capturedAt
    ) {

        /**
         * 主线程抓快照；没有「已锁定交易」时返回 {@code null}。
         */
        static VillagerTradeSnapshot capture(ServerLevel level, Villager villager) {
            MerchantOffers offers = villager.getOffers();
            if (offers.isEmpty()) {
                return null;
            }
            if (!hasLockedTrades(villager, offers)) {
                return null;
            }

            List<TradeItemInfo> items = new ArrayList<>(offers.size());
            for (MerchantOffer offer : offers) {
                TradeItemInfo item = toTradeItem(offer);
                // 白名单没过的（冷门产物）直接不收录
                if (item != null) {
                    items.add(item);
                }
            }
            if (items.isEmpty()) {
                return null;
            }

            return new VillagerTradeSnapshot(
                    villager.getUUID(),
                    villager.blockPosition().immutable(),
                    level.dimension(),
                    List.copyOf(items),
                    // 用单调时钟给这份快照打时间戳，缓存用它挡住「死后才到的异步写入」
                    System.nanoTime());
        }

        /**
         * 一笔交易 → <b>纯数据</b>快照（id / 数量 / 可交易次数 / 附魔）；空结果返回 {@code null}。
         *
         * <p><b>不按物品做过滤</b>：只跳过「没有结果物品」这种无意义记录。曾经的「白名单」
         * （只收 {@code trade_index.json} 登记过的物品 / 附魔）会把书架、地图、命名牌、盔甲、
         * 食物这些正常出售交易全部误杀，而「收购绿宝石」方向又全都能过 —— 索引最后只剩
         * 一堆「X -> 1 绿宝石」。交易索引的用途是「查谁卖什么」，不该替玩家决定什么值得查。
         *
         * <p><b>读的是「规则生效之后」的 offer</b>：{@code VillagerToolsmithExchange} 这类规则
         * 是在 {@code AbstractVillager#getOffers()} 的 RETURN 上原地改写列表
         * （{@code offers.set(j, new MerchantOffer(...))}），所以这里 {@code villager.getOffers()}
         * 拿到的就是改写后的真实交易 —— 价格物 / 结果物会跟着规则变，不需要额外处理。
         *
         * <p><b>不拼展示文本</b>：只留 id 与数量，名字由客户端按自己的语言本地化。
         */
        private static TradeItemInfo toTradeItem(MerchantOffer offer) {
            if (offer == null) {
                return null;
            }
            ItemStack result = offer.getResult();
            if (result.isEmpty()) {
                return null;
            }

            // 价格：getCostA() / getCostB() 是「已经计入供需涨价 + 声望折扣」的实际价格（ItemStack），
            // getItemCostA() / getItemCostB() 才是原始标价（ItemCost）。取前者，取不到再退回后者。
            ItemStack priceStackA = offer.getCostA();
            ItemCost baseCostA = offer.getItemCostA();
            String price1Id = isPresent(priceStackA) ? TradeItemNameResolver.itemId(priceStackA)
                    : TradeItemNameResolver.itemId(baseCostA.item());
            int price1Count = isPresent(priceStackA) ? priceStackA.getCount()
                    : baseCostA.count();

            // costB 是「第二样东西」：原版大多数交易没有（取不到就是空栈 / 空 Optional）
            ItemStack priceStackB = offer.getCostB();
            ItemCost baseCostB = offer.getItemCostB().orElse(null);
            String price2Id = isPresent(priceStackB) ? TradeItemNameResolver.itemId(priceStackB)
                    : TradeItemNameResolver.itemId(baseCostB == null ? null : baseCostB.item());
            int price2Count = isPresent(priceStackB) ? priceStackB.getCount()
                    : (baseCostB == null ? 0 : baseCostB.count());

            return new TradeItemInfo(
                    TradeItemNameResolver.itemId(result),
                    result.getCount(),
                    price1Id,
                    price1Count,
                    price2Id,
                    price2Count,
                    offer.getMaxUses(),
                    TradeItemNameResolver.enchantments(result));
        }

        /**
         * 这个价格物品栈可不可用（{@code getCostX()} 在「没有这一侧价格」时可能给 {@code null}
         * 或空栈，两种都要当成「没有」处理）。
         */
        private static boolean isPresent(ItemStack stack) {
            return stack != null && !stack.isEmpty();
        }

        /**
         * 交易是否已经「锁定」。
         *
         * <p>原版语义：村民被交易过一次之后，整套交易表就固定下来（不再随等级重刷）。
         * 判定用两条和版本无关的证据：
         * <ul>
         *     <li>{@code villagerXp > 0}：确实完成过交易（本模组的自动锁交易也会把它置 1）；</li>
         *     <li>任意一条 offer 的 {@code uses > 0}：这一条被用过（{@code isRewarded} 在这个版本不存在）。</li>
         * </ul>
         *
         * <p><b>刻意不判 {@code villager.isTrading()}</b>：它表示「当前有玩家开着交易界面」，
         * 区块卸载时几乎永远是 {@code false}，用它过滤会把所有村民都漏掉。
         */
        private static boolean hasLockedTrades(Villager villager, MerchantOffers offers) {
            if (villager.getVillagerXp() > 0) {
                return true;
            }
            for (MerchantOffer offer : offers) {
                if (offer.getUses() > 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 子线程调用：包成缓存 / 落盘用的 DTO。
         */
        VillagerTradeInfo toInfo() {
            return new VillagerTradeInfo(uuid, pos, dimension, items);
        }
    }
}
