package com.villagerdetails.handler;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.mixin.MobInterfaceMixin;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.world.entity.monster.Monster;

public final class NoBrainOnPortalHandler {

    private NoBrainOnPortalHandler() {}

    /** 由主类在 onInitialize 里调用一次 */
    public static void register() {
        ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL.register(
                (_, newEntity, _, _) -> {
                    if (!RuleCache.isEnabled(RuleType.NO_BRAIN_ON_PORTAL)) return;
                    if (!(newEntity instanceof Monster mob)) return;
                    MobInterfaceMixin accessor = (MobInterfaceMixin) mob;
                    accessor.villagerdetails$getGoalSelector().getAvailableGoals().clear();
                    accessor.villagerdetails$getTargetSelector().getAvailableGoals().clear();
                }
        );
    }
}