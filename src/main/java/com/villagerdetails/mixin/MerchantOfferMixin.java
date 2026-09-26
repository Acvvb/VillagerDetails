package com.villagerdetails.mixin;

import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MerchantOffer.class)
public interface MerchantOfferMixin {

    @Accessor("uses")
    void setUses(int uses);

    @Accessor("uses")
    int getUses();

    @Accessor("maxUses")
    int getMaxUses();
}