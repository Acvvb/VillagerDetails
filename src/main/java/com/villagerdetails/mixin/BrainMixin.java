package com.villagerdetails.mixin;

import com.villagerdetails.cache.ModMemories;
import com.villagerdetails.handler.villager.move.ModActivities;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.schedule.Activity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Brain.class)
public abstract class BrainMixin {

    @Inject(method = "setActiveActivity", at = @At("HEAD"), cancellable = true)
    private void villagerdetails$lockToDestination(Activity activity, CallbackInfo ci) {
        Brain<?> brain = (Brain<?>) (Object) this;

        if (!brain.hasMemoryValue(ModMemories.DESTINATION)) return;
        if (activity == ModActivities.GO_TO_DESTINATION) return;
        if (activity == Activity.CORE) return;

        ci.cancel();
        ((BrainAccessor) brain).invokeSetActiveActivity(ModActivities.GO_TO_DESTINATION);
    }
}