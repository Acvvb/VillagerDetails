package com.villagerdetails.mixin;

import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractVillager.class)
public interface AbstractVillagerMixin {

    @Mutable
    @Accessor("offers")
    void setOffersField(MerchantOffers offers);
}