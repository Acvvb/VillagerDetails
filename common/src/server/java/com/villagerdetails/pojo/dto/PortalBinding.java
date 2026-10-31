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
 * <p>在原始 6 个字段之外，这里还带两样东西：
 * <ul>
 *     <li>{@link #id()} —— 展示 / 删除 / 刷新用的<b>自增数字 ID</b>（{@code "1"}、{@code "2"}…），
 *         由 {@code PortalBindingConfig} 统一分配。新建时可以是 {@code null}（还没入库），
 *         入库那一刻补上，之后<b>永远不变</b>：删掉别的条目也不会让谁的数字往前挪。</li>
 *     <li>{@link #portalA()} / {@link #portalB()} —— 两端各自的「已解析门底坐标」缓存，
 *         可为 null 表示尚未解析。</li>
 * </ul>
 *
 * <p><b>重要</b>：equals/hashCode 只比较 6 个原始坐标字段，<b>既不比较 ID、也不比较缓存</b>——
 * 这是为了让 {@code PortalBindingConfig.add} 的去重、{@code bindings.indexOf(binding)}
 * 的定位，以及「拿扫描前的旧副本去回写缓存」都不受 ID / 缓存字段影响。
 * ID 只是给人和指令看的标签，<b>不是身份</b>；身份始终是「两端区域的 6 个坐标」。
 */
public record PortalBinding(
        @Nullable String id,
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
        // 空串是「还没分配」，统一归一化成 null，省得外面还要区分 "" 和 null
        if (id != null && id.isBlank()) {
            id = null;
        }
    }

    /** 不带 ID、不带缓存的构造。 */
    public PortalBinding(ResourceKey<Level> dimA, BlockPos minA, BlockPos maxA,
                         ResourceKey<Level> dimB, BlockPos minB, BlockPos maxB) {
        this(null, dimA, minA, maxA, dimB, minB, maxB, null, null);
    }

    /** 不带 ID、带两侧缓存的构造（缓存回写 / 反序列化用，ID 由 Config 后补）。 */
    public PortalBinding(ResourceKey<Level> dimA, BlockPos minA, BlockPos maxA,
                         ResourceKey<Level> dimB, BlockPos minB, BlockPos maxB,
                         @Nullable BlockPos portalA, @Nullable BlockPos portalB) {
        this(null, dimA, minA, maxA, dimB, minB, maxB, portalA, portalB);
    }

    // ==================== 区域尺寸上限 ====================

    /**
     * 单侧区域允许的最大边长（格，<b>含两端</b>）。
     *
     * <p>X / Z 各 128 格、Y 64 格。这个上限同时是「扫描一定看得全」的前提：
     * POI 扫描的覆盖半径是区域中心到角点的水平距离（这里最大 64），
     * 远小于 {@code PortalLocator} 的<b>单次</b>扫描安全半径 128，
     * 所以合法区域永远一次扫完，不会出现「区域内明明有门却扫不到」。
     *
     * <p>命令 {@code set} 会按它拦下过大的选区；手工改配置文件塞进更大的区域时
     * 不会丢数据，但刷新/传送会退化成「只扫中心一片」并提示未覆盖完整
     * （见 {@code PortalLocator#MAX_TILES} 与 {@code scanRegion}）。
     */
    public static final int MAX_SIZE_X = 128;
    public static final int MAX_SIZE_Y = 64;
    public static final int MAX_SIZE_Z = 128;

    /**
     * 一个区域的三边格数（含两端）。
     */
    public record Size(int x, int y, int z) {
    }

    /**
     * 量出两个角点构成的区域大小（含两端，所以单格区域是 1×1×1）。
     */
    public static Size sizeOf(BlockPos a, BlockPos b) {
        return new Size(
                Math.abs(a.getX() - b.getX()) + 1,
                Math.abs(a.getY() - b.getY()) + 1,
                Math.abs(a.getZ() - b.getZ()) + 1);
    }

    /**
     * 单侧区域是否在 {@link #MAX_SIZE_X} / {@link #MAX_SIZE_Y} / {@link #MAX_SIZE_Z} 之内。
     */
    public static boolean withinSizeLimits(BlockPos a, BlockPos b) {
        Size size = sizeOf(a, b);
        return size.x() <= MAX_SIZE_X && size.y() <= MAX_SIZE_Y && size.z() <= MAX_SIZE_Z;
    }

    /**
     * 两侧区域是否都在尺寸上限之内。
     */
    public boolean withinSizeLimits() {
        return withinSizeLimits(minA, maxA) && withinSizeLimits(minB, maxB);
    }

    /** 返回换了 ID 的新副本（原对象不变）；{@code null} 表示「还没分配」。 */
    public PortalBinding withId(@Nullable String newId) {
        return new PortalBinding(newId, dimA, minA, maxA, dimB, minB, maxB, portalA, portalB);
    }

    /** 返回替换了 A 侧缓存的新副本（原对象不变，ID 保留）。 */
    public PortalBinding withPortalA(@Nullable BlockPos pos) {
        return new PortalBinding(id, dimA, minA, maxA, dimB, minB, maxB, pos, portalB);
    }

    /** 返回替换了 B 侧缓存的新副本（原对象不变，ID 保留）。 */
    public PortalBinding withPortalB(@Nullable BlockPos pos) {
        return new PortalBinding(id, dimA, minA, maxA, dimB, minB, maxB, portalA, pos);
    }

    /**
     * 给定当前维度和坐标，尝试解析出目标维度 + 目标坐标。
     * 不在任何一侧区域内时返回 null。
     *
     * <p>返回的 {@link PortalDestination} 携带 {@code source=this}、{@code targetIsB}
     * 与目标区域边界，供调用方回写缓存与限定扫描范围。
     */
    public @Nullable PortalDestination resolve(ResourceKey<Level> currentDim, BlockPos pos) {
        if (currentDim.equals(dimA) && inRegion(pos, minA, maxA)) {
            return new PortalDestination(dimB, centerOf(minB, maxB), this, true, minB, maxB);
        }
        if (currentDim.equals(dimB) && inRegion(pos, minB, maxB)) {
            return new PortalDestination(dimA, centerOf(minA, maxA), this, false, minA, maxA);
        }
        return null;
    }

    // ==================== 原 6 字段归一化 / 工具 ====================

    /**
     * 归一化构造：两个角点谁大谁小无所谓。<b>不带 ID</b> —— 入库时由配置层分配。
     */
    public static PortalBinding of(ResourceKey<Level> dimA, BlockPos a1, BlockPos a2,
                                   ResourceKey<Level> dimB, BlockPos b1, BlockPos b2) {
        return new PortalBinding(null, dimA, min(a1, a2), max(a1, a2),
                dimB, min(b1, b2), max(b1, b2), null, null);
    }

    /**
     * 坐标是否落在区域里（含边界）。
     *
     * <p>public 是因为「刷新时保留旧缓存」那条判定要用同一套边界语义：
     * 旧缓存的门一旦被缩小后的选区排除在外，就必须清掉，
     * 否则下次传送会把玩家送到区域外的那扇门。
     */
    public static boolean inRegion(BlockPos p, BlockPos min, BlockPos max) {
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

    // ==================== equals / hashCode：忽略 ID 与缓存 ====================

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
        return "PortalBinding{#" + id + " " + dimA.identifier() + " " + minA + "~" + maxA
                + " <-> " + dimB.identifier() + " " + minB + "~" + maxB
                + ", portalA=" + portalA + ", portalB=" + portalB + '}';
    }
}
