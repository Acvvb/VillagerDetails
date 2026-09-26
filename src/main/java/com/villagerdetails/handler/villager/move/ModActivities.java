package com.villagerdetails.handler.villager.move;

import com.villagerdetails.VillagerDetails;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.schedule.Activity;

public final class ModActivities {

    private ModActivities() {}

    public static final Activity GO_TO_DESTINATION =
            new Activity(VillagerDetails.MOD_ID + ":go_to_destination");

    public static void register() {
        Registry.register(
                BuiltInRegistries.ACTIVITY,
                VillagerDetails.id("go_to_destination"),
                GO_TO_DESTINATION
        );
    }
}