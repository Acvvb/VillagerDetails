package com.villagerdetails.cache.impl;

import com.villagerdetails.concurrent.ServerThreadManager;
import com.villagerdetails.config.impl.TradeIndexConfig;
import com.villagerdetails.handler.entity.villager.trade.TradeItemNameResolver;
import com.villagerdetails.handler.entity.villager.trader.refresh.TradeEnchantmentScanner;
import com.villagerdetails.handler.entity.villager.trader.refresh.TradeSetAdapter;
import com.villagerdetails.mixin.villager.trader.autoTrader.VillagerTradeMixin;
import com.villagerdetails.pojo.dto.TradeItemInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.TradeCost;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.VillagerTrade;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tab 补全用的「交易关联物品 / 附魔」缓存。
 * 支持异步计算，避免在数据包重载或手动刷新时阻塞主线程。
 */
public final class TradeItemCompletionCache {

    private TradeItemCompletionCache() {
    }

    private static final Logger log = LogManager.getLogger(TradeItemCompletionCache.class);

    private static final String ENCHANTED_BOOK_ID = "minecraft:enchanted_book";
    private static final int MAX_PROFESSION_LEVEL = 5;

    // 使用 volatile 保证多线程下的可见性（后台计算完成后切回主线程赋值）
    private static volatile Set<Identifier> CACHED_TRADE_ITEM_IDS = Collections.emptySet();
    private static volatile Set<Identifier> CACHED_ENCHANTMENT_IDS = Collections.emptySet();
    private static volatile Set<String> CACHED_ITEM_SUGGESTIONS = Collections.emptySet();
    private static volatile Set<String> CACHED_ENCHANTMENT_SUGGESTIONS = Collections.emptySet();

