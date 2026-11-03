package com.villagerdetails.handler.entity.villager.trader.refresh;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>自播种</b>的交易表生成器：完全按原版的抽取规则重掷 1..n 级交易，
 * <b>不做任何「定向插入」</b>（塞目标交易等于改概率，这里不干）。
 *
 * <p>不调用 {@code Villager#updateTrades} 的好处：
 * <ul>
 *     <li>不依赖（也不修改）{@code villagerData.level}，想要 1..n 级就自己按级取 set；</li>
 *     <li>不动世界随机序列（{@code RandomSequences} 存档数据），随机源由
 *     {@link #seedFor(UUID, int)} 按「村民 UUID + 第几次尝试」派生，结果可复现；</li>
 *     <li>可以在一帧里连掷几十上百次而不影响实体状态，配合
 *     {@link TradeRerollScheduler} 做分帧拒绝采样。</li>
 * </ul>
 *
 * <p>抽取规则对齐原版 {@code AbstractVillager#addOffersFromTradeSet} 里那个
 * 包私有的 {@code addOffersFromItemListings}：用 {@link LootContextParamSets#VILLAGER_TRADE}
 * 建上下文（ORIGIN / THIS_ENTITY / ADDITIONAL_COST_COMPONENT_ALLOWED），
 * 取 {@link TradeSet#calculateNumberOfTrades} 笔，遵循 {@link TradeSet#allowDuplicates()}
 * 的「允许 / 不允许重复」两种抽取语义，{@code getOffer} 返回 {@code null} 的交易
 * 从候选池剔除（原版就是这样处理 {@code merchant_predicate} 不满足的情况）。
 */
public final class TradeTableBuilder {

    private TradeTableBuilder() {
    }

    /**
     * 补齐剩余槽位时的防死循环上限：正常情况一轮就够，这里只是兜住
     * 「候选池里全是 {@code merchant_predicate} 不满足」的病态数据包。
     */
    private static final int FILL_GUARD = 64;

    // ==============================================================
    // 种子
    // ==============================================================

    /**
     * 由「村民 UUID + 第几次尝试」派生种子：同一次尝试结果可复现，
     * 不同村民 / 不同尝试互不干扰，且完全不碰世界随机序列。
     */
    public static long seedFor(UUID uuid, int attempt) {
        long h = uuid.getMostSignificantBits();
        h = h * 0x9E3779B97F4A7C15L ^ uuid.getLeastSignificantBits();
        h ^= 0x632BE59BD9B4E019L * (attempt + 1L);
        // murmur3 尾部混淆，避免低位规律直接漏进 Xoroshiro
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    // ==============================================================
    // 生成
    // ==============================================================

    /**
     * 按原版分布生成 1..{@code upToLevel} 级的交易表（纯抽样，不改实体）。
     */
    public static MerchantOffers build(ServerLevel level, Villager villager, int upToLevel, RandomSource random) {
        MerchantOffers offers = new MerchantOffers();
        int maxLevel = Math.max(0, Math.min(upToLevel, TradePoolChecker.MAX_LEVEL));
        if (maxLevel <= 0 || random == null) return offers;

        Holder<VillagerProfession> profession = villager.getVillagerData().profession();
        RegistryAccess registryAccess = level.registryAccess();

        // 原版建上下文的参数（见 AbstractVillager#addOffersFromTradeSet）
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, villager.position())
                .withParameter(LootContextParams.THIS_ENTITY, villager)
                .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
                .create(LootContextParamSets.VILLAGER_TRADE);

        // 只用自己塞进去的随机源：create(empty) 时 LootContext 直接用它，
        // 不会退到 worldRandom / 世界随机序列
        LootContext context = new LootContext.Builder(params)
                .withOptionalRandomSource(random)
                .create(Optional.empty());

        for (int lv = 1; lv <= maxLevel; lv++) {
            TradeSet tradeSet = TradePoolChecker.tradeSet(profession, registryAccess, lv);
            if (tradeSet == null) continue;

            int amount = Math.max(0, tradeSet.calculateNumberOfTrades(context));
            if (amount == 0) continue;

            boolean allowDuplicates = tradeSet.allowDuplicates();
            List<Holder<VillagerTrade>> pool =
                    new ArrayList<>(TradePoolChecker.holdersAtLevel(profession, registryAccess, lv));
            if (pool.isEmpty()) continue;

            int remaining = amount;
            int guard = remaining + FILL_GUARD;
            while (remaining > 0 && !pool.isEmpty() && guard-- > 0) {
                int idx = random.nextInt(pool.size());
                MerchantOffer offer = pool.get(idx).value().getOffer(context);
                if (offer == null) {
                    pool.remove(idx);                            // 条件不满足：剔除，不占名额
                    continue;
                }
                offers.add(offer);
                remaining--;
                if (!allowDuplicates) pool.remove(idx);
            }
        }
        return offers;
    }

    // ==============================================================
    // 命中判定
    // ==============================================================

    /**
     * 这份表里第一笔「结果满足条件」的交易下标；没有则返回 {@code -1}。
     */
    public static int indexOfMatch(MerchantOffers offers, java.util.function.Predicate<ItemStack> test) {
        if (offers == null || test == null) return -1;
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            ItemStack result = offer == null ? null : offer.getResult();
            if (result != null && !result.isEmpty() && test.test(result)) return i;
        }
        return -1;
    }

    /**
     * 所有条件都要有对应的交易（陶瓦那种「普通 + 釉陶」成对需求）。
     */
    public static boolean containsAll(MerchantOffers offers, List<java.util.function.Predicate<ItemStack>> tests) {
        if (offers == null || tests == null || tests.isEmpty()) return false;
        for (java.util.function.Predicate<ItemStack> test : tests) {
            if (indexOfMatch(offers, test) < 0) return false;
        }
        return true;
    }
}
