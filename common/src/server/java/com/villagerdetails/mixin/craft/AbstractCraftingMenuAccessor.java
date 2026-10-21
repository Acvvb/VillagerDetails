package com.villagerdetails.mixin.craft;

import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractCraftingMenu.class)
public interface AbstractCraftingMenuAccessor {

    @Accessor("craftSlots")
    CraftingContainer villagerdetails$getCraftSlots();

    @Accessor("resultSlots")
    ResultContainer villagerdetails$getResultSlots();
}