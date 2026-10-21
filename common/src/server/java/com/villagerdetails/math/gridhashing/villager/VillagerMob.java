package com.villagerdetails.math.gridhashing.villager;

import com.villagerdetails.math.gridhashing.BindableMob;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 村民生物适配器
 */
public record VillagerMob(Villager villager) implements BindableMob {

    @Override
    public Entity getEntity() {
        return villager;
    }

    @Override
    public BlockPos getPosition() {
        return villager.blockPosition();
    }

    /**
     * 获取原始村民对象（方便后续执行绑定操作）
     */
    @Override
    public Villager villager() {
        return villager;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VillagerMob that = (VillagerMob) o;
        return villager.getId() == that.villager.getId();
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(villager.getId());
    }
}