package com.villagerdetails.pojo.dto;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 「禁用地狱门刷僵尸猪灵」的一条绑定区域：<b>一个维度 + 一个方块坐标区域</b>。
 *
 * <p>和传送门绑定（{@link PortalBinding}）不一样：那边一条绑定横跨两个维度、必须成对；
 * 这边每条绑定只落在<b>一个</b>维度里（绑的时候玩家站在哪就在哪），
 * 因为「地狱门刷猪灵」本来就是一个方块自己随机刻的事，和另一侧无关。
 *
 * <p>{@link #id()} 是配置层分配的<b>自增数字 ID</b>（{@code "1"}、{@code "2"}…），
 * 新建时可以为 {@code null}，入库那一刻补上，之后永远不变。
 *
 * <p><b>重要</b>：equals/hashCode <b>不比较 ID</b>，只比较「维度 + 两个角点」——
 * 去重（{@code regions.contains}）、按 equals 反查（{@code indexOf}）都靠它，
 * 否则同一个区域换个 ID 就会被当成两条不同的记录。
 *
 * <p>两个角点存在这里时已经归一化（{@link #min()} &lt;= {@link #max()}），
 * 所以 {@link #contains(BlockPos)} 只做三次大小比较。
 */
public record PortalPiglinNoSpawnRegion(
        @Nullable String id,
        ResourceKey<Level> dimension, BlockPos min, BlockPos max
) {

    public PortalPiglinNoSpawnRegion {
        if (dimension == null) {
            throw new IllegalArgumentException("PortalPiglinNoSpawnRegion 维度不能为 null");
        }
        if (min == null || max == null) {
            throw new IllegalArgumentException("PortalPiglinNoSpawnRegion 两个角点不能为 null");
        }
        // 空串是「还没分配」，统一归一化成 null
        if (id != null && id.isBlank()) {
            id = null;
        }
    }

    /** 不带 ID 的构造（ID 由配置层在入库时分配）。 */
    public PortalPiglinNoSpawnRegion(ResourceKey<Level> dimension, BlockPos min, BlockPos max) {
        this(null, dimension, min, max);
    }

    /**
     * 归一化构造：两个角点谁大谁小无所谓。<b>不带 ID</b> —— 入库时由配置层分配。
     */
    public static PortalPiglinNoSpawnRegion of(ResourceKey<Level> dimension, BlockPos a, BlockPos b) {
        return new PortalPiglinNoSpawnRegion(null, dimension, min(a, b), max(a, b));
    }

    /** 返回换了 ID 的新副本（原对象不变）；{@code null} 表示「还没分配」。 */
    public PortalPiglinNoSpawnRegion withId(@Nullable String newId) {
        return new PortalPiglinNoSpawnRegion(newId, dimension, min, max);
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

    // ==================== equals / hashCode：忽略 ID ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PortalPiglinNoSpawnRegion r)) return false;
        return dimension.equals(r.dimension) && min.equals(r.min) && max.equals(r.max);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimension, min, max);
    }

    @Override
    public @NonNull String toString() {
        return "PortalPiglinNoSpawnRegion{#" + id + " " + dimension.identifier()
                + " " + min + "~" + max + '}';
    }
}
