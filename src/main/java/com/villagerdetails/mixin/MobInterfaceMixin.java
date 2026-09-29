package com.villagerdetails.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 让外部代码可以访问 {@link Mob} 的 protected 字段 {@code goalSelector} / {@code targetSelector}。
 * <p>Mixin 会在编译期生成实现，运行时由 ASM 注入。
 */
@Mixin(Mob.class)
public interface MobInterfaceMixin {

    @Accessor("goalSelector")
    GoalSelector villagerdetails$getGoalSelector();

    @Accessor("targetSelector")
    GoalSelector villagerdetails$getTargetSelector();
}