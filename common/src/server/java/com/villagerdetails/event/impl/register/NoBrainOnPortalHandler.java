package com.villagerdetails.event.impl.register;

import com.villagerdetails.cache.RuleCache;
import com.villagerdetails.event.server.ServerEventRegisterServer;
import com.villagerdetails.mixin.monster.noBrainOnPortal.MobInterfaceMixin;
import com.villagerdetails.rule.type.RuleType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Monster;

/**
 * 「过门失智」的事件入口：怪物<b>换过维度</b>之后清空它的全部 AI。
 *
 * <p>监听的是 {@code ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL}，所以触发面
 * <b>不限于传送门</b>：末地门、跨维度 {@code /tp}、被其它模组搬运都会触发。
 *
 * <p><b>不可逆</b>：被清掉的 goal 不会恢复，只有实体重载（区块卸载再加载）或服务器重启
 * 才会回到原版行为。规则只清 {@code Monster}，其它实体不受影响。
 */
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