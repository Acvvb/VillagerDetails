package com.villagerdetails.handler.villager.trader.refresh;

import com.villagerdetails.mixin.EnchantRandomlyFunctionMixin;
import com.villagerdetails.mixin.VillagerTradeMixin;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
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

        VillagerData data = villager.getVillagerData();
        if (data.profession().is(VillagerProfession.NONE)) return false;

        ResourceKey<TradeSet> key = data.profession().value().getTrades(level);
        if (key == null) return false;

        Registry<TradeSet> registry = sl.registryAccess().lookupOrThrow(Registries.TRADE_SET);
        TradeSet tradeSet = registry.getValue(key);
        if (tradeSet == null) return false;

        for (Holder<VillagerTrade> holder : tradeSet.getTrades()) {
            VillagerTradeMixin acc = (VillagerTradeMixin) holder.value();
            ItemStackTemplate gives = acc.getGives();

            if (gives.item().value() != Items.ENCHANTED_BOOK
                    && gives.item().value() != Items.BOOK) continue;

            for (LootItemFunction func : acc.getGivenItemModifiers()) {
                if (!(func instanceof EnchantRandomlyFunction erf)) continue;
                Optional<HolderSet<Enchantment>> options =
                        ((EnchantRandomlyFunctionMixin) erf).getOptions();

                if (options.isEmpty()) return true;   // 任意附魔

                for (Holder<Enchantment> h : options.get()) {
                    if (h.value() == target) return true;
                }
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
        for (Holder<VillagerTrade> holder : tradeSet.getTrades()) {
            ItemStackTemplate gives = ((VillagerTradeMixin) holder.value()).getGives();
            items.add(gives.item().value());
        }
        return Collections.unmodifiableSet(items);
    }

}