package com.villagerdetails.handler.entity.villager.trader.refresh;

import com.villagerdetails.mixin.entity.villager.trader.autoTrader.book.EnchantRandomlyFunctionMixin;
import com.villagerdetails.mixin.villager.trader.autoTrader.VillagerTradeMixin;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;

import java.util.*;
import java.util.function.Predicate;

/**
 * 「某个职业的某个等级能刷出什么」的静态判定：只看数据包里的
 * {@code VILLAGER_TRADE} / {@code TRADE_SET} 注册表，<b>不生成任何报价</b>。
 *
 * <p>两条使用路径：
 * <ul>
 *     <li>带 {@code Villager} 的重载：老的便捷版本，内部取实体的职业 / 注册表；</li>
 *     <li>带 {@link Holder}{@code <VillagerProfession>} + {@link RegistryAccess} 的重载：
 *     <b>不依赖实体</b>，给「命名牌点名刷取」判断最低可出等级用
 *     （见 {@link #requiredLevel}）。</li>
 * </ul>
 */
public final class TradePoolChecker {

    private TradePoolChecker() {}

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 5;

    /**
     * 该职业没有任何等级能产出目标。
     */
    public static final int NO_LEVEL = -1;

    // ==============================================================
    // 条件构造器
    // ==============================================================

    /**
     * 「这笔交易的结果物品就是这个」。
     */
    public static Predicate<VillagerTrade> givesItem(Item item) {
        if (item == null) return trade -> false;
        return trade -> trade != null && gives(trade).item().value() == item;
    }

    /**
     * 「这笔交易能刷出这个附魔」——只看 {@code enchant_randomly} 的候选池，不看具体随机结果。
     */
    public static Predicate<VillagerTrade> givesEnchantment(Enchantment target) {
        if (target == null) return trade -> false;
        return trade -> trade != null && canProduceEnchantment(trade, target);
    }

    // ==============================================================
    // 物品检测
    // ==============================================================

    public static boolean canProduceAtLevel(Villager villager, int level, Item item) {
        if (item == null) return false;
        return listLevel(villager, level).contains(item);
    }

    public static boolean canProduceAtAnyLevel(Villager villager, Item item) {
        if (item == null) return false;
        for (int lv = MIN_LEVEL; lv <= MAX_LEVEL; lv++) {
            if (canProduceAtLevel(villager, lv, item)) return true;
        }
        return false;
    }

    // ==============================================================
    // 附魔书检测
    // ==============================================================

    public static boolean canProduceEnchantmentAtLevel(Villager villager, int level, Enchantment target) {
        if (target == null) return false;
        if (!(villager.level() instanceof ServerLevel sl)) return false;
        return canProduceEnchantmentAtLevel(sl.registryAccess(),
                villager.getVillagerData().profession(), level, target);
    }

    /**
     * 不依赖实体实例的重载：只要注册表和职业就能判断某个等级能不能刷出目标附魔。
     */
    public static boolean canProduceEnchantmentAtLevel(RegistryAccess registryAccess,
                                                       Holder<VillagerProfession> profession,
                                                       int level,
                                                       Enchantment target) {
        if (target == null) return false;
        return !candidatesAtLevel(profession, registryAccess, level, givesEnchantment(target)).isEmpty();
    }

    public static boolean canProduceEnchantmentAtAnyLevel(Villager villager, Enchantment target) {
        for (int lv = MIN_LEVEL; lv <= MAX_LEVEL; lv++) {
            if (canProduceEnchantmentAtLevel(villager, lv, target)) return true;
        }
        return false;
    }

    /**
     * 这笔交易（附魔书 / 书）能不能刷出目标附魔。
     *
     * <p>26.2 的 {@code given_item_modifiers} 是平铺的 {@code List<LootItemFunction>}，
     * JSON 数组里的每一项各自解码，<b>不存在</b> 26.3 那种「数组被折成
     * {@code SequenceFunction}」的情况，所以逐个 {@code instanceof} 即可。
     */
    private static boolean canProduceEnchantment(VillagerTrade trade, Enchantment target) {
        ItemStackTemplate gives = gives(trade);
        if (gives.item().value() != Items.ENCHANTED_BOOK
                && gives.item().value() != Items.BOOK) return false;

        for (LootItemFunction modifier : givenModifiers(trade)) {
            if (!(modifier instanceof EnchantRandomlyFunction erf)) continue;

            Optional<HolderSet<Enchantment>> options =
                    ((EnchantRandomlyFunctionMixin) erf).getOptions();
            if (options.isEmpty()) return true;   // 任意附魔

            for (Holder<Enchantment> h : options.get()) {
                if (h.value() == target) return true;
            }
        }
        return false;
    }

