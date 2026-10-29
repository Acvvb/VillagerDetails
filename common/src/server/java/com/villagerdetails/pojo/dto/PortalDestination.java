package com.villagerdetails.pojo.dto;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * 一次绑定解析的结果：目标维度 + 目标坐标（另一侧区域中心）。
 *
 * <p>额外携带 {@link #source()} / {@link #targetIsB()} 两个上下文，
 * 让调用方（如传送 Mixin）能在解析出实际门坐标后回写到对应绑定上。
 * 旧用法 {@code new PortalDestination(dim, pos)} 保持不变，此时 source 为 null。
 *
 * <p>{@link #regionMin()} / {@link #regionMax()} 是目标侧绑定区域，
 * 供 {@code PortalLocator.locateInRegion} 只在区域内选门/建门；旧构造为 null。
 */
public record PortalDestination(
        ResourceKey<Level> dimension,
        BlockPos pos,
        @Nullable PortalBinding source,
        boolean targetIsB,
        @Nullable BlockPos regionMin,
        @Nullable BlockPos regionMax
) {

    /** 是否携带绑定区域信息。 */
    public boolean hasRegion() {
        return regionMin != null && regionMax != null;
    }

    /**
     * 该目标侧已缓存的门坐标；无缓存 / 无来源时返回 null。
     *
     * <p>targetIsB = true 说明目标在绑定 B 侧，缓存字段是 {@code source.portalB()}；
     * 反之取 {@code source.portalA()}。
     */
    public @Nullable BlockPos cachedPortalPos() {
        if (source == null) return null;
        return targetIsB ? source.portalB() : source.portalA();
    }
}
