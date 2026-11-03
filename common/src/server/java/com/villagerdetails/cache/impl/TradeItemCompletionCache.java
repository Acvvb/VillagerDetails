package com.villagerdetails.cache.impl;

import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.handler.entity.villager.trade.TradeItemNameResolver;
import com.villagerdetails.handler.entity.villager.trader.refresh.TradeEnchantmentScanner;
import com.villagerdetails.handler.entity.villager.trader.refresh.TradeSetAdapter;
import com.villagerdetails.mixin.villager.trader.autoTrader.VillagerTradeMixin;
import com.villagerdetails.pojo.dto.TradeItemInfo;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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

/**
 * Tab 补全用的「交易关联物品 / 附魔」缓存：<b>直接遍历 {@code VILLAGER_TRADE} 注册表</b>动态收集。
 *
 * <p>为什么不再用静态表：26.x 的村民交易彻底数据驱动化（{@code data/<ns>/villager_trade/…} +
 * {@code trade_set} 注册表），数据包 / 模组可以随时往里加新交易。写死一份清单必然漏 ——
 * 玩家补全不到那些新交易里出现的物品。所以这里在<b>服务端启动</b>和<b>数据包重载</b>后各刷一次：
 *
 * <ol>
 *     <li>遍历 {@code Registries.VILLAGER_TRADE} 里每一笔 {@link VillagerTrade}；</li>
 *     <li>取 {@code wants}（付什么）、{@code additionalWants}（可选的第二样付款物）、
 *         {@code gives}（卖什么）三处涉及的<b>基础物品 id</b>；</li>
 *     <li>再从两处取「可能产出的附魔」：{@code gives} 的组件（{@code ENCHANTMENTS} /
 *         {@code STORED_ENCHANTMENTS}，对应 {@code set_components} 写法），以及
 *         {@code given_item_modifier} 里的 {@code enchant_randomly}（原版图书管理员的写法，
 *         候选在它的 {@code options} 里，通常是 {@code #minecraft:tradeable} 标签）；</li>
 *     <li>去重后整体替换缓存（不可变集合，读侧无锁）。</li>
 * </ol>
 *
 * <p><b>为什么用 mixin accessor 读</b>：26.x 的 {@link VillagerTrade} 是普通类，字段全部
 * {@code private} 且<b>没有任何 getter</b>（只有需要 {@code LootContext} 的 {@code getOffer()}）。
 * 项目里本来就有 {@link VillagerTradeMixin}（读 {@code givenItemModifier}），这里给它补上
 * {@code wants} / {@code additionalWants} 的 accessor 复用同一套做法。
 *
 * <p>补全项同时给「id」和「中文叫法」（{@code IdTranslationConfig}：{@code trade_index.json} +
 * 内置兜底）：{@code item} 子命令吃物品，{@code book} 子命令吃附魔（短 id {@code mending}、
 * 完整 id {@code minecraft:mending}、中文 {@code 经验修补} 都给）。
 *
 * <p><b>线程</b>：{@link #refresh} 只在服务端线程（启动 / 重载回调）跑；读侧是 Tab 补全（服务端线程），
 * 但缓存用 {@code volatile} 的整体替换，即便从别处读也不会看到半成品。
 */
public final class TradeItemCompletionCache {

    private TradeItemCompletionCache() {
    }

    private static final Logger log = LogManager.getLogger(TradeItemCompletionCache.class);

    /**
     * 附魔书的物品 id：{@code item} 子命令要把它排除掉（那是 {@code book} 子命令的地盘）。
     */
    private static final String ENCHANTED_BOOK_ID = "minecraft:enchanted_book";

    /**
     * 村民职业的最高等级（原版 {@code VillagerData} 上限是 5 级，5 级之后交易不再刷新）。
     */
    private static final int MAX_PROFESSION_LEVEL = 5;

    /**
     * 交易注册表里出现过的物品 id（不可变快照）。
     */
    private static volatile Set<Identifier> CACHED_TRADE_ITEM_IDS = Collections.emptySet();

    /**
     * 交易里可能产出的附魔 id（不可变快照）。
     */
    private static volatile Set<Identifier> CACHED_ENCHANTMENT_IDS = Collections.emptySet();

    /**
     * {@code item} 子命令的补全项：物品 id + 中文叫法（不含附魔书）。
     */
    private static volatile Set<String> CACHED_ITEM_SUGGESTIONS = Collections.emptySet();

    /**
     * {@code book} 子命令的补全项：附魔 id（短 / 完整）+ 中文叫法。
     */
    private static volatile Set<String> CACHED_ENCHANTMENT_SUGGESTIONS = Collections.emptySet();

    // ============================================================
    // 刷新
    // ============================================================

