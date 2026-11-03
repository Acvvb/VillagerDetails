package com.villagerdetails.cache.impl;

import com.villagerdetails.config.impl.IdTranslationConfig;
import com.villagerdetails.mixin.villager.trader.autoTrader.VillagerTradeMixin;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
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
 * Tab 补全用的「交易关联物品」缓存：<b>直接遍历 {@code VILLAGER_TRADE} 注册表</b>动态收集。
 *
 * <p>为什么不再用静态表：26.x 的村民交易彻底数据驱动化（{@code data/<ns>/villager_trade/…} +
 * {@code trade_set} 注册表），数据包 / 模组可以随时往里加新交易。写死一份物品清单必然漏 ——
 * 玩家补全不到那些新交易里出现的物品。所以这里在<b>服务端启动</b>和<b>数据包重载</b>后各刷一次：
 *
 * <ol>
 *     <li>遍历 {@code Registries.VILLAGER_TRADE} 里每一笔 {@link VillagerTrade}；</li>
 *     <li>取 {@code wants}（付什么）、{@code additionalWants}（可选的第二样付款物）、
 *         {@code gives}（卖什么）三处涉及的<b>基础物品 id</b>（附魔书只取
 *         {@code minecraft:enchanted_book}，附魔名由客户端渲染，不参与补全）；</li>
 *     <li>去重后整体替换缓存（不可变集合，读侧无锁）。</li>
 * </ol>
 *
 * <p><b>为什么用 mixin accessor 读</b>：26.x 的 {@link VillagerTrade} 是普通类，字段全部
 * {@code private} 且<b>没有任何 getter</b>（只有需要 {@code LootContext} 的 {@code getOffer()}）。
 * 项目里本来就有 {@link VillagerTradeMixin}（读 {@code givenItemModifier}），这里给它补上
 * {@code wants} / {@code additionalWants} 的 accessor 复用同一套做法。
 *
 * <p>顺带把每个 id 的中文叫法（{@code IdTranslationConfig}：{@code trade_index.json} + 内置兜底）
 * 一起塞进补全项 —— 只对「注册表里真的出现过」的 id 取名，所以不会补出无关条目。
 * 搜索本身既认 id 也认这些叫法，两边保持一致。
 *
 * <p><b>线程</b>：{@link #refresh} 只在服务端线程（启动 / 重载回调）跑；读侧是 Tab 补全（服务端线程），
 * 但缓存用 {@code volatile} 的整体替换，即便从别处读也不会看到半成品。
 */
public final class TradeItemCompletionCache {

    private TradeItemCompletionCache() {
    }

    private static final Logger log = LogManager.getLogger(TradeItemCompletionCache.class);

    /**
     * 交易注册表里出现过的物品 id（不可变快照）。
     */
    private static volatile Set<Identifier> CACHED_TRADE_ITEM_IDS = Collections.emptySet();

    /**
     * 补全项：{@code id} + 它的中文叫法（如果有），顺序 = 先全部 id、再全部叫法。
     */
    private static volatile Set<String> CACHED_SUGGESTIONS = Collections.emptySet();

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

        Set<Identifier> ids = new LinkedHashSet<>();
        int trades = 0;
        for (Map.Entry<ResourceKey<VillagerTrade>, VillagerTrade> entry : registry.entrySet()) {
            VillagerTrade trade = entry.getValue();
            if (trade == null) {
                continue;
            }
            trades++;
            // 字段全 private 且没有 getter：只能走 accessor（见类注释）
            VillagerTradeMixin accessor = (VillagerTradeMixin) trade;
            collect(accessor.getWants(), ids);
            Optional<TradeCost> additional = accessor.getAdditionalWants();
            if (additional != null) {
                additional.ifPresent(cost -> collect(cost, ids));
            }
            ItemStackTemplate gives = accessor.getGives();
            if (gives != null) {
                collectItem(gives.item(), ids);
            }
        }

        Set<String> suggestions = new LinkedHashSet<>(ids.size() * 2);
        for (Identifier id : ids) {
            suggestions.add(id.toString());
        }
        for (Identifier id : ids) {
            String alias = IdTranslationConfig.displayNameOf(id);
            if (alias != null && !alias.isBlank()) {
                suggestions.add(alias);
            }
        }

        CACHED_TRADE_ITEM_IDS = Collections.unmodifiableSet(ids);
        CACHED_SUGGESTIONS = Collections.unmodifiableSet(suggestions);
        log.info("已动态收集到 {} 种村民交易关联物品，用于补全", ids.size());
        log.debug("交易补全缓存刷新完成：{} 笔交易，{} 条补全项，物品 id = {}",
                trades, suggestions.size(), ids);
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
     * Tab 补全项：id + 中文叫法。缓存还没刷过（服务端启动早期）时返回空集合，
     * 调用方自己决定要不要退回静态表。
     */
    public static Set<String> getSuggestions() {
        return CACHED_SUGGESTIONS;
    }
}
