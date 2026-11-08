package com.villagerdetails.handler.entity.villager.move;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public final class StoodFinder {

    private StoodFinder() {}

    /**
     * 从给定位置出发，找到最近的可站立方块。
     * 搜索顺序：输入方块 → 上方 1 格 → 从输入向下逐格搜索。
     */
    private static final int MAX_SEARCH_DOWN = 3;   // 最多向下搜 3 格（就是本常量，不是 8）

    /**
     * @return 找到的可站立位置；附近没有可站立位置时返回 {@link Optional#empty()}
     *         （调用方据此判断"绑定失败"，不要再当成"已成功但没动"）
     */
    public static Optional<BlockPos> find(ServerLevel level, BlockPos input) {
        if (isEnableStood(level, input)) return Optional.of(input);
        BlockPos above = input.above();
        if (isEnableStood(level, above)) return Optional.of(above);
        BlockPos.MutableBlockPos pos = input.mutable();
        for (int i = 0; i < MAX_SEARCH_DOWN; i++) {
            pos.move(0, -1, 0);
            if (pos.getY() <= level.getMinY()) break;
            if (isEnableStood(level, pos)) return Optional.of(pos.immutable());
        }
        return Optional.empty();   // 超出范围就不走
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