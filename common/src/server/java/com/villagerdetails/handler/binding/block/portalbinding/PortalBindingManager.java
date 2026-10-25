package com.villagerdetails.handler.binding.block.portalbinding;

import com.villagerdetails.config.impl.section.item.PortalBindingConfig;
import com.villagerdetails.pojo.dto.PortalBinding;
import com.villagerdetails.pojo.dto.PortalDestination;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 传送门绑定的注册、查询中心。
 *
 * <p>数据不再存在这里，而是由 {@link PortalBindingConfig} 拿着 ——
 * 它同时也是 {@code villagerdetails/config.json} 里 {@code PortalBinding} 节的落盘方，
 * 所以「绑定成功」和「写进配置文件」是同一件事，不会出现内存里有、文件里没有的情况。
 */
public final class PortalBindingManager {

    private static final PortalBindingManager INSTANCE = new PortalBindingManager();

    public static PortalBindingManager getInstance() {
        return INSTANCE;
    }

    private PortalBindingManager() {
    }

    /**
     * 新增一条绑定（会立刻写入统一配置文件）。两侧都是区域，四角坐标均为必填。
     */
    public void addBinding(BlockPos owMin, BlockPos owMax,
                           BlockPos netherMin, BlockPos netherMax) {
        PortalBindingConfig.INSTANCE.add(PortalBinding.of(
                Level.OVERWORLD, owMin, owMax,
                Level.NETHER, netherMin, netherMax
        ));
    }

    /**
     * 按维度 + 坐标查找目标；找不到返回 null（表示走原版逻辑）
     */
    public PortalDestination findDestination(ResourceKey<Level> currentDim, BlockPos pos) {
        for (PortalBinding b : PortalBindingConfig.INSTANCE.snapshot()) {
            PortalDestination d = b.resolve(currentDim, pos);
            if (d != null) return d;
        }
        return null;
    }

    /**
     * 清空全部绑定（连同配置文件里的）
     */
    public void clear() {
        PortalBindingConfig.INSTANCE.clear();
    }

    public List<PortalBinding> getBindings() {
        return PortalBindingConfig.INSTANCE.snapshot();
    }

    /**
     * 按下标取一条绑定（越界返回 null）
     */
    public PortalBinding getBinding(int index) {
        return PortalBindingConfig.INSTANCE.get(index);
    }

    /**
     * 按下标删除一条绑定并持久化（越界返回 null）
     */
    public PortalBinding removeBinding(int index) {
        return PortalBindingConfig.INSTANCE.removeAt(index);
    }
}