    /**
     * 重新扫描 {@code VILLAGER_TRADE} 注册表并整体替换缓存。
     *
     * <p>注册表读不到（服务端还没起来 / 数据包重载失败）时只记日志，<b>保留上一次的结果</b> ——
     * 补全列表退化成旧快照，总比突然空掉好。
     *
     * @param server 目标服务器；{@code null} 直接返回
     */
    public static void refresh(MinecraftServer server) {
        if (server == null) {
            return;
        }

        Registry<VillagerTrade> registry;
        try {
            registry = server.registryAccess().lookupOrThrow(Registries.VILLAGER_TRADE);
        } catch (Exception e) {
            log.warn("读取 VILLAGER_TRADE 注册表失败，交易补全缓存保持上一次的结果：", e);
            return;
        }

        // 「村民职业实际能挂出来」的交易集：索引只收录 {@code Villager} 实体，而
        // {@code WanderingTrader} 是另一条实体线（那里全是花 / 珊瑚 / 树苗 / 海龟壳……），
        // 永远不会出现在索引里。所以补全也只取村民这一侧 —— 这是本类唯一「收口」的地方，
        // 提取字段仍然只有 wants / additionalWants / gives 三处。
        Set<ResourceKey<VillagerTrade>> villagerTrades = villagerReachableTrades(server);
        boolean scoped = !villagerTrades.isEmpty();
        if (!scoped) {
            // 拿不到职业表（极端情况：TRADE_SET 注册表不可用）时退回全表扫描，
            // 宁可比实际范围大一点，也别让补全空掉
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
                // 流浪商人的交易：索引永远收不到，补全里也就不该出现
                skipped++;
                continue;
            }
            trades++;
            // 字段全 private 且没有 getter：只能走 accessor（见类注释）
            VillagerTradeMixin accessor = (VillagerTradeMixin) trade;
            collect(accessor.getWants(), itemIds);
            Optional<TradeCost> additional = accessor.getAdditionalWants();
            if (additional != null) {
                additional.ifPresent(cost -> collect(cost, itemIds));
            }
            ItemStackTemplate gives = accessor.getGives();
            if (gives != null) {
                collectItem(gives.item(), itemIds);
                collectEnchantments(gives, enchantmentIds);
            }
            // enchant_randomly 的候选（原版图书管理员的写法；options 为空时这一笔枚举不出来）
            TradeEnchantmentScanner.collectCandidates(trade,
                    holder -> collectEnchantment(holder, enchantmentIds));
        }

        if (enchantmentIds.isEmpty()) {
            // 兜底：一笔都没扫出来（options 全为空 / 模组改了写法）时，用「服务器认识的
            // 全部附魔」当补全项 —— 宁可比实际交易范围大一点，也别让玩家补全不出东西
            collectAllEnchantments(server, enchantmentIds);
        }

        Set<String> itemSuggestions = new LinkedHashSet<>(itemIds.size() * 2);
        for (Identifier id : itemIds) {
            if (ENCHANTED_BOOK_ID.equals(id.toString())) {
                // 附魔书交易归 book 子命令，item 的补全里不要再出现它
                continue;
            }
            itemSuggestions.add(id.toString());
        }
        for (String idText : new LinkedHashSet<>(itemSuggestions)) {
            String alias = IdTranslationConfig.displayNameOf(idText);
            if (alias != null && !alias.isBlank()) {
                itemSuggestions.add(alias);
            }
        }

        Set<String> enchantmentSuggestions = new LinkedHashSet<>(enchantmentIds.size() * 3);
        for (Identifier id : enchantmentIds) {
            enchantmentSuggestions.add(id.getPath());               // mending
            enchantmentSuggestions.add(id.toString());              // minecraft:mending
            String alias = IdTranslationConfig.displayNameOf(id);   // 经验修补
            if (alias != null && !alias.isBlank()) {
                enchantmentSuggestions.add(alias);
            }
        }