    // 防抖锁：防止玩家狂点导致重复提交计算任务
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);

    // ============================================================
    // 刷新入口
    // ============================================================

    /**
     * 异步刷新补全缓存。
     *
     * @param server 服务端实例
     * @param source 触发指令的玩家（用于发送提示），如果为 null 则不发送提示消息
     */
    public static void refreshAsync(MinecraftServer server, CommandSourceStack source) {
        if (server == null) {
            return;
        }

        // 防抖：如果已经在刷新中，直接提示玩家
        if (!REFRESHING.compareAndSet(false, true)) {
            if (source != null) {
                source.sendSystemMessage(Component.literal("交易补全缓存正在后台计算中，请稍候...")
                        .withStyle(ChatFormatting.YELLOW));
            }
            return;
        }

        boolean submitted = ServerThreadManager.execute(() -> {
            try {
                // 1. 后台线程：执行耗时的遍历和计算
                RefreshResult result = compute(server);

                // 2. 计算完成，切回主线程更新缓存并通知玩家
                server.execute(() -> {
                    applyResult(result);
                    REFRESHING.set(false);
                    if (source != null) {
                        source.sendSystemMessage(Component.literal("交易补全缓存刷新完成！共处理 " + result.trades() + " 笔交易。")
                                .withStyle(ChatFormatting.GREEN));
                    }
                });
            } catch (Throwable t) {
                log.error("异步刷新交易补全缓存发生异常", t);
                // 异常也要切回主线程解锁并通知
                server.execute(() -> {
                    REFRESHING.set(false);
                    if (source != null) {
                        source.sendSystemMessage(Component.literal("刷新失败，请查看后台日志！")
                                .withStyle(ChatFormatting.RED));
                    }
                });
            }
        });

        // 如果线程池队列满了拒绝了任务
        if (!submitted) {
            REFRESHING.set(false);
            if (source != null) {
                source.sendSystemMessage(Component.literal("系统繁忙，刷新任务被拒绝，请稍后再试。")
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    /**
     * 同步刷新补全缓存（兼容旧调用，如启动时、数据包重载时不需要发给玩家的场景）。
     */
    public static void refresh(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (!REFRESHING.compareAndSet(false, true)) {
            log.warn("忽略同步刷新请求：已有刷新任务在执行中");
            return;
        }
        try {
            applyResult(compute(server));
        } catch (Throwable t) {
            log.error("同步刷新交易补全缓存失败", t);
        } finally {
            REFRESHING.set(false);
        }
    }

    // ============================================================
    // 核心计算（运行在后台线程）
    // ============================================================

    /** 承载后台计算结果，只包含基础数据类型，绝对不包含 Minecraft 实例 */
    private record RefreshResult(
            Set<Identifier> itemIds,
            Set<Identifier> enchantmentIds,
            Set<String> itemSuggestions,
            Set<String> enchantmentSuggestions,
            int trades,
            int skipped
    ) {}

    /**
     * 后台耗时计算逻辑。原封不动保留了原有的扫描逻辑，绝不触碰任何需要主线程的状态。
     */
    private static RefreshResult compute(MinecraftServer server) {
        Registry<VillagerTrade> registry;
        try {
            registry = server.registryAccess().lookupOrThrow(Registries.VILLAGER_TRADE);
        } catch (Exception e) {
            log.warn("读取 VILLAGER_TRADE 注册表失败，交易补全缓存保持上一次的结果：", e);
            return new RefreshResult(Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), 0, 0);
        }

        Set<ResourceKey<VillagerTrade>> villagerTrades = villagerReachableTrades(server);
        boolean scoped = !villagerTrades.isEmpty();
        if (!scoped) {
            log.warn("读不到村民职业的交易集，补全退回扫描整个 VILLAGER_TRADE 注册表");
        }

        Set<Identifier> itemIds = new LinkedHashSet<>();
        Set<Identifier> enchantmentIds = new LinkedHashSet<>();
        int trades = 0;
        int skipped = 0;
        for (Map.Entry<ResourceKey<VillagerTrade>, VillagerTrade> entry : registry.entrySet()) {
            VillagerTrade trade = entry.getValue();
            if (trade == null) {
                continue;
            }
            if (scoped && !villagerTrades.contains(entry.getKey())) {
                skipped++;
                continue;
            }
            trades++;
            VillagerTradeMixin accessor = (VillagerTradeMixin) trade;
            collect(accessor.getWants(), itemIds);
            Optional<TradeCost> additional = accessor.getAdditionalWants();
            if (additional.isPresent()) {
                additional.ifPresent(cost -> collect(cost, itemIds));
            }
            ItemStackTemplate gives = accessor.getGives();
            if (gives != null) {
                collectItem(gives.item(), itemIds);
                collectEnchantments(gives, enchantmentIds);
            }
            TradeEnchantmentScanner.collectCandidates(trade,
                    holder -> collectEnchantment(holder, enchantmentIds));
        }

        if (enchantmentIds.isEmpty()) {
            collectAllEnchantments(server, enchantmentIds);
        }

        Set<String> itemSuggestions = new LinkedHashSet<>(itemIds.size() * 2);
        for (Identifier id : itemIds) {
            if (ENCHANTED_BOOK_ID.equals(id.toString())) {
                continue;
            }
            itemSuggestions.add(id.toString());
        }
        for (String idText : new LinkedHashSet<>(itemSuggestions)) {
            String alias = TradeIndexConfig.displayNameOf(idText);
            if (alias != null && !alias.isBlank()) {
                itemSuggestions.add(alias);
            }
        }

        Set<String> enchantmentSuggestions = new LinkedHashSet<>(enchantmentIds.size() * 3);
        for (Identifier id : enchantmentIds) {
            enchantmentSuggestions.add(id.getPath());
            enchantmentSuggestions.add(id.toString());
            String alias = TradeIndexConfig.displayNameOf(id);
            if (alias != null && !alias.isBlank()) {
                enchantmentSuggestions.add(alias);
            }
        }

        return new RefreshResult(itemIds, enchantmentIds, itemSuggestions, enchantmentSuggestions, trades, skipped);
    }

    /**
     * 主线程应用结果：更新静态缓存，并打印日志。
     */
    private static void applyResult(RefreshResult result) {
        CACHED_TRADE_ITEM_IDS = Collections.unmodifiableSet(result.itemIds());
        CACHED_ENCHANTMENT_IDS = Collections.unmodifiableSet(result.enchantmentIds());
        CACHED_ITEM_SUGGESTIONS = unmodifiableSorted(result.itemSuggestions());
        CACHED_ENCHANTMENT_SUGGESTIONS = unmodifiableSorted(result.enchantmentSuggestions());

        log.info("已动态收集到 {} 种村民交易关联物品、{} 种附魔", result.itemIds().size(), result.enchantmentIds().size());
        log.debug("交易补全缓存刷新完成：村民交易 {} 笔（跳过流浪商人 {} 笔）；"
                        + "item 补全 {} 条、book 补全 {} 条；物品 id = {}",
                result.trades(), result.skipped(), result.itemSuggestions().size(), result.enchantmentSuggestions().size(), result.itemIds());
        log.debug("item 补全项（{} 条）= {}", result.itemSuggestions().size(), result.itemSuggestions());
        log.debug("book 补全项（{} 条）= {}", result.enchantmentSuggestions().size(), result.enchantmentSuggestions());
    }

    // ============================================================
    // 辅助方法（原样保留）
    // ============================================================

    private static Set<String> unmodifiableSorted(Set<String> raw) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(raw)));
    }

    private static Set<ResourceKey<VillagerTrade>> villagerReachableTrades(MinecraftServer server) {
        Set<ResourceKey<VillagerTrade>> trades = new HashSet<>();
        Registry<TradeSet> tradeSets;
        try {
            tradeSets = server.registryAccess().lookupOrThrow(Registries.TRADE_SET);
        } catch (Exception e) {
            log.debug("读取 TRADE_SET 注册表失败，本次不做职业收口：", e);
            return trades;
        }

        Set<ResourceKey<TradeSet>> visited = new HashSet<>();
        VillagerProfession none = BuiltInRegistries.VILLAGER_PROFESSION.getValue(VillagerProfession.NONE);
        for (VillagerProfession profession : BuiltInRegistries.VILLAGER_PROFESSION) {
            if (profession == none) {
                continue;
            }
            for (int level = 1; level <= MAX_PROFESSION_LEVEL; level++) {
                ResourceKey<TradeSet> key;
                try {
                    key = profession.getTrades(level);
                } catch (Exception e) {
                    continue;
                }
                if (key == null || !visited.add(key)) {
                    continue;
                }
                TradeSet set = tradeSets.getValue(key);
                if (set == null) {
                    continue;
                }
                HolderSet<VillagerTrade> members = TradeSetAdapter.trades(set);
                for (Holder<VillagerTrade> holder : members) {
                    holder.unwrapKey().ifPresent(trades::add);
                }
            }
        }
        return trades;
    }

    private static void collect(TradeCost cost, Set<Identifier> ids) {
        if (cost == null) {
            return;
        }
        collectItem(cost.item(), ids);
    }

    private static void collectItem(Holder<Item> holder, Set<Identifier> ids) {
        if (holder == null) {
            return;
        }
        Item item = holder.value();
        if (item == Items.AIR) {
            return;
        }
        try {
            ids.add(BuiltInRegistries.ITEM.getKey(item));
        } catch (Exception e) {
            log.debug("跳过不在 ITEM 注册表里的物品：{}", item);
        }
    }

    private static void collectEnchantments(ItemStackTemplate gives, Set<Identifier> out) {
        ItemStack stack;
        try {
            stack = gives.create();
        } catch (Exception e) {
            log.debug("创建交易结果物品栈失败，跳过其自带附魔：{}", gives, e);
            return;
        }
        if (stack.isEmpty()) {
            return;
        }
        for (TradeItemInfo.EnchantmentEntry enchantment : TradeItemNameResolver.enchantments(stack)) {
            Identifier id = Identifier.tryParse(enchantment.id());
            if (id != null) {
                out.add(id);
            }
        }
    }

    private static void collectEnchantment(Holder<Enchantment> holder, Set<Identifier> out) {
        if (holder == null) {
            return;
        }
        try {
            holder.unwrapKey().map(ResourceKey::identifier).ifPresent(out::add);
        } catch (Exception e) {
            log.debug("跳过拿不到 id 的附魔：{}", holder);
        }
    }

    private static void collectAllEnchantments(MinecraftServer server, Set<Identifier> out) {
        try {
            Registry<Enchantment> registry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            out.addAll(registry.keySet());
            log.info("交易注册表里没扫到具体附魔（options 可能为空），已退回「全部附魔」共 {} 种", out.size());
        } catch (Exception e) {
            log.warn("读取 ENCHANTMENT 注册表失败，附魔补全将为空：", e);
        }
    }

    // ============================================================
    // 读取
    // ============================================================

    public static Set<Identifier> getTradeItemIds() {
        return CACHED_TRADE_ITEM_IDS;
    }

    public static Set<Identifier> getTradeEnchantmentIds() {
        return CACHED_ENCHANTMENT_IDS;
    }

    public static Set<String> getItemSuggestions() {
        return CACHED_ITEM_SUGGESTIONS;
    }

    public static Set<String> getEnchantmentSuggestions() {
        return CACHED_ENCHANTMENT_SUGGESTIONS;
    }
}