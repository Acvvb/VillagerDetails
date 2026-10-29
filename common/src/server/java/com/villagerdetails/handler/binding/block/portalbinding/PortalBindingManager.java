package com.villagerdetails.handler.binding.block.portalbinding;

import com.villagerdetails.config.impl.section.item.PortalBindingConfig;
import com.villagerdetails.pojo.dto.PortalBinding;
import com.villagerdetails.pojo.dto.PortalDestination;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

public final class PortalBindingManager {

    private static final PortalBindingManager INSTANCE = new PortalBindingManager();

    public static PortalBindingManager getInstance() {
        return INSTANCE;
    }

    private PortalBindingManager() {
    }

    public void addBinding(BlockPos owMin, BlockPos owMax, BlockPos netherMin, BlockPos netherMax) {
        PortalBindingConfig.INSTANCE.add(PortalBinding.of(
                Level.OVERWORLD, owMin, owMax,
                Level.NETHER, netherMin, netherMax
        ));
    }

    /**
     * 返回全部命中的候选（每条候选的 {@link PortalDestination#source()} 指向来源 binding）。
     */
    public List<PortalDestination> findAllDestinations(ResourceKey<Level> currentDim, BlockPos pos) {
        List<PortalDestination> result = new ArrayList<>();
        for (PortalBinding b : PortalBindingConfig.INSTANCE.snapshot()) {
            PortalDestination d = b.resolve(currentDim, pos);
            if (d != null) result.add(d);
        }
        return result;
    }

    public void clear() {
        PortalBindingConfig.INSTANCE.clear();
    }

    public List<PortalBinding> getBindings() {
        return PortalBindingConfig.INSTANCE.snapshot();
    }

    public PortalBinding getBinding(int index) {
        return PortalBindingConfig.INSTANCE.get(index);
    }

    public PortalBinding removeBinding(int index) {
        return PortalBindingConfig.INSTANCE.removeAt(index);
    }
}