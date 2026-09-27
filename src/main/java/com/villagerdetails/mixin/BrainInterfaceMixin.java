package com.villagerdetails.mixin;

import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Brain.class)
public interface BrainInterfaceMixin {

    @Invoker("setActiveActivity")
    void invokeSetActiveActivity(Activity activity);
}