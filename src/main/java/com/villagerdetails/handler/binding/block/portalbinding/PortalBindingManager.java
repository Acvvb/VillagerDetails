package com.villagerdetails.handler.binding.block.portalbinding;

import com.villagerdetails.cache.PortalBinding;
import com.villagerdetails.pojo.PortalDestination;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 传送门绑定的注册、查询中心。
 */
public final class PortalBindingManager {

    private static final PortalBindingManager INSTANCE = new PortalBindingManager();

    public static PortalBindingManager getInstance() {
        return INSTANCE;
    }

    private final List<PortalBinding> bindings = new ArrayList<>();

    private PortalBindingManager() {
    }

    /**
     * 新增一条绑定。两侧都是区域，四角坐标均为必填。
     */
    public void addBinding(BlockPos owMin, BlockPos owMax,
                           BlockPos netherMin, BlockPos netherMax) {
        bindings.add(PortalBinding.of(
                Level.OVERWORLD, owMin, owMax,
                Level.NETHER, netherMin, netherMax
        ));
    }

    /**
     * 按维度 + 坐标查找目标；找不到返回 null（表示走原版逻辑）
     */
    public PortalDestination findDestination(ResourceKey<Level> currentDim, BlockPos pos) {
        for (PortalBinding b : bindings) {
            PortalDestination d = b.resolve(currentDim, pos);
            if (d != null) return d;
        }
        return null;
    }

    public boolean isBound(ResourceKey<Level> dim, BlockPos pos) {
        return findDestination(dim, pos) != null;
    }

    public void clear() {
        bindings.clear();
    }

    public List<PortalBinding> getBindings() {
        return List.copyOf(bindings);
    }
}