package com.villagerdetails.handler.villager.move;

import com.villagerdetails.cache.ModMemories;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.WeakHashMap;

public class MoveToDestination extends Behavior<Villager> {

    private static final float SPEED = 0.5F;
    private static final double ARRIVE_EPSILON = 0.7;

    /** 超时时间（tick 数）。1200 = 60 秒 */
    private static final long MAX_TICKS = 20L * 60L;

    /** 每个村民的任务开始时间，用于超时判断。WeakHashMap 随实体 GC 自动清理 */
    private static final Map<Villager, Long> START_TIMES = new WeakHashMap<>();

    /** 外部调用：重置某村民的超时计时（在 sendTo 时调用） */
    public static void resetTimer(Villager villager) {
        START_TIMES.remove(villager);
    }

    public MoveToDestination() {
        super(Map.of(ModMemories.DESTINATION, MemoryStatus.VALUE_PRESENT));
    }

    @Override
    protected boolean checkExtraStartConditions(@NonNull ServerLevel level, Villager villager) {
        return villager.getBrain().hasMemoryValue(ModMemories.DESTINATION);
    }

    @Override
    protected void start(@NonNull ServerLevel level, Villager villager, long gameTime) {
        BlockPos dest = villager.getBrain().getMemory(ModMemories.DESTINATION).orElse(null);
        if (dest == null) return;

        // 首次 start 记录开始时间
        START_TIMES.putIfAbsent(villager, gameTime);

        villager.getBrain().setMemory(
                MemoryModuleType.WALK_TARGET,
                new WalkTarget(dest, SPEED, 0)
        );
    }

    @Override
    protected boolean canStillUse(@NonNull ServerLevel level, Villager villager, long gameTime) {
        return villager.getBrain().hasMemoryValue(ModMemories.DESTINATION);
    }

    @Override
    protected void tick(@NonNull ServerLevel level, Villager villager, long gameTime) {
        Brain<Villager> brain = villager.getBrain();
        BlockPos dest = brain.getMemory(ModMemories.DESTINATION).orElse(null);
        if (dest == null) return;

        // 超时兜底
        Long start = START_TIMES.get(villager);
        if (start == null) {
            START_TIMES.put(villager, gameTime);
            start = gameTime;
        }
        if (gameTime - start > MAX_TICKS) {
            abandon(villager);
            return;
        }

        // 到达判断（Vec3 精确判断，避免 blockPosition 跳变）
        double dx = villager.getX() - (dest.getX() + 0.5);
        double dz = villager.getZ() - (dest.getZ() + 0.5);
        if (dx * dx + dz * dz <= ARRIVE_EPSILON * ARRIVE_EPSILON) {
            villager.getNavigation().stop();
            brain.eraseMemory(ModMemories.DESTINATION);
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            brain.useDefaultActivity();
            START_TIMES.remove(villager);
            return;
        }

        // 没在寻路 → 重设
        if (!villager.getNavigation().isInProgress()) {
            brain.setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(dest, SPEED, 0));
        }
    }

    @Override
    protected void stop(@NonNull ServerLevel level, Villager villager, long gameTime) {
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        START_TIMES.remove(villager);
    }

    /** 超时/放弃任务 */
    private void abandon(Villager villager) {
        Brain<Villager> brain = villager.getBrain();
        villager.getNavigation().stop();
        brain.eraseMemory(ModMemories.DESTINATION);
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        brain.useDefaultActivity();
        START_TIMES.remove(villager);
    }
}