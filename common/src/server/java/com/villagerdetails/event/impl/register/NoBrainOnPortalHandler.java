package com.villagerdetails.event.impl.register;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.mixin.monster.noBrainOnPortal.MobInterfaceMixin;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Monster;

public final class NoBrainOnPortalHandler implements ServerEventRegisterServer {

    private NoBrainOnPortalHandler() {
    }

    /**
     * 由主类在 onInitialize 里调用一次
     */
    @Override
    public void register() {
        ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL.register(
                (_, newEntity, _, _) -> {
                    if (!RuleCache.isEnabled(RuleType.NO_BRAIN_ON_PORTAL)) return;
                    if (!(newEntity instanceof Monster mob)) return;
                    MobInterfaceMixin accessor = (MobInterfaceMixin) mob;
                    clearGoals(accessor.villagerdetails$getGoalSelector());
                    clearGoals(accessor.villagerdetails$getTargetSelector());
                }
        );
    }

    /**
     * 逐个 removeGoal 而不是 getAvailableGoals().clear()。
     *
     * <p>Mojang 的 {@link GoalSelector} 内部同时维护两个结构：
     * <ul>
     *   <li>{@code availableGoals}（Set，{@code getAvailableGoals()} 暴露的那个）</li>
     *   <li>{@code goalMap}（内部 Map，tick 时真正遍历的）</li>
     * </ul>
     * 直接对 {@code getAvailableGoals()} 调 {@code clear()} 只清 Set，
     * {@code goalMap} 里的 {@link WrappedGoal} 依然会被 tick 命中。
     *
     * <p>用 {@code toList()} 先做快照再删，避免遍历中改集合触发
     * {@code ConcurrentModificationException}。
     */
    private static void clearGoals(GoalSelector selector) {
        selector.getAvailableGoals().stream()
                .map(WrappedGoal::getGoal)
                .toList()
                .forEach(selector::removeGoal);
    }
}