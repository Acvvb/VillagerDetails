package com.villagerdetails.pojo.dto;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 「禁用地狱门刷僵尸猪灵」的一条绑定区域：<b>一个维度 + 一个方块坐标区域</b>。
 *
 * <p>和传送门绑定（{@link PortalBinding}）不一样：那边一条绑定横跨两个维度、必须成对；
 * 这边每条绑定只落在<b>一个</b>维度里（绑的时候玩家站在哪就在哪），
 * 因为「地狱门刷猪灵」本来就是一个方块自己随机刻的事，和另一侧无关。
 *
 * <p>两个角点存在这里时已经归一化（{@link #min()} &lt;= {@link #max()}），
 * 所以 {@link #contains(BlockPos)} 只做三次大小比较。
 */
public record PortalPiglinNoSpawnRegion(ResourceKey<Level> dimension, BlockPos min, BlockPos max) {

    public PortalPiglinNoSpawnRegion {
        if (dimension == null) {
            throw new IllegalArgumentException("PortalPiglinNoSpawnRegion 维度不能为 null");
        }
        if (min == null || max == null) {
            throw new IllegalArgumentException("PortalPiglinNoSpawnRegion 两个角点不能为 null");
        }
    }

    /**
     * 归一化构造：两个角点谁大谁小无所谓
     */
    public static PortalPiglinNoSpawnRegion of(ResourceKey<Level> dimension, BlockPos a, BlockPos b) {
        return new PortalPiglinNoSpawnRegion(dimension, min(a, b), max(a, b));
    }

    /**
     * 这个方块坐标是不是落在本区域内（角点本身算在内，闭区间）
     */
    public boolean contains(BlockPos pos) {
        if (pos == null) return false;
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
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
