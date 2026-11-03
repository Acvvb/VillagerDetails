package com.villagerdetails.mixin.villager.trader.autoTrader;

import net.minecraft.core.HolderSet;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.SequenceFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 26.3 的 {@code VillagerTrade} 把 {@code given_item_modifier} 从
 * {@code List<LootItemFunction>} 改成了单个 {@code Optional<Holder<LootItemFunction>>}，
 * 反序列化走的是 {@code LootItemFunctions.CODEC}（{@code Codec.either}）：
 * <b>JSON 里写成数组时会被折成一个 {@link SequenceFunction}</b>，
 * 里面才是真正的成员函数（例如 {@code enchant_randomly}）。
 *
 * <p>所以「图书管理员的附魔书交易里到底有没有某个附魔」不能再直接
 * {@code instanceof EnchantRandomlyFunction}，必须先把 {@code SequenceFunction} 拆开。
 * 这个访问器就是拿它内部那个 {@code functions} 集合用的。
 */
@Mixin(SequenceFunction.class)
public interface SequenceFunctionMixin {

    @Accessor("functions")
    HolderSet<LootItemFunction> getFunctions();
}
