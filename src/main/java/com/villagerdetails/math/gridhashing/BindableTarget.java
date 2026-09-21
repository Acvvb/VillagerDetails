package com.villagerdetails.math.gridhashing;

import net.minecraft.core.BlockPos;

/**
 * 可绑定的目标方块（床、工作方块等）
 */
public interface BindableTarget {
    BlockPos getPosition();

    /**
     * 是否可以被绑定（未被占用、未被破坏等）
     */
    boolean isAvailable();
}