package com.villagerdetails.pojo;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 一次绑定解析的结果：目标维度 + 目标坐标（另一侧区域中心）
 */
public record PortalDestination(ResourceKey<Level> dimension, BlockPos pos) {
}