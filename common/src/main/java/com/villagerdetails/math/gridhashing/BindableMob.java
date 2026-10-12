package com.villagerdetails.math.gridhashing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

public interface BindableMob {
    Entity getEntity();

    BlockPos getPosition();
}