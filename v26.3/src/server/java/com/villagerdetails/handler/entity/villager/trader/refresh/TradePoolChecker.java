package com.villagerdetails.handler.entity.villager.trader.refresh;

import com.villagerdetails.mixin.entity.villager.trader.autoTrader.book.EnchantRandomlyFunctionMixin;
import com.villagerdetails.mixin.villager.trader.autoTrader.SequenceFunctionMixin;
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
import net.minecraft.world.level.storage.loot.functions.SequenceFunction;

import java.util.*;

public final class TradePoolChecker {

    private TradePoolChecker() {}

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 5;

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
     * 除了给上面的便捷版复用，也方便在启动期做自检（不需要真的生成一个村民）。
     */
    public static boolean canProduceEnchantmentAtLevel(RegistryAccess registryAccess,
                                                       Holder<VillagerProfession> profession,
                                                       int level,
                                                       Enchantment target) {
        if (target == null || profession == null) return false;
        if (profession.is(VillagerProfession.NONE)) return false;

        ResourceKey<TradeSet> key = profession.value().getTrades(level);
        if (key == null) return false;

        Registry<TradeSet> registry = registryAccess.lookupOrThrow(Registries.TRADE_SET);
        TradeSet tradeSet = registry.getValue(key);
        if (tradeSet == null) return false;

        for (Holder<VillagerTrade> holder : tradeSetTrades(tradeSet)) {
            VillagerTradeMixin acc = (VillagerTradeMixin) holder.value();
            ItemStackTemplate gives = acc.getGives();

            if (gives.item().value() != Items.ENCHANTED_BOOK
                    && gives.item().value() != Items.BOOK) continue;

            // 26.3：given_item_modifier 变成 Optional<Holder<LootItemFunction>>，
            // 数组写法会被 LootItemFunctions.CODEC 折成一层 SequenceFunction，必须递归拆
            Optional<Holder<LootItemFunction>> modifier = acc.getGivenItemModifiers();
            if (modifier.isEmpty()) continue;
            if (canProduceEnchantment(modifier.get().value(), target)) return true;
        }
        return false;
    }

    /**
     * 在（可能嵌套的）战利品函数里找 {@code enchant_randomly}，看它能不能产出目标附魔。
     *
     * <p>为什么要递归：26.2 的 {@code VillagerTrade} 存的是 {@code List<LootItemFunction>}，
     * 每个函数都是平的；26.3 改成单个 {@code Holder<LootItemFunction>}，而
     * <b>JSON 里写成数组就会被折成一个 {@link SequenceFunction}</b> ——
     * 图书管理员那笔附魔书交易正是 {@code [enchant_randomly, filtered]} 两段，
     * 于是拿到手的是 {@code SequenceFunction} 而不是 {@code EnchantRandomlyFunction}。
     * 只做一层 {@code instanceof} 的话，所有附魔都会被判成「这个村民刷不出来」。
     */
    private static boolean canProduceEnchantment(LootItemFunction function, Enchantment target) {
        if (function instanceof EnchantRandomlyFunction erf) {
            Optional<HolderSet<Enchantment>> options =
                    ((EnchantRandomlyFunctionMixin) erf).getOptions();

            if (options.isEmpty()) return true;   // 任意附魔

            for (Holder<Enchantment> h : options.get()) {
                if (h.value() == target) return true;
            }
            return false;
        }

        // 数组折叠成的序列函数：逐个成员再看，允许嵌套
        if (function instanceof SequenceFunction sequence) {
            for (Holder<LootItemFunction> nested : ((SequenceFunctionMixin) sequence).getFunctions()) {
                if (canProduceEnchantment(nested.value(), target)) return true;
            }
        }
        return false;
    }

    public static boolean canProduceEnchantmentAtAnyLevel(Villager villager, Enchantment target) {
        for (int lv = MIN_LEVEL; lv <= MAX_LEVEL; lv++) {
            if (canProduceEnchantmentAtLevel(villager, lv, target)) return true;
        }
        return false;
    }

    // ==============================================================
    // 列出物品
    // ==============================================================

    public static Set<Item> listLevel(Villager villager, int level) {
        if (!(villager.level() instanceof ServerLevel sl)) return Set.of();
        VillagerData data = villager.getVillagerData();
        if (data.profession().is(VillagerProfession.NONE)) return Set.of();

        ResourceKey<TradeSet> key = data.profession().value().getTrades(level);
        if (key == null) return Set.of();

        Registry<TradeSet> registry = sl.registryAccess().lookupOrThrow(Registries.TRADE_SET);
        TradeSet tradeSet = registry.getValue(key);
        if (tradeSet == null) return Set.of();

        Set<Item> items = new LinkedHashSet<>();
        for (Holder<VillagerTrade> holder : tradeSetTrades(tradeSet)) {
            ItemStackTemplate gives = ((VillagerTradeMixin) holder.value()).getGives();
            items.add(gives.item().value());
        }
        return Collections.unmodifiableSet(items);
    }

    /**
     * 26.3 把 {@code TradeSet} 改成了 record，{@code getTrades()} 随之改名成 {@code trades()}。
     */
    private static HolderSet<VillagerTrade> tradeSetTrades(TradeSet tradeSet) {

        return tradeSet.trades();



    }

}