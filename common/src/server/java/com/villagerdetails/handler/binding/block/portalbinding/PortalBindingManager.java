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

    /**
     * 写入一条绑定：维度固定为「主世界 ↔ 地狱」，坐标由调用方给出。
     *
     * @return 真的写进配置返回 {@code true}；重复 / 两端维度非法 / 占位坐标被拒返回 {@code false}
     */
    public boolean addBinding(BlockPos owMin, BlockPos owMax, BlockPos netherMin, BlockPos netherMax) {
        return PortalBindingConfig.INSTANCE.add(PortalBinding.of(
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

    /**
     * 按 ID 取一条；找不到返回 {@code null}。
     *
     * <p>指令一律走 ID：那个号只增不减、删除不回收，所以不会像序号那样
     * 在别人增删之后指到别的条目上。
     */
    public PortalBinding getBinding(String id) {
        return PortalBindingConfig.INSTANCE.getById(id);
    }

    /**
     * 按 ID 删除一条；找不到返回 {@code null}。
     */
    public PortalBinding removeBinding(String id) {
        return PortalBindingConfig.INSTANCE.removeById(id);
    }
}
