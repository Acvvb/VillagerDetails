package com.villagerdetails.mixin.villager.trader.autoTrader;

import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//? if >=26.3 {
/*import net.minecraft.core.Holder;
import java.util.Optional;
*///?} else {
import java.util.List;
//?}

@Mixin(VillagerTrade.class)
public interface VillagerTradeMixin {

    @Accessor("gives")
    ItemStackTemplate getGives();

    /**
     * 26.3 把这个字段从 {@code List<LootItemFunction> givenItemModifiers} 换成了
     * {@code Optional<Holder<LootItemFunction>> givenItemModifier}（单数 + Holder 化）。
     * 方法名保持不变，调用点用 {@code TradePoolChecker#givenModifiers} 抹平差异。
     */
    //? if >=26.3 {
    /*@Accessor("givenItemModifier")
    Optional<Holder<LootItemFunction>> getGivenItemModifiers();
    *///?} else {
    @Accessor("givenItemModifiers")
    List<LootItemFunction> getGivenItemModifiers();
    //?}
}
