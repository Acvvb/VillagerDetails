package com.villagerdetails.mixin.villager.trader.autoTrader;

import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MerchantMenu.class)
public interface MerchantMenuInterfaceMixin {

    @Accessor("trader")
    Merchant getTrader();

    @Accessor("tradeContainer")
    MerchantContainer getTradeContainer();

}