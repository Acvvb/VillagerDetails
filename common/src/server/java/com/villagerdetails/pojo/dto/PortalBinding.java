package com.villagerdetails.pojo.dto;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 一对传送门绑定。
 * A 侧（主世界）是一个方块坐标区域，B 侧（地狱）是一个方块坐标区域。
 * 任意一侧进入都会传送到另一侧区域的中心点。
 */
public record PortalBinding(
        ResourceKey<Level> dimA, BlockPos minA, BlockPos maxA,
        ResourceKey<Level> dimB, BlockPos minB, BlockPos maxB
) {

    public PortalBinding {
        if (minA == null || maxA == null || minB == null || maxB == null) {
            throw new IllegalArgumentException("PortalBinding 四角坐标不能为 null");
        }
        if (dimA == null || dimB == null) {
            throw new IllegalArgumentException("PortalBinding 维度不能为 null");
        }
    }

    /**
     * 给定当前维度和坐标，尝试解析出目标维度 + 目标坐标。
     * 不在任何一侧区域内时返回 null。
     */
    public PortalDestination resolve(ResourceKey<Level> currentDim, BlockPos pos) {
        if (currentDim.equals(dimA) && inRange(pos, minA, maxA)) {
            return new PortalDestination(dimB, centerOf(minB, maxB));
        }
        if (currentDim.equals(dimB) && inRange(pos, minB, maxB)) {
            return new PortalDestination(dimA, centerOf(minA, maxA));
        }
        return null;
    }

    private static boolean inRange(BlockPos p, BlockPos min, BlockPos max) {
        return p.getX() >= min.getX() && p.getX() <= max.getX()
                && p.getY() >= min.getY() && p.getY() <= max.getY()
                && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
    }

    private static BlockPos centerOf(BlockPos min, BlockPos max) {
        return new BlockPos(
                (min.getX() + max.getX()) / 2,
                (min.getY() + max.getY()) / 2,
                (min.getZ() + max.getZ()) / 2
        );
    }

    /**
     * 归一化：保证 min <= max
     */
    public static PortalBinding of(ResourceKey<Level> dimA, BlockPos a1, BlockPos a2,
                                   ResourceKey<Level> dimB, BlockPos b1, BlockPos b2) {
        BlockPos minA = min(a1, a2);
        BlockPos maxA = max(a1, a2);
        BlockPos minB = min(b1, b2);
        BlockPos maxB = max(b1, b2);
        return new PortalBinding(dimA, minA, maxA, dimB, minB, maxB);
    }

    private static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ())
        );
    }

    private static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ())
        );
    }
}