package com.villagerdetails.math.gridhashing.villager;

import com.villagerdetails.math.gridhashing.BindableTarget;
import net.minecraft.core.BlockPos;

/**
 * 床目标适配器
 */
public class BedTarget implements BindableTarget {

    private final BlockPos bedPos;
    private final boolean available;

    /**
     * @param bedPos    床的方块位置
     * @param available 床是否可用（true=未被占用，false=已被其他村民绑定）
     */
    public BedTarget(BlockPos bedPos, boolean available) {
        this.bedPos = bedPos;
        this.available = available;
    }

    @Override
    public BlockPos getPosition() {
        return bedPos;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BedTarget bedTarget = (BedTarget) o;
        return bedPos.equals(bedTarget.bedPos);
    }

    @Override
    public int hashCode() {
        return bedPos.hashCode();
    }
}