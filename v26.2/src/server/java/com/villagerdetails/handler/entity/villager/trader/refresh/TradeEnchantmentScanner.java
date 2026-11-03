package com.villagerdetails.handler.entity.villager.trader.refresh;

import com.villagerdetails.mixin.entity.villager.trader.autoTrader.book.EnchantRandomlyFunctionMixin;
import com.villagerdetails.mixin.villager.trader.autoTrader.VillagerTradeMixin;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 从一笔 {@link VillagerTrade} 里提取「可能产出的附魔」候选。
 *
 * <p>26.2 的 {@code givenItemModifiers} 是<b>扁平的 {@code List<LootItemFunction>}</b>
 * （JSON 数组直接映射成列表，不会折成 {@code SequenceFunction}），所以只要遍历一层：
 * 遇到 {@link EnchantRandomlyFunction} 就把它 {@code options()} 里的附魔吐出来。
 *
 * <p>26.3 的实现在同名类里（那边要先把 {@code SequenceFunction} 拆开）。
 */
public final class TradeEnchantmentScanner {

    private TradeEnchantmentScanner() {
    }

    /**
     * 把这笔交易可能产出的附魔交给 {@code sink}（去重由调用方负责）。
     *
     * @param trade 目标交易
     * @param sink  接收回调；{@code null} 表示只关心「有没有」
     */
    public static void collectCandidates(VillagerTrade trade, Consumer<Holder<Enchantment>> sink) {
        if (trade == null || sink == null) {
            return;
        }
        List<LootItemFunction> modifiers = ((VillagerTradeMixin) trade).getGivenItemModifiers();
        if (modifiers == null || modifiers.isEmpty()) {
            return;
        }
        for (LootItemFunction function : modifiers) {
            collect(function, sink);
        }
    }

    private static void collect(LootItemFunction function, Consumer<Holder<Enchantment>> sink) {
        if (!(function instanceof EnchantRandomlyFunction random)) {
            return;
        }
        // options 为空 = 任意附魔：这种交易没法静态枚举，交给调用方用「全部附魔」兜底
        Optional<HolderSet<Enchantment>> options =
                ((EnchantRandomlyFunctionMixin) random).getOptions();
        options.ifPresent(set -> set.forEach(sink));
    }
}
