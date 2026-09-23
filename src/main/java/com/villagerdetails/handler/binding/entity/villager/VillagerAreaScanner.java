package com.villagerdetails.handler.binding.entity.villager;

import com.villagerdetails.math.gridhashing.BindableMob;
import com.villagerdetails.math.gridhashing.BindableTarget;
import com.villagerdetails.math.gridhashing.villager.BedTarget;
import com.villagerdetails.math.gridhashing.villager.VillagerMob;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
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
     * 计算长方体区域的 AABB
     *
     * @param pos1 对角点1
     * @param pos2 对角点2
     * @return 包围盒
     */
    private static AABB toAABB(BlockPos pos1, BlockPos pos2) {
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
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
        AABB area = toAABB(pos1, pos2);
        for (Entity entity : level.getEntities(null, area)) {
            if (entity instanceof Villager villager) {
                mobs.add(new VillagerMob(villager));
            }
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
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof BedBlock) {
                        beds.add(new BedTarget(pos, true));
                    }
                }
            }
        }
        return beds;
    }
}