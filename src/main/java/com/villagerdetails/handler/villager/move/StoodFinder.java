package com.villagerdetails.handler.villager.move;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public final class StoodFinder {

    private StoodFinder() {}

    /**
     * 从给定位置出发，找到最近的可站立方块。
     * 搜索顺序：输入方块 → 上方 1 格 → 从输入向下逐格搜索。
     */
    private static final int MAX_SEARCH_DOWN = 3;   // 最多向下搜 8 格

    public static @Nullable BlockPos find(ServerLevel level, BlockPos input) {
        if (isEnableStood(level, input)) return input;
        BlockPos above = input.above();
        if (isEnableStood(level, above)) return above;
        BlockPos.MutableBlockPos pos = input.mutable();
        for (int i = 0; i < MAX_SEARCH_DOWN; i++) {
            pos.move(0, -1, 0);
            if (pos.getY() <= level.getMinY()) break;
            if (isEnableStood(level, pos)) return pos.immutable();
        }
        return null;   // 超出范围就不走
    }

    /**
     * 判断一个位置能否让村民站立：
     *  · 该方块本身是空气
     *  · 下方方块是固体
     */
    private static boolean isEnableStood(ServerLevel level, BlockPos pos) {
        BlockState at = level.getBlockState(pos);
        if (!at.isAir()) return false;

        BlockPos belowPos = pos.below();
        BlockState below = level.getBlockState(belowPos);
        if (below.isAir()) return false;

        // 用 isFaceSturdy 判断下方方块是否能"托住"村民
        return below.isFaceSturdy(level, belowPos, Direction.UP);
    }
}