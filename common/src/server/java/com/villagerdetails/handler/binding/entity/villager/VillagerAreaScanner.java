package com.villagerdetails.handler.binding.entity.villager;

import com.villagerdetails.math.gridhashing.BindableMob;
import com.villagerdetails.math.gridhashing.BindableTarget;
import com.villagerdetails.math.gridhashing.villager.BedTarget;
import com.villagerdetails.math.gridhashing.villager.VillagerMob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 村民区域扫描器
 * <p>
 * 负责在指定长方体区域内扫描村民和床，并包装成通用匹配框架所需的适配器对象。
 * </p>
 */
public class VillagerAreaScanner {

    /**
     * 单次扫描的最大方块体积限制（防止玩家选择过大区域导致服务器卡死）
     * 32 * 32 * 32 = 32768 个方块
     */
    private static final int MAX_SCAN_VOLUME = 32768;

    /**
     * 区域边界封装类（消除重复的 6 行边界计算代码）
     */
    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        static Bounds of(BlockPos pos1, BlockPos pos2) {
            return new Bounds(
                    Math.min(pos1.getX(), pos2.getX()),
                    Math.min(pos1.getY(), pos2.getY()),
                    Math.min(pos1.getZ(), pos2.getZ()),
                    Math.max(pos1.getX(), pos2.getX()),
                    Math.max(pos1.getY(), pos2.getY()),
                    Math.max(pos1.getZ(), pos2.getZ())
            );
        }

        AABB toAABB() {
            // max 坐标 +1 是保证 AABB 完全包裹边界方块
            return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        }

        void checkVolumeLimit() {
            long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (volume > MAX_SCAN_VOLUME) {
                throw new IllegalArgumentException("扫描区域过大！最大允许体积为 " + MAX_SCAN_VOLUME + " 方块，当前为 " + volume);
            }
        }
    }

    /**
     * 扫描长方体区域内的村民，包装成 BindableMob
     *
     * @param level 服务端世界
     * @param pos1  对角点1
     * @param pos2  对角点2
     * @return 村民列表
     */
    public static List<BindableMob> scanVillagers(ServerLevel level, BlockPos pos1, BlockPos pos2) {
        List<BindableMob> mobs = new ArrayList<>();
        if (level == null || pos1 == null || pos2 == null) return mobs;

        AABB area = Bounds.of(pos1, pos2).toAABB();

        // 直接抓取 Villager 类，性能远高于 getEntities(null) + instanceof
        for (Villager villager : level.getEntitiesOfClass(Villager.class, area)) {
            mobs.add(new VillagerMob(villager));
        }
        return mobs;
    }

    /**
     * 扫描长方体区域内的床，包装成 BindableTarget
     *
     * @param level 服务端世界
     * @param pos1  对角点1
     * @param pos2  对角点2
     * @return 床列表
     */
    public static List<BindableTarget> scanBeds(ServerLevel level, BlockPos pos1, BlockPos pos2) {
        List<BindableTarget> beds = new ArrayList<>();
        if (level == null || pos1 == null || pos2 == null) return beds;

        Bounds bounds = Bounds.of(pos1, pos2);
        bounds.checkVolumeLimit(); // 安全体积检查

        // 复用 MutableBlockPos，避免在几十万次循环中 new 出无数个 BlockPos 对象
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        // 区块加载状态缓存（避免在循环内重复调用 getChunkNow）
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;
        boolean isChunkLoaded = false;

        // 优化循环顺序：Y轴在最外层，通常高度维度最小（-64~320），可以最快跳过空区域
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {

                    // 获取当前方块所属的区块坐标
                    int chunkX = SectionPos.blockToSectionCoord(x);
                    int chunkZ = SectionPos.blockToSectionCoord(z);

                    // 只有当跨越到新的区块时，才重新检查该区块是否已加载
                    if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                        lastChunkX = chunkX;
                        lastChunkZ = chunkZ;
                        isChunkLoaded = level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
                    }

                    // 核心保护：如果区块未加载，直接跳过
                    if (!isChunkLoaded) {
                        continue;
                    }

                    mutablePos.set(x, y, z);
                    BlockState state = level.getBlockState(mutablePos);

                    if (state.getBlock() instanceof BedBlock) {
                        // 只记录床头（HEAD），避免一张床被记录两次（床头和床尾）
                        if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
                            beds.add(new BedTarget(mutablePos.immutable(), true));
                        }
                    }
                }
            }
        }
        return beds;
    }
}