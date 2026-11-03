package com.villagerdetails.handler.entity.villager.trader.refresh;

import com.villagerdetails.mixin.entity.villager.trader.autoTrader.book.EnchantRandomlyFunctionMixin;
import com.villagerdetails.mixin.villager.trader.autoTrader.SequenceFunctionMixin;
import com.villagerdetails.mixin.villager.trader.autoTrader.VillagerTradeMixin;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.SequenceFunction;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 从一笔 {@link VillagerTrade} 里提取「可能产出的附魔」候选。
 *
 * <p>26.3 的 {@code givenItemModifier} 是<b>单个</b> {@code Optional<Holder<LootItemFunction>>}，
 * 而 JSON 里写成数组会被 {@code LootItemFunctions.CODEC} 折成一个 {@link SequenceFunction}
 * （图书管理员的附魔书交易正是 {@code [enchant_randomly, filtered]} 两段）——
 * 所以必须递归拆开，只做一层 {@code instanceof} 会一个附魔都找不到。
 *
 * <p>26.2 的实现在同名类里（那边是扁平列表，只遍历一层）。
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
        Optional<Holder<LootItemFunction>> modifier = ((VillagerTradeMixin) trade).getGivenItemModifiers();
        if (modifier == null || modifier.isEmpty()) {
            return;
        }
        collect(modifier.get().value(), sink, 0);
    }

    private static void collect(LootItemFunction function, Consumer<Holder<Enchantment>> sink, int depth) {
        if (function == null || depth > 8) {
            return;
        }
        if (function instanceof EnchantRandomlyFunction random) {
            // options 为空 = 任意附魔：这种交易没法静态枚举，交给调用方用「全部附魔」兜底
            Optional<HolderSet<Enchantment>> options =
                    ((EnchantRandomlyFunctionMixin) random).getOptions();
            options.ifPresent(set -> set.forEach(sink));
            return;
        }
        if (function instanceof SequenceFunction sequence) {
            for (Holder<LootItemFunction> nested : ((SequenceFunctionMixin) sequence).getFunctions()) {
                collect(nested.value(), sink, depth + 1);
            }
        }
    }
}
