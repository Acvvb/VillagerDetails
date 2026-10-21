package com.villagerdetails.mixin.villager.trader.autoTrader;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;


@Mixin(VillagerTrade.class)
public interface VillagerTradeMixin {

    @Accessor("gives")
    ItemStackTemplate getGives();

    /**
     * 26.3 把这个字段从 {@code List<LootItemFunction> givenItemModifiers} 换成了
     * {@code Optional<Holder<LootItemFunction>> givenItemModifier}（单数 + Holder 化）。
     * 方法名保持不变，调用点用 {@code TradePoolChecker#givenModifiers} 抹平差异。
     */

    @Accessor("givenItemModifier")
    Optional<Holder<LootItemFunction>> getGivenItemModifiers();




}
