package com.villagerdetails.mixin.villager.move;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;
import com.villagerdetails.cache.ModMemories;
import com.villagerdetails.handler.villager.move.ModActivities;
import com.villagerdetails.handler.villager.move.MoveToDestination;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public class VillagerBrainGoalsMixin {

    @Inject(method = "registerBrainGoals", at = @At("RETURN"))
    private void villagerdetails$addDestinationActivity(Brain<Villager> brain, CallbackInfo ci) {
        brain.addActivity(
                ModActivities.GO_TO_DESTINATION,
                ImmutableList.of(Pair.of(0, new MoveToDestination())),
                ImmutableSet.of(Pair.of(ModMemories.DESTINATION, MemoryStatus.VALUE_PRESENT)),
                ImmutableSet.of()
        );
    }

}