    // ==============================================================
    // 列出物品 / 交易
    // ==============================================================

    public static Set<Item> listLevel(Villager villager, int level) {
        if (!(villager.level() instanceof ServerLevel sl)) return Set.of();
        VillagerData data = villager.getVillagerData();
        if (data.profession().is(VillagerProfession.NONE)) return Set.of();
        return listLevel(data.profession(), sl.registryAccess(), level);
    }

    /**
     * 不依赖实体：某职业某等级交易集里出现的全部结果物品。
     */
    public static Set<Item> listLevel(Holder<VillagerProfession> profession,
                                      RegistryAccess registryAccess,
                                      int level) {
        Set<Item> items = new LinkedHashSet<>();
        for (Holder<VillagerTrade> holder : holdersAtLevel(profession, registryAccess, level)) {
            items.add(gives(holder.value()).item().value());
        }
        return items.isEmpty() ? Set.of() : Collections.unmodifiableSet(items);
    }

    /**
     * 某职业某等级的<b>全部候选交易</b>（没命中 / 没数据时返回空列表，永远不返回 {@code null}）。
     */
    public static List<Holder<VillagerTrade>> holdersAtLevel(Holder<VillagerProfession> profession,
                                                             RegistryAccess registryAccess,
                                                             int level) {
        TradeSet tradeSet = tradeSet(profession, registryAccess, level);
        if (tradeSet == null) return List.of();

        HolderSet<VillagerTrade> trades = TradeSetAdapter.trades(tradeSet);
        if (trades == null) return List.of();

        List<Holder<VillagerTrade>> out = new ArrayList<>();
        for (Holder<VillagerTrade> holder : trades) {
            out.add(holder);
        }
        return out;
    }

    /**
     * 该职业某等级里满足条件的候选交易。
     */
    public static List<Holder<VillagerTrade>> candidatesAtLevel(Holder<VillagerProfession> profession,
                                                                RegistryAccess registryAccess,
                                                                int level,
                                                                Predicate<VillagerTrade> matcher) {
        if (matcher == null) return List.of();
        List<Holder<VillagerTrade>> out = new ArrayList<>();
        for (Holder<VillagerTrade> holder : holdersAtLevel(profession, registryAccess, level)) {
            if (matcher.test(holder.value())) {
                out.add(holder);
            }
        }
        return out;
    }

    /**
     * 最低能刷出目标的等级（1~5）；整个职业都刷不出时返回 {@link #NO_LEVEL}。
     *
     * <p>这是「命名牌点名刷取」的可行性判定：返回 -1 就说明这个职业的任何一个等级
     * 都不会生成目标交易，继续重掷多少次也没用。
     */
    public static int requiredLevel(Holder<VillagerProfession> profession,
                                    RegistryAccess registryAccess,
                                    Predicate<VillagerTrade> matcher) {
        if (matcher == null) return NO_LEVEL;
        for (int lv = MIN_LEVEL; lv <= MAX_LEVEL; lv++) {
            if (!holdersAtLevel(profession, registryAccess, lv).isEmpty()
                    && !candidatesAtLevel(profession, registryAccess, lv, matcher).isEmpty()) {
                return lv;
            }
        }
        return NO_LEVEL;
    }

    /**
     * 该职业该等级的交易集（没有数据包数据 / 该等级无 set 时返回 {@code null}）。
     */
    public static TradeSet tradeSet(Holder<VillagerProfession> profession,
                                    RegistryAccess registryAccess,
                                    int level) {
        if (profession == null || registryAccess == null) return null;
        if (profession.is(VillagerProfession.NONE)) return null;

        ResourceKey<TradeSet> key = profession.value().getTrades(level);
        if (key == null) return null;

        Registry<TradeSet> registry = registryAccess.lookupOrThrow(Registries.TRADE_SET);
        return registry.getValue(key);
    }

    // ==============================================================
    // 版本适配
    // ==============================================================

    private static ItemStackTemplate gives(VillagerTrade trade) {
        return ((VillagerTradeMixin) trade).getGives();
    }

    /**
     * 26.2 是平铺的 {@code List<LootItemFunction>}，直接返回。
     */
    private static List<LootItemFunction> givenModifiers(VillagerTrade trade) {
        List<LootItemFunction> modifiers =
                ((VillagerTradeMixin) trade).getGivenItemModifiers();
        return modifiers == null ? List.of() : modifiers;
    }
}
