package com.villagerdetails.mixin.villager.trader.autoTrader;

import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MerchantOffer.class)
public interface MerchantOfferInterfaceMixin {

    @Accessor("uses")
    void setUses(int uses);
}