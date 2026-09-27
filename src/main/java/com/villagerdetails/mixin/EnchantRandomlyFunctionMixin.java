package com.villagerdetails.mixin;

import net.minecraft.core.HolderSet;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

@Mixin(EnchantRandomlyFunction.class)
public interface EnchantRandomlyFunctionMixin {

    @Accessor("options")
    Optional<HolderSet<Enchantment>> getOptions();
}