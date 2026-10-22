package com.villagerdetails.client.cache.impl;

import com.villagerdetails.client.cache.base.ClientMapState;
import com.villagerdetails.client.cache.base.ClientSessionState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Optional;

/**
 * 客户端记录的「村民绑定的床」缓存。
 *
 * <p>多人联机时客户端拿不到服务端的大脑记忆，床坐标由服务端逐个村民推下来（{@code villager_bed_sync}）；
 * 单人 / 集成服里没有服务端包，直接读本地大脑记忆（{@link #getBedPosition(Villager)} 的第二条分支）。
 */
public final class VillagerBedClientState implements ClientSessionState {

    /**
     * 单例：扫包框架靠它拿实例，调用方统一写 {@code VillagerBedClientState.INSTANCE.xxx()}
     */
    public static final VillagerBedClientState INSTANCE = new VillagerBedClientState();

    /**
     * 村民实体 ID → 床坐标。
     *
     * <p>用 {@link ClientMapState}（不可变 Map 整体替换）而不是裸 ConcurrentHashMap：
     * 渲染线程每帧要遍历/查询它，写入则发生在收包和实体卸载时，快照式读写不需要再加锁语义。
     */
    private final ClientMapState<Integer, BlockPos> beds = new ClientMapState<>();

    private VillagerBedClientState() {
    }

    /**
     * 获取村民绑定的床的坐标（优先从缓存读取，缓存没有则尝试从记忆读取）
     */
    public Optional<BlockPos> getBedPosition(Villager villager) {
        // 1. 优先从客户端缓存读取（多人联机场景）
        BlockPos cached = beds.get(villager.getId());
        if (cached != null) {
            return Optional.of(cached);
        }

        // 2. 缓存没有，尝试从村民大脑记忆读取（单人游戏场景）
        Optional<GlobalPos> homeMemory = villager.getBrain().getMemory(MemoryModuleType.HOME);
        return homeMemory.map(GlobalPos::pos);
    }

    /**
     * 客户端收到服务端发来的床位置时调用，更新缓存。
     */
    public void updateBedPosition(int villagerId, BlockPos bedPos) {
        if (bedPos == null) {
            beds.remove(villagerId);
        } else {
            beds.put(villagerId, bedPos);
        }
    }

    /**
     * 清理已卸载实体的缓存（防止内存泄漏，也防止实体ID被复用后连出错误的线）
     */
    public void removeBedPosition(int villagerId) {
        beds.remove(villagerId);
    }

    /**
     * 断线 / 切换世界时整体清空（由 {@link com.villagerdetails.client.cache.ClientSessionStates} 统一调用）
     */
    @Override
    public void clearSession() {
        beds.clear();
    }
}
