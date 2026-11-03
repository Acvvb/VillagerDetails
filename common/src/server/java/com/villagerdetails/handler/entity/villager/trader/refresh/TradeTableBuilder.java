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
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * <b>自播种</b>的交易表生成器：不再调用 {@code Villager#updateTrades}，因此
 * <ul>
 *     <li>不依赖（也不修改）{@code villagerData.level}，想要 1..n 级就自己按级取 set；</li>
 *     <li>不动世界随机序列（{@code RandomSequences} 存档数据），随机源由
 *     {@link #seedFor(UUID, int)} 按「村民 UUID + 第几次尝试」派生，结果可复现；</li>
 *     <li>可以「定向构造」：把指定交易直接放进目标等级，保证必中。</li>
 * </ul>
 *
 * <p>生成规则对齐原版 {@code AbstractVillager#addOffersFromTradeSet}：
 * 用 {@link LootContextParamSets#VILLAGER_TRADE} 建上下文（ORIGIN / THIS_ENTITY /
 * ADDITIONAL_COST_COMPONENT_ALLOWED），取 {@link TradeSet#calculateNumberOfTrades} 笔，
 * 遵循 {@link TradeSet#allowDuplicates()} 的「允许 / 不允许重复」两种抽取语义，
 * {@code getOffer} 返回 {@code null} 的交易从候选池里剔除（原版就是这样处理
 * {@code merchant_predicate} 不满足的情况）。
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
    // 纯重掷（331a / 331b / 陶瓦这类「拒绝采样」路线用）
    // ==============================================================

    /**
     * 按原版分布生成 1..{@code upToLevel} 级的交易表。
     */
    public static MerchantOffers build(ServerLevel level, Villager villager, int upToLevel, RandomSource random) {
        return buildUpTo(level, villager, upToLevel, -1, null, random);
    }

    // ==============================================================
    // 定向构造（命名牌点名刷取）
    // ==============================================================

    /**
     * 一笔「必须出现」的交易：候选池 + 结果校验 + 结果改写。
     *
     * @param candidates 该等级里能产出目标的交易（{@link TradePoolChecker#candidatesAtLevel}）
     * @param accept     结果校验；{@code null} 表示不校验
     * @param shaper     结果改写（附魔书用：直接把目标附魔塞进书里）；{@code null} 表示不改
     */
    public record Placement(List<Holder<VillagerTrade>> candidates,
                            Predicate<ItemStack> accept,
                            UnaryOperator<ItemStack> shaper) {

        public Placement {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        static boolean usable(Placement placement) {
            return placement != null && !placement.candidates().isEmpty();
        }
    }

    /**
     * 定向构造：把所有 {@code placements} 的交易<b>一定</b>放进 {@code requiredLevel} 级
     * （陶瓦那种「普通 + 釉陶」成对需求就是两个 placement），
     * 其余槽位仍按原版分布补齐；低等级（1..requiredLevel-1）照常随机生成。
     *
     * @return 1..requiredLevel 级的交易表；槽位不够或目标交易一个都放不进去时返回 {@code null}
     */
    public static MerchantOffers buildTargeted(ServerLevel level,
                                               Villager villager,
                                               int requiredLevel,
                                               List<Placement> placements,
                                               RandomSource random) {
        if (placements == null || placements.isEmpty()) return null;
        for (Placement placement : placements) {
            if (!Placement.usable(placement)) return null;
        }
        return buildUpTo(level, villager, requiredLevel, requiredLevel, placements, random);
    }

    // ==============================================================
    // 实现
    // ==============================================================

    private static MerchantOffers buildUpTo(ServerLevel level,
                                            Villager villager,
                                            int upToLevel,
                                            int targetLevel,
                                            List<Placement> placements,
                                            RandomSource random) {
        int maxLevel = Math.max(0, Math.min(upToLevel, TradePoolChecker.MAX_LEVEL));
        Holder<VillagerProfession> profession = villager.getVillagerData().profession();
        RegistryAccess registryAccess = level.registryAccess();
        MerchantOffers offers = new MerchantOffers();
        if (maxLevel <= 0 || random == null) return offers;

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

            int placed = 0;

            // ── 定向：先把「必须出现」的交易塞进这一级，保证必中 ──
            if (lv == targetLevel && placements != null && !placements.isEmpty()) {
                if (amount < placements.size()) return null;     // 槽位不够放全部目标
                List<Holder<VillagerTrade>> taken = new ArrayList<>(placements.size());
                for (Placement placement : placements) {
                    Shaped chosen = pick(placement, context, random, taken);
                    if (chosen == null) return null;              // 有目标放不进去 → 整体失败
                    offers.add(chosen.offer());
                    taken.add(chosen.trade());
                    placed++;
                }
                if (!allowDuplicates) {
                    for (Holder<VillagerTrade> chosen : taken) {
                        pool.remove(chosen);
                    }
                }
            }

            // ── 补齐剩余槽位：完全照抄原版抽取语义 ──
            int remaining = amount - placed;
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

    /**
     * 从候选池里挑一笔能用的：随机起点绕一圈，跳过已经用过的，返回 {@code null} 表示一个都不行。
     */
    private static Shaped pick(Placement placement,
                               LootContext context,
                               RandomSource random,
                               List<Holder<VillagerTrade>> taken) {
        List<Holder<VillagerTrade>> candidates = placement.candidates();
        int offset = random.nextInt(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Holder<VillagerTrade> candidate = candidates.get((offset + i) % candidates.size());
            if (taken.contains(candidate)) continue;
            Shaped shaped = shape(candidate, context, placement.accept(), placement.shaper());
            if (shaped != null) return shaped;
        }
        return null;
    }

    /**
     * 一笔「挑中并改造完」的报价。
     */
    private record Shaped(Holder<VillagerTrade> trade, MerchantOffer offer) {
    }

    /**
     * 单笔候选 → 可用的报价：可能为 {@code null}（条件不满足 / 结果不符）。
     */
    private static Shaped shape(Holder<VillagerTrade> candidate,
                                LootContext context,
                                Predicate<ItemStack> accept,
                                UnaryOperator<ItemStack> resultShaper) {
        MerchantOffer raw = candidate.value().getOffer(context);
        if (raw == null) return null;

        ItemStack result = raw.getResult();
        if (result == null || result.isEmpty()) return null;

        if (resultShaper == null && accept == null) return new Shaped(candidate, raw);

        ItemStack shaped = result;
        if (resultShaper != null) {
            shaped = resultShaper.apply(result.copy());
            if (shaped == null || shaped.isEmpty()) return null;
        }
        if (accept != null && !accept.test(shaped)) return null;
        if (shaped == result) return new Shaped(candidate, raw);

        MerchantOffer rebuilt = new MerchantOffer(raw.getItemCostA(), raw.getItemCostB(), shaped,
                raw.getMaxUses(), raw.getXp(), raw.getPriceMultiplier());
        return new Shaped(candidate, rebuilt);
    }
}
