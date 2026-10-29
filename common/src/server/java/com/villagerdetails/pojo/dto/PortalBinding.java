package com.villagerdetails.pojo.dto;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 一对传送门绑定。
 *
 * <p>在原始 6 个字段之外，额外携带两端各自的「已解析门底坐标」缓存
 * （{@link #portalA()} / {@link #portalB()}），可为 null 表示尚未解析。
 *
 * <p><b>重要</b>：equals/hashCode 只比较 6 个原始字段，<b>忽略缓存</b>——
 * 这是为了让 {@code PortalBindingConfig.add} 的去重、以及
 * {@code bindings.indexOf(binding)} 的定位不受缓存字段影响。
 */
public record PortalBinding(
        ResourceKey<Level> dimA, BlockPos minA, BlockPos maxA,
        ResourceKey<Level> dimB, BlockPos minB, BlockPos maxB,
        @Nullable BlockPos portalA,
        @Nullable BlockPos portalB
) {

    public PortalBinding {
        if (minA == null || maxA == null || minB == null || maxB == null) {
            throw new IllegalArgumentException("PortalBinding 四角坐标不能为 null");
        }
        if (dimA == null || dimB == null) {
            throw new IllegalArgumentException("PortalBinding 维度不能为 null");
        }
    }

    /** 不带缓存的构造（原 6 字段版本）。 */
    public PortalBinding(ResourceKey<Level> dimA, BlockPos minA, BlockPos maxA,
                         ResourceKey<Level> dimB, BlockPos minB, BlockPos maxB) {
        this(dimA, minA, maxA, dimB, minB, maxB, null, null);
    }

    /** 返回替换了 A 侧缓存的新副本（原对象不变）。 */
    public PortalBinding withPortalA(@Nullable BlockPos pos) {
        return new PortalBinding(dimA, minA, maxA, dimB, minB, maxB, pos, portalB);
    }

    /** 返回替换了 B 侧缓存的新副本（原对象不变）。 */
    public PortalBinding withPortalB(@Nullable BlockPos pos) {
        return new PortalBinding(dimA, minA, maxA, dimB, minB, maxB, portalA, pos);
    }

    /**
     * 给定当前维度和坐标，尝试解析出目标维度 + 目标坐标。
     * 不在任何一侧区域内时返回 null。
     *
     * <p>返回的 {@link PortalDestination} 携带 {@code source=this}、{@code targetIsB}
     * 与目标区域边界，供调用方回写缓存与限定扫描范围。
     */
    public @Nullable PortalDestination resolve(ResourceKey<Level> currentDim, BlockPos pos) {
        if (currentDim.equals(dimA) && inRange(pos, minA, maxA)) {
            return new PortalDestination(dimB, centerOf(minB, maxB), this, true, minB, maxB);
        }
        if (currentDim.equals(dimB) && inRange(pos, minB, maxB)) {
            return new PortalDestination(dimA, centerOf(minA, maxA), this, false, minA, maxA);
        }
        return null;
    }

    // ==================== 原 6 字段归一化 / 工具 ====================

    public static PortalBinding of(ResourceKey<Level> dimA, BlockPos a1, BlockPos a2,
                                   ResourceKey<Level> dimB, BlockPos b1, BlockPos b2) {
        return new PortalBinding(dimA, min(a1, a2), max(a1, a2),
                dimB, min(b1, b2), max(b1, b2), null, null);
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

    private static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    // ==================== equals / hashCode：忽略缓存 ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PortalBinding b)) return false;
        return dimA.equals(b.dimA) && minA.equals(b.minA) && maxA.equals(b.maxA)
                && dimB.equals(b.dimB) && minB.equals(b.minB) && maxB.equals(b.maxB);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimA, minA, maxA, dimB, minB, maxB);
    }

    @Override
    public @NonNull String toString() {
        return "PortalBinding{" + dimA.identifier() + " " + minA + "~" + maxA
                + " <-> " + dimB.identifier() + " " + minB + "~" + maxB
                + ", portalA=" + portalA + ", portalB=" + portalB + '}';
    }
}