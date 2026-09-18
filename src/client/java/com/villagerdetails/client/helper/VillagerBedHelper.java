package com.villagerdetails.client.helper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class VillagerBedHelper {

    /** 客户端本地缓存：村民实体ID → 床坐标 */
    private static final Map<Integer, BlockPos> CLIENT_BED_CACHE = new ConcurrentHashMap<>();

    /**
     * 获取村民绑定的床的坐标（优先从缓存读取，缓存没有则尝试从记忆读取）
     */
    public static Optional<BlockPos> getBedPosition(Villager villager) {
        // 1. 优先从客户端缓存读取（多人联机场景）
        BlockPos cached = CLIENT_BED_CACHE.get(villager.getId());
        if (cached != null) {
            return Optional.of(cached);
        }

        // 2. 缓存没有，尝试从村民大脑记忆读取（单人游戏场景）
        Optional<GlobalPos> homeMemory = villager.getBrain().getMemory(MemoryModuleType.HOME);
        return homeMemory.map(GlobalPos::pos);
    }

    /**
     * 客户端收到服务端发来的床位置时调用，更新缓存
     */
    public static void updateBedPosition(int villagerId, Optional<BlockPos> bedPos) {
        // 有值就 put，没值就 remove
        bedPos.ifPresentOrElse(
                pos -> CLIENT_BED_CACHE.put(villagerId, pos),
                () -> CLIENT_BED_CACHE.remove(villagerId)
        );
    }

    /**
     * 清理已卸载实体的缓存（防止内存泄漏）
     */
    public static void removeBedPosition(int villagerId) {
        CLIENT_BED_CACHE.remove(villagerId);
    }
}