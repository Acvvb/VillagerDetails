package com.villagerdetails.mixin.villager.trader.autoTrader;

import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(VillagerTrade.class)
public interface VillagerTradeMixin {

    @Accessor("gives")
    ItemStackTemplate getGives();

    @Accessor("givenItemModifiers")
    List<LootItemFunction> getGivenItemModifiers();
}