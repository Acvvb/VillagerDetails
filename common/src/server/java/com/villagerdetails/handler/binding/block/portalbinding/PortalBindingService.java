package com.villagerdetails.handler.binding.block.portalbinding;

import com.villagerdetails.pojo.dto.PortalDestination;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 传送门绑定业务入口。
 */
public final class PortalBindingService {

    private PortalBindingService() {
    }

    /**
     * 执行绑定。四个坐标均为必填，两侧都是区域。
     */
    public static void bind(BlockPos overworldMin, BlockPos overworldMax,
                            BlockPos netherMin, BlockPos netherMax) {
        PortalBindingManager.getInstance().addBinding(
                overworldMin, overworldMax, netherMin, netherMax
        );
    }

    /**
     * 实体进入传送门时查询目标；没有绑定返回 null。
     */
    public static PortalDestination resolve(Entity entity, BlockPos portalPos) {
        ResourceKey<Level> dim = entity.level().dimension();
        return PortalBindingManager.getInstance().findDestination(dim, portalPos);
    }

    /**
     * 按维度 + 坐标查询目标，供非实体场景使用。
     */
    public static PortalDestination resolve(ResourceKey<Level> dim, BlockPos pos) {
        return PortalBindingManager.getInstance().findDestination(dim, pos);
    }
}