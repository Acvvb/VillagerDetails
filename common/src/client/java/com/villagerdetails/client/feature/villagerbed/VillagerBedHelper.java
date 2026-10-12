package com.villagerdetails.client.feature.villagerbed;

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
     * 客户端收到服务端发来的床位置时调用，更新缓存。
     *
     * <p>参数收<b>可空的</b>{@link BlockPos}：{@code Optional} 是给返回值用的，
     * 当形参类型用属于反模式（IDEA 会报「Optional 用作形参类型」）。
     * 传 {@code null} = 这个村民现在没有床，缓存里直接删掉。
     */
    public static void updateBedPosition(int villagerId, BlockPos bedPos) {
        if (bedPos == null) {
            CLIENT_BED_CACHE.remove(villagerId);
        } else {
            CLIENT_BED_CACHE.put(villagerId, bedPos);
        }
    }

    /**
     * 清理已卸载实体的缓存（防止内存泄漏，也防止实体ID被复用后连出错误的线）
     */
    public static void removeBedPosition(int villagerId) {
        CLIENT_BED_CACHE.remove(villagerId);
    }

    /**
     * 断开连接 / 切换世界时整体清空
     */
    public static void clear() {
        CLIENT_BED_CACHE.clear();
    }
}
