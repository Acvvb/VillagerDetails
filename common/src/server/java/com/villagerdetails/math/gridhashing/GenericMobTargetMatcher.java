package com.villagerdetails.math.gridhashing;

import net.minecraft.core.BlockPos;

import java.util.*;

public class GenericMobTargetMatcher {

    private static final int CELL_SIZE = 16;
    private static final int MAX_RADIUS = 8;

    /**
     * 通用匹配：生物 <-> 目标方块
     *
     * @param mobs   生物列表
     * @param targets 目标方块列表
     * @return 匹配结果
     */
    public static <M extends BindableMob, T extends BindableTarget> Map<M, T> match(
            List<M> mobs, List<T> targets) {

        if (mobs.isEmpty() || targets.isEmpty()) {
            return Collections.emptyMap();
        }

        // 构建网格
        Map<BlockPos, List<T>> grid = buildGrid(targets);

        // 随机打乱，保证同距离随机分配
        for (List<T> bucket : grid.values()) {
            Collections.shuffle(bucket);
        }

        Set<T> used = new HashSet<>();
        Map<M, T> result = new HashMap<>();

        for (M mob : mobs) {
            T nearest = findNearestAvailable(grid, mob.getPosition(), used);
            if (nearest != null) {
                result.put(mob, nearest);
                used.add(nearest);
            }
        }

        return result;
    }

    private static <T extends BindableTarget> Map<BlockPos, List<T>> buildGrid(List<T> targets) {
        Map<BlockPos, List<T>> grid = new HashMap<>();
        for (T target : targets) {
            BlockPos key = getGridKey(target.getPosition());
            grid.computeIfAbsent(key, _ -> new ArrayList<>()).add(target);
        }
        return grid;
    }

    private static BlockPos getGridKey(BlockPos pos) {
        return new BlockPos(
                Math.floorDiv(pos.getX(), CELL_SIZE),
                Math.floorDiv(pos.getY(), CELL_SIZE),
                Math.floorDiv(pos.getZ(), CELL_SIZE)
        );
    }

    private static <T extends BindableTarget> T findNearestAvailable(
            Map<BlockPos, List<T>> grid,
            BlockPos mobPos,
            Set<T> used) {

        BlockPos mobGrid = getGridKey(mobPos);

        for (int radius = 0; radius <= MAX_RADIUS; radius++) {
            T nearest = searchLayer(grid, mobGrid, mobPos, radius, used);
            if (nearest != null) {
                return nearest;
            }
        }

        return null;
    }

    private static <T extends BindableTarget> T searchLayer(
            Map<BlockPos, List<T>> grid,
            BlockPos center,
            BlockPos mobPos,
            int radius,
            Set<T> used) {

        T nearest = null;
        long minDistSqr = Long.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dy) != radius && Math.abs(dz) != radius) {
                        continue;
                    }

                    BlockPos gridKey = new BlockPos(
                            center.getX() + dx,
                            center.getY() + dy,
                            center.getZ() + dz
                    );

                    List<T> bucket = grid.get(gridKey);
                    if (bucket == null || bucket.isEmpty()) {
                        continue;
                    }

                    for (T target : bucket) {
                        if (used.contains(target) || !target.isAvailable()) {
                            continue;
                        }

                        //丢失精度可能会引发bug,临时这么写了
                        long distSqr = (long) mobPos.distSqr(target.getPosition());
                        if (distSqr < minDistSqr) {
                            minDistSqr = distSqr;
                            nearest = target;
                        }
                    }
                }
            }
        }

        return nearest;
    }
}