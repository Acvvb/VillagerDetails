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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 村民交易查询的<b>异步处理管线</b>（主线程只做「读 + 拷字符串」，解析交给线程池）。
 *
 * <p>两段式：
 * <ol>
 *     <li><b>主线程（实体 / 区块卸载）</b>：只挑「已锁定交易」的村民，
 *         把 UUID / 坐标 / 维度 / 每件结果物品的字符串快照拷出来。
 *         这一步是<b>深拷贝</b>：不把 {@link Villager}、{@link MerchantOffer}、
 *         {@link ItemStack} 这些原版对象带进子线程，避免主线程继续改交易时出现
 *         {@code ConcurrentModificationException}；</li>
 *     <li><b>线程池</b>：把快照组装成 {@link VillagerTradeInfo}，写进
 *         {@link VillagerTradeCache}。这一步只碰不可变字符串 / record。</li>
 * </ol>
 *
 * <p><b>两个主线程入口</b>：{@link #onEntityUnload(Entity, ServerLevel)} 是实际生效的那个
 * （原版在区块变成 HIDDEN 时就把实体移出追踪，比 {@code CHUNK_UNLOAD} 更早）；
 * {@link #onChunkUnload(ServerLevel, LevelChunk)} 保留为整区块兜底。
 *
 * <p>村民死亡时由 {@code ServerLivingEntityEvents.AFTER_DEATH} 调 {@link #onLivingDeath}，
 * 在主线程立刻摘掉缓存（并留下墓碑挡住迟到的异步写入，见 {@link VillagerTradeCache}）。
 *
 * <p><b>为什么不在区块卸载时直接遍历 {@code MerchantOffers} 到子线程</b>：
 * 交易列表是村民实体的一部分，玩家随时可能点开交易界面修改它；跨线程读同一个
 * {@code ArrayList} 没有可见性保证，也没有锁。所以这里坚持「主线程拷贝成纯数据」。
 */
public final class VillagerTradePipeline {

    private VillagerTradePipeline() {
    }

    private static final Logger log = LogManager.getLogger(VillagerTradePipeline.class);

    /**
     * 「线程池不可用」只提示一次，避免每次卸载刷屏。
     */
    private static final AtomicBoolean POOL_WARNING = new AtomicBoolean();

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
            villagers = level.getEntitiesOfClass(Villager.class, chunkBounds(level, chunk),
                    villager -> villager != null && !villager.isRemoved());
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
     * 村民死亡：主线程清掉它在内存缓存里的那份交易。
     *
     * <p>只认 {@link Villager}；僵尸村民转化之类不是死亡，不走这里
     * （转化后 UUID 不变，但交易已经没了，下次区块卸载自然不会再收录）。
     */
    public static void onLivingDeath(LivingEntity entity) {
        if (!(entity instanceof Villager villager)) {
            return;
        }
        try {
            VillagerTradeCache.INSTANCE.remove(villager.getUUID());
        } catch (Throwable t) {
            log.warn("清理村民交易缓存失败：uuid={}", villager.getUUID(), t);
        }
    }

    // ============================================================
    // 线程池
    // ============================================================

    /**
     * 把一份纯字符串快照交给项目的服务端线程池。
     *
     * <p><b>不自己 new Thread / ExecutorService</b>：池子是
     * {@link com.villagerdetails.concurrent.ServerThreadManager} 在服务端初始化时建好的那一个。
     */
    private static void submit(VillagerTradeSnapshot snapshot) {
        ModThreadPool pool = ModContext.getServerPool();
        if (pool == null || pool.isShutdown()) {
            if (POOL_WARNING.compareAndSet(false, true)) {
                // 停服过程中池子可能已经先关掉了，属于正常收尾；只在第一次提示一下
                log.debug("服务端线程池不可用，村民交易快照已丢弃（若持续出现请检查 ServerThreadManager.init 的接线）");
            }
            return;
        }

        pool.execute(() -> {
            try {
                VillagerTradeInfo info = snapshot.toInfo();
                VillagerTradeCache.INSTANCE.put(info, snapshot.capturedAt());
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
            List<TradeItemSnapshot> items,
            long capturedAt
    ) {

        /**
         * 主线程抓快照；没有「已锁定交易」时返回 {@code null}。
         */
        static VillagerTradeSnapshot capture(ServerLevel level, Villager villager) {
            MerchantOffers offers = villager.getOffers();
            if (offers == null || offers.isEmpty()) {
                return null;
            }
            if (!hasLockedTrades(villager, offers)) {
                return null;
            }

            List<TradeItemSnapshot> items = new ArrayList<>(offers.size());
            for (MerchantOffer offer : offers) {
                // 只记录「村民卖什么」，支付物（costA / costB）不参与查询
                ItemStack result = offer.getResult();
                if (result == null || result.isEmpty()) {
                    continue;
                }

                String itemId = TradeItemNameResolver.itemId(result);
                String displayName = TradeItemNameResolver.itemName(result);
                if (displayName.isBlank()) {
                    displayName = itemId;
                }

                List<TradeItemNameResolver.EnchantmentText> enchantments =
                        TradeItemNameResolver.enchantmentTexts(result);
                List<String> enchantmentNames = new ArrayList<>(enchantments.size());
                List<String> searchTokens = new ArrayList<>(2 + enchantments.size() * 2);
                searchTokens.add(itemId);
                searchTokens.add(displayName);
                for (TradeItemNameResolver.EnchantmentText enchantment : enchantments) {
                    enchantmentNames.add(enchantment.displayName());
                    if (!enchantment.id().isBlank()) {
                        searchTokens.add(enchantment.id());
                    }
                    searchTokens.add(enchantment.displayName());
                }

                items.add(new TradeItemSnapshot(
                        itemId,
                        displayName,
                        List.copyOf(enchantmentNames),
                        result.getCount(),
                        String.join(" ", searchTokens).toLowerCase(Locale.ROOT)));
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
         * 子线程调用：把快照组装成缓存 DTO。
         */
        VillagerTradeInfo toInfo() {
            List<TradeItemInfo> tradeItems = new ArrayList<>(items.size());
            for (TradeItemSnapshot item : items) {
                tradeItems.add(item.toInfo());
            }
            return new VillagerTradeInfo(uuid, pos, dimension, tradeItems);
        }
    }

    /**
     * 一件结果物品的字符串快照。
     */
    private record TradeItemSnapshot(
            String itemId,
            String displayName,
            List<String> enchantments,
            int count,
            String searchText
    ) {

        TradeItemSnapshot {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(displayName, "displayName");
            enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
            searchText = searchText == null ? "" : searchText;
        }

        /**
         * 子线程调用：拼出「附魔书[经验修补] x1」这种展示文本。
         */
        TradeItemInfo toInfo() {
            StringBuilder text = new StringBuilder(displayName.length() + 24).append(displayName);
            if (!enchantments.isEmpty()) {
                text.append('[').append(String.join(", ", enchantments)).append(']');
            }
            text.append(" x").append(count);
            return new TradeItemInfo(itemId, displayName, text.toString(), count,
                    !enchantments.isEmpty(), searchText);
        }
    }
}
