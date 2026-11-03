package com.villagerdetails.cache.impl;

import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.handler.entity.villager.trade.TradeItemNameResolver;
import com.villagerdetails.handler.entity.villager.trader.refresh.TradeEnchantmentScanner;
import com.villagerdetails.mixin.villager.trader.autoTrader.VillagerTradeMixin;
import com.villagerdetails.pojo.dto.TradeItemInfo;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.TradeCost;
import net.minecraft.world.item.trading.VillagerTrade;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

        Set<Identifier> itemIds = new LinkedHashSet<>();
        Set<Identifier> enchantmentIds = new LinkedHashSet<>();
        int trades = 0;
        for (Map.Entry<ResourceKey<VillagerTrade>, VillagerTrade> entry : registry.entrySet()) {
            VillagerTrade trade = entry.getValue();
            if (trade == null) {
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
        CACHED_ITEM_SUGGESTIONS = Collections.unmodifiableSet(itemSuggestions);
        CACHED_ENCHANTMENT_SUGGESTIONS = Collections.unmodifiableSet(enchantmentSuggestions);
        log.info("已动态收集到 {} 种村民交易关联物品、{} 种附魔，用于补全",
                itemIds.size(), enchantmentIds.size());
        log.debug("交易补全缓存刷新完成：{} 笔交易；物品 {} 条、附魔 {} 条补全项；物品 id = {}",
                trades, itemSuggestions.size(), enchantmentSuggestions.size(), itemIds);
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