        CACHED_TRADE_ITEM_IDS = Collections.unmodifiableSet(itemIds);
        CACHED_ENCHANTMENT_IDS = Collections.unmodifiableSet(enchantmentIds);
        CACHED_ITEM_SUGGESTIONS = unmodifiableSorted(itemSuggestions);
        CACHED_ENCHANTMENT_SUGGESTIONS = unmodifiableSorted(enchantmentSuggestions);
        log.info("已动态收集到 {} 种村民交易关联物品、{} 种附魔，用于补全", itemIds.size(), enchantmentIds.size());
        log.debug("交易补全缓存刷新完成：村民交易 {} 笔（跳过流浪商人 {} 笔）；"
                        + "item 补全 {} 条、book 补全 {} 条；物品 id = {}",
                trades, skipped, itemSuggestions.size(), enchantmentSuggestions.size(), itemIds);
        log.debug("item 补全项（{} 条）= {}", itemSuggestions.size(), itemSuggestions);
        log.debug("book 补全项（{} 条）= {}", enchantmentSuggestions.size(), enchantmentSuggestions);
    }

    /**
     * 排序后包装成不可变集合：注册表遍历顺序不稳定（{@code HashMap} 支撑），
     * 排序能保证每次按 Tab 出来的顺序都一样。
     */
    private static Set<String> unmodifiableSorted(Set<String> raw) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(raw)));
    }

    /**
     * 村民职业实际能挂出来的交易（{@code profession × level → TradeSet → trades}）。
     *
     * <p>为什么要这一层：{@code VILLAGER_TRADE} 注册表里混着 <b>{@code wandering_trader}</b> 的
     * 交易（花、珊瑚、树苗、海龟壳……），而本模组的索引只收录 {@code Villager} 实体
     * （{@code WanderingTrader} 不在其中），那些物品永远不可能被搜到 —— 放进补全只会让列表
     * 显得「什么方块都有」。
     *
     * <p>返回空集表示拿不到职业表（TRADE_SET 不可用等），调用方应退回全表扫描。
     */
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
        // VillagerProfession.NONE 是个 ResourceKey（不是实例），从注册表取回实例再比
        VillagerProfession none = BuiltInRegistries.VILLAGER_PROFESSION.getValue(VillagerProfession.NONE);
        for (VillagerProfession profession : BuiltInRegistries.VILLAGER_PROFESSION) {
            if (profession == null || profession == none) {
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
                if (members == null) {
                    continue;
                }
                for (Holder<VillagerTrade> holder : members) {
                    holder.unwrapKey().ifPresent(trades::add);
                }
            }
        }
        return trades;
    }

    /**
     * {@link TradeCost} 里的付款物品。
     */
    private static void collect(TradeCost cost, Set<Identifier> ids) {
        if (cost == null) {
            return;
        }
        collectItem(cost.item(), ids);
    }

    /**
     * {@code Holder<Item>} → 注册 id。空气（理论上不该出现）和拿不到 id 的条目直接跳过。
     */
    private static void collectItem(Holder<Item> holder, Set<Identifier> ids) {
        if (holder == null) {
            return;
        }
        Item item = holder.value();
        if (item == null || item == Items.AIR) {
            return;
        }
        try {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                ids.add(id);
            }
        } catch (Exception e) {
            // 物品不在 ITEM 注册表里（理论上不可能）：跳过这一条，不要让整轮刷新失败
            log.debug("跳过不在 ITEM 注册表里的物品：{}", item);
        }
    }

    /**
     * 交易「卖出物」自带的附魔（{@code set_components} 写法：{@code ENCHANTMENTS} /
     * {@code STORED_ENCHANTMENTS}）。
     *
     * <p>直接 {@code create()} 一个 ItemStack 再读组件：{@link ItemStackTemplate} 的
     * {@code components()} 是 patch，读起来要传原型，不如让原版自己把栈拼出来 ——
     * 每笔交易一次、只在启动 / 重载时做，代价可以忽略。
     */
    private static void collectEnchantments(ItemStackTemplate gives, Set<Identifier> out) {
        ItemStack stack;
        try {
            stack = gives.create();
        } catch (Exception e) {
            log.debug("创建交易结果物品栈失败，跳过其自带附魔：{}", gives, e);
            return;
        }
        if (stack == null || stack.isEmpty()) {
            return;
        }
        for (TradeItemInfo.EnchantmentEntry enchantment : TradeItemNameResolver.enchantments(stack)) {
            Identifier id = Identifier.tryParse(enchantment.id());
            if (id != null) {
                out.add(id);
            }
        }
    }

    /**
     * 一个附魔候选（{@code enchant_randomly} 的 options 元素）。
     *
     * <p>用 {@code unwrapKey()} 拿 id：1.21.9+ 的附魔是<b>动态注册表</b>，
     * {@code BuiltInRegistries} 里没有 {@code ENCHANTMENT} 入口；而 options 里的元素
     * 都是注册表里的 {@code Holder.Reference}，本来就带着 key。
     */
    private static void collectEnchantment(Holder<Enchantment> holder, Set<Identifier> out) {
        if (holder == null) {
            return;
        }
        try {
            holder.unwrapKey().map(key -> key.identifier()).ifPresent(out::add);
        } catch (Exception e) {
            log.debug("跳过拿不到 id 的附魔：{}", holder);
        }
    }

    /**
     * 兜底：把服务器认识的附魔全塞进去（动态注册表，走 {@code registryAccess}）。
     */
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
    // 读
    // ============================================================

    /**
     * 交易注册表里出现过的物品 id（不可变快照，可以放心遍历）。
     */
    public static Set<Identifier> getTradeItemIds() {
        return CACHED_TRADE_ITEM_IDS;
    }

    /**
     * 交易里可能产出的附魔 id（不可变快照）。
     */
    public static Set<Identifier> getTradeEnchantmentIds() {
        return CACHED_ENCHANTMENT_IDS;
    }

    /**
     * {@code item} 子命令的 Tab 补全项：物品 id + 中文叫法（不含附魔书）。
     * 缓存还没刷过（服务端启动早期）时返回空集合，调用方自己决定要不要退回静态表。
     */
    public static Set<String> getItemSuggestions() {
        return CACHED_ITEM_SUGGESTIONS;
    }

    /**
     * {@code book} 子命令的 Tab 补全项：附魔短 id / 完整 id / 中文叫法。
     */
    public static Set<String> getEnchantmentSuggestions() {
        return CACHED_ENCHANTMENT_SUGGESTIONS;
    }
}
